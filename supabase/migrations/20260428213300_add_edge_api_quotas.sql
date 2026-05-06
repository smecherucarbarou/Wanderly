-- MAJ-06: per-user daily quotas for paid Edge Function upstream providers.

CREATE TABLE IF NOT EXISTS public.api_usage_limits (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    provider text NOT NULL CHECK (provider IN ('gemini', 'places')),
    window_start date NOT NULL DEFAULT current_date,
    request_count integer NOT NULL DEFAULT 0 CHECK (request_count >= 0),
    PRIMARY KEY (user_id, provider, window_start)
);

ALTER TABLE public.api_usage_limits ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.api_usage_limits FROM PUBLIC;
REVOKE ALL ON public.api_usage_limits FROM anon;
REVOKE ALL ON public.api_usage_limits FROM authenticated;

CREATE OR REPLACE FUNCTION public.consume_api_quota(
    provider_name text,
    max_requests_per_day integer
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_window_start date := (now() AT TIME ZONE 'utc')::date;
    v_request_count integer;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF provider_name NOT IN ('gemini', 'places') THEN
        RAISE EXCEPTION 'Unsupported provider'
            USING ERRCODE = '22023';
    END IF;

    IF max_requests_per_day IS NULL OR max_requests_per_day <= 0 THEN
        RAISE EXCEPTION 'Invalid quota limit'
            USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.api_usage_limits (
        user_id,
        provider,
        window_start,
        request_count
    )
    VALUES (
        v_user_id,
        provider_name,
        v_window_start,
        0
    )
    ON CONFLICT (user_id, provider, window_start) DO NOTHING;

    UPDATE public.api_usage_limits AS usage
    SET request_count = usage.request_count + 1
    WHERE usage.user_id = v_user_id
      AND usage.provider = provider_name
      AND usage.window_start = v_window_start
      AND usage.request_count < max_requests_per_day
    RETURNING usage.request_count
    INTO v_request_count;

    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.consume_api_quota(text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.consume_api_quota(text, integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.consume_api_quota(text, integer) TO authenticated;
