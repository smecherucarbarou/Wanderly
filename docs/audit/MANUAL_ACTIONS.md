# Manual Actions Required

Items that cannot be fully completed in the repository alone and require human action in external systems.

## Completed Live Backend Actions

**Status:** Completed on 2026-05-06
**System:** Supabase CLI

Completed:
- Applied live migrations with `npx supabase db push`.
- Applied `20260505000100_add_admin_update_profile_stats_rpc.sql`.
- Applied `20260506000100_add_missing_rpcs_and_column_grants.sql`.
- Applied `20260506000200_revoke_profiles_dangerous_table_grants.sql`.
- Verified required RPCs, `api_quotas`, profile grants, column-level grants, and friendship identity trigger through live SQL.
- Verified required Edge Function secrets exist: `GEMINI_API_KEY`, `MAPS_API_KEY`, `ALLOWED_ORIGINS`.
- Redeployed `gemini-proxy` version 20.
- Redeployed `google-places-proxy` version 15.

Optional quota secrets are not required unless the default limits need to change:
- `GEMINI_DAILY_QUOTA` (optional, defaults to 50)
- `PLACES_DAILY_QUOTA` (optional, defaults to 100)

---

## 1. Play App Signing Configuration

**Priority:** High (blocks Play Store upload)
**System:** Google Play Console -> Release -> Setup -> App signing

1. Enroll in Google Play App Signing.
2. Upload the app signing key or let Google generate one.
3. Download the upload key certificate.
4. Configure CI secrets: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.

---

## 2. Privacy Policy

**Priority:** High (Play Store requirement)
**System:** External hosted page

1. Draft privacy policy covering: location data, user profiles, friend connections, analytics.
2. Host at a stable URL (for example, `https://wanderly.app/privacy`).
3. Add URL to Play Store listing and in-app settings.

---

## 3. Play Store Data Safety Form

**Priority:** High (Play Store requirement)
**System:** Google Play Console -> Policy -> App content -> Data safety

Declare:
- Location data: collected, not shared, used for app functionality
- User identifiers: collected for account management
- Crash logs: collected via Firebase Crashlytics
- Usage data: collected via Firebase Analytics

---

## 4. App Links / assetlinks.json

**Priority:** Medium (auth security hardening)
**System:** Web server hosting `wanderly.app`

1. Host `/.well-known/assetlinks.json` at `https://wanderly.app/.well-known/assetlinks.json`:
```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.novahorizon.wanderly",
    "sha256_cert_fingerprints": ["<SHA256 of your signing certificate>"]
  }
}]
```

2. Update `AndroidManifest.xml` to use HTTPS verified links for auth callback instead of custom scheme.
3. Update Supabase project auth redirect URL to `https://wanderly.app/auth/callback`.

---

## 5. Spend Alerts / Quotas

**Priority:** Low
**System:** Google Cloud Console, Supabase Dashboard

- Set billing alerts on Google Cloud for Gemini API and Places API usage.
- Monitor `api_quotas` table for usage patterns.
- Adjust `GEMINI_DAILY_QUOTA` and `PLACES_DAILY_QUOTA` only if defaults need to change.
