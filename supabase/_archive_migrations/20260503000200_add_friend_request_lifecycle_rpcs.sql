-- Add authenticated friend-request lifecycle RPCs used by SocialRepository.

CREATE OR REPLACE FUNCTION public.get_pending_friend_request_profiles()
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
    FROM public.friendships AS f
    JOIN public.profiles AS p
      ON p.id = f.user_id
    WHERE auth.uid() IS NOT NULL
      AND f.friend_id = auth.uid()
      AND f.status = 'pending'
    ORDER BY f.created_at ASC, p.username NULLS LAST, p.id;
$$;

CREATE OR REPLACE FUNCTION public.accept_friend_request(p_requester_id uuid)
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
BEGIN
    IF v_user_id IS NULL THEN
        RETURN QUERY SELECT false, 'not_authenticated'::text, 'Authentication required'::text;
        RETURN;
    END IF;

    UPDATE public.friendships AS f
    SET status = 'accepted'
    WHERE f.user_id = p_requester_id
      AND f.friend_id = v_user_id
      AND f.status = 'pending';

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'not_pending_request'::text, 'Pending friend request not found'::text;
        RETURN;
    END IF;

    RETURN QUERY SELECT true, NULL::text, NULL::text;
END;
$$;

CREATE OR REPLACE FUNCTION public.reject_friend_request(p_requester_id uuid)
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
BEGIN
    IF v_user_id IS NULL THEN
        RETURN QUERY SELECT false, 'not_authenticated'::text, 'Authentication required'::text;
        RETURN;
    END IF;

    DELETE FROM public.friendships AS f
    WHERE f.user_id = p_requester_id
      AND f.friend_id = v_user_id
      AND f.status = 'pending';

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'not_pending_request'::text, 'Pending friend request not found'::text;
        RETURN;
    END IF;

    RETURN QUERY SELECT true, NULL::text, NULL::text;
END;
$$;

REVOKE ALL ON FUNCTION public.get_pending_friend_request_profiles() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.accept_friend_request(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.reject_friend_request(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_pending_friend_request_profiles() FROM anon;
REVOKE ALL ON FUNCTION public.accept_friend_request(uuid) FROM anon;
REVOKE ALL ON FUNCTION public.reject_friend_request(uuid) FROM anon;
GRANT EXECUTE ON FUNCTION public.get_pending_friend_request_profiles() TO authenticated;
GRANT EXECUTE ON FUNCTION public.accept_friend_request(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.reject_friend_request(uuid) TO authenticated;
