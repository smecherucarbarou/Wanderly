-- Applied directly to live 2026-06-24 via Management API + Studio.
-- DO NOT re-run. Mark applied: supabase migration repair --status applied 20260624000000
-- DO NOT: supabase db push / supabase db reset --linked

-- ============================================================
-- Block 1: finalize_hive_challenge — service_role only.
-- No client calls it; no auth.uid() guard; confirmed via grep.
-- ============================================================
REVOKE ALL ON FUNCTION public.finalize_hive_challenge(uuid)
  FROM anon, authenticated;

-- ============================================================
-- Block 2: mutators — drop anon access, keep authenticated.
-- All have internal auth.uid() IS NULL guards; anon exposure
-- was accidental (ALTER DEFAULT PRIVILEGES FOR ROLE postgres
-- previously granted ALL ON FUNCTIONS to anon).
-- ============================================================
REVOKE EXECUTE ON FUNCTION public.claim_referral(text) FROM anon;
REVOKE EXECUTE ON FUNCTION public.claim_streak_milestone(integer) FROM anon;
REVOKE EXECUTE ON FUNCTION public.consume_ai_quota(integer, integer, integer) FROM anon;
REVOKE EXECUTE ON FUNCTION public.contribute_to_challenge(uuid, integer) FROM anon;
REVOKE EXECUTE ON FUNCTION public.discover_gem(uuid) FROM anon;
REVOKE EXECUTE ON FUNCTION public.discover_gem_by_place(text, double precision, double precision, text, text) FROM anon;
REVOKE EXECUTE ON FUNCTION public.equip_cosmetic(uuid) FROM anon;
REVOKE EXECUTE ON FUNCTION public.log_mission_completion(uuid, text) FROM anon;
REVOKE EXECUTE ON FUNCTION public.purchase_shop_item(uuid) FROM anon;
REVOKE EXECUTE ON FUNCTION public.use_streak_freeze() FROM anon;
REVOKE EXECUTE ON FUNCTION public.get_my_plus_entitlement() FROM anon;
REVOKE EXECUTE ON FUNCTION public.get_friend_locations() FROM anon;

-- Internal helper — called only from SECURITY DEFINER context (consume_ai_quota).
-- No client calls confirmed via grep. Revoke from both anon and authenticated.
REVOKE EXECUTE ON FUNCTION public.is_wanderly_plus(uuid) FROM anon, authenticated;

-- ============================================================
-- Block 3: default privilege posture fix.
-- Only affects FUTURE functions created by the postgres role.
-- Existing grants handled by explicit REVOKEs above/below.
-- supabase_admin default ACL left unchanged (platform-managed).
-- ============================================================
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
  REVOKE ALL ON FUNCTIONS FROM anon;

-- ============================================================
-- Block 4: supabase_admin-granted functions.
-- Management API session cannot revoke these (not supabase_admin
-- member). Applied manually via Studio SQL editor.
-- Risk: trigger functions (is_trigger=true) cannot be invoked
-- via RPC regardless of EXECUTE grant — cosmetic only.
-- generate_friend_code is read-only (probes friend_code uniqueness)
-- — low risk, but revoked for hygiene.
-- ============================================================
REVOKE EXECUTE ON FUNCTION public.generate_friend_code()           FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.protect_friendship_identity()    FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.protect_profiles_client_fields() FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.set_updated_at()                 FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.sync_hive_rank()                 FROM anon, authenticated;
