-- Remove pre-existing broad table privileges that bypass column-level profile hardening.

REVOKE ALL PRIVILEGES ON TABLE public.profiles FROM anon;
REVOKE ALL PRIVILEGES ON TABLE public.profiles FROM authenticated;

GRANT SELECT, INSERT ON public.profiles TO authenticated;
GRANT UPDATE (username, avatar_url, explorer_class) ON public.profiles TO authenticated;
