-- MAJ-01 mission reward integrity verification.
--
-- Usage after applying migrations:
--
--   psql "$DATABASE_URL" \
--     --set=user_a='00000000-0000-0000-0000-000000000001' \
--     --set=user_b='00000000-0000-0000-0000-000000000002' \
--     --file supabase/tests/mission_reward_integrity.sql
--
-- The supplied users must already exist in auth.users and public.profiles.
-- The script runs in a transaction and rolls back its profile mutations.

\if :{?user_a}
\else
  \error 'Missing required psql variable: user_a'
\endif

\if :{?user_b}
\else
  \error 'Missing required psql variable: user_b'
\endif

BEGIN;

RESET ROLE;

UPDATE public.profiles
SET honey = 0,
    streak_count = 0,
    last_mission_date = NULL
WHERE id IN (:'user_a'::uuid, :'user_b'::uuid);

-- Unauthenticated completion rejected.
SET LOCAL ROLE anon;
SELECT set_config('request.jwt.claim.role', 'anon', true);
SELECT set_config('request.jwt.claim.sub', '', true);

DO $$
DECLARE
    was_rejected boolean := false;
BEGIN
    BEGIN
        PERFORM * FROM public.complete_mission();
    EXCEPTION
        WHEN invalid_authorization_specification OR insufficient_privilege THEN
            was_rejected := true;
    END;

    IF NOT was_rejected THEN
        RAISE EXCEPTION 'Unauthenticated completion rejected check failed';
    END IF;
END $$;

-- Valid completion succeeds once for User A.
RESET ROLE;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.role', 'authenticated', true);
SELECT set_config('request.jwt.claim.sub', :'user_a', true);

CREATE TEMP TABLE mission_first_result AS
SELECT * FROM public.complete_mission();

SELECT
    'Valid completion succeeds once' AS check_name,
    completed = true
    AND duplicate = false
    AND honey = 50
    AND streak_count = 1
    AND reward_honey = 50 AS pass
FROM mission_first_result;

-- Duplicate completion does not double-award.
CREATE TEMP TABLE mission_duplicate_result AS
SELECT * FROM public.complete_mission();

SELECT
    'Duplicate completion does not double-award' AS check_name,
    duplicate = true
    AND completed = false
    AND honey = (SELECT honey FROM mission_first_result) AS pass
FROM mission_duplicate_result;

-- User A cannot complete User B mission because complete_mission has no user_id parameter
-- and updates only auth.uid().
SELECT
    'User A cannot complete User B mission' AS check_name,
    p.honey = 0 AND p.streak_count = 0 AND p.last_mission_date IS NULL AS pass
FROM public.profiles AS p
WHERE p.id = :'user_b'::uuid;

SELECT
    'complete_mission does not accept user_id' AS check_name,
    count(*) = 0 AS pass
FROM information_schema.parameters
WHERE specific_schema = 'public'
  AND specific_name LIKE 'complete_mission%'
  AND parameter_name IN ('user_id', 'p_user_id', 'profile_id');

-- Direct PATCH honey/streak_count fails.
DO $$
DECLARE
    was_blocked boolean := false;
BEGIN
    BEGIN
        UPDATE public.profiles
        SET honey = 9999,
            streak_count = 999
        WHERE id = current_setting('request.jwt.claim.sub')::uuid;
    EXCEPTION
        WHEN insufficient_privilege THEN
            was_blocked := true;
    END;

    IF NOT was_blocked THEN
        RAISE EXCEPTION 'Direct PATCH honey/streak_count fails check failed';
    END IF;
END $$;

SELECT
    'Direct PATCH honey/streak_count fails' AS check_name,
    true AS pass;

ROLLBACK;
