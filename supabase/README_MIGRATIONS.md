# Supabase Migrations

Apply every SQL file in `supabase/migrations/` before releasing a build that depends on it.

## Apply With Supabase CLI

1. Link the project if needed:
   ```sh
   supabase link --project-ref <project-ref>
   ```
2. Push pending migrations:
   ```sh
   supabase db push
   ```

## Apply With Supabase Dashboard

1. Open the Supabase project dashboard.
2. Go to SQL Editor.
3. Open each migration file in chronological order.
4. Run the SQL and confirm it succeeds before deploying the app.

## Current Required Migrations

- `20260424_add_friend_code_unique_index.sql`: adds a unique index on `profiles(friend_code)`.
- `20260424_add_friendships_indexes.sql`: adds lookup indexes on `friendships(user_id)` and `friendships(friend_id)`.
