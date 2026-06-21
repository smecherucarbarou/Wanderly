-- Streak milestones: catalog of streak-day rewards plus per-user claim ledger,
-- and the claim_streak_milestone RPC that grants the reward exactly once.

CREATE TABLE IF NOT EXISTS public.streak_milestones (
    threshold integer PRIMARY KEY CHECK (threshold > 0),
    title text NOT NULL,
    reward_honey integer NOT NULL DEFAULT 0 CHECK (reward_honey >= 0)
);

ALTER TABLE public.streak_milestones ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Anyone can view streak milestones" ON public.streak_milestones;
CREATE POLICY "Anyone can view streak milestones"
ON public.streak_milestones FOR SELECT
TO authenticated
USING (true);

GRANT SELECT ON public.streak_milestones TO authenticated;

CREATE TABLE IF NOT EXISTS public.streak_milestone_claims (
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    threshold integer NOT NULL REFERENCES public.streak_milestones(threshold) ON DELETE CASCADE,
    claimed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, threshold)
);

ALTER TABLE public.streak_milestone_claims ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own milestone claims" ON public.streak_milestone_claims;
CREATE POLICY "Users can view own milestone claims"
ON public.streak_milestone_claims FOR SELECT
TO authenticated
USING (auth.uid() = user_id);

GRANT SELECT ON public.streak_milestone_claims TO authenticated;

-- Seed the default milestone ladder. Values live in the table so the client renders
-- whatever the catalog holds without any code changes.
INSERT INTO public.streak_milestones (threshold, title, reward_honey) VALUES
    (3, 'Warming Up', 50),
    (7, 'One Week Strong', 150),
    (30, 'Monthly Devotee', 750),
    (100, 'Centurion', 3000)
ON CONFLICT (threshold) DO NOTHING;

CREATE OR REPLACE FUNCTION public.claim_streak_milestone(p_threshold integer)
RETURNS TABLE (
    success boolean,
    error text,
    reward_honey integer,
    honey integer
)
LANGUAGE plpgsql
SECURITY DEFINER
VOLATILE
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_milestone public.streak_milestones%ROWTYPE;
    v_profile public.profiles%ROWTYPE;
    v_new_honey integer;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT m.* INTO v_milestone
    FROM public.streak_milestones AS m
    WHERE m.threshold = p_threshold;

    IF NOT FOUND THEN
        RETURN QUERY SELECT false, 'unknown_milestone'::text, NULL::integer, NULL::integer;
        RETURN;
    END IF;

    SELECT p.* INTO v_profile
    FROM public.profiles AS p
    WHERE p.id = v_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found'
            USING ERRCODE = 'P0002';
    END IF;

    IF COALESCE(v_profile.streak_count, 0) < p_threshold THEN
        RETURN QUERY SELECT false, 'not_reached'::text, v_milestone.reward_honey, COALESCE(v_profile.honey, 0);
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.streak_milestone_claims AS c
        WHERE c.user_id = v_user_id AND c.threshold = p_threshold
    ) THEN
        RETURN QUERY SELECT false, 'already_claimed'::text, v_milestone.reward_honey, COALESCE(v_profile.honey, 0);
        RETURN;
    END IF;

    INSERT INTO public.streak_milestone_claims (user_id, threshold)
    VALUES (v_user_id, p_threshold);

    UPDATE public.profiles AS p
    SET honey = COALESCE(p.honey, 0) + v_milestone.reward_honey
    WHERE p.id = v_user_id
    RETURNING p.honey INTO v_new_honey;

    RETURN QUERY SELECT true, NULL::text, v_milestone.reward_honey, v_new_honey;
END;
$$;

REVOKE ALL ON FUNCTION public.claim_streak_milestone(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.claim_streak_milestone(integer) FROM anon;
GRANT EXECUTE ON FUNCTION public.claim_streak_milestone(integer) TO authenticated;
