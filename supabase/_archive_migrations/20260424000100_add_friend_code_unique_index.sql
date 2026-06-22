-- Ensure friend_code lookups are fast and unique
CREATE UNIQUE INDEX IF NOT EXISTS idx_profiles_friend_code
ON profiles(friend_code);
