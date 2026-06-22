-- Streak milestones. This mirrors the definition already applied to the LIVE database
-- via the SQL editor (it was NOT applied through this migration). Kept idempotent so the
-- repo matches live; do not add thresholds/columns that are not present in live.

CREATE TABLE IF NOT EXISTS public.streak_milestones (
    threshold integer PRIMARY KEY,
    reward_honey integer NOT NULL,
    badge text
);

CREATE TABLE IF NOT EXISTS public.streak_milestone_claims (
    user_id uuid NOT NULL,
    threshold integer NOT NULL,
    claimed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, threshold)
);

ALTER TABLE public.streak_milestones ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.streak_milestone_claims ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "streak_milestones_read" ON public.streak_milestones;
CREATE POLICY "streak_milestones_read"
ON public.streak_milestones FOR SELECT
TO authenticated
USING (true);

DROP POLICY IF EXISTS "smc_select_own" ON public.streak_milestone_claims;
CREATE POLICY "smc_select_own"
ON public.streak_milestone_claims FOR SELECT
TO authenticated
USING (user_id = auth.uid());

GRANT SELECT ON public.streak_milestones TO authenticated;
GRANT SELECT ON public.streak_milestone_claims TO authenticated;

-- Live seed values; client renders whatever the catalog holds.
INSERT INTO public.streak_milestones (threshold, reward_honey, badge) VALUES
    (7, 50, 'week_warrior'),
    (30, 250, 'month_master'),
    (100, 1000, 'centurion')
ON CONFLICT (threshold) DO NOTHING;

-- Verbatim live body: returns jsonb, grants honey + badge server-side.
CREATE OR REPLACE FUNCTION public.claim_streak_milestone(p_threshold integer)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'auth', 'extensions'
AS $function$
DECLARE v_uid uuid := auth.uid(); v_m public.streak_milestones%rowtype; v_streak int;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  SELECT * INTO v_m FROM public.streak_milestones WHERE threshold=p_threshold;
  IF NOT FOUND THEN RETURN jsonb_build_object('success',false,'error','invalid_milestone'); END IF;
  SELECT streak_count INTO v_streak FROM public.profiles WHERE id=v_uid;
  IF v_streak < p_threshold THEN RETURN jsonb_build_object('success',false,'error','not_reached'); END IF;
  IF EXISTS (SELECT 1 FROM public.streak_milestone_claims WHERE user_id=v_uid AND threshold=p_threshold) THEN
    RETURN jsonb_build_object('success',false,'error','already_claimed'); END IF;
  INSERT INTO public.streak_milestone_claims(user_id, threshold) VALUES (v_uid, p_threshold);
  UPDATE public.profiles
     SET honey = honey + v_m.reward_honey,
         badges = CASE WHEN v_m.badge IS NOT NULL AND NOT (v_m.badge = ANY(badges))
                       THEN array_append(badges, v_m.badge) ELSE badges END
   WHERE id=v_uid;
  RETURN jsonb_build_object('success',true,'reward_honey',v_m.reward_honey,'badge',v_m.badge);
END $function$;

GRANT EXECUTE ON FUNCTION public.claim_streak_milestone(integer) TO authenticated;
