-- Harden admin_update_profile_stats so even server-confirmed admins cannot write
-- negative or unreasonable progress values by accident.

CREATE OR REPLACE FUNCTION public.admin_update_profile_stats(
    target_profile_id uuid,
    new_honey integer,
    new_streak_count integer,
    new_hive_rank integer
)
RETURNS TABLE (
    success boolean,
    honey integer,
    streak_count integer,
    hive_rank integer
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, auth, storage, extensions, pg_temp
AS $$
DECLARE
    v_profile public.profiles%ROWTYPE;
    v_safe_honey integer;
    v_safe_streak_count integer;
    v_safe_hive_rank integer;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF NOT public.is_current_profile_admin() THEN
        RAISE EXCEPTION 'Admin role required'
            USING ERRCODE = '42501';
    END IF;

    SELECT *
    INTO v_profile
    FROM public.profiles
    WHERE id = target_profile_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found'
            USING ERRCODE = 'P0002';
    END IF;

    v_safe_honey := LEAST(GREATEST(COALESCE(new_honey, v_profile.honey, 0), 0), 1000000);
    v_safe_streak_count := LEAST(GREATEST(COALESCE(new_streak_count, v_profile.streak_count, 0), 0), 3650);
    v_safe_hive_rank := LEAST(GREATEST(COALESCE(new_hive_rank, v_profile.hive_rank, 1), 1), 4);

    UPDATE public.profiles AS p
    SET
        honey = v_safe_honey,
        streak_count = v_safe_streak_count,
        hive_rank = v_safe_hive_rank
    WHERE p.id = target_profile_id
    RETURNING p.*
    INTO v_profile;

    RETURN QUERY SELECT
        true,
        COALESCE(v_profile.honey, 0),
        COALESCE(v_profile.streak_count, 0),
        COALESCE(v_profile.hive_rank, 1);
END;
$$;

REVOKE ALL ON FUNCTION public.admin_update_profile_stats(uuid, integer, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin_update_profile_stats(uuid, integer, integer, integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.admin_update_profile_stats(uuid, integer, integer, integer) TO authenticated;

-- Verification query:
-- SELECT
--   p.proname,
--   has_function_privilege('anon', p.oid, 'EXECUTE') AS anon_can_execute,
--   has_function_privilege('authenticated', p.oid, 'EXECUTE') AS authenticated_can_execute,
--   p.prosecdef AS security_definer,
--   p.proconfig
-- FROM pg_proc p
-- JOIN pg_namespace n ON n.oid = p.pronamespace
-- WHERE n.nspname = 'public'
--   AND p.proname = 'admin_update_profile_stats';
