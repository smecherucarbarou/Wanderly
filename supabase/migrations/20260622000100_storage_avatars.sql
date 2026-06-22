-- ============================================================
-- Storage config: avatars bucket + RLS policies on storage.objects
-- Reflects LIVE state (project kllgqwryipbegrnwfczo), schema "storage".
-- Verified byte-identical to `supabase db dump --linked --schema storage`.
--
-- Baseline (20260622000000) is --schema public only, so storage was
-- unrepresented in repo. This migration closes that gap.
--
-- storage.objects/buckets are platform-owned by supabase_storage_admin.
-- On hosted (supabase db push) the postgres migration role is a member of
-- that role, so we assume it to own these objects exactly as live.
-- The local shadow DB used by `supabase db diff` runs with a restricted
-- role that lacks this membership; there we skip gracefully so this
-- migration never breaks db diff (storage is platform-managed and is
-- excluded from db dump/diff by default for this same reason).
--
-- NOTE: no SELECT policy by design — bucket is public, reads served via
-- bucket.public=true without a read policy.
-- Idempotent: safe if ever actually run (objects already exist in live).
-- ============================================================

do $$
begin
  begin
    set local role supabase_storage_admin;
  exception when insufficient_privilege then
    raise notice 'storage_avatars: no supabase_storage_admin membership; skipping (local shadow)';
    return;
  end;

  insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
  values ('avatars', 'avatars', true, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
  on conflict (id) do nothing;

  alter table storage.objects enable row level security;

  drop policy if exists "wanderly_avatar_delete_profiles_path" on storage.objects;
  create policy "wanderly_avatar_delete_profiles_path"
  on storage.objects
  for delete
  to authenticated
  using (((bucket_id = 'avatars'::text) AND ((storage.foldername(name))[1] = 'profiles'::text) AND ((storage.foldername(name))[2] = (auth.uid())::text)));

  drop policy if exists "wanderly_avatar_insert_profiles_path" on storage.objects;
  create policy "wanderly_avatar_insert_profiles_path"
  on storage.objects
  for insert
  to authenticated
  with check (((bucket_id = 'avatars'::text) AND ((storage.foldername(name))[1] = 'profiles'::text) AND ((storage.foldername(name))[2] = (auth.uid())::text) AND (lower(storage.extension(name)) = ANY (ARRAY['jpg'::text, 'jpeg'::text, 'png'::text, 'webp'::text]))));

  drop policy if exists "wanderly_avatar_update_profiles_path" on storage.objects;
  create policy "wanderly_avatar_update_profiles_path"
  on storage.objects
  for update
  to authenticated
  using (((bucket_id = 'avatars'::text) AND ((storage.foldername(name))[1] = 'profiles'::text) AND ((storage.foldername(name))[2] = (auth.uid())::text)))
  with check (((bucket_id = 'avatars'::text) AND ((storage.foldername(name))[1] = 'profiles'::text) AND ((storage.foldername(name))[2] = (auth.uid())::text) AND (lower(storage.extension(name)) = ANY (ARRAY['jpg'::text, 'jpeg'::text, 'png'::text, 'webp'::text]))));
end
$$;
