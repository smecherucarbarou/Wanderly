-- Profiles protected-field column-level GRANT verification.
--
-- After applying 20260506000100_add_missing_rpcs_and_column_grants.sql, users
-- should only be able to directly UPDATE: username, avatar_url, explorer_class.
-- All other fields (honey, streak_count, badges, cities_visited, hive_rank,
-- last_buzz_date, last_mission_date, admin_role, last_lat, last_lng) must be
-- protected and only mutable via SECURITY DEFINER RPCs.
--
-- Usage:
--   psql "$DATABASE_URL" \
--     --set=user_a='00000000-0000-0000-0000-000000000001' \
--     --file supabase/tests/profiles_rls_protected_fields.sql
--
-- user_a must exist in auth.users and public.profiles.

\if :{?user_a}
\else
  \error 'Missing required psql variable: user_a'
\endif

BEGIN;

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.role', 'authenticated', true);
SELECT set_config('request.jwt.claim.sub', :'user_a', true);

-- Test 1: User can update safe fields (username, avatar_url, explorer_class).
WITH safe_update AS (
    UPDATE public.profiles
    SET username = username,
        avatar_url = avatar_url,
        explorer_class = explorer_class
    WHERE id = :'user_a'::uuid
    RETURNING id
)
SELECT
    'User can update safe fields' AS check_name,
    count(*) = 1 AS pass
FROM safe_update;

-- Test 2: User cannot directly update honey.
DO $$
DECLARE
    was_blocked boolean := false;
BEGIN
    BEGIN
        UPDATE public.profiles
        SET honey = 9999
        WHERE id = current_setting('request.jwt.claim.sub')::uuid;
    EXCEPTION
        WHEN insufficient_privilege THEN
            was_blocked := true;
    END;
    IF NOT was_blocked THEN
        RAISE EXCEPTION 'Direct UPDATE honey should be blocked';
    END IF;
END $$;
SELECT 'User cannot update honey directly' AS check_name, true AS pass;

-- Test 3: User cannot directly update streak_count.
DO $$
DECLARE
    was_blocked boolean := false;
BEGIN
    BEGIN
        UPDATE public.profiles
        SET streak_count = 999
        WHERE id = current_setting('request.jwt.claim.sub')::uuid;
    EXCEPTION
        WHEN insufficient_privilege THEN
            was_blocked := true;
    END;
    IF NOT was_blocked THEN
        RAISE EXCEPTION 'Direct UPDATE streak_count should be blocked';
    END IF;
END $$;
SELECT 'User cannot update streak_count directly' AS check_name, true AS pass;

-- Test 4: User cannot directly update badges or cities_visited.
DO $$
DECLARE
    was_blocked boolean := false;
BEGIN
    BEGIN
        UPDATE public.profiles
        SET badges = ARRAY['hacked']
        WHERE id = current_setting('request.jwt.claim.sub')::uuid;
    EXCEPTION
        WHEN insufficient_privilege THEN
            was_blocked := true;
    END;
    IF NOT was_blocked THEN
        RAISE EXCEPTION 'Direct UPDATE badges should be blocked';
    END IF;
END $$;
SELECT 'User cannot update badges directly' AS check_name, true AS pass;

DO $$
DECLARE
    was_blocked boolean := false;
BEGIN
    BEGIN
        UPDATE public.profiles
        SET cities_visited = ARRAY['hacked']
        WHERE id = current_setting('request.jwt.claim.sub')::uuid;
    EXCEPTION
        WHEN insufficient_privilege THEN
            was_blocked := true;
    END;
    IF NOT was_blocked THEN
        RAISE EXCEPTION 'Direct UPDATE cities_visited should be blocked';
    END IF;
END $$;
SELECT 'User cannot update cities_visited directly' AS check_name, true AS pass;

-- Test 5: Non-admin cannot update admin_role.
DO $$
DECLARE
    was_blocked boolean := false;
BEGIN
    BEGIN
        UPDATE public.profiles
        SET admin_role = true
        WHERE id = current_setting('request.jwt.claim.sub')::uuid;
    EXCEPTION
        WHEN insufficient_privilege THEN
            was_blocked := true;
    END;
    IF NOT was_blocked THEN
        RAISE EXCEPTION 'Direct UPDATE admin_role should be blocked';
    END IF;
END $$;
SELECT 'Non-admin cannot update admin_role directly' AS check_name, true AS pass;

ROLLBACK;
