$PROJECT_REF = "kllgqwryipbegrnwfczo"

Write-Host "== Link project =="
npx supabase link --project-ref $PROJECT_REF

Write-Host "== Migration list =="
npx supabase migration list

Write-Host "== Push DB migrations =="
npx supabase db push

Write-Host "== Deploy Edge Functions =="
npx supabase functions deploy gemini-proxy --project-ref $PROJECT_REF
npx supabase functions deploy google-places-proxy --project-ref $PROJECT_REF

Write-Host "== Check secrets =="
npx supabase secrets list --project-ref $PROJECT_REF

Write-Host "== Dump schema after migrations =="
npx supabase db dump --schema public --file supabase_after_remediation.sql

Write-Host "== Done. Now check Dashboard linter + run SQL verification scripts manually if psql is unavailable. =="