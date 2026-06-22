-- Allow authenticated admin users to inspect and patch profile progress while
-- keeping admin_role protected by the existing trigger.

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS idx_profiles_admin_role
ON public.profiles (id)
WHERE admin_role = true;

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

DROP POLICY IF EXISTS "admins_select_all_profiles" ON public.profiles;
CREATE POLICY "admins_select_all_profiles"
ON public.profiles
FOR SELECT
TO authenticated
USING (public.is_current_profile_admin());

DROP POLICY IF EXISTS "admins_update_any_profile" ON public.profiles;
CREATE POLICY "admins_update_any_profile"
ON public.profiles
FOR UPDATE
TO authenticated
USING (public.is_current_profile_admin())
WITH CHECK (public.is_current_profile_admin());
