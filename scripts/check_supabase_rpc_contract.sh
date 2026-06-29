#!/usr/bin/env bash
# Supabase RPC contract check.
# Verifies that every RPC called from Android app code or Edge Functions
# has a corresponding CREATE [OR REPLACE] FUNCTION public.<name> in supabase/ SQL files.
#
# Exit code 0: all RPCs have SQL definitions.
# Exit code 1: one or more RPCs are missing SQL definitions.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Collect RPC names from Kotlin/TypeScript/JavaScript source files.
# Handles both single-line: .rpc("name", ...) and multi-line:
#   .rpc(
#       "name",
#       ...
#   )
rpc_calls=""

search_dirs=()
[ -d "$REPO_ROOT/app/src/main" ] && search_dirs+=("$REPO_ROOT/app/src/main")
[ -d "$REPO_ROOT/supabase/functions" ] && search_dirs+=("$REPO_ROOT/supabase/functions")

if [ ${#search_dirs[@]} -eq 0 ]; then
    echo "WARNING: No source directories found."
    exit 0
fi

# Use a two-pass approach:
# 1. Find lines containing .rpc( or rpc( with a string literal on the same line
# 2. Find string literals on the line AFTER .rpc( for multi-line calls
for dir in "${search_dirs[@]}"; do
    # Single-line: rpc("name" or rpc('name'
    while IFS= read -r match; do
        [ -z "$match" ] && continue
        rpc_calls="$rpc_calls"$'\n'"$match"
    done < <(grep -rh --include="*.kt" --include="*.ts" --include="*.js" \
        'rpc(' "$dir" 2>/dev/null \
        | grep -o 'rpc([[:space:]]*["'"'"'][^"'"'"']*["'"'"']' \
        | sed -E 's/rpc\([[:space:]]*["'"'"']([^"'"'"']+)["'"'"']/\1/' \
        || true)

    # Multi-line: .rpc(\n    "name"
    # Concatenate each file, look for rpc( followed by a quoted string on the next line
    while IFS= read -r file; do
        [ -z "$file" ] && continue
        while IFS= read -r match; do
            [ -z "$match" ] && continue
            rpc_calls="$rpc_calls"$'\n'"$match"
        done < <(sed -n '/\.rpc(/{n;s/^[[:space:]]*["'"'"']\([^"'"'"']*\)["'"'"'].*/\1/p;}' "$file" 2>/dev/null || true)
    done < <(find "$dir" -type f \( -name "*.kt" -o -name "*.ts" -o -name "*.js" \) 2>/dev/null)
done

# Deduplicate and sort
rpc_calls=$(echo "$rpc_calls" | grep -v '^$' | sort -u)

if [ -z "$rpc_calls" ]; then
    echo "WARNING: No RPC calls found in source code."
    exit 0
fi

# Check each RPC has a corresponding SQL definition in supabase/ directory.
missing=()
defined=()

while IFS= read -r rpc_name; do
    [ -z "$rpc_name" ] && continue
    # Match the function name after CREATE ... FUNCTION regardless of schema prefix or quoting style:
    # `FUNCTION public.name(`, `FUNCTION name(`, or the pg_dump form `FUNCTION "public"."name"(`.
    if grep -rqiE "CREATE.*FUNCTION.*\\b$rpc_name\\b" "$REPO_ROOT/supabase/" 2>/dev/null; then
        defined+=("$rpc_name")
    else
        missing+=("$rpc_name")
    fi
done <<< "$rpc_calls"

echo "=== Supabase RPC Contract Check ==="
echo ""
echo "Defined RPCs (${#defined[@]}):"
for name in "${defined[@]}"; do
    echo "  [OK] $name"
done

if [ ${#missing[@]} -gt 0 ]; then
    echo ""
    echo "MISSING RPCs (${#missing[@]}):"
    for name in "${missing[@]}"; do
        echo "  [MISSING] $name — called from code but no CREATE FUNCTION public.$name found in supabase/*.sql"
    done
    echo ""
    echo "FAIL: ${#missing[@]} RPC(s) called from code but not defined in supabase/ SQL files."
    echo "Action required: Add SQL function definitions or remove unused RPC calls."
    exit 1
fi

echo ""
echo "PASS: All ${#defined[@]} RPCs have SQL definitions."
exit 0
