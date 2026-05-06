# Supabase RPC contract check (PowerShell).
# Verifies that every RPC called from Android app code or Edge Functions
# has a corresponding CREATE [OR REPLACE] FUNCTION public.<name> in supabase/ SQL files.
#
# Exit code 0: all RPCs have SQL definitions.
# Exit code 1: one or more RPCs are missing SQL definitions.

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

$searchPaths = @(
    (Join-Path $repoRoot "app\src\main"),
    (Join-Path $repoRoot "supabase\functions")
)

$rpcNames = @()

foreach ($searchPath in $searchPaths) {
    if (-not (Test-Path $searchPath)) { continue }
    $files = Get-ChildItem -Path $searchPath -Recurse -File -Include "*.kt","*.ts","*.js"
    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw
        $matches = [regex]::Matches($content, 'rpc\(\s*["'']([^"'']+)["'']')
        foreach ($match in $matches) {
            $rpcNames += $match.Groups[1].Value
        }
    }
}

$rpcNames = $rpcNames | Sort-Object -Unique

if ($rpcNames.Count -eq 0) {
    Write-Host "WARNING: No RPC calls found in source code."
    exit 0
}

$supabasePath = Join-Path $repoRoot "supabase"
$sqlFiles = Get-ChildItem -Path $supabasePath -Recurse -File -Include "*.sql"
$allSql = ($sqlFiles | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"

$defined = @()
$missing = @()

foreach ($rpcName in $rpcNames) {
    $pattern = "CREATE\s+(OR\s+REPLACE\s+)?FUNCTION\s+public\.$rpcName\b"
    if ($allSql -match $pattern) {
        $defined += $rpcName
    } else {
        $missing += $rpcName
    }
}

Write-Host "=== Supabase RPC Contract Check ==="
Write-Host ""
Write-Host "Defined RPCs ($($defined.Count)):"
foreach ($name in $defined) {
    Write-Host "  [OK] $name"
}

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "MISSING RPCs ($($missing.Count)):"
    foreach ($name in $missing) {
        Write-Host "  [MISSING] $name"
    }
    Write-Host ""
    Write-Host "FAIL: $($missing.Count) RPC(s) called from code but not defined in supabase/ SQL files."
    exit 1
}

Write-Host ""
Write-Host "PASS: All $($defined.Count) RPCs have SQL definitions."
exit 0
