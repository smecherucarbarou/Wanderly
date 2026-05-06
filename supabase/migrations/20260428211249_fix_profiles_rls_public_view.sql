-- CRIT-01: private profile rows must be owner-only; public/social profile access must expose safe fields only.

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can read profiles for social" ON public.profiles;
DROP POLICY IF EXISTS "Anyone can view profiles" ON public.profiles;
DROP POLICY IF EXISTS "Users can view own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users can read own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users read own profile" ON public.profiles;
DROP POLICY IF EXISTS "profiles_select_own" ON public.profiles;

CREATE POLICY "profiles_select_own"
ON public.profiles
FOR SELECT
TO authenticated
USING (id = auth.uid());

DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users insert own profile" ON public.profiles;
DROP POLICY IF EXISTS "profiles_insert_own" ON public.profiles;

CREATE POLICY "profiles_insert_own"
ON public.profiles
FOR INSERT
TO authenticated
WITH CHECK (id = auth.uid());

DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users update own profile" ON public.profiles;
DROP POLICY IF EXISTS "profiles_update_own" ON public.profiles;

CREATE POLICY "profiles_update_own"
ON public.profiles
FOR UPDATE
TO authenticated
USING (id = auth.uid())
WITH CHECK (id = auth.uid());

DROP FUNCTION IF EXISTS public.find_profile_by_friend_code(text);
DROP FUNCTION IF EXISTS public.get_public_profile(uuid);
DROP FUNCTION IF EXISTS public.get_accepted_friend_profiles();
DROP FUNCTION IF EXISTS public.get_social_leaderboard(integer);

DROP VIEW IF EXISTS public.profiles_public CASCADE;

CREATE VIEW public.profiles_public
WITH (security_invoker = true) AS
SELECT
    id,
    username,
    avatar_url,
    friend_code,
    honey,
    hive_rank,
    badges,
    cities_visited,
    streak_count,
    explorer_class
FROM public.profiles;

REVOKE ALL ON public.profiles_public FROM PUBLIC;
REVOKE ALL ON public.profiles_public FROM anon;
GRANT SELECT ON public.profiles_public TO authenticated;

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
      AND upper(trim(code)) ~ '^[A-Z0-9]{6}$'
      AND p.friend_code = upper(trim(code))
    LIMIT 1;
$$;

CREATE OR REPLACE FUNCTION public.get_public_profile(profile_user_id uuid)
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
      AND p.id = profile_user_id
    LIMIT 1;
$$;

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
      AND EXISTS (
          SELECT 1
          FROM public.friendships AS f
          WHERE f.status = 'accepted'
            AND (
                (f.user_id = auth.uid() AND f.friend_id = p.id)
                OR (f.friend_id = auth.uid() AND f.user_id = p.id)
            )
      )
    ORDER BY p.username NULLS LAST, p.id;
$$;

CREATE OR REPLACE FUNCTION public.get_social_leaderboard(max_rows integer DEFAULT 50)
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
      AND (
          p.id = auth.uid()
          OR EXISTS (
              SELECT 1
              FROM public.friendships AS f
              WHERE f.status = 'accepted'
                AND (
                    (f.user_id = auth.uid() AND f.friend_id = p.id)
                    OR (f.friend_id = auth.uid() AND f.user_id = p.id)
                )
          )
      )
    ORDER BY p.honey DESC NULLS LAST, p.username NULLS LAST, p.id
    LIMIT LEAST(GREATEST(COALESCE(max_rows, 50), 1), 100);
$$;

REVOKE ALL ON FUNCTION public.find_profile_by_friend_code(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_public_profile(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_accepted_friend_profiles() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_social_leaderboard(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.find_profile_by_friend_code(text) FROM anon;
REVOKE ALL ON FUNCTION public.get_public_profile(uuid) FROM anon;
REVOKE ALL ON FUNCTION public.get_accepted_friend_profiles() FROM anon;
REVOKE ALL ON FUNCTION public.get_social_leaderboard(integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.find_profile_by_friend_code(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_public_profile(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_accepted_friend_profiles() TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_social_leaderboard(integer) TO authenticated;
