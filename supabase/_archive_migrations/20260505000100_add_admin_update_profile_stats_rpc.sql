-- Admin-only RPC for updating honey/streak_count/hive_rank on any profile.
-- Replaces direct UPDATE from the client which fails due to column-level GRANT restrictions.

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
SET search_path = public, pg_temp
AS $$
DECLARE
    v_profile public.profiles%ROWTYPE;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF NOT public.is_current_profile_admin() THEN
        RAISE EXCEPTION 'Admin role required'
            USING ERRCODE = '42501';
    END IF;

    UPDATE public.profiles AS p
    SET
        honey = COALESCE(new_honey, p.honey),
        streak_count = COALESCE(new_streak_count, p.streak_count),
        hive_rank = COALESCE(new_hive_rank, p.hive_rank)
    WHERE p.id = target_profile_id
    RETURNING p.*
    INTO v_profile;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found'
            USING ERRCODE = 'P0002';
    END IF;

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
