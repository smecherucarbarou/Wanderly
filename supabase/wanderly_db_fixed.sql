-- ============================================================
-- WANDERLY - Complete Database Definition
-- Generated: 2026-04-28T10:47:53.2247131+03:00
-- Based on: supabase.txt + AUDIT.md fixes
-- Issues fixed: CRIT-01, CRIT-02, MIN-06, MIN-07
-- ============================================================

-- ============================================================
-- SECTION 1 - EXTENSIONS
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS supabase_vault WITH SCHEMA vault;

-- ============================================================
-- SECTION 2 - TABLES
-- ============================================================

CREATE TABLE IF NOT EXISTS public.profiles (
    id uuid NOT NULL,
    username text,
    honey integer DEFAULT 0,
    hive_rank integer DEFAULT 1,
    badges text[] DEFAULT '{}'::text[],
    cities_visited text[] DEFAULT '{}'::text[],
    avatar_url text,
    updated_at timestamp with time zone DEFAULT now(),
    last_buzz_date date,
    last_lat double precision,
    last_lng double precision,
    friend_code text,
    streak_count integer DEFAULT 0,
    explorer_class text,
    -- AUDIT FIX MIN-07: store mission dates as DATE instead of TEXT.
    last_mission_date date,
    admin_role boolean NOT NULL DEFAULT false,
    CONSTRAINT profiles_pkey PRIMARY KEY (id),
    CONSTRAINT profiles_id_fkey FOREIGN KEY (id) REFERENCES auth.users(id),
    CONSTRAINT profiles_username_key UNIQUE (username),
    CONSTRAINT profiles_friend_code_key UNIQUE (friend_code)
);

-- AUDIT FIX MIN-07: make existing installations converge on DATE.
ALTER TABLE public.profiles
    ALTER COLUMN last_mission_date TYPE date
    USING CASE
        WHEN last_mission_date::text ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
            THEN last_mission_date::date
        ELSE NULL
    END;

CREATE TABLE IF NOT EXISTS public.friendships (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    friend_id uuid NOT NULL,
    -- AUDIT FIX CRIT-02: new friendships must begin pending.
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'accepted', 'blocked')),
    created_at timestamp with time zone DEFAULT now(),
    CONSTRAINT friendships_pkey PRIMARY KEY (id),
    CONSTRAINT friendships_user_id_fkey FOREIGN KEY (user_id) REFERENCES auth.users(id),
    CONSTRAINT friendships_friend_id_fkey FOREIGN KEY (friend_id) REFERENCES auth.users(id),
    CONSTRAINT friendships_no_self_friend CHECK (user_id <> friend_id),
    CONSTRAINT friendships_user_id_friend_id_key UNIQUE (user_id, friend_id)
);

-- AUDIT FIX CRIT-02: make existing installations converge on pending-by-default.
UPDATE public.friendships
SET status = 'pending'
WHERE status IS NULL;

ALTER TABLE public.friendships
    ALTER COLUMN status SET DEFAULT 'pending',
    ALTER COLUMN status SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'friendships_status_valid'
          AND conrelid = 'public.friendships'::regclass
    ) THEN
        ALTER TABLE public.friendships
            ADD CONSTRAINT friendships_status_valid
            CHECK (status IN ('pending', 'accepted', 'blocked'));
    END IF;
END $$;

-- ============================================================
-- SECTION 3 - INDEXES
-- ============================================================

-- AUDIT FIX MIN-06: remove redundant indexes covered by primary/unique constraints.
DROP INDEX IF EXISTS public.idx_profiles_friend_code;
DROP INDEX IF EXISTS public.idx_profiles_id;
DROP INDEX IF EXISTS public.idx_profiles_username;
DROP INDEX IF EXISTS public.idx_friendships_friend_id;

CREATE INDEX IF NOT EXISTS friendships_friend_id_idx
    ON public.friendships USING btree (friend_id);

CREATE UNIQUE INDEX IF NOT EXISTS friendships_pair_unique_idx
    ON public.friendships USING btree (LEAST(user_id, friend_id), GREATEST(user_id, friend_id));

CREATE INDEX IF NOT EXISTS idx_friendships_user_id
    ON public.friendships USING btree (user_id);

CREATE INDEX IF NOT EXISTS idx_profiles_admin_role
    ON public.profiles USING btree (id)
    WHERE admin_role = true;

-- ============================================================
-- SECTION 4 - ROW LEVEL SECURITY
-- ============================================================

-- AUDIT FIX CRIT-01: private profile rows are readable only by their owner.
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Anyone can view profiles" ON public.profiles;
DROP POLICY IF EXISTS "Users can view own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users read own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users insert own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users update own profile" ON public.profiles;
DROP POLICY IF EXISTS "admins_select_all_profiles" ON public.profiles;
DROP POLICY IF EXISTS "admins_update_any_profile" ON public.profiles;

CREATE OR REPLACE FUNCTION public.is_current_profile_admin()
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
STABLE
SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.profiles AS p
        WHERE p.id = auth.uid()
          AND p.admin_role = true
    );
$$;

REVOKE ALL ON FUNCTION public.is_current_profile_admin() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.is_current_profile_admin() FROM anon;
GRANT EXECUTE ON FUNCTION public.is_current_profile_admin() TO authenticated;

CREATE POLICY "Users read own profile"
  ON public.profiles FOR SELECT
  TO authenticated
  USING (auth.uid() = id);

CREATE POLICY "Users insert own profile"
  ON public.profiles FOR INSERT
  TO authenticated
  WITH CHECK (auth.uid() = id);

CREATE POLICY "Users update own profile"
  ON public.profiles FOR UPDATE
  TO authenticated
  USING (auth.uid() = id)
  WITH CHECK (auth.uid() = id);

CREATE POLICY "admins_select_all_profiles"
  ON public.profiles FOR SELECT
  TO authenticated
  USING (public.is_current_profile_admin());

CREATE POLICY "admins_update_any_profile"
  ON public.profiles FOR UPDATE
  TO authenticated
  USING (public.is_current_profile_admin())
  WITH CHECK (public.is_current_profile_admin());

-- AUDIT FIX CRIT-02: friendships use pending request plus recipient action.
ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can add friends" ON public.friendships;
DROP POLICY IF EXISTS "Users can insert friendships" ON public.friendships;
DROP POLICY IF EXISTS "Users can update friendships" ON public.friendships;
DROP POLICY IF EXISTS "Users can delete friendships" ON public.friendships;
DROP POLICY IF EXISTS "Users can view own friendships" ON public.friendships;
DROP POLICY IF EXISTS "Users can delete their own friendships" ON public.friendships;
DROP POLICY IF EXISTS "Users can view their own friendships" ON public.friendships;
DROP POLICY IF EXISTS "Users insert own pending friendship" ON public.friendships;
DROP POLICY IF EXISTS "Recipient can accept or block friendship" ON public.friendships;
DROP POLICY IF EXISTS "Either party can delete friendship" ON public.friendships;
DROP POLICY IF EXISTS "Users view own friendships" ON public.friendships;

CREATE POLICY "Users insert own pending friendship"
  ON public.friendships FOR INSERT
  TO authenticated
  WITH CHECK (auth.uid() = user_id AND status = 'pending');

CREATE POLICY "Recipient can accept or block friendship"
  ON public.friendships FOR UPDATE
  TO authenticated
  USING (auth.uid() = friend_id)
  WITH CHECK (auth.uid() = friend_id AND status IN ('accepted', 'blocked'));

CREATE POLICY "Either party can delete friendship"
  ON public.friendships FOR DELETE
  TO authenticated
  USING (auth.uid() = user_id OR auth.uid() = friend_id);

CREATE POLICY "Users view own friendships"
  ON public.friendships FOR SELECT
  TO authenticated
  USING (auth.uid() = user_id OR auth.uid() = friend_id);

-- ============================================================
-- SECTION 5 - VIEWS
-- ============================================================

-- AUDIT FIX CRIT-01: public social reads expose only non-location profile fields.
CREATE OR REPLACE VIEW public.profiles_public
WITH (security_invoker = true) AS
SELECT
    id,
    username,
    honey,
    hive_rank,
    badges,
    cities_visited,
    avatar_url,
    friend_code,
    streak_count,
    explorer_class
FROM public.profiles;

REVOKE ALL ON public.profiles_public FROM anon;
REVOKE ALL ON public.profiles_public FROM public;
GRANT SELECT ON public.profiles_public TO authenticated;

-- ============================================================
-- SECTION 6 - FUNCTIONS & TRIGGERS
-- ============================================================

CREATE OR REPLACE FUNCTION public.generate_friend_code()
RETURNS text
LANGUAGE plpgsql
AS $$
DECLARE
    generated_code text;
BEGIN
    LOOP
        generated_code := upper(substr(encode(gen_random_bytes(4), 'hex'), 1, 6));
        EXIT WHEN NOT EXISTS (
            SELECT 1
            FROM public.profiles
            WHERE friend_code = generated_code
        );
    END LOOP;

    RETURN generated_code;
END;
$$;

CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
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

CREATE OR REPLACE FUNCTION public.protect_profile_admin_role()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
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

CREATE OR REPLACE FUNCTION public.sync_hive_rank()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.hive_rank := CASE
        WHEN COALESCE(NEW.honey, 0) >= 600 THEN 4
        WHEN COALESCE(NEW.honey, 0) >= 300 THEN 3
        WHEN COALESCE(NEW.honey, 0) >= 100 THEN 2
        ELSE 1
    END;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
AFTER INSERT ON auth.users
FOR EACH ROW
EXECUTE FUNCTION public.handle_new_user();

DROP TRIGGER IF EXISTS trg_protect_profile_admin_role ON public.profiles;
CREATE TRIGGER trg_protect_profile_admin_role
BEFORE INSERT OR UPDATE ON public.profiles
FOR EACH ROW
EXECUTE FUNCTION public.protect_profile_admin_role();

DROP TRIGGER IF EXISTS trg_sync_hive_rank ON public.profiles;
CREATE TRIGGER trg_sync_hive_rank
BEFORE INSERT OR UPDATE ON public.profiles
FOR EACH ROW
EXECUTE FUNCTION public.sync_hive_rank();

-- ============================================================
-- SECTION 7 - GRANTS & PERMISSIONS
-- ============================================================

GRANT USAGE ON SCHEMA public TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.profiles TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.friendships TO authenticated;
GRANT EXECUTE ON FUNCTION public.generate_friend_code() TO authenticated;
REVOKE EXECUTE ON FUNCTION public.handle_new_user() FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.protect_profile_admin_role() FROM anon, authenticated;

-- ============================================================
-- SECTION 8 - STORAGE BUCKETS
-- ============================================================

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'avatars',
    'avatars',
    true,
    5242880,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE
SET
    name = EXCLUDED.name,
    public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Public read avatars" ON storage.objects;
DROP POLICY IF EXISTS "Users can upload own avatar" ON storage.objects;
DROP POLICY IF EXISTS "Users can update own avatar" ON storage.objects;
DROP POLICY IF EXISTS "Users can delete own avatar" ON storage.objects;

CREATE POLICY "Public read avatars"
ON storage.objects
FOR SELECT
TO public
USING (bucket_id = 'avatars'::text);

CREATE POLICY "Users can upload own avatar"
ON storage.objects
FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'avatars'::text
    AND (storage.foldername(name))[1] = 'profiles'::text
    AND (storage.foldername(name))[2] = (auth.uid())::text
);

CREATE POLICY "Users can update own avatar"
ON storage.objects
FOR UPDATE
TO authenticated
USING (
    bucket_id = 'avatars'::text
    AND (storage.foldername(name))[1] = 'profiles'::text
    AND (storage.foldername(name))[2] = (auth.uid())::text
)
WITH CHECK (
    bucket_id = 'avatars'::text
    AND (storage.foldername(name))[1] = 'profiles'::text
    AND (storage.foldername(name))[2] = (auth.uid())::text
);

CREATE POLICY "Users can delete own avatar"
ON storage.objects
FOR DELETE
TO authenticated
USING (
    bucket_id = 'avatars'::text
    AND (storage.foldername(name))[1] = 'profiles'::text
    AND (storage.foldername(name))[2] = (auth.uid())::text
);
