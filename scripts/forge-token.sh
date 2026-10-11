#!/usr/bin/env bash
# Forges a MITPass access token: rewrites its payload to "roles":["ADMIN"] and keeps the original signature.
# This is the "edit the JWT" attack; the gateway and every service must answer 401 (docs/plans/phase-2.md, exit 5).
#
#   FORGED=$(scripts/forge-token.sh "$STUDENT_TOKEN")
#   curl -i -H "Authorization: Bearer $FORGED" http://localhost:8080/api/users     # gateway      → 401
#   curl -i -H "Authorization: Bearer $FORGED" http://localhost:8081/api/users     # auth-service → 401
#
# Works in Git Bash, Linux and macOS (needs only base64, tr and sed). Demo use only.
set -euo pipefail

token="${1:-$(cat)}"
IFS=. read -r header payload signature <<<"$token"
if [ -z "${signature:-}" ]; then
    echo "usage: $0 <access token>   (or pipe the token in)" >&2
    exit 2
fi

b64url_decode() {
    local s="${1//-/+}"
    s="${s//_//}"
    case $(( ${#s} % 4 )) in
        2) s="$s==" ;;
        3) s="$s=" ;;
    esac
    printf '%s' "$s" | base64 -d
}

b64url_encode() {
    base64 | tr -d '=\n' | tr '/+' '_-'
}

claims="$(b64url_decode "$payload")"
forged="$(printf '%s' "$claims" | sed -E 's/"roles":\[[^]]*\]/"roles":["ADMIN"]/')"
if [ "$forged" = "$claims" ]; then
    echo "warning: no roles to change (already ADMIN?); use a student's token" >&2
fi

printf '%s.%s.%s\n' "$header" "$(printf '%s' "$forged" | b64url_encode)" "$signature"
