-- Add authenticated username update RPC used by ProfileRepository.updateUsername.

DROP FUNCTION IF EXISTS public.update_profile_username(text);

CREATE OR REPLACE FUNCTION public.update_profile_username(p_username text)
RETURNS TABLE (
    success boolean,
    error_code text,
    error_message text
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_username text := btrim(COALESCE(p_username, ''));
BEGIN
    IF v_user_id IS NULL THEN
        RETURN QUERY SELECT false, 'not_authenticated'::text, 'Authentication required'::text;
        RETURN;
    END IF;

    IF v_username = ''
       OR char_length(v_username) < 3
       OR char_length(v_username) > 32
       OR v_username !~ '^[A-Za-z0-9_][A-Za-z0-9_.-]{2,31}$' THEN
        RETURN QUERY SELECT false, 'invalid_username'::text, 'Username is invalid'::text;
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.profiles AS p
        WHERE lower(p.username) = lower(v_username)
          AND p.id <> v_user_id
    ) THEN
        RETURN QUERY SELECT false, 'username_taken'::text, 'Username is already taken'::text;
        RETURN;
    END IF;

    UPDATE public.profiles AS p
    SET
        username = v_username,
        updated_at = now()
    WHERE p.id = v_user_id;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'profile_not_found'::text, 'Profile not found'::text;
        RETURN;
    END IF;

    RETURN QUERY SELECT true, NULL::text, NULL::text;
EXCEPTION
    WHEN unique_violation THEN
        RETURN QUERY SELECT false, 'username_taken'::text, 'Username is already taken'::text;
END;
$$;

REVOKE ALL ON FUNCTION public.update_profile_username(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.update_profile_username(text) FROM anon;
GRANT EXECUTE ON FUNCTION public.update_profile_username(text) TO authenticated;
