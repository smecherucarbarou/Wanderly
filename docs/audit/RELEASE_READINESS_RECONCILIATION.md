# Release Readiness Reconciliation

## Why one audit says ~83/100 and another says ~56/100

The ~83 score reflects **demo/competition readiness**: the app builds, tests pass, UI works, and the core features are functional against a configured backend. This is the perspective of "can we show this and have it work."

The ~56 score reflects **production security readiness**: does the backend enforce integrity? Can a modified client cheat? Are release artifacts properly signed? Is the supply chain auditable? This is the perspective of "can a malicious or curious user exploit this in production."

Both scores are valid for their context. The original gap existed because:
1. Security-critical SQL (RPCs, column grants) was not committed to the repo even though the app calls them.
2. Release signing had a permissive fallback to debug signing.
3. Auth callback accepted raw tokens from deep links.
4. No CI validation of backend contract integrity.

## Score by context

| Context | Score | Reason |
|---|---:|---|
| Demo / competition | 90/100 | Build/test/lint pass, core flows work, signing strictness correct, auth hardened, live backend aligned. Remaining: some UI polish. |
| Closed testing | 84/100 | Signing path strict, backend RPCs applied live, RLS column grants verified, Edge Functions redeployed. Remaining: App Links not verified. |
| Public Play Store | 72/100 | Release signing strict and backend aligned. Remaining: privacy policy, Data Safety form, App Links/assetlinks, Play App Signing. |
| Production security | 78/100 | Column-level grants block mass assignment, friendship identity immutable, auth rejects tokens, Guava patched, RPC contract enforced in CI, live grants verified. Remaining: local Deno unavailable and App Links. |

## What was fixed in this remediation

1. **Release signing** - no longer falls back to debug. `assembleRelease`/`bundleRelease` fail without keystore.
2. **Auth callback** - rejects `access_token`/`refresh_token` in fragment or query. Only PKCE `code` flow accepted.
3. **RPC contract** - all 11 RPCs called from code now have SQL definitions in repo. CI script validates this.
4. **Profiles mass assignment** - column-level GRANT restricts direct UPDATE to `username`, `avatar_url`, `explorer_class` only. Economy fields require SECURITY DEFINER RPCs.
5. **Friendships identity** - trigger prevents mutation of `user_id`/`friend_id` on existing rows.
6. **Guava CVE** - forced to 33.4.0-android, patching transitive 31.1-android from Firebase.
7. **CI gate** - RPC contract check added to CI pipeline.
8. **Live Supabase migration** - `20260505000100`, `20260506000100`, and `20260506000200` applied with `npx supabase db push`.
9. **Edge Function deployment** - `gemini-proxy` and `google-places-proxy` redeployed after import pinning.

## What remains manual

See `MANUAL_ACTIONS.md` for complete list. Key items:
- Configure Play App Signing
- Create privacy policy
- Complete Data Safety form
- Host `assetlinks.json` for App Links verification
- Set spend alerts or optional quota overrides if needed

## What still blocks a real public release

| Blocker | Category | Effort |
|---|---|---|
| Privacy policy not published | Legal/Play | 1-2 hours |
| Data Safety form not completed | Play Console | 30 min |
| App Links / assetlinks.json not hosted | Auth security | 1 hour |
| Play App Signing not configured | Release | 30 min |
