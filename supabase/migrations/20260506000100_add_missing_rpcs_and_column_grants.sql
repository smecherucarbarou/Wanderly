-- Add missing RPCs called by Android app and Edge Functions.
-- Also restrict direct UPDATE on profiles to safe columns only.

-- ============================================================
-- SECTION 1: Column-level GRANT hardening for profiles
-- ============================================================

-- Revoke broad UPDATE/DELETE on profiles from authenticated.
REVOKE UPDATE ON public.profiles FROM authenticated;
REVOKE DELETE ON public.profiles FROM authenticated;

-- Grant UPDATE only on safe, user-editable columns.
GRANT UPDATE (username, avatar_url, explorer_class) ON public.profiles TO authenticated;

-- ============================================================
-- SECTION 2: complete_mission RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.complete_mission()
RETURNS TABLE (
    completed boolean,
    duplicate boolean,
    honey integer,
    streak_count integer,
    last_mission_date date,
    reward_honey integer,
    streak_bonus_honey integer
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_profile public.profiles%ROWTYPE;
    v_today date := current_date;
    v_base_reward integer := 50;
    v_streak_bonus integer := 0;
    v_new_honey integer;
    v_new_streak integer;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT * INTO v_profile FROM public.profiles WHERE id = v_user_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found' USING ERRCODE = 'P0002';
    END IF;

    -- Already completed today
    IF v_profile.last_mission_date = v_today THEN
        RETURN QUERY SELECT
            false,
            true,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_today,
            0,
            0;
        RETURN;
    END IF;

    -- Calculate streak
    IF v_profile.last_mission_date = v_today - 1 THEN
        v_new_streak := COALESCE(v_profile.streak_count, 0) + 1;
    ELSE
        v_new_streak := 1;
    END IF;

    -- Streak bonus: 10 honey per streak day (capped at 50)
    v_streak_bonus := LEAST(v_new_streak * 10, 50);
    v_new_honey := COALESCE(v_profile.honey, 0) + v_base_reward + v_streak_bonus;

    UPDATE public.profiles
    SET
        honey = v_new_honey,
        streak_count = v_new_streak,
        last_mission_date = v_today
    WHERE id = v_user_id;

    RETURN QUERY SELECT
        true,
        false,
        v_new_honey,
        v_new_streak,
        v_today,
        v_base_reward,
        v_streak_bonus;
END;
$$;

REVOKE ALL ON FUNCTION public.complete_mission() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.complete_mission() FROM anon;
GRANT EXECUTE ON FUNCTION public.complete_mission() TO authenticated;

-- ============================================================
-- SECTION 3: update_profile_location RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.update_profile_location(lat double precision, lng double precision)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF lat < -90 OR lat > 90 OR lng < -180 OR lng > 180 THEN
        RAISE EXCEPTION 'Invalid coordinates'
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.profiles
    SET last_lat = lat, last_lng = lng, updated_at = now()
    WHERE id = v_user_id;
END;
$$;

REVOKE ALL ON FUNCTION public.update_profile_location(double precision, double precision) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.update_profile_location(double precision, double precision) FROM anon;
GRANT EXECUTE ON FUNCTION public.update_profile_location(double precision, double precision) TO authenticated;

-- ============================================================
-- SECTION 4: accept_streak_loss RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.accept_streak_loss()
RETURNS TABLE (
    updated boolean,
    honey integer,
    streak_count integer,
    last_mission_date date
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_profile public.profiles%ROWTYPE;
    v_today date := current_date;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT * INTO v_profile FROM public.profiles WHERE id = v_user_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found' USING ERRCODE = 'P0002';
    END IF;

    -- Only allow streak loss if the streak is broken (missed more than 1 day)
    IF v_profile.last_mission_date IS NOT NULL
       AND v_profile.last_mission_date < v_today - 1
       AND COALESCE(v_profile.streak_count, 0) > 0 THEN
        UPDATE public.profiles
        SET streak_count = 0
        WHERE id = v_user_id;

        RETURN QUERY SELECT
            true,
            COALESCE(v_profile.honey, 0),
            0,
            v_profile.last_mission_date;
    ELSE
        RETURN QUERY SELECT
            false,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.accept_streak_loss() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.accept_streak_loss() FROM anon;
GRANT EXECUTE ON FUNCTION public.accept_streak_loss() TO authenticated;

-- ============================================================
-- SECTION 5: restore_streak RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.restore_streak(cost integer)
RETURNS TABLE (
    restored boolean,
    reason text,
    honey integer,
    streak_count integer,
    last_mission_date date
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_profile public.profiles%ROWTYPE;
    v_today date := current_date;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF cost <= 0 THEN
        RETURN QUERY SELECT false, 'invalid_cost'::text, 0, 0, NULL::text;
        RETURN;
    END IF;

    SELECT * INTO v_profile FROM public.profiles WHERE id = v_user_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found' USING ERRCODE = 'P0002';
    END IF;

    IF COALESCE(v_profile.honey, 0) < cost THEN
        RETURN QUERY SELECT
            false,
            'insufficient_honey'::text,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
        RETURN;
    END IF;

    -- Restore streak: deduct honey cost, set last_mission_date to yesterday so
    -- completing today's mission continues the streak.
    UPDATE public.profiles
    SET
        honey = COALESCE(v_profile.honey, 0) - cost,
        last_mission_date = v_today - 1
    WHERE id = v_user_id;

    RETURN QUERY SELECT
        true,
        NULL::text,
        COALESCE(v_profile.honey, 0) - cost,
        COALESCE(v_profile.streak_count, 0),
        v_today - 1;
END;
$$;

REVOKE ALL ON FUNCTION public.restore_streak(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.restore_streak(integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.restore_streak(integer) TO authenticated;

-- ============================================================
-- SECTION 6: get_social_leaderboard RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.get_social_leaderboard()
RETURNS TABLE (
    id uuid,
    username text,
    avatar_url text,
    friend_code text,
    honey integer,
    hive_rank integer,
    badges text[],
    cities_visited text[],
    streak_count integer,
    explorer_class text
)
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, pg_temp
AS $$
    SELECT
        p.id,
        p.username,
        p.avatar_url,
        p.friend_code,
        p.honey,
        p.hive_rank,
        p.badges,
        p.cities_visited,
        p.streak_count,
        p.explorer_class
    FROM public.profiles AS p
    WHERE p.id = auth.uid()
       OR p.id IN (
           SELECT CASE WHEN f.user_id = auth.uid() THEN f.friend_id ELSE f.user_id END
           FROM public.friendships AS f
           WHERE (f.user_id = auth.uid() OR f.friend_id = auth.uid())
             AND f.status = 'accepted'
       )
    ORDER BY p.honey DESC NULLS LAST, p.username NULLS LAST
    LIMIT 50;
$$;

REVOKE ALL ON FUNCTION public.get_social_leaderboard() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_social_leaderboard() FROM anon;
GRANT EXECUTE ON FUNCTION public.get_social_leaderboard() TO authenticated;

-- ============================================================
-- SECTION 7: find_profile_by_friend_code RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.find_profile_by_friend_code(code text)
RETURNS TABLE (
    id uuid,
    username text,
    avatar_url text,
    friend_code text,
    honey integer,
    hive_rank integer,
    badges text[],
    cities_visited text[],
    streak_count integer,
    explorer_class text
)
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, pg_temp
AS $$
    SELECT
        p.id,
        p.username,
        p.avatar_url,
        p.friend_code,
        p.honey,
        p.hive_rank,
        p.badges,
        p.cities_visited,
        p.streak_count,
        p.explorer_class
    FROM public.profiles AS p
    WHERE auth.uid() IS NOT NULL
      AND upper(p.friend_code) = upper(code);
$$;

REVOKE ALL ON FUNCTION public.find_profile_by_friend_code(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.find_profile_by_friend_code(text) FROM anon;
GRANT EXECUTE ON FUNCTION public.find_profile_by_friend_code(text) TO authenticated;

-- ============================================================
-- SECTION 8: get_accepted_friend_profiles RPC
-- ============================================================

CREATE OR REPLACE FUNCTION public.get_accepted_friend_profiles()
RETURNS TABLE (
    id uuid,
    username text,
    avatar_url text,
    friend_code text,
    honey integer,
    hive_rank integer,
    badges text[],
    cities_visited text[],
    streak_count integer,
    explorer_class text
)
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, pg_temp
AS $$
    SELECT
        p.id,
        p.username,
        p.avatar_url,
        p.friend_code,
        p.honey,
        p.hive_rank,
        p.badges,
        p.cities_visited,
        p.streak_count,
        p.explorer_class
    FROM public.profiles AS p
    WHERE auth.uid() IS NOT NULL
      AND p.id IN (
          SELECT CASE WHEN f.user_id = auth.uid() THEN f.friend_id ELSE f.user_id END
          FROM public.friendships AS f
          WHERE (f.user_id = auth.uid() OR f.friend_id = auth.uid())
            AND f.status = 'accepted'
      )
    ORDER BY p.username NULLS LAST, p.id;
$$;

REVOKE ALL ON FUNCTION public.get_accepted_friend_profiles() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_accepted_friend_profiles() FROM anon;
GRANT EXECUTE ON FUNCTION public.get_accepted_friend_profiles() TO authenticated;

-- ============================================================
-- SECTION 9: consume_api_quota RPC (for Edge Functions)
-- ============================================================

CREATE TABLE IF NOT EXISTS public.api_quotas (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES auth.users(id),
    provider text NOT NULL,
    request_date date NOT NULL DEFAULT current_date,
    request_count integer NOT NULL DEFAULT 0,
    CONSTRAINT api_quotas_pkey PRIMARY KEY (id),
    CONSTRAINT api_quotas_user_provider_date_key UNIQUE (user_id, provider, request_date)
);

ALTER TABLE public.api_quotas ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view own quotas"
ON public.api_quotas FOR SELECT
TO authenticated
USING (auth.uid() = user_id);

GRANT SELECT ON public.api_quotas TO authenticated;

CREATE OR REPLACE FUNCTION public.consume_api_quota(provider_name text, max_requests_per_day integer)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_today date := current_date;
    v_count integer;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    INSERT INTO public.api_quotas (user_id, provider, request_date, request_count)
    VALUES (v_user_id, provider_name, v_today, 1)
    ON CONFLICT (user_id, provider, request_date)
    DO UPDATE SET request_count = public.api_quotas.request_count + 1
    RETURNING request_count INTO v_count;

    RETURN v_count <= max_requests_per_day;
END;
$$;

REVOKE ALL ON FUNCTION public.consume_api_quota(text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.consume_api_quota(text, integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.consume_api_quota(text, integer) TO authenticated;

-- ============================================================
-- SECTION 10: Friendships identity immutability trigger
-- ============================================================

CREATE OR REPLACE FUNCTION public.protect_friendship_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
        RAISE EXCEPTION 'Cannot change friendship user_id'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.friend_id IS DISTINCT FROM OLD.friend_id THEN
        RAISE EXCEPTION 'Cannot change friendship friend_id'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_protect_friendship_identity ON public.friendships;
CREATE TRIGGER trg_protect_friendship_identity
BEFORE UPDATE ON public.friendships
FOR EACH ROW
EXECUTE FUNCTION public.protect_friendship_identity();
