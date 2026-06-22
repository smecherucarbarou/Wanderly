-- MAJ-01: make mission reward/progression updates server-authoritative.
--
-- Schema gap: the deployed export does not contain a missions table, so this RPC
-- cannot validate a mission row or mission ownership yet. It protects the current
-- available invariant: one server-derived reward per authenticated user per UTC day.

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
    v_profile public.profiles%ROWTYPE;
    v_today date := (now() AT TIME ZONE 'utc')::date;
    v_yesterday date := ((now() AT TIME ZONE 'utc')::date - 1);
    v_new_streak integer;
    v_reward_honey integer := 50;
    v_streak_bonus_honey integer := 0;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT p.*
    INTO v_profile
    FROM public.profiles AS p
    WHERE p.id = auth.uid()
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found'
            USING ERRCODE = 'P0002';
    END IF;

    IF v_profile.last_mission_date = v_today THEN
        RETURN QUERY SELECT
            false,
            true,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date,
            0,
            0;
        RETURN;
    END IF;

    IF v_profile.last_mission_date = v_yesterday THEN
        v_new_streak := COALESCE(v_profile.streak_count, 0) + 1;
        v_streak_bonus_honey := 10 + (v_new_streak / 5) * 5;
    ELSE
        v_new_streak := 1;
    END IF;

    UPDATE public.profiles AS p
    SET
        honey = COALESCE(p.honey, 0) + v_reward_honey + v_streak_bonus_honey,
        streak_count = v_new_streak,
        last_mission_date = v_today
    WHERE p.id = auth.uid()
    RETURNING p.*
    INTO v_profile;

    RETURN QUERY SELECT
        true,
        false,
        COALESCE(v_profile.honey, 0),
        COALESCE(v_profile.streak_count, 0),
        v_profile.last_mission_date,
        v_reward_honey,
        v_streak_bonus_honey;
END;
$$;

CREATE OR REPLACE FUNCTION public.update_profile_location(lat double precision, lng double precision)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF lat IS NULL OR lng IS NULL OR lat < -90 OR lat > 90 OR lng < -180 OR lng > 180 THEN
        RAISE EXCEPTION 'Invalid coordinates'
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.profiles AS p
    SET
        last_lat = lat,
        last_lng = lng
    WHERE p.id = auth.uid();
END;
$$;

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
    v_profile public.profiles%ROWTYPE;
    v_today date := (now() AT TIME ZONE 'utc')::date;
    v_yesterday date := ((now() AT TIME ZONE 'utc')::date - 1);
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT p.*
    INTO v_profile
    FROM public.profiles AS p
    WHERE p.id = auth.uid()
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found'
            USING ERRCODE = 'P0002';
    END IF;

    IF COALESCE(v_profile.streak_count, 0) <= 0
       OR (v_profile.last_mission_date IS NOT NULL AND v_today - v_profile.last_mission_date <= 2) THEN
        RETURN QUERY SELECT
            false,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
        RETURN;
    END IF;

    UPDATE public.profiles AS p
    SET
        streak_count = 0,
        last_mission_date = v_yesterday
    WHERE p.id = auth.uid()
    RETURNING p.*
    INTO v_profile;

    RETURN QUERY SELECT
        true,
        COALESCE(v_profile.honey, 0),
        COALESCE(v_profile.streak_count, 0),
        v_profile.last_mission_date;
END;
$$;

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
    v_profile public.profiles%ROWTYPE;
    v_today date := (now() AT TIME ZONE 'utc')::date;
    v_yesterday date := ((now() AT TIME ZONE 'utc')::date - 1);
    v_expected_cost integer;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT p.*
    INTO v_profile
    FROM public.profiles AS p
    WHERE p.id = auth.uid()
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found'
            USING ERRCODE = 'P0002';
    END IF;

    v_expected_cost := COALESCE(v_profile.streak_count, 0) * 5;

    IF COALESCE(v_profile.streak_count, 0) <= 0
       OR v_profile.last_mission_date IS NULL
       OR v_today - v_profile.last_mission_date <> 2 THEN
        RETURN QUERY SELECT
            false,
            'not_freeze_eligible'::text,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
        RETURN;
    END IF;

    IF COALESCE(v_profile.honey, 0) < v_expected_cost THEN
        RETURN QUERY SELECT
            false,
            'insufficient_honey'::text,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
        RETURN;
    END IF;

    IF cost IS NOT NULL AND cost <> v_expected_cost THEN
        RETURN QUERY SELECT
            false,
            'stale_cost'::text,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
        RETURN;
    END IF;

    UPDATE public.profiles AS p
    SET
        honey = COALESCE(p.honey, 0) - v_expected_cost,
        last_mission_date = v_yesterday
    WHERE p.id = auth.uid()
    RETURNING p.*
    INTO v_profile;

    RETURN QUERY SELECT
        true,
        'restored'::text,
        COALESCE(v_profile.honey, 0),
        COALESCE(v_profile.streak_count, 0),
        v_profile.last_mission_date;
END;
$$;

REVOKE UPDATE ON public.profiles FROM authenticated;
GRANT UPDATE (username, badges, cities_visited, avatar_url, friend_code, explorer_class)
ON public.profiles TO authenticated;

REVOKE ALL ON FUNCTION public.complete_mission() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.update_profile_location(double precision, double precision) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.accept_streak_loss() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.restore_streak(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.complete_mission() FROM anon;
REVOKE ALL ON FUNCTION public.update_profile_location(double precision, double precision) FROM anon;
REVOKE ALL ON FUNCTION public.accept_streak_loss() FROM anon;
REVOKE ALL ON FUNCTION public.restore_streak(integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.complete_mission() TO authenticated;
GRANT EXECUTE ON FUNCTION public.update_profile_location(double precision, double precision) TO authenticated;
GRANT EXECUTE ON FUNCTION public.accept_streak_loss() TO authenticated;
GRANT EXECUTE ON FUNCTION public.restore_streak(integer) TO authenticated;
