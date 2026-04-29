-- CRIT-01 profile RLS isolation verification.
--
-- Usage after applying migrations to a disposable or production-like Supabase project:
--
--   psql "$DATABASE_URL" \
--     --set=user_a='00000000-0000-0000-0000-000000000001' \
--     --set=user_b='00000000-0000-0000-0000-000000000002' \
--     --set=user_b_friend_code='ABC123' \
--     --file supabase/tests/profile_rls_isolation.sql
--
-- The supplied users must already exist in auth.users and public.profiles.
-- The expected result is every "pass" column returning true and no exceptions.

\if :{?user_a}
\else
  \error 'Missing required psql variable: user_a'
\endif

\if :{?user_b}
\else
  \error 'Missing required psql variable: user_b'
\endif

\if :{?user_b_friend_code}
\else
  \error 'Missing required psql variable: user_b_friend_code'
\endif

BEGIN;

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.role', 'authenticated', true);
SELECT set_config('request.jwt.claim.sub', :'user_a', true);

-- User A can SELECT own profile.
SELECT
    'User A can SELECT own profile' AS check_name,
    count(*) = 1 AS pass
FROM public.profiles
WHERE id = :'user_a'::uuid;

-- User A cannot SELECT User B from profiles.
SELECT
    'User A cannot SELECT User B from profiles' AS check_name,
    count(*) = 0 AS pass
FROM public.profiles
WHERE id = :'user_b'::uuid;

-- User A cannot UPDATE User B profile.
WITH attempted_update AS (
    UPDATE public.profiles
    SET username = username
    WHERE id = :'user_b'::uuid
    RETURNING id
)
SELECT
    'User A cannot UPDATE User B profile' AS check_name,
    count(*) = 0 AS pass
FROM attempted_update;

-- profiles_public does not contain private columns.
SELECT
    'profiles_public does not contain private columns' AS check_name,
    count(*) = 0 AS pass
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'profiles_public'
  AND column_name IN (
      'last_lat',
      'last_lng',
      'admin_role',
      'last_mission_date',
      'updated_at',
      'email',
      'phone'
  );

-- Social/friend lookup still returns approved public fields.
WITH friend_lookup AS (
    SELECT *
    FROM public.find_profile_by_friend_code(:'user_b_friend_code')
)
SELECT
    'Social/friend lookup returns approved public fields' AS check_name,
    count(*) = 1 AS pass
FROM friend_lookup
WHERE id = :'user_b'::uuid;

-- Unauthenticated access is denied.
RESET ROLE;
SET LOCAL ROLE anon;
SELECT set_config('request.jwt.claim.role', 'anon', true);
SELECT set_config('request.jwt.claim.sub', '', true);

DO $$
DECLARE
    unauthenticated_profile_rows integer;
BEGIN
    SELECT count(*) INTO unauthenticated_profile_rows
    FROM public.profiles
    WHERE id = current_setting('request.jwt.claim.sub', true)::uuid;

    IF unauthenticated_profile_rows <> 0 THEN
        RAISE EXCEPTION 'Unauthenticated profile SELECT unexpectedly returned rows';
    END IF;
EXCEPTION
    WHEN invalid_text_representation THEN
        NULL;
END $$;

DO $$
DECLARE
    view_was_readable boolean := false;
BEGIN
    BEGIN
        PERFORM 1 FROM public.profiles_public LIMIT 1;
        view_was_readable := true;
    EXCEPTION
        WHEN insufficient_privilege THEN
            view_was_readable := false;
    END;

    IF view_was_readable THEN
        RAISE EXCEPTION 'Unauthenticated access is denied check failed: profiles_public was readable by anon';
    END IF;
END $$;

SELECT
    'Unauthenticated access is denied' AS check_name,
    true AS pass;

ROLLBACK;
