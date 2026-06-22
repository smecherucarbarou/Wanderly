-- Add AI/Plus RPCs used by the app and restore server-calculated streak restore cost.

CREATE TABLE IF NOT EXISTS public.plus_entitlements (
    user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'inactive',
    provider text,
    product_id text,
    entitlement text,
    current_period_end timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE public.plus_entitlements ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own plus entitlement" ON public.plus_entitlements;
CREATE POLICY "Users can view own plus entitlement"
ON public.plus_entitlements FOR SELECT
TO authenticated
USING (auth.uid() = user_id);

GRANT SELECT ON public.plus_entitlements TO authenticated;

CREATE OR REPLACE FUNCTION public.get_my_plus_entitlement()
RETURNS TABLE (
    is_plus boolean,
    status text,
    provider text,
    product_id text,
    entitlement text,
    current_period_end text
)
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, pg_temp
AS $$
    WITH latest AS (
        SELECT
            e.status,
            e.provider,
            e.product_id,
            e.entitlement,
            e.current_period_end
        FROM public.plus_entitlements AS e
        WHERE e.user_id = auth.uid()
        ORDER BY e.updated_at DESC
        LIMIT 1
    )
    SELECT
        (
            lower(latest.status) IN ('active', 'trialing', 'dev')
            AND (latest.current_period_end IS NULL OR latest.current_period_end > now())
        ) AS is_plus,
        latest.status,
        latest.provider,
        latest.product_id,
        latest.entitlement,
        latest.current_period_end::text
    FROM latest
    UNION ALL
    SELECT
        false,
        NULL::text,
        NULL::text,
        NULL::text,
        NULL::text,
        NULL::text
    WHERE NOT EXISTS (SELECT 1 FROM latest);
$$;

REVOKE ALL ON FUNCTION public.get_my_plus_entitlement() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_my_plus_entitlement() FROM anon;
GRANT EXECUTE ON FUNCTION public.get_my_plus_entitlement() TO authenticated;

CREATE OR REPLACE FUNCTION public.consume_ai_quota(
    p_cost integer DEFAULT 1,
    p_free_limit integer DEFAULT 5,
    p_plus_limit integer DEFAULT 100
)
RETURNS TABLE (
    allowed boolean,
    is_plus boolean,
    used integer,
    "limit" integer,
    remaining integer,
    reset_date text
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_today date := (now() AT TIME ZONE 'utc')::date;
    v_reset_date text := ((now() AT TIME ZONE 'utc')::date + 1)::text;
    v_is_plus boolean := false;
    v_limit integer;
    v_used integer := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF p_cost IS NULL OR p_cost <= 0 THEN
        RAISE EXCEPTION 'Invalid quota cost'
            USING ERRCODE = '22023';
    END IF;

    IF p_free_limit IS NULL OR p_free_limit <= 0 OR p_plus_limit IS NULL OR p_plus_limit <= 0 THEN
        RAISE EXCEPTION 'Invalid quota limit'
            USING ERRCODE = '22023';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM public.plus_entitlements AS e
        WHERE e.user_id = v_user_id
          AND lower(e.status) IN ('active', 'trialing', 'dev')
          AND (e.current_period_end IS NULL OR e.current_period_end > now())
    )
    INTO v_is_plus;

    v_limit := CASE WHEN v_is_plus THEN p_plus_limit ELSE p_free_limit END;

    INSERT INTO public.api_quotas (user_id, provider, request_date, request_count)
    VALUES (v_user_id, 'ai', v_today, 0)
    ON CONFLICT (user_id, provider, request_date) DO NOTHING;

    SELECT q.request_count
    INTO v_used
    FROM public.api_quotas AS q
    WHERE q.user_id = v_user_id
      AND q.provider = 'ai'
      AND q.request_date = v_today
    FOR UPDATE;

    IF COALESCE(v_used, 0) + p_cost > v_limit THEN
        RETURN QUERY SELECT
            false,
            v_is_plus,
            COALESCE(v_used, 0),
            v_limit,
            GREATEST(v_limit - COALESCE(v_used, 0), 0),
            v_reset_date;
        RETURN;
    END IF;

    UPDATE public.api_quotas AS q
    SET request_count = q.request_count + p_cost
    WHERE q.user_id = v_user_id
      AND q.provider = 'ai'
      AND q.request_date = v_today
    RETURNING q.request_count INTO v_used;

    RETURN QUERY SELECT
        true,
        v_is_plus,
        v_used,
        v_limit,
        GREATEST(v_limit - v_used, 0),
        v_reset_date;
END;
$$;

REVOKE ALL ON FUNCTION public.consume_ai_quota(integer, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.consume_ai_quota(integer, integer, integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.consume_ai_quota(integer, integer, integer) TO authenticated;

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

    IF cost IS NULL OR cost <> v_expected_cost THEN
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

REVOKE ALL ON FUNCTION public.restore_streak(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.restore_streak(integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.restore_streak(integer) TO authenticated;
