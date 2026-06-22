


SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;


CREATE SCHEMA IF NOT EXISTS "public";


ALTER SCHEMA "public" OWNER TO "pg_database_owner";


COMMENT ON SCHEMA "public" IS 'standard public schema';



CREATE OR REPLACE FUNCTION "public"."accept_friend_request"("p_requester_id" "uuid") RETURNS TABLE("success" boolean, "error_code" "text", "error_message" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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


ALTER FUNCTION "public"."accept_friend_request"("p_requester_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."accept_streak_loss"() RETURNS TABLE("updated" boolean, "honey" integer, "streak_count" integer, "last_mission_date" "date")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_profile public.profiles%ROWTYPE;
    v_today date := current_date;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT * INTO v_profile FROM public.profiles WHERE id = v_user_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found' USING ERRCODE = 'P0002';
    END IF;

    -- Only allow streak loss if the streak is broken (missed more than 1 day)
    IF v_profile.last_mission_date IS NOT NULL
       AND v_profile.last_mission_date < v_today - 1
       AND COALESCE(v_profile.streak_count, 0) > 0 THEN
        UPDATE public.profiles
        SET streak_count = 0
        WHERE id = v_user_id;

        RETURN QUERY SELECT
            true,
            COALESCE(v_profile.honey, 0),
            0,
            v_profile.last_mission_date;
    ELSE
        RETURN QUERY SELECT
            false,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
    END IF;
END;
$$;


ALTER FUNCTION "public"."accept_streak_loss"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."admin_update_profile_stats"("target_profile_id" "uuid", "new_honey" integer, "new_streak_count" integer, "new_hive_rank" integer) RETURNS TABLE("success" boolean, "honey" integer, "streak_count" integer, "hive_rank" integer)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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


ALTER FUNCTION "public"."admin_update_profile_stats"("target_profile_id" "uuid", "new_honey" integer, "new_streak_count" integer, "new_hive_rank" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."claim_referral"("p_friend_code" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_uid uuid := auth.uid(); v_ref uuid; v_reward int := 100;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  SELECT id INTO v_ref FROM public.profiles WHERE friend_code = upper(p_friend_code);
  IF v_ref IS NULL THEN RETURN jsonb_build_object('success',false,'error','code_not_found'); END IF;
  IF v_ref = v_uid THEN RETURN jsonb_build_object('success',false,'error','self_referral'); END IF;
  IF EXISTS (SELECT 1 FROM public.referrals WHERE referred_id=v_uid) THEN
    RETURN jsonb_build_object('success',false,'error','already_referred'); END IF;
  INSERT INTO public.referrals(referrer_id, referred_id, reward_honey, rewarded)
  VALUES (v_ref, v_uid, v_reward, true);
  UPDATE public.profiles SET honey = honey + v_reward WHERE id IN (v_ref, v_uid);
  RETURN jsonb_build_object('success',true,'reward_honey',v_reward);
END $$;


ALTER FUNCTION "public"."claim_referral"("p_friend_code" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."claim_streak_milestone"("p_threshold" integer) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
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
END $$;


ALTER FUNCTION "public"."claim_streak_milestone"("p_threshold" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."complete_mission"() RETURNS TABLE("completed" boolean, "duplicate" boolean, "honey" integer, "streak_count" integer, "last_mission_date" "date", "reward_honey" integer, "streak_bonus_honey" integer)
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_profile public.profiles%ROWTYPE;
    v_today date := current_date;
    v_base_reward integer := 50;
    v_streak_bonus integer := 0;
    v_new_honey integer;
    v_new_streak integer;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    SELECT * INTO v_profile FROM public.profiles WHERE id = v_user_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found' USING ERRCODE = 'P0002';
    END IF;

    -- Already completed today
    IF v_profile.last_mission_date = v_today THEN
        RETURN QUERY SELECT
            false,
            true,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_today,
            0,
            0;
        RETURN;
    END IF;

    -- Calculate streak
    IF v_profile.last_mission_date = v_today - 1 THEN
        v_new_streak := COALESCE(v_profile.streak_count, 0) + 1;
    ELSE
        v_new_streak := 1;
    END IF;

    -- Streak bonus: 10 honey per streak day (capped at 50)
    v_streak_bonus := LEAST(v_new_streak * 10, 50);
    v_new_honey := COALESCE(v_profile.honey, 0) + v_base_reward + v_streak_bonus;

    UPDATE public.profiles
    SET
        honey = v_new_honey,
        streak_count = v_new_streak,
        last_mission_date = v_today
    WHERE id = v_user_id;

    RETURN QUERY SELECT
        true,
        false,
        v_new_honey,
        v_new_streak,
        v_today,
        v_base_reward,
        v_streak_bonus;
END;
$$;


ALTER FUNCTION "public"."complete_mission"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."consume_ai_quota"("p_cost" integer DEFAULT 1, "p_free_limit" integer DEFAULT 5, "p_plus_limit" integer DEFAULT 100) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
declare
  v_uid uuid;
  v_is_plus boolean;
  v_limit integer;
  v_today date;
  v_current_count integer;
  v_new_count integer;
begin
  v_uid := auth.uid();

  if v_uid is null then
    raise exception 'not_authenticated'
      using errcode = '28000';
  end if;

  if p_cost is null or p_cost < 1 then
    raise exception 'invalid_cost'
      using errcode = '22023';
  end if;

  if p_free_limit is null or p_free_limit < 0 then
    raise exception 'invalid_free_limit'
      using errcode = '22023';
  end if;

  if p_plus_limit is null or p_plus_limit < 0 then
    raise exception 'invalid_plus_limit'
      using errcode = '22023';
  end if;

  v_today := current_date;
  v_is_plus := public.is_wanderly_plus(v_uid);
  v_limit := case when v_is_plus then p_plus_limit else p_free_limit end;

  insert into public.ai_usage_daily (
    user_id,
    usage_date,
    request_count,
    token_estimate,
    last_request_at
  )
  values (
    v_uid,
    v_today,
    0,
    0,
    null
  )
  on conflict (user_id, usage_date)
  do nothing;

  select request_count
  into v_current_count
  from public.ai_usage_daily
  where user_id = v_uid
    and usage_date = v_today
  for update;

  if v_current_count + p_cost > v_limit then
    return jsonb_build_object(
      'allowed', false,
      'is_plus', v_is_plus,
      'used', v_current_count,
      'limit', v_limit,
      'remaining', greatest(v_limit - v_current_count, 0),
      'reset_date', (v_today + 1)::text
    );
  end if;

  update public.ai_usage_daily
  set
    request_count = request_count + p_cost,
    last_request_at = now(),
    updated_at = now()
  where user_id = v_uid
    and usage_date = v_today
  returning request_count
  into v_new_count;

  return jsonb_build_object(
    'allowed', true,
    'is_plus', v_is_plus,
    'used', v_new_count,
    'limit', v_limit,
    'remaining', greatest(v_limit - v_new_count, 0),
    'reset_date', (v_today + 1)::text
  );
end;
$$;


ALTER FUNCTION "public"."consume_ai_quota"("p_cost" integer, "p_free_limit" integer, "p_plus_limit" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."consume_api_quota"("provider_name" "text", "max_requests_per_day" integer) RETURNS boolean
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_today date := current_date;
    v_count integer;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    INSERT INTO public.api_quotas (user_id, provider, request_date, request_count)
    VALUES (v_user_id, provider_name, v_today, 1)
    ON CONFLICT (user_id, provider, request_date)
    DO UPDATE SET request_count = public.api_quotas.request_count + 1
    RETURNING request_count INTO v_count;

    RETURN v_count <= max_requests_per_day;
END;
$$;


ALTER FUNCTION "public"."consume_api_quota"("provider_name" "text", "max_requests_per_day" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."contribute_to_challenge"("p_challenge_id" "uuid", "p_amount" integer DEFAULT 1) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_uid uuid := auth.uid(); v_start timestamptz; v_end timestamptz;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  SELECT starts_at, ends_at INTO v_start, v_end FROM public.hive_challenges WHERE id=p_challenge_id;
  IF NOT FOUND THEN RETURN jsonb_build_object('success',false,'error','challenge_not_found'); END IF;
  IF now() NOT BETWEEN v_start AND v_end THEN
    RETURN jsonb_build_object('success',false,'error','challenge_inactive'); END IF;
  INSERT INTO public.hive_challenge_progress(challenge_id, user_id, contribution)
  VALUES (p_challenge_id, v_uid, GREATEST(p_amount,0))
  ON CONFLICT (challenge_id, user_id)
  DO UPDATE SET contribution = public.hive_challenge_progress.contribution + GREATEST(p_amount,0),
                updated_at = now();
  RETURN jsonb_build_object('success',true);
END $$;


ALTER FUNCTION "public"."contribute_to_challenge"("p_challenge_id" "uuid", "p_amount" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."discover_gem"("p_gem_id" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_uid uuid := auth.uid(); v_reward int := 10;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  IF NOT EXISTS (SELECT 1 FROM public.gems WHERE id=p_gem_id) THEN
    RETURN jsonb_build_object('success',false,'error','gem_not_found'); END IF;
  IF EXISTS (SELECT 1 FROM public.gem_discoveries WHERE user_id=v_uid AND gem_id=p_gem_id) THEN
    RETURN jsonb_build_object('success',false,'error','already_discovered'); END IF;
  INSERT INTO public.gem_discoveries(user_id, gem_id) VALUES (v_uid, p_gem_id);
  UPDATE public.profiles SET honey = honey + v_reward WHERE id = v_uid;
  RETURN jsonb_build_object('success',true,'reward_honey',v_reward);
END $$;


ALTER FUNCTION "public"."discover_gem"("p_gem_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."discover_gem_by_place"("p_name" "text", "p_lat" double precision, "p_lng" double precision, "p_category" "text" DEFAULT NULL::"text", "p_place_id" "text" DEFAULT NULL::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE
  v_uid    uuid := auth.uid();
  v_gem_id uuid;
  v_reward int  := 10;
  v_first  boolean;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  IF p_name IS NULL OR p_lat IS NULL OR p_lng IS NULL THEN
    RETURN jsonb_build_object('success',false,'error','invalid_place'); END IF;

  -- find-or-create gem: după place_id dacă există, altfel după coordonate rotunjite (~1m)
  IF p_place_id IS NOT NULL THEN
    SELECT id INTO v_gem_id FROM public.gems WHERE place_id = p_place_id;
  ELSE
    SELECT id INTO v_gem_id FROM public.gems
     WHERE place_id IS NULL
       AND round(lat::numeric, 5) = round(p_lat::numeric, 5)
       AND round(lng::numeric, 5) = round(p_lng::numeric, 5)
     LIMIT 1;
  END IF;

  IF v_gem_id IS NULL THEN
    INSERT INTO public.gems(name, category, place_id, lat, lng)
    VALUES (p_name, p_category, p_place_id, p_lat, p_lng)
    RETURNING id INTO v_gem_id;
  END IF;

  -- descoperire o singură dată per user+gem
  INSERT INTO public.gem_discoveries(user_id, gem_id)
  VALUES (v_uid, v_gem_id)
  ON CONFLICT (user_id, gem_id) DO NOTHING;

  IF NOT FOUND THEN
    RETURN jsonb_build_object('success',false,'error','already_discovered','gem_id',v_gem_id);
  END IF;

  -- prima descoperire ever? (acordă badge-ul gem_finder)
  SELECT NOT EXISTS (
    SELECT 1 FROM public.gem_discoveries WHERE user_id = v_uid AND gem_id <> v_gem_id
  ) INTO v_first;

  UPDATE public.profiles
     SET honey  = honey + v_reward,
         badges = CASE WHEN v_first AND NOT ('gem_finder' = ANY(badges))
                       THEN array_append(badges, 'gem_finder') ELSE badges END
   WHERE id = v_uid;

  RETURN jsonb_build_object('success',true,'reward_honey',v_reward,'gem_id',v_gem_id,
                            'badge', CASE WHEN v_first THEN 'gem_finder' ELSE NULL END);
END $$;


ALTER FUNCTION "public"."discover_gem_by_place"("p_name" "text", "p_lat" double precision, "p_lng" double precision, "p_category" "text", "p_place_id" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."equip_cosmetic"("p_item_id" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_uid uuid := auth.uid(); v_type text;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  IF NOT EXISTS (SELECT 1 FROM public.user_inventory WHERE user_id=v_uid AND item_id=p_item_id) THEN
    RETURN jsonb_build_object('success',false,'error','not_owned'); END IF;
  SELECT type INTO v_type FROM public.shop_items WHERE id=p_item_id;
  UPDATE public.profiles SET
    equipped_frame        = CASE WHEN v_type='avatar_frame' THEN p_item_id ELSE equipped_frame END,
    equipped_skin         = CASE WHEN v_type='buzzy_skin'   THEN p_item_id ELSE equipped_skin END,
    equipped_widget_theme = CASE WHEN v_type='widget_theme' THEN p_item_id ELSE equipped_widget_theme END
  WHERE id=v_uid;
  RETURN jsonb_build_object('success',true,'type',v_type);
END $$;


ALTER FUNCTION "public"."equip_cosmetic"("p_item_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."finalize_hive_challenge"("p_challenge_id" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_ch public.hive_challenges%rowtype; v_total int; v_paid int;
BEGIN
  SELECT * INTO v_ch FROM public.hive_challenges WHERE id = p_challenge_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('success',false,'error','challenge_not_found'); END IF;
  IF v_ch.rewarded THEN RETURN jsonb_build_object('success',false,'error','already_rewarded'); END IF;

  SELECT COALESCE(SUM(contribution),0) INTO v_total
  FROM public.hive_challenge_progress WHERE challenge_id = p_challenge_id;

  IF v_total < v_ch.goal_target THEN
    RETURN jsonb_build_object('success',false,'error','goal_not_reached','total',v_total,'target',v_ch.goal_target);
  END IF;

  UPDATE public.profiles p
     SET honey = honey + v_ch.reward_honey
    FROM public.hive_challenge_progress hp
   WHERE hp.challenge_id = p_challenge_id
     AND hp.contribution > 0
     AND p.id = hp.user_id;
  GET DIAGNOSTICS v_paid = ROW_COUNT;

  UPDATE public.hive_challenges SET rewarded = true WHERE id = p_challenge_id;

  RETURN jsonb_build_object('success',true,'rewarded_users',v_paid,
                            'reward_each',v_ch.reward_honey,'total',v_total);
END $$;


ALTER FUNCTION "public"."finalize_hive_challenge"("p_challenge_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."find_profile_by_friend_code"("code" "text") RETURNS TABLE("id" "uuid", "username" "text", "avatar_url" "text", "friend_code" "text", "honey" integer, "hive_rank" integer, "badges" "text"[], "cities_visited" "text"[], "streak_count" integer, "explorer_class" "text")
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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
    FROM public.profiles AS p
    WHERE auth.uid() IS NOT NULL
      AND upper(p.friend_code) = upper(code);
$$;


ALTER FUNCTION "public"."find_profile_by_friend_code"("code" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."generate_friend_code"() RETURNS "text"
    LANGUAGE "plpgsql"
    SET "search_path" TO 'public', 'extensions'
    AS $$
DECLARE
    generated_code text;
BEGIN
    LOOP
        generated_code := upper(substr(encode(extensions.gen_random_bytes(4), 'hex'), 1, 6));
        EXIT WHEN NOT EXISTS (
            SELECT 1 FROM public.profiles WHERE friend_code = generated_code
        );
    END LOOP;

    RETURN generated_code;
END;
$$;


ALTER FUNCTION "public"."generate_friend_code"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_accepted_friend_profiles"() RETURNS TABLE("id" "uuid", "username" "text", "avatar_url" "text", "friend_code" "text", "honey" integer, "hive_rank" integer, "badges" "text"[], "cities_visited" "text"[], "streak_count" integer, "explorer_class" "text")
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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
    FROM public.profiles AS p
    WHERE auth.uid() IS NOT NULL
      AND p.id IN (
          SELECT CASE WHEN f.user_id = auth.uid() THEN f.friend_id ELSE f.user_id END
          FROM public.friendships AS f
          WHERE (f.user_id = auth.uid() OR f.friend_id = auth.uid())
            AND f.status = 'accepted'
      )
    ORDER BY p.username NULLS LAST, p.id;
$$;


ALTER FUNCTION "public"."get_accepted_friend_profiles"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_friend_locations"() RETURNS TABLE("id" "uuid", "username" "text", "avatar_url" "text", "last_lat" double precision, "last_lng" double precision)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
  SELECT p.id, p.username, p.avatar_url, p.last_lat, p.last_lng
  FROM public.profiles p
  WHERE p.last_lat IS NOT NULL
    AND p.last_lng IS NOT NULL
    AND EXISTS (
      SELECT 1 FROM public.friendships f
      WHERE f.status = 'accepted'
        AND ( (f.user_id = auth.uid() AND f.friend_id = p.id)
           OR (f.friend_id = auth.uid() AND f.user_id = p.id) )
    );
$$;


ALTER FUNCTION "public"."get_friend_locations"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_my_plus_entitlement"() RETURNS TABLE("is_plus" boolean, "status" "text", "provider" "text", "product_id" "text", "entitlement" "text", "current_period_end" timestamp with time zone)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
declare
  v_uid uuid;
begin
  v_uid := auth.uid();

  if v_uid is null then
    raise exception 'not_authenticated'
      using errcode = '28000';
  end if;

  return query
  with best as (
    select
      s.status,
      s.provider,
      s.product_id,
      s.entitlement,
      s.current_period_end
    from public.wanderly_plus_subscriptions s
    where s.user_id = v_uid
      and s.entitlement = 'wanderly_plus'
      and s.status in ('active', 'trialing', 'canceled', 'dev')
      and (
        s.current_period_end is null
        or s.current_period_end > now()
      )
      and s.revoked_at is null
    order by
      s.current_period_end desc nulls first,
      s.updated_at desc
    limit 1
  )
  select
    true as is_plus,
    best.status,
    best.provider,
    best.product_id,
    best.entitlement,
    best.current_period_end
  from best

  union all

  select
    false as is_plus,
    null::text as status,
    null::text as provider,
    null::text as product_id,
    'wanderly_plus'::text as entitlement,
    null::timestamptz as current_period_end
  where not exists (select 1 from best);
end;
$$;


ALTER FUNCTION "public"."get_my_plus_entitlement"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_pending_friend_request_profiles"() RETURNS TABLE("id" "uuid", "username" "text", "avatar_url" "text", "friend_code" "text", "honey" integer, "hive_rank" integer, "badges" "text"[], "cities_visited" "text"[], "streak_count" integer, "explorer_class" "text")
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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


ALTER FUNCTION "public"."get_pending_friend_request_profiles"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_public_profile"("profile_user_id" "uuid") RETURNS TABLE("id" "uuid", "username" "text", "avatar_url" "text", "friend_code" "text", "honey" integer, "hive_rank" integer, "badges" "text"[], "cities_visited" "text"[], "streak_count" integer, "explorer_class" "text")
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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
    FROM public.profiles AS p
    WHERE auth.uid() IS NOT NULL
      AND p.id = profile_user_id
    LIMIT 1;
$$;


ALTER FUNCTION "public"."get_public_profile"("profile_user_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."get_social_leaderboard"("max_rows" integer DEFAULT 50) RETURNS TABLE("id" "uuid", "username" "text", "avatar_url" "text", "friend_code" "text", "honey" integer, "hive_rank" integer, "badges" "text"[], "cities_visited" "text"[], "streak_count" integer, "explorer_class" "text")
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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
    FROM public.profiles AS p
    WHERE auth.uid() IS NOT NULL
      AND (
          p.id = auth.uid()
          OR EXISTS (
              SELECT 1
              FROM public.friendships AS f
              WHERE f.status = 'accepted'
                AND (
                    (f.user_id = auth.uid() AND f.friend_id = p.id)
                    OR (f.friend_id = auth.uid() AND f.user_id = p.id)
                )
          )
      )
    ORDER BY p.honey DESC NULLS LAST, p.username NULLS LAST, p.id
    LIMIT LEAST(GREATEST(COALESCE(max_rows, 50), 1), 100);
$$;


ALTER FUNCTION "public"."get_social_leaderboard"("max_rows" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."handle_new_user"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
BEGIN
    INSERT INTO public.profiles (id, username, friend_code)
    VALUES (
        NEW.id,
        COALESCE(NULLIF(NEW.raw_user_meta_data ->> 'username', ''), 'user_' || substr(NEW.id::text, 1, 6)),
        public.generate_friend_code()
    )
    ON CONFLICT (id) DO NOTHING;

    RETURN NEW;
END;
$$;


ALTER FUNCTION "public"."handle_new_user"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."is_current_profile_admin"() RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.profiles AS p
        WHERE p.id = auth.uid()
          AND p.admin_role = true
    );
$$;


ALTER FUNCTION "public"."is_current_profile_admin"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."is_wanderly_plus"("p_user_id" "uuid" DEFAULT "auth"."uid"()) RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
  select exists (
    select 1
    from public.wanderly_plus_subscriptions s
    where s.user_id = p_user_id
      and s.entitlement = 'wanderly_plus'
      and s.status in ('active', 'trialing', 'canceled', 'dev')
      and (
        s.current_period_end is null
        or s.current_period_end > now()
      )
      and s.revoked_at is null
    limit 1
  );
$$;


ALTER FUNCTION "public"."is_wanderly_plus"("p_user_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."log_mission_completion"("p_mission_id" "uuid", "p_photo_path" "text" DEFAULT NULL::"text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_uid uuid := auth.uid(); v_m public.missions%rowtype;
        v_today date := current_date; v_last date; v_streak int; v_honey int;
        v_reward int; v_bonus int; v_counts boolean;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  SELECT * INTO v_m FROM public.missions WHERE id = p_mission_id;
  IF NOT FOUND THEN RETURN jsonb_build_object('success',false,'error','mission_not_found'); END IF;
  IF v_m.user_id IS NOT NULL AND v_m.user_id <> v_uid THEN
    RETURN jsonb_build_object('success',false,'error','not_your_mission'); END IF;
  IF EXISTS (SELECT 1 FROM public.mission_completions WHERE user_id=v_uid AND mission_id=p_mission_id) THEN
    RETURN jsonb_build_object('success',false,'error','already_completed'); END IF;

  SELECT last_mission_date, streak_count, honey INTO v_last, v_streak, v_honey
  FROM public.profiles WHERE id = v_uid FOR UPDATE;

  v_counts := (v_last IS DISTINCT FROM v_today);
  IF v_counts THEN
    IF v_last = v_today - 1 THEN v_streak := v_streak + 1; ELSE v_streak := 1; END IF;
  END IF;

  v_reward := LEAST(COALESCE(v_m.reward_honey,25), 200);     -- clamp anti-cheat
  v_bonus  := CASE WHEN v_counts THEN LEAST(v_streak, 10) ELSE 0 END;

  UPDATE public.profiles
     SET honey = honey + v_reward + v_bonus,
         streak_count = v_streak,
         last_mission_date = v_today
   WHERE id = v_uid;

  INSERT INTO public.mission_completions(user_id, mission_id, reward_honey, streak_bonus_honey, photo_path)
  VALUES (v_uid, p_mission_id, v_reward, v_bonus, p_photo_path);

  UPDATE public.missions SET active = false WHERE id = p_mission_id AND user_id = v_uid;

  RETURN jsonb_build_object('success',true,'reward_honey',v_reward,'streak_bonus',v_bonus,
                            'streak_count',v_streak,'honey', v_honey + v_reward + v_bonus);
END $$;


ALTER FUNCTION "public"."log_mission_completion"("p_mission_id" "uuid", "p_photo_path" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."protect_friendship_identity"() RETURNS "trigger"
    LANGUAGE "plpgsql"
    AS $$
BEGIN
    IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
        RAISE EXCEPTION 'Cannot change friendship user_id'
            USING ERRCODE = '42501';
    END IF;
    IF NEW.friend_id IS DISTINCT FROM OLD.friend_id THEN
        RAISE EXCEPTION 'Cannot change friendship friend_id'
            USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;


ALTER FUNCTION "public"."protect_friendship_identity"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."protect_profile_admin_role"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
BEGIN
    IF auth.role() <> 'service_role' THEN
        IF TG_OP = 'INSERT' THEN
            NEW.admin_role := false;
        ELSIF NEW.admin_role IS DISTINCT FROM OLD.admin_role THEN
            NEW.admin_role := OLD.admin_role;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;


ALTER FUNCTION "public"."protect_profile_admin_role"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."protect_profiles_client_fields"() RETURNS "trigger"
    LANGUAGE "plpgsql"
    SET "search_path" TO 'public'
    AS $$
DECLARE
  v_is_admin boolean := false;
  v_jwt_role text := nullif(current_setting('request.jwt.claim.role', true), '');
BEGIN
  IF TG_OP <> 'UPDATE' THEN
    RETURN NEW;
  END IF;

  -- Allow privileged backend/service contexts.
  IF current_user IN ('postgres', 'service_role', 'supabase_admin')
     OR v_jwt_role = 'service_role' THEN
    NEW.updated_at = now();
    RETURN NEW;
  END IF;

  -- Allow app admins if helper exists and returns true.
  BEGIN
    SELECT public.is_current_profile_admin()
    INTO v_is_admin;
  EXCEPTION
    WHEN undefined_function THEN
      v_is_admin := false;
  END;

  IF COALESCE(v_is_admin, false) THEN
    NEW.updated_at = now();
    RETURN NEW;
  END IF;

  IF NEW.id IS DISTINCT FROM OLD.id THEN
    RAISE EXCEPTION 'profiles.id is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.honey IS DISTINCT FROM OLD.honey THEN
    RAISE EXCEPTION 'profiles.honey is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.hive_rank IS DISTINCT FROM OLD.hive_rank THEN
    RAISE EXCEPTION 'profiles.hive_rank is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.badges IS DISTINCT FROM OLD.badges THEN
    RAISE EXCEPTION 'profiles.badges is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.cities_visited IS DISTINCT FROM OLD.cities_visited THEN
    RAISE EXCEPTION 'profiles.cities_visited is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.last_buzz_date IS DISTINCT FROM OLD.last_buzz_date THEN
    RAISE EXCEPTION 'profiles.last_buzz_date is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.last_lat IS DISTINCT FROM OLD.last_lat THEN
    RAISE EXCEPTION 'profiles.last_lat is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.last_lng IS DISTINCT FROM OLD.last_lng THEN
    RAISE EXCEPTION 'profiles.last_lng is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.friend_code IS DISTINCT FROM OLD.friend_code THEN
    RAISE EXCEPTION 'profiles.friend_code is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.streak_count IS DISTINCT FROM OLD.streak_count THEN
    RAISE EXCEPTION 'profiles.streak_count is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.explorer_class IS DISTINCT FROM OLD.explorer_class THEN
    RAISE EXCEPTION 'profiles.explorer_class is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.last_mission_date IS DISTINCT FROM OLD.last_mission_date THEN
    RAISE EXCEPTION 'profiles.last_mission_date is protected' USING ERRCODE = '42501';
  END IF;

  IF NEW.admin_role IS DISTINCT FROM OLD.admin_role THEN
    RAISE EXCEPTION 'profiles.admin_role is protected' USING ERRCODE = '42501';
  END IF;

  -- Server-side timestamp.
  NEW.updated_at = now();

  RETURN NEW;
END;
$$;


ALTER FUNCTION "public"."protect_profiles_client_fields"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."purchase_shop_item"("p_item_id" "uuid") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_uid uuid := auth.uid(); v_item public.shop_items%rowtype; v_honey int;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  SELECT * INTO v_item FROM public.shop_items WHERE id=p_item_id AND active;
  IF NOT FOUND THEN RETURN jsonb_build_object('success',false,'error','item_unavailable'); END IF;
  IF EXISTS (SELECT 1 FROM public.user_inventory WHERE user_id=v_uid AND item_id=p_item_id) THEN
    RETURN jsonb_build_object('success',false,'error','already_owned'); END IF;
  UPDATE public.profiles SET honey = honey - v_item.cost_honey
   WHERE id=v_uid AND honey >= v_item.cost_honey
   RETURNING honey INTO v_honey;
  IF NOT FOUND THEN RETURN jsonb_build_object('success',false,'error','insufficient_honey'); END IF;
  INSERT INTO public.user_inventory(user_id, item_id) VALUES (v_uid, p_item_id);
  RETURN jsonb_build_object('success',true,'honey',v_honey,'item',v_item.sku);
END $$;


ALTER FUNCTION "public"."purchase_shop_item"("p_item_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."reject_friend_request"("p_requester_id" "uuid") RETURNS TABLE("success" boolean, "error_code" "text", "error_message" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
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


ALTER FUNCTION "public"."reject_friend_request"("p_requester_id" "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."restore_streak"("cost" integer) RETURNS TABLE("restored" boolean, "reason" "text", "honey" integer, "streak_count" integer, "last_mission_date" "date")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE
    v_user_id uuid := auth.uid();
    v_profile public.profiles%ROWTYPE;
    v_today date := current_date;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF cost <= 0 THEN
        RETURN QUERY SELECT false, 'invalid_cost'::text, 0, 0, NULL::text;
        RETURN;
    END IF;

    SELECT * INTO v_profile FROM public.profiles WHERE id = v_user_id FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Profile not found' USING ERRCODE = 'P0002';
    END IF;

    IF COALESCE(v_profile.honey, 0) < cost THEN
        RETURN QUERY SELECT
            false,
            'insufficient_honey'::text,
            COALESCE(v_profile.honey, 0),
            COALESCE(v_profile.streak_count, 0),
            v_profile.last_mission_date;
        RETURN;
    END IF;

    -- Restore streak: deduct honey cost, set last_mission_date to yesterday so
    -- completing today's mission continues the streak.
    UPDATE public.profiles
    SET
        honey = COALESCE(v_profile.honey, 0) - cost,
        last_mission_date = v_today - 1
    WHERE id = v_user_id;

    RETURN QUERY SELECT
        true,
        NULL::text,
        COALESCE(v_profile.honey, 0) - cost,
        COALESCE(v_profile.streak_count, 0),
        v_today - 1;
END;
$$;


ALTER FUNCTION "public"."restore_streak"("cost" integer) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."set_updated_at"() RETURNS "trigger"
    LANGUAGE "plpgsql"
    AS $$
begin
  new.updated_at = now();
  return new;
end;
$$;


ALTER FUNCTION "public"."set_updated_at"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."sync_hive_rank"() RETURNS "trigger"
    LANGUAGE "plpgsql"
    SET "search_path" TO 'public'
    AS $$
BEGIN
    NEW.hive_rank := CASE
        WHEN COALESCE(NEW.honey, 0) >= 600 THEN 4
        WHEN COALESCE(NEW.honey, 0) >= 300 THEN 3
        WHEN COALESCE(NEW.honey, 0) >= 100 THEN 2
        ELSE 1
    END;

    NEW.updated_at := now();
    RETURN NEW;
END;
$$;


ALTER FUNCTION "public"."sync_hive_rank"() OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."update_profile_location"("lat" double precision, "lng" double precision) RETURNS "void"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE
    v_user_id uuid := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required'
            USING ERRCODE = '28000';
    END IF;

    IF lat < -90 OR lat > 90 OR lng < -180 OR lng > 180 THEN
        RAISE EXCEPTION 'Invalid coordinates'
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.profiles
    SET last_lat = lat, last_lng = lng, updated_at = now()
    WHERE id = v_user_id;
END;
$$;


ALTER FUNCTION "public"."update_profile_location"("lat" double precision, "lng" double precision) OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."update_profile_username"("p_username" "text") RETURNS TABLE("success" boolean, "error_code" "text", "error_message" "text")
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $_$
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
$_$;


ALTER FUNCTION "public"."update_profile_username"("p_username" "text") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."use_streak_freeze"() RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO 'public', 'auth', 'extensions'
    AS $$
DECLARE v_uid uuid := auth.uid(); v_freezes int;
BEGIN
  IF v_uid IS NULL THEN RETURN jsonb_build_object('success',false,'error','not_authenticated'); END IF;
  SELECT streak_freezes INTO v_freezes FROM public.profiles WHERE id=v_uid FOR UPDATE;
  IF v_freezes <= 0 THEN RETURN jsonb_build_object('success',false,'error','no_freezes'); END IF;
  UPDATE public.profiles
     SET streak_freezes = streak_freezes - 1, last_mission_date = current_date
   WHERE id=v_uid;
  RETURN jsonb_build_object('success',true,'freezes_left', v_freezes-1);
END $$;


ALTER FUNCTION "public"."use_streak_freeze"() OWNER TO "postgres";

SET default_tablespace = '';

SET default_table_access_method = "heap";


CREATE TABLE IF NOT EXISTS "public"."ai_usage_daily" (
    "user_id" "uuid" NOT NULL,
    "usage_date" "date" DEFAULT CURRENT_DATE NOT NULL,
    "request_count" integer DEFAULT 0 NOT NULL,
    "token_estimate" integer DEFAULT 0 NOT NULL,
    "last_request_at" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "ai_usage_daily_request_count_check" CHECK (("request_count" >= 0)),
    CONSTRAINT "ai_usage_daily_token_estimate_check" CHECK (("token_estimate" >= 0))
);

ALTER TABLE ONLY "public"."ai_usage_daily" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."ai_usage_daily" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."api_usage_limits" (
    "user_id" "uuid" NOT NULL,
    "provider" "text" NOT NULL,
    "window_start" "date" DEFAULT CURRENT_DATE NOT NULL,
    "request_count" integer DEFAULT 0 NOT NULL,
    CONSTRAINT "api_usage_limits_provider_check" CHECK (("provider" = ANY (ARRAY['gemini'::"text", 'places'::"text"]))),
    CONSTRAINT "api_usage_limits_request_count_check" CHECK (("request_count" >= 0))
);


ALTER TABLE "public"."api_usage_limits" OWNER TO "postgres";


CREATE OR REPLACE VIEW "public"."api_quota_usage" WITH ("security_invoker"='on') AS
 SELECT "user_id",
    "provider",
    "window_start",
    "request_count"
   FROM "public"."api_usage_limits";


ALTER VIEW "public"."api_quota_usage" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."api_quotas" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "provider" "text" NOT NULL,
    "request_date" "date" DEFAULT CURRENT_DATE NOT NULL,
    "request_count" integer DEFAULT 0 NOT NULL
);


ALTER TABLE "public"."api_quotas" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."friendships" (
    "id" "uuid" DEFAULT "extensions"."gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "friend_id" "uuid" NOT NULL,
    "status" "text" DEFAULT 'pending'::"text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "friendships_no_self_friend" CHECK (("user_id" <> "friend_id")),
    CONSTRAINT "friendships_status_valid" CHECK (("status" = ANY (ARRAY['pending'::"text", 'accepted'::"text", 'blocked'::"text"])))
);


ALTER TABLE "public"."friendships" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."gem_discoveries" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "gem_id" "uuid" NOT NULL,
    "discovered_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE ONLY "public"."gem_discoveries" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."gem_discoveries" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."gems" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "name" "text" NOT NULL,
    "category" "text",
    "place_id" "text",
    "lat" double precision,
    "lng" double precision,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


ALTER TABLE "public"."gems" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."hive_challenge_progress" (
    "challenge_id" "uuid" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "contribution" integer DEFAULT 0 NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "hive_challenge_progress_contribution_check" CHECK (("contribution" >= 0))
);


ALTER TABLE "public"."hive_challenge_progress" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."hive_challenges" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "title" "text" NOT NULL,
    "description" "text",
    "goal_type" "text" DEFAULT 'missions'::"text" NOT NULL,
    "goal_target" integer NOT NULL,
    "reward_honey" integer DEFAULT 0 NOT NULL,
    "starts_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "ends_at" timestamp with time zone NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "rewarded" boolean DEFAULT false NOT NULL,
    CONSTRAINT "hive_challenges_goal_target_check" CHECK (("goal_target" > 0)),
    CONSTRAINT "hive_challenges_goal_type_check" CHECK (("goal_type" = ANY (ARRAY['missions'::"text", 'gems'::"text", 'honey'::"text"])))
);


ALTER TABLE "public"."hive_challenges" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."mission_completions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "mission_id" "uuid" NOT NULL,
    "reward_honey" integer DEFAULT 0 NOT NULL,
    "streak_bonus_honey" integer DEFAULT 0 NOT NULL,
    "photo_path" "text",
    "completed_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE ONLY "public"."mission_completions" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."mission_completions" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."missions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid",
    "title" "text" NOT NULL,
    "description" "text",
    "category" "text",
    "theme" "text",
    "place_id" "text",
    "place_name" "text",
    "lat" double precision,
    "lng" double precision,
    "reward_honey" integer DEFAULT 25 NOT NULL,
    "source" "text" DEFAULT 'ai'::"text" NOT NULL,
    "active" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone,
    CONSTRAINT "missions_reward_honey_check" CHECK (("reward_honey" >= 0)),
    CONSTRAINT "missions_source_check" CHECK (("source" = ANY (ARRAY['ai'::"text", 'curated'::"text", 'weekly'::"text"])))
);


ALTER TABLE "public"."missions" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."profiles" (
    "id" "uuid" NOT NULL,
    "username" "text",
    "honey" integer DEFAULT 0 NOT NULL,
    "hive_rank" integer DEFAULT 1 NOT NULL,
    "badges" "text"[] DEFAULT ARRAY[]::"text"[] NOT NULL,
    "cities_visited" "text"[] DEFAULT ARRAY[]::"text"[] NOT NULL,
    "avatar_url" "text",
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "last_buzz_date" "date",
    "last_lat" double precision,
    "last_lng" double precision,
    "friend_code" "text",
    "streak_count" integer DEFAULT 0 NOT NULL,
    "explorer_class" "text",
    "last_mission_date" "date",
    "admin_role" boolean DEFAULT false NOT NULL,
    "streak_freezes" integer DEFAULT 0 NOT NULL,
    "equipped_frame" "uuid",
    "equipped_skin" "uuid",
    "equipped_widget_theme" "uuid",
    CONSTRAINT "profiles_honey_nonneg" CHECK (("honey" >= 0)),
    CONSTRAINT "profiles_streak_freezes_nonneg" CHECK (("streak_freezes" >= 0))
);


ALTER TABLE "public"."profiles" OWNER TO "postgres";


CREATE OR REPLACE VIEW "public"."profiles_public" WITH ("security_invoker"='on') AS
 SELECT "id",
    "username",
    "avatar_url",
    "friend_code",
    "honey",
    "hive_rank",
    "badges",
    "cities_visited",
    "streak_count",
    "explorer_class"
   FROM "public"."profiles";


ALTER VIEW "public"."profiles_public" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."referrals" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "referrer_id" "uuid" NOT NULL,
    "referred_id" "uuid" NOT NULL,
    "reward_honey" integer DEFAULT 0 NOT NULL,
    "rewarded" boolean DEFAULT false NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "referrals_check" CHECK (("referrer_id" <> "referred_id"))
);

ALTER TABLE ONLY "public"."referrals" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."referrals" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."shop_items" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "sku" "text" NOT NULL,
    "name" "text" NOT NULL,
    "type" "text" NOT NULL,
    "cost_honey" integer NOT NULL,
    "active" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "shop_items_cost_honey_check" CHECK (("cost_honey" >= 0)),
    CONSTRAINT "shop_items_type_check" CHECK (("type" = ANY (ARRAY['avatar_frame'::"text", 'buzzy_skin'::"text", 'widget_theme'::"text"])))
);


ALTER TABLE "public"."shop_items" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."streak_milestone_claims" (
    "user_id" "uuid" NOT NULL,
    "threshold" integer NOT NULL,
    "claimed_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE ONLY "public"."streak_milestone_claims" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."streak_milestone_claims" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."streak_milestones" (
    "threshold" integer NOT NULL,
    "reward_honey" integer NOT NULL,
    "badge" "text",
    CONSTRAINT "streak_milestones_reward_honey_check" CHECK (("reward_honey" >= 0))
);


ALTER TABLE "public"."streak_milestones" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."user_inventory" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "item_id" "uuid" NOT NULL,
    "acquired_at" timestamp with time zone DEFAULT "now"() NOT NULL
);

ALTER TABLE ONLY "public"."user_inventory" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."user_inventory" OWNER TO "postgres";


CREATE TABLE IF NOT EXISTS "public"."wanderly_plus_subscriptions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "user_id" "uuid" NOT NULL,
    "provider" "text" DEFAULT 'manual'::"text" NOT NULL,
    "entitlement" "text" DEFAULT 'wanderly_plus'::"text" NOT NULL,
    "product_id" "text",
    "purchase_token_hash" "text",
    "status" "text" DEFAULT 'active'::"text" NOT NULL,
    "starts_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "current_period_end" timestamp with time zone,
    "verified_at" timestamp with time zone,
    "canceled_at" timestamp with time zone,
    "revoked_at" timestamp with time zone,
    "raw_provider_payload" "jsonb" DEFAULT '{}'::"jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "wanderly_plus_period_valid" CHECK ((("current_period_end" IS NULL) OR ("current_period_end" > "starts_at"))),
    CONSTRAINT "wanderly_plus_subscriptions_entitlement_check" CHECK (("entitlement" = 'wanderly_plus'::"text")),
    CONSTRAINT "wanderly_plus_subscriptions_provider_check" CHECK (("provider" = ANY (ARRAY['manual'::"text", 'google_play'::"text", 'revenuecat'::"text", 'stripe'::"text", 'dev'::"text"]))),
    CONSTRAINT "wanderly_plus_subscriptions_status_check" CHECK (("status" = ANY (ARRAY['active'::"text", 'trialing'::"text", 'canceled'::"text", 'expired'::"text", 'revoked'::"text", 'past_due'::"text", 'dev'::"text"])))
);

ALTER TABLE ONLY "public"."wanderly_plus_subscriptions" FORCE ROW LEVEL SECURITY;


ALTER TABLE "public"."wanderly_plus_subscriptions" OWNER TO "postgres";


CREATE OR REPLACE VIEW "public"."v_wanderly_plus_active" WITH ("security_invoker"='on') AS
 SELECT "user_id",
    "provider",
    "status",
    "product_id",
    "entitlement",
    "current_period_end",
    "updated_at"
   FROM "public"."wanderly_plus_subscriptions" "s"
  WHERE (("entitlement" = 'wanderly_plus'::"text") AND ("status" = ANY (ARRAY['active'::"text", 'trialing'::"text", 'canceled'::"text", 'dev'::"text"])) AND (("current_period_end" IS NULL) OR ("current_period_end" > "now"())) AND ("revoked_at" IS NULL));


ALTER VIEW "public"."v_wanderly_plus_active" OWNER TO "postgres";


ALTER TABLE ONLY "public"."ai_usage_daily"
    ADD CONSTRAINT "ai_usage_daily_pkey" PRIMARY KEY ("user_id", "usage_date");



ALTER TABLE ONLY "public"."api_quotas"
    ADD CONSTRAINT "api_quotas_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."api_quotas"
    ADD CONSTRAINT "api_quotas_user_provider_date_key" UNIQUE ("user_id", "provider", "request_date");



ALTER TABLE ONLY "public"."api_usage_limits"
    ADD CONSTRAINT "api_usage_limits_pkey" PRIMARY KEY ("user_id", "provider", "window_start");



ALTER TABLE ONLY "public"."friendships"
    ADD CONSTRAINT "friendships_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."gem_discoveries"
    ADD CONSTRAINT "gem_discoveries_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."gem_discoveries"
    ADD CONSTRAINT "gem_discoveries_user_id_gem_id_key" UNIQUE ("user_id", "gem_id");



ALTER TABLE ONLY "public"."gems"
    ADD CONSTRAINT "gems_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."gems"
    ADD CONSTRAINT "gems_place_id_key" UNIQUE ("place_id");



ALTER TABLE ONLY "public"."hive_challenge_progress"
    ADD CONSTRAINT "hive_challenge_progress_pkey" PRIMARY KEY ("challenge_id", "user_id");



ALTER TABLE ONLY "public"."hive_challenges"
    ADD CONSTRAINT "hive_challenges_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."mission_completions"
    ADD CONSTRAINT "mission_completions_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."mission_completions"
    ADD CONSTRAINT "mission_completions_user_id_mission_id_key" UNIQUE ("user_id", "mission_id");



ALTER TABLE ONLY "public"."missions"
    ADD CONSTRAINT "missions_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."profiles"
    ADD CONSTRAINT "profiles_friend_code_key" UNIQUE ("friend_code");



ALTER TABLE ONLY "public"."profiles"
    ADD CONSTRAINT "profiles_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."profiles"
    ADD CONSTRAINT "profiles_username_key" UNIQUE ("username");



ALTER TABLE ONLY "public"."referrals"
    ADD CONSTRAINT "referrals_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."referrals"
    ADD CONSTRAINT "referrals_referred_id_key" UNIQUE ("referred_id");



ALTER TABLE ONLY "public"."shop_items"
    ADD CONSTRAINT "shop_items_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."shop_items"
    ADD CONSTRAINT "shop_items_sku_key" UNIQUE ("sku");



ALTER TABLE ONLY "public"."streak_milestone_claims"
    ADD CONSTRAINT "streak_milestone_claims_pkey" PRIMARY KEY ("user_id", "threshold");



ALTER TABLE ONLY "public"."streak_milestones"
    ADD CONSTRAINT "streak_milestones_pkey" PRIMARY KEY ("threshold");



ALTER TABLE ONLY "public"."user_inventory"
    ADD CONSTRAINT "user_inventory_pkey" PRIMARY KEY ("id");



ALTER TABLE ONLY "public"."user_inventory"
    ADD CONSTRAINT "user_inventory_user_id_item_id_key" UNIQUE ("user_id", "item_id");



ALTER TABLE ONLY "public"."wanderly_plus_subscriptions"
    ADD CONSTRAINT "wanderly_plus_subscriptions_pkey" PRIMARY KEY ("id");



CREATE INDEX "friendships_friend_id_idx" ON "public"."friendships" USING "btree" ("friend_id");



CREATE UNIQUE INDEX "friendships_pair_unique_idx" ON "public"."friendships" USING "btree" (LEAST("user_id", "friend_id"), GREATEST("user_id", "friend_id"));



CREATE INDEX "idx_ai_usage_daily_user_date" ON "public"."ai_usage_daily" USING "btree" ("user_id", "usage_date" DESC);



CREATE INDEX "idx_friendships_user_id" ON "public"."friendships" USING "btree" ("user_id");



CREATE INDEX "idx_gem_disc_user" ON "public"."gem_discoveries" USING "btree" ("user_id");



CREATE INDEX "idx_mission_completions_user" ON "public"."mission_completions" USING "btree" ("user_id", "completed_at" DESC);



CREATE INDEX "idx_missions_active" ON "public"."missions" USING "btree" ("user_id", "active") WHERE "active";



CREATE INDEX "idx_missions_user" ON "public"."missions" USING "btree" ("user_id");



CREATE INDEX "idx_profiles_admin_role" ON "public"."profiles" USING "btree" ("id") WHERE ("admin_role" = true);



CREATE INDEX "idx_referrals_referrer" ON "public"."referrals" USING "btree" ("referrer_id");



CREATE INDEX "idx_user_inventory_user" ON "public"."user_inventory" USING "btree" ("user_id");



CREATE INDEX "idx_wanderly_plus_subscriptions_period_end" ON "public"."wanderly_plus_subscriptions" USING "btree" ("current_period_end");



CREATE INDEX "idx_wanderly_plus_subscriptions_status" ON "public"."wanderly_plus_subscriptions" USING "btree" ("status");



CREATE INDEX "idx_wanderly_plus_subscriptions_user_id" ON "public"."wanderly_plus_subscriptions" USING "btree" ("user_id");



CREATE UNIQUE INDEX "uq_wanderly_plus_one_granting_row_per_user" ON "public"."wanderly_plus_subscriptions" USING "btree" ("user_id", "entitlement") WHERE ("status" = ANY (ARRAY['active'::"text", 'trialing'::"text", 'canceled'::"text", 'dev'::"text"]));



CREATE UNIQUE INDEX "uq_wanderly_plus_purchase_token_hash" ON "public"."wanderly_plus_subscriptions" USING "btree" ("purchase_token_hash") WHERE ("purchase_token_hash" IS NOT NULL);



CREATE OR REPLACE TRIGGER "trg_ai_usage_daily_updated_at" BEFORE UPDATE ON "public"."ai_usage_daily" FOR EACH ROW EXECUTE FUNCTION "public"."set_updated_at"();



CREATE OR REPLACE TRIGGER "trg_protect_friendship_identity" BEFORE UPDATE ON "public"."friendships" FOR EACH ROW EXECUTE FUNCTION "public"."protect_friendship_identity"();



CREATE OR REPLACE TRIGGER "trg_protect_profile_admin_role" BEFORE INSERT OR UPDATE ON "public"."profiles" FOR EACH ROW EXECUTE FUNCTION "public"."protect_profile_admin_role"();



CREATE OR REPLACE TRIGGER "trg_protect_profiles_client_fields" BEFORE UPDATE ON "public"."profiles" FOR EACH ROW EXECUTE FUNCTION "public"."protect_profiles_client_fields"();



CREATE OR REPLACE TRIGGER "trg_sync_hive_rank" BEFORE INSERT OR UPDATE ON "public"."profiles" FOR EACH ROW EXECUTE FUNCTION "public"."sync_hive_rank"();



CREATE OR REPLACE TRIGGER "trg_wanderly_plus_subscriptions_updated_at" BEFORE UPDATE ON "public"."wanderly_plus_subscriptions" FOR EACH ROW EXECUTE FUNCTION "public"."set_updated_at"();



ALTER TABLE ONLY "public"."ai_usage_daily"
    ADD CONSTRAINT "ai_usage_daily_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."api_quotas"
    ADD CONSTRAINT "api_quotas_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id");



ALTER TABLE ONLY "public"."api_usage_limits"
    ADD CONSTRAINT "api_usage_limits_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."friendships"
    ADD CONSTRAINT "friendships_friend_id_fkey" FOREIGN KEY ("friend_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."friendships"
    ADD CONSTRAINT "friendships_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."gem_discoveries"
    ADD CONSTRAINT "gem_discoveries_gem_id_fkey" FOREIGN KEY ("gem_id") REFERENCES "public"."gems"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."gem_discoveries"
    ADD CONSTRAINT "gem_discoveries_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."hive_challenge_progress"
    ADD CONSTRAINT "hive_challenge_progress_challenge_id_fkey" FOREIGN KEY ("challenge_id") REFERENCES "public"."hive_challenges"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."hive_challenge_progress"
    ADD CONSTRAINT "hive_challenge_progress_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."mission_completions"
    ADD CONSTRAINT "mission_completions_mission_id_fkey" FOREIGN KEY ("mission_id") REFERENCES "public"."missions"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."mission_completions"
    ADD CONSTRAINT "mission_completions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."missions"
    ADD CONSTRAINT "missions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."profiles"
    ADD CONSTRAINT "profiles_equipped_frame_fkey" FOREIGN KEY ("equipped_frame") REFERENCES "public"."shop_items"("id") ON DELETE SET NULL;



ALTER TABLE ONLY "public"."profiles"
    ADD CONSTRAINT "profiles_equipped_skin_fkey" FOREIGN KEY ("equipped_skin") REFERENCES "public"."shop_items"("id") ON DELETE SET NULL;



ALTER TABLE ONLY "public"."profiles"
    ADD CONSTRAINT "profiles_equipped_widget_theme_fkey" FOREIGN KEY ("equipped_widget_theme") REFERENCES "public"."shop_items"("id") ON DELETE SET NULL;



ALTER TABLE ONLY "public"."profiles"
    ADD CONSTRAINT "profiles_id_fkey" FOREIGN KEY ("id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."referrals"
    ADD CONSTRAINT "referrals_referred_id_fkey" FOREIGN KEY ("referred_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."referrals"
    ADD CONSTRAINT "referrals_referrer_id_fkey" FOREIGN KEY ("referrer_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."streak_milestone_claims"
    ADD CONSTRAINT "streak_milestone_claims_threshold_fkey" FOREIGN KEY ("threshold") REFERENCES "public"."streak_milestones"("threshold");



ALTER TABLE ONLY "public"."streak_milestone_claims"
    ADD CONSTRAINT "streak_milestone_claims_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."user_inventory"
    ADD CONSTRAINT "user_inventory_item_id_fkey" FOREIGN KEY ("item_id") REFERENCES "public"."shop_items"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."user_inventory"
    ADD CONSTRAINT "user_inventory_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



ALTER TABLE ONLY "public"."wanderly_plus_subscriptions"
    ADD CONSTRAINT "wanderly_plus_subscriptions_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "auth"."users"("id") ON DELETE CASCADE;



CREATE POLICY "Either party can delete friendship" ON "public"."friendships" FOR DELETE TO "authenticated" USING ((("auth"."uid"() = "user_id") OR ("auth"."uid"() = "friend_id")));



CREATE POLICY "Recipient can accept or block friendship" ON "public"."friendships" FOR UPDATE TO "authenticated" USING (("auth"."uid"() = "friend_id")) WITH CHECK ((("auth"."uid"() = "friend_id") AND ("status" = ANY (ARRAY['accepted'::"text", 'blocked'::"text"]))));



CREATE POLICY "Users can view own quotas" ON "public"."api_quotas" FOR SELECT TO "authenticated" USING (("auth"."uid"() = "user_id"));



CREATE POLICY "Users insert own pending friendship" ON "public"."friendships" FOR INSERT TO "authenticated" WITH CHECK ((("auth"."uid"() = "user_id") AND ("status" = 'pending'::"text")));



CREATE POLICY "Users view own friendships" ON "public"."friendships" FOR SELECT TO "authenticated" USING ((("auth"."uid"() = "user_id") OR ("auth"."uid"() = "friend_id")));



CREATE POLICY "admins_select_all_friendships" ON "public"."friendships" FOR SELECT TO "authenticated" USING ((EXISTS ( SELECT 1
   FROM "public"."profiles" "p"
  WHERE (("p"."id" = "auth"."uid"()) AND ("p"."admin_role" = true)))));



CREATE POLICY "admins_select_all_profiles" ON "public"."profiles" FOR SELECT TO "authenticated" USING ("public"."is_current_profile_admin"());



CREATE POLICY "admins_update_any_profile" ON "public"."profiles" FOR UPDATE TO "authenticated" USING ("public"."is_current_profile_admin"()) WITH CHECK ("public"."is_current_profile_admin"());



ALTER TABLE "public"."ai_usage_daily" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "ai_usage_select_own" ON "public"."ai_usage_daily" FOR SELECT TO "authenticated" USING ((( SELECT "auth"."uid"() AS "uid") = "user_id"));



ALTER TABLE "public"."api_quotas" ENABLE ROW LEVEL SECURITY;


ALTER TABLE "public"."api_usage_limits" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "api_usage_limits_block_regular_users" ON "public"."api_usage_limits" TO "authenticated" USING ((EXISTS ( SELECT 1
   FROM "public"."profiles" "p"
  WHERE (("p"."id" = "auth"."uid"()) AND ("p"."admin_role" = true))))) WITH CHECK (false);



CREATE POLICY "authenticated_select_profiles_public" ON "public"."profiles" FOR SELECT TO "authenticated" USING (true);



ALTER TABLE "public"."friendships" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "gd_select_own" ON "public"."gem_discoveries" FOR SELECT TO "authenticated" USING (("user_id" = "auth"."uid"()));



ALTER TABLE "public"."gem_discoveries" ENABLE ROW LEVEL SECURITY;


ALTER TABLE "public"."gems" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "gems_read" ON "public"."gems" FOR SELECT TO "authenticated" USING (true);



ALTER TABLE "public"."hive_challenge_progress" ENABLE ROW LEVEL SECURITY;


ALTER TABLE "public"."hive_challenges" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "hive_challenges_read" ON "public"."hive_challenges" FOR SELECT TO "authenticated" USING (true);



CREATE POLICY "hive_progress_read" ON "public"."hive_challenge_progress" FOR SELECT TO "authenticated" USING (true);



CREATE POLICY "inv_select_own" ON "public"."user_inventory" FOR SELECT TO "authenticated" USING (("user_id" = "auth"."uid"()));



CREATE POLICY "mc_select_own" ON "public"."mission_completions" FOR SELECT TO "authenticated" USING (("user_id" = "auth"."uid"()));



ALTER TABLE "public"."mission_completions" ENABLE ROW LEVEL SECURITY;


ALTER TABLE "public"."missions" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "missions_delete_own" ON "public"."missions" FOR DELETE TO "authenticated" USING (("user_id" = "auth"."uid"()));



CREATE POLICY "missions_insert_own" ON "public"."missions" FOR INSERT TO "authenticated" WITH CHECK (("user_id" = "auth"."uid"()));



CREATE POLICY "missions_select" ON "public"."missions" FOR SELECT TO "authenticated" USING ((("user_id" = "auth"."uid"()) OR ("user_id" IS NULL)));



ALTER TABLE "public"."profiles" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "profiles_insert_own" ON "public"."profiles" FOR INSERT TO "authenticated" WITH CHECK (("auth"."uid"() = "id"));



CREATE POLICY "profiles_update_own" ON "public"."profiles" FOR UPDATE TO "authenticated" USING (("auth"."uid"() = "id")) WITH CHECK (("auth"."uid"() = "id"));



ALTER TABLE "public"."referrals" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "referrals_select_own" ON "public"."referrals" FOR SELECT TO "authenticated" USING ((("referrer_id" = "auth"."uid"()) OR ("referred_id" = "auth"."uid"())));



ALTER TABLE "public"."shop_items" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "shop_items_read" ON "public"."shop_items" FOR SELECT TO "authenticated" USING (true);



CREATE POLICY "smc_select_own" ON "public"."streak_milestone_claims" FOR SELECT TO "authenticated" USING (("user_id" = "auth"."uid"()));



ALTER TABLE "public"."streak_milestone_claims" ENABLE ROW LEVEL SECURITY;


ALTER TABLE "public"."streak_milestones" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "streak_milestones_read" ON "public"."streak_milestones" FOR SELECT TO "authenticated" USING (true);



ALTER TABLE "public"."user_inventory" ENABLE ROW LEVEL SECURITY;


CREATE POLICY "wanderly_plus_select_own" ON "public"."wanderly_plus_subscriptions" FOR SELECT TO "authenticated" USING ((( SELECT "auth"."uid"() AS "uid") = "user_id"));



ALTER TABLE "public"."wanderly_plus_subscriptions" ENABLE ROW LEVEL SECURITY;


GRANT USAGE ON SCHEMA "public" TO "postgres";
GRANT USAGE ON SCHEMA "public" TO "anon";
GRANT USAGE ON SCHEMA "public" TO "authenticated";
GRANT USAGE ON SCHEMA "public" TO "service_role";



REVOKE ALL ON FUNCTION "public"."accept_friend_request"("p_requester_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."accept_friend_request"("p_requester_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."accept_friend_request"("p_requester_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."accept_streak_loss"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."accept_streak_loss"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."accept_streak_loss"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."admin_update_profile_stats"("target_profile_id" "uuid", "new_honey" integer, "new_streak_count" integer, "new_hive_rank" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."admin_update_profile_stats"("target_profile_id" "uuid", "new_honey" integer, "new_streak_count" integer, "new_hive_rank" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."admin_update_profile_stats"("target_profile_id" "uuid", "new_honey" integer, "new_streak_count" integer, "new_hive_rank" integer) TO "service_role";



REVOKE ALL ON FUNCTION "public"."claim_referral"("p_friend_code" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."claim_referral"("p_friend_code" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."claim_referral"("p_friend_code" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."claim_referral"("p_friend_code" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."claim_streak_milestone"("p_threshold" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."claim_streak_milestone"("p_threshold" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."claim_streak_milestone"("p_threshold" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."claim_streak_milestone"("p_threshold" integer) TO "service_role";



REVOKE ALL ON FUNCTION "public"."complete_mission"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."complete_mission"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."complete_mission"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."consume_ai_quota"("p_cost" integer, "p_free_limit" integer, "p_plus_limit" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."consume_ai_quota"("p_cost" integer, "p_free_limit" integer, "p_plus_limit" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."consume_ai_quota"("p_cost" integer, "p_free_limit" integer, "p_plus_limit" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."consume_ai_quota"("p_cost" integer, "p_free_limit" integer, "p_plus_limit" integer) TO "service_role";



REVOKE ALL ON FUNCTION "public"."consume_api_quota"("provider_name" "text", "max_requests_per_day" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."consume_api_quota"("provider_name" "text", "max_requests_per_day" integer) TO "service_role";
GRANT ALL ON FUNCTION "public"."consume_api_quota"("provider_name" "text", "max_requests_per_day" integer) TO "authenticated";



REVOKE ALL ON FUNCTION "public"."contribute_to_challenge"("p_challenge_id" "uuid", "p_amount" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."contribute_to_challenge"("p_challenge_id" "uuid", "p_amount" integer) TO "anon";
GRANT ALL ON FUNCTION "public"."contribute_to_challenge"("p_challenge_id" "uuid", "p_amount" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."contribute_to_challenge"("p_challenge_id" "uuid", "p_amount" integer) TO "service_role";



REVOKE ALL ON FUNCTION "public"."discover_gem"("p_gem_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."discover_gem"("p_gem_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."discover_gem"("p_gem_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."discover_gem"("p_gem_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."discover_gem_by_place"("p_name" "text", "p_lat" double precision, "p_lng" double precision, "p_category" "text", "p_place_id" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."discover_gem_by_place"("p_name" "text", "p_lat" double precision, "p_lng" double precision, "p_category" "text", "p_place_id" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."discover_gem_by_place"("p_name" "text", "p_lat" double precision, "p_lng" double precision, "p_category" "text", "p_place_id" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."discover_gem_by_place"("p_name" "text", "p_lat" double precision, "p_lng" double precision, "p_category" "text", "p_place_id" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."equip_cosmetic"("p_item_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."equip_cosmetic"("p_item_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."equip_cosmetic"("p_item_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."equip_cosmetic"("p_item_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."finalize_hive_challenge"("p_challenge_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."finalize_hive_challenge"("p_challenge_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."finalize_hive_challenge"("p_challenge_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."finalize_hive_challenge"("p_challenge_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."find_profile_by_friend_code"("code" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."find_profile_by_friend_code"("code" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."find_profile_by_friend_code"("code" "text") TO "service_role";



GRANT ALL ON FUNCTION "public"."generate_friend_code"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."generate_friend_code"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."get_accepted_friend_profiles"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."get_accepted_friend_profiles"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_accepted_friend_profiles"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."get_friend_locations"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."get_friend_locations"() TO "anon";
GRANT ALL ON FUNCTION "public"."get_friend_locations"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_friend_locations"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."get_my_plus_entitlement"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."get_my_plus_entitlement"() TO "anon";
GRANT ALL ON FUNCTION "public"."get_my_plus_entitlement"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_my_plus_entitlement"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."get_pending_friend_request_profiles"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."get_pending_friend_request_profiles"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_pending_friend_request_profiles"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."get_public_profile"("profile_user_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."get_public_profile"("profile_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_public_profile"("profile_user_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."get_social_leaderboard"("max_rows" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."get_social_leaderboard"("max_rows" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."get_social_leaderboard"("max_rows" integer) TO "service_role";



REVOKE ALL ON FUNCTION "public"."handle_new_user"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."handle_new_user"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."is_current_profile_admin"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."is_current_profile_admin"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."is_current_profile_admin"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."is_wanderly_plus"("p_user_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."is_wanderly_plus"("p_user_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."is_wanderly_plus"("p_user_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."is_wanderly_plus"("p_user_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."log_mission_completion"("p_mission_id" "uuid", "p_photo_path" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."log_mission_completion"("p_mission_id" "uuid", "p_photo_path" "text") TO "anon";
GRANT ALL ON FUNCTION "public"."log_mission_completion"("p_mission_id" "uuid", "p_photo_path" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."log_mission_completion"("p_mission_id" "uuid", "p_photo_path" "text") TO "service_role";



GRANT ALL ON FUNCTION "public"."protect_friendship_identity"() TO "anon";
GRANT ALL ON FUNCTION "public"."protect_friendship_identity"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."protect_friendship_identity"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."protect_profile_admin_role"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."protect_profile_admin_role"() TO "service_role";



GRANT ALL ON FUNCTION "public"."protect_profiles_client_fields"() TO "anon";
GRANT ALL ON FUNCTION "public"."protect_profiles_client_fields"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."protect_profiles_client_fields"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."purchase_shop_item"("p_item_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."purchase_shop_item"("p_item_id" "uuid") TO "anon";
GRANT ALL ON FUNCTION "public"."purchase_shop_item"("p_item_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."purchase_shop_item"("p_item_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."reject_friend_request"("p_requester_id" "uuid") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."reject_friend_request"("p_requester_id" "uuid") TO "authenticated";
GRANT ALL ON FUNCTION "public"."reject_friend_request"("p_requester_id" "uuid") TO "service_role";



REVOKE ALL ON FUNCTION "public"."restore_streak"("cost" integer) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."restore_streak"("cost" integer) TO "authenticated";
GRANT ALL ON FUNCTION "public"."restore_streak"("cost" integer) TO "service_role";



GRANT ALL ON FUNCTION "public"."set_updated_at"() TO "anon";
GRANT ALL ON FUNCTION "public"."set_updated_at"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."set_updated_at"() TO "service_role";



GRANT ALL ON FUNCTION "public"."sync_hive_rank"() TO "service_role";



REVOKE ALL ON FUNCTION "public"."update_profile_location"("lat" double precision, "lng" double precision) FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."update_profile_location"("lat" double precision, "lng" double precision) TO "authenticated";
GRANT ALL ON FUNCTION "public"."update_profile_location"("lat" double precision, "lng" double precision) TO "service_role";



REVOKE ALL ON FUNCTION "public"."update_profile_username"("p_username" "text") FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."update_profile_username"("p_username" "text") TO "authenticated";
GRANT ALL ON FUNCTION "public"."update_profile_username"("p_username" "text") TO "service_role";



REVOKE ALL ON FUNCTION "public"."use_streak_freeze"() FROM PUBLIC;
GRANT ALL ON FUNCTION "public"."use_streak_freeze"() TO "anon";
GRANT ALL ON FUNCTION "public"."use_streak_freeze"() TO "authenticated";
GRANT ALL ON FUNCTION "public"."use_streak_freeze"() TO "service_role";



GRANT ALL ON TABLE "public"."ai_usage_daily" TO "service_role";
GRANT SELECT ON TABLE "public"."ai_usage_daily" TO "authenticated";



GRANT ALL ON TABLE "public"."api_usage_limits" TO "service_role";



GRANT SELECT,MAINTAIN ON TABLE "public"."api_quota_usage" TO "authenticated";
GRANT ALL ON TABLE "public"."api_quota_usage" TO "service_role";



GRANT SELECT,MAINTAIN ON TABLE "public"."api_quotas" TO "authenticated";
GRANT ALL ON TABLE "public"."api_quotas" TO "service_role";



GRANT MAINTAIN ON TABLE "public"."friendships" TO "anon";
GRANT SELECT,INSERT,DELETE,MAINTAIN,UPDATE ON TABLE "public"."friendships" TO "authenticated";
GRANT ALL ON TABLE "public"."friendships" TO "service_role";



GRANT ALL ON TABLE "public"."gem_discoveries" TO "service_role";
GRANT SELECT ON TABLE "public"."gem_discoveries" TO "authenticated";



GRANT ALL ON TABLE "public"."gems" TO "service_role";
GRANT SELECT ON TABLE "public"."gems" TO "authenticated";



GRANT ALL ON TABLE "public"."hive_challenge_progress" TO "service_role";
GRANT SELECT ON TABLE "public"."hive_challenge_progress" TO "authenticated";



GRANT ALL ON TABLE "public"."hive_challenges" TO "service_role";
GRANT SELECT ON TABLE "public"."hive_challenges" TO "authenticated";



GRANT ALL ON TABLE "public"."mission_completions" TO "service_role";
GRANT SELECT ON TABLE "public"."mission_completions" TO "authenticated";



GRANT ALL ON TABLE "public"."missions" TO "service_role";
GRANT SELECT,INSERT,DELETE ON TABLE "public"."missions" TO "authenticated";



GRANT ALL ON TABLE "public"."profiles" TO "service_role";
GRANT UPDATE ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("id"),INSERT("id") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("username"),INSERT("username"),UPDATE("username") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("honey") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("hive_rank") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("badges") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("cities_visited") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("avatar_url"),INSERT("avatar_url"),UPDATE("avatar_url") ON TABLE "public"."profiles" TO "authenticated";



GRANT INSERT("updated_at"),UPDATE("updated_at") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("friend_code") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("streak_count") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("explorer_class") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("streak_freezes") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("equipped_frame") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("equipped_skin") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT("equipped_widget_theme") ON TABLE "public"."profiles" TO "authenticated";



GRANT SELECT,MAINTAIN ON TABLE "public"."profiles_public" TO "authenticated";
GRANT ALL ON TABLE "public"."profiles_public" TO "service_role";



GRANT ALL ON TABLE "public"."referrals" TO "service_role";
GRANT SELECT ON TABLE "public"."referrals" TO "authenticated";



GRANT ALL ON TABLE "public"."shop_items" TO "service_role";
GRANT SELECT ON TABLE "public"."shop_items" TO "authenticated";



GRANT ALL ON TABLE "public"."streak_milestone_claims" TO "service_role";
GRANT SELECT ON TABLE "public"."streak_milestone_claims" TO "authenticated";



GRANT ALL ON TABLE "public"."streak_milestones" TO "service_role";
GRANT SELECT ON TABLE "public"."streak_milestones" TO "authenticated";



GRANT ALL ON TABLE "public"."user_inventory" TO "service_role";
GRANT SELECT ON TABLE "public"."user_inventory" TO "authenticated";



GRANT ALL ON TABLE "public"."wanderly_plus_subscriptions" TO "service_role";
GRANT SELECT ON TABLE "public"."wanderly_plus_subscriptions" TO "authenticated";



GRANT ALL ON TABLE "public"."v_wanderly_plus_active" TO "service_role";



ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON SEQUENCES TO "service_role";






ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON FUNCTIONS TO "service_role";






ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "postgres";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "anon";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "authenticated";
ALTER DEFAULT PRIVILEGES FOR ROLE "postgres" IN SCHEMA "public" GRANT ALL ON TABLES TO "service_role";








-- ============================================================
-- LIVE-ONLY REVOKES (anon/authenticated privilege hardening)
-- pg_dump emits default GRANTs but not the REVOKEs applied in
-- live via SQL Editor. Appended so repo == DB (zero db diff).
-- ============================================================

revoke delete on table "public"."ai_usage_daily" from "anon";
revoke insert on table "public"."ai_usage_daily" from "anon";
revoke references on table "public"."ai_usage_daily" from "anon";
revoke select on table "public"."ai_usage_daily" from "anon";
revoke trigger on table "public"."ai_usage_daily" from "anon";
revoke truncate on table "public"."ai_usage_daily" from "anon";
revoke update on table "public"."ai_usage_daily" from "anon";
revoke delete on table "public"."ai_usage_daily" from "authenticated";
revoke insert on table "public"."ai_usage_daily" from "authenticated";
revoke references on table "public"."ai_usage_daily" from "authenticated";
revoke trigger on table "public"."ai_usage_daily" from "authenticated";
revoke truncate on table "public"."ai_usage_daily" from "authenticated";
revoke update on table "public"."ai_usage_daily" from "authenticated";
revoke delete on table "public"."api_quotas" from "anon";
revoke insert on table "public"."api_quotas" from "anon";
revoke references on table "public"."api_quotas" from "anon";
revoke select on table "public"."api_quotas" from "anon";
revoke trigger on table "public"."api_quotas" from "anon";
revoke truncate on table "public"."api_quotas" from "anon";
revoke update on table "public"."api_quotas" from "anon";
revoke delete on table "public"."api_quotas" from "authenticated";
revoke insert on table "public"."api_quotas" from "authenticated";
revoke references on table "public"."api_quotas" from "authenticated";
revoke trigger on table "public"."api_quotas" from "authenticated";
revoke truncate on table "public"."api_quotas" from "authenticated";
revoke update on table "public"."api_quotas" from "authenticated";
revoke delete on table "public"."api_usage_limits" from "anon";
revoke insert on table "public"."api_usage_limits" from "anon";
revoke references on table "public"."api_usage_limits" from "anon";
revoke select on table "public"."api_usage_limits" from "anon";
revoke trigger on table "public"."api_usage_limits" from "anon";
revoke truncate on table "public"."api_usage_limits" from "anon";
revoke update on table "public"."api_usage_limits" from "anon";
revoke delete on table "public"."api_usage_limits" from "authenticated";
revoke insert on table "public"."api_usage_limits" from "authenticated";
revoke references on table "public"."api_usage_limits" from "authenticated";
revoke select on table "public"."api_usage_limits" from "authenticated";
revoke trigger on table "public"."api_usage_limits" from "authenticated";
revoke truncate on table "public"."api_usage_limits" from "authenticated";
revoke update on table "public"."api_usage_limits" from "authenticated";
revoke delete on table "public"."friendships" from "anon";
revoke insert on table "public"."friendships" from "anon";
revoke references on table "public"."friendships" from "anon";
revoke select on table "public"."friendships" from "anon";
revoke trigger on table "public"."friendships" from "anon";
revoke truncate on table "public"."friendships" from "anon";
revoke update on table "public"."friendships" from "anon";
revoke references on table "public"."friendships" from "authenticated";
revoke trigger on table "public"."friendships" from "authenticated";
revoke truncate on table "public"."friendships" from "authenticated";
revoke delete on table "public"."gem_discoveries" from "anon";
revoke insert on table "public"."gem_discoveries" from "anon";
revoke references on table "public"."gem_discoveries" from "anon";
revoke select on table "public"."gem_discoveries" from "anon";
revoke trigger on table "public"."gem_discoveries" from "anon";
revoke truncate on table "public"."gem_discoveries" from "anon";
revoke update on table "public"."gem_discoveries" from "anon";
revoke delete on table "public"."gem_discoveries" from "authenticated";
revoke insert on table "public"."gem_discoveries" from "authenticated";
revoke references on table "public"."gem_discoveries" from "authenticated";
revoke trigger on table "public"."gem_discoveries" from "authenticated";
revoke truncate on table "public"."gem_discoveries" from "authenticated";
revoke update on table "public"."gem_discoveries" from "authenticated";
revoke delete on table "public"."gems" from "anon";
revoke insert on table "public"."gems" from "anon";
revoke references on table "public"."gems" from "anon";
revoke select on table "public"."gems" from "anon";
revoke trigger on table "public"."gems" from "anon";
revoke truncate on table "public"."gems" from "anon";
revoke update on table "public"."gems" from "anon";
revoke delete on table "public"."gems" from "authenticated";
revoke insert on table "public"."gems" from "authenticated";
revoke references on table "public"."gems" from "authenticated";
revoke trigger on table "public"."gems" from "authenticated";
revoke truncate on table "public"."gems" from "authenticated";
revoke update on table "public"."gems" from "authenticated";
revoke delete on table "public"."hive_challenge_progress" from "anon";
revoke insert on table "public"."hive_challenge_progress" from "anon";
revoke references on table "public"."hive_challenge_progress" from "anon";
revoke select on table "public"."hive_challenge_progress" from "anon";
revoke trigger on table "public"."hive_challenge_progress" from "anon";
revoke truncate on table "public"."hive_challenge_progress" from "anon";
revoke update on table "public"."hive_challenge_progress" from "anon";
revoke delete on table "public"."hive_challenge_progress" from "authenticated";
revoke insert on table "public"."hive_challenge_progress" from "authenticated";
revoke references on table "public"."hive_challenge_progress" from "authenticated";
revoke trigger on table "public"."hive_challenge_progress" from "authenticated";
revoke truncate on table "public"."hive_challenge_progress" from "authenticated";
revoke update on table "public"."hive_challenge_progress" from "authenticated";
revoke delete on table "public"."hive_challenges" from "anon";
revoke insert on table "public"."hive_challenges" from "anon";
revoke references on table "public"."hive_challenges" from "anon";
revoke select on table "public"."hive_challenges" from "anon";
revoke trigger on table "public"."hive_challenges" from "anon";
revoke truncate on table "public"."hive_challenges" from "anon";
revoke update on table "public"."hive_challenges" from "anon";
revoke delete on table "public"."hive_challenges" from "authenticated";
revoke insert on table "public"."hive_challenges" from "authenticated";
revoke references on table "public"."hive_challenges" from "authenticated";
revoke trigger on table "public"."hive_challenges" from "authenticated";
revoke truncate on table "public"."hive_challenges" from "authenticated";
revoke update on table "public"."hive_challenges" from "authenticated";
revoke delete on table "public"."mission_completions" from "anon";
revoke insert on table "public"."mission_completions" from "anon";
revoke references on table "public"."mission_completions" from "anon";
revoke select on table "public"."mission_completions" from "anon";
revoke trigger on table "public"."mission_completions" from "anon";
revoke truncate on table "public"."mission_completions" from "anon";
revoke update on table "public"."mission_completions" from "anon";
revoke delete on table "public"."mission_completions" from "authenticated";
revoke insert on table "public"."mission_completions" from "authenticated";
revoke references on table "public"."mission_completions" from "authenticated";
revoke trigger on table "public"."mission_completions" from "authenticated";
revoke truncate on table "public"."mission_completions" from "authenticated";
revoke update on table "public"."mission_completions" from "authenticated";
revoke delete on table "public"."missions" from "anon";
revoke insert on table "public"."missions" from "anon";
revoke references on table "public"."missions" from "anon";
revoke select on table "public"."missions" from "anon";
revoke trigger on table "public"."missions" from "anon";
revoke truncate on table "public"."missions" from "anon";
revoke update on table "public"."missions" from "anon";
revoke references on table "public"."missions" from "authenticated";
revoke trigger on table "public"."missions" from "authenticated";
revoke truncate on table "public"."missions" from "authenticated";
revoke update on table "public"."missions" from "authenticated";
revoke delete on table "public"."profiles" from "anon";
revoke insert on table "public"."profiles" from "anon";
revoke references on table "public"."profiles" from "anon";
revoke select on table "public"."profiles" from "anon";
revoke trigger on table "public"."profiles" from "anon";
revoke truncate on table "public"."profiles" from "anon";
revoke update on table "public"."profiles" from "anon";
revoke delete on table "public"."profiles" from "authenticated";
revoke insert on table "public"."profiles" from "authenticated";
revoke references on table "public"."profiles" from "authenticated";
revoke select on table "public"."profiles" from "authenticated";
revoke trigger on table "public"."profiles" from "authenticated";
revoke truncate on table "public"."profiles" from "authenticated";
revoke delete on table "public"."referrals" from "anon";
revoke insert on table "public"."referrals" from "anon";
revoke references on table "public"."referrals" from "anon";
revoke select on table "public"."referrals" from "anon";
revoke trigger on table "public"."referrals" from "anon";
revoke truncate on table "public"."referrals" from "anon";
revoke update on table "public"."referrals" from "anon";
revoke delete on table "public"."referrals" from "authenticated";
revoke insert on table "public"."referrals" from "authenticated";
revoke references on table "public"."referrals" from "authenticated";
revoke trigger on table "public"."referrals" from "authenticated";
revoke truncate on table "public"."referrals" from "authenticated";
revoke update on table "public"."referrals" from "authenticated";
revoke delete on table "public"."shop_items" from "anon";
revoke insert on table "public"."shop_items" from "anon";
revoke references on table "public"."shop_items" from "anon";
revoke select on table "public"."shop_items" from "anon";
revoke trigger on table "public"."shop_items" from "anon";
revoke truncate on table "public"."shop_items" from "anon";
revoke update on table "public"."shop_items" from "anon";
revoke delete on table "public"."shop_items" from "authenticated";
revoke insert on table "public"."shop_items" from "authenticated";
revoke references on table "public"."shop_items" from "authenticated";
revoke trigger on table "public"."shop_items" from "authenticated";
revoke truncate on table "public"."shop_items" from "authenticated";
revoke update on table "public"."shop_items" from "authenticated";
revoke delete on table "public"."streak_milestone_claims" from "anon";
revoke insert on table "public"."streak_milestone_claims" from "anon";
revoke references on table "public"."streak_milestone_claims" from "anon";
revoke select on table "public"."streak_milestone_claims" from "anon";
revoke trigger on table "public"."streak_milestone_claims" from "anon";
revoke truncate on table "public"."streak_milestone_claims" from "anon";
revoke update on table "public"."streak_milestone_claims" from "anon";
revoke delete on table "public"."streak_milestone_claims" from "authenticated";
revoke insert on table "public"."streak_milestone_claims" from "authenticated";
revoke references on table "public"."streak_milestone_claims" from "authenticated";
revoke trigger on table "public"."streak_milestone_claims" from "authenticated";
revoke truncate on table "public"."streak_milestone_claims" from "authenticated";
revoke update on table "public"."streak_milestone_claims" from "authenticated";
revoke delete on table "public"."streak_milestones" from "anon";
revoke insert on table "public"."streak_milestones" from "anon";
revoke references on table "public"."streak_milestones" from "anon";
revoke select on table "public"."streak_milestones" from "anon";
revoke trigger on table "public"."streak_milestones" from "anon";
revoke truncate on table "public"."streak_milestones" from "anon";
revoke update on table "public"."streak_milestones" from "anon";
revoke delete on table "public"."streak_milestones" from "authenticated";
revoke insert on table "public"."streak_milestones" from "authenticated";
revoke references on table "public"."streak_milestones" from "authenticated";
revoke trigger on table "public"."streak_milestones" from "authenticated";
revoke truncate on table "public"."streak_milestones" from "authenticated";
revoke update on table "public"."streak_milestones" from "authenticated";
revoke delete on table "public"."user_inventory" from "anon";
revoke insert on table "public"."user_inventory" from "anon";
revoke references on table "public"."user_inventory" from "anon";
revoke select on table "public"."user_inventory" from "anon";
revoke trigger on table "public"."user_inventory" from "anon";
revoke truncate on table "public"."user_inventory" from "anon";
revoke update on table "public"."user_inventory" from "anon";
revoke delete on table "public"."user_inventory" from "authenticated";
revoke insert on table "public"."user_inventory" from "authenticated";
revoke references on table "public"."user_inventory" from "authenticated";
revoke trigger on table "public"."user_inventory" from "authenticated";
revoke truncate on table "public"."user_inventory" from "authenticated";
revoke update on table "public"."user_inventory" from "authenticated";
revoke delete on table "public"."wanderly_plus_subscriptions" from "anon";
revoke insert on table "public"."wanderly_plus_subscriptions" from "anon";
revoke references on table "public"."wanderly_plus_subscriptions" from "anon";
revoke select on table "public"."wanderly_plus_subscriptions" from "anon";
revoke trigger on table "public"."wanderly_plus_subscriptions" from "anon";
revoke truncate on table "public"."wanderly_plus_subscriptions" from "anon";
revoke update on table "public"."wanderly_plus_subscriptions" from "anon";
revoke delete on table "public"."wanderly_plus_subscriptions" from "authenticated";
revoke insert on table "public"."wanderly_plus_subscriptions" from "authenticated";
revoke references on table "public"."wanderly_plus_subscriptions" from "authenticated";
revoke trigger on table "public"."wanderly_plus_subscriptions" from "authenticated";
revoke truncate on table "public"."wanderly_plus_subscriptions" from "authenticated";
revoke update on table "public"."wanderly_plus_subscriptions" from "authenticated";
