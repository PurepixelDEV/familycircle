#!/usr/bin/env bash
# End-to-end API smoke test against a running FamilyCircle instance (e.g. the compose stack).
# Exercises: setup → invite → register → device login → location upload → share → map → revoke.
#
# Usage: FC_URL=http://localhost:9092 FC_SETUP_CODE=XXXX-XXXX scripts/smoke-test.sh
# The instance must be fresh (setup not yet done). Requires curl and python3.
set -euo pipefail

URL="${FC_URL:-http://localhost:9092}"
CODE="${FC_SETUP_CODE:?FC_SETUP_CODE is required (see container log)}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PY=""
for cand in python3 python; do
  if command -v "$cand" >/dev/null && "$cand" -c 'import sys' 2>/dev/null; then PY="$cand"; break; fi
done
[[ -n "$PY" ]] || { echo "python3 is required"; exit 1; }

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; exit 1; }
json() { "$PY" -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {}, {'d': d}))" "$1"; }

# req <cookiejar|-> <bearer|-> METHOD PATH [JSON]  → prints body; status goes to a file because
# req often runs inside $(...) (a subshell), where a variable would not survive.
req() {
  local jar="$1" bearer="$2" method="$3" path="$4" body="${5:-}"
  local args=(-s -o "$TMP/body" -w '%{http_code}' -X "$method" -H 'X-FC-CSRF: 1')
  [[ "$jar" != "-" ]] && args+=(-b "$TMP/$jar" -c "$TMP/$jar")
  [[ "$bearer" != "-" ]] && args+=(-H "Authorization: Bearer $bearer")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  curl "${args[@]}" "$URL/api/v1$path" >"$TMP/status"
  cat "$TMP/body"
}
expect() { local st; st="$(cat "$TMP/status")"; [[ "$st" == "$1" ]] || fail "$2 (HTTP $st: $(cat "$TMP/body"))"; pass "$2"; }

echo "FamilyCircle smoke test against $URL"
curl -sf "$URL/healthz" >/dev/null && pass "healthz"
curl -sf "$URL/readyz" >/dev/null && pass "readyz (database reachable)"
curl -sf "$URL/" | grep -q '<div id="root">' && pass "web app served"
[[ "$(req - - GET /instance | json 'd["setup_required"]')" == "True" ]] && pass "instance requires setup"

PW="smoke test password 1"
req anna - POST /setup "{\"setup_token\":\"$CODE\",\"email\":\"anna@smoke.test\",\"password\":\"$PW\",\"display_name\":\"Anna\",\"family_name\":\"Smoke\"}" >/dev/null
expect 201 "setup creates admin account"
FAM="$(req anna - GET /families | json 'd["families"][0]["id"]')"
INVITE="$(req anna - POST "/families/$FAM/invites" '{}' | json 'd["code"]')"
expect 201 "admin creates invite"

BOB_TOKEN_JSON="$(req - - POST /auth/token "{\"grant_type\":\"password\",\"invite_code\":\"$INVITE\",\"email\":\"bob@smoke.test\",\"password\":\"$PW\",\"display_name\":\"Bob\",\"device_name\":\"Bob phone\"}")"
expect 200 "device registers with invite code (Android flow)"
BOB_TOKEN="$(echo "$BOB_TOKEN_JSON" | json 'd["access_token"]')"
BOB_ID="$(echo "$BOB_TOKEN_JSON" | json 'd["user"]["id"]')"
ANNA_ID="$(req anna - GET /me | json 'd["id"]')"

req - - POST /auth/token "{\"grant_type\":\"password\",\"invite_code\":\"$INVITE\",\"email\":\"eve@smoke.test\",\"password\":\"$PW\",\"display_name\":\"Eve\",\"device_name\":\"x\"}" >/dev/null
expect 400 "used invite code is rejected"

NOW="$("$PY" -c 'import datetime;print(datetime.datetime.now(datetime.timezone.utc).isoformat())')"
PID="$("$PY" -c 'import uuid;print(uuid.uuid4())')"
req - "$BOB_TOKEN" POST /locations "{\"points\":[{\"id\":\"$PID\",\"recorded_at\":\"$NOW\",\"lat\":47.3769,\"lon\":8.5417,\"accuracy_m\":9,\"battery_pct\":80,\"motion\":\"walking\"}]}" | grep -q "$PID"
expect 200 "device uploads a location"

[[ "$(req anna - GET "/families/$FAM/map" | json "[m for m in d['members'] if m['user_id']=='$BOB_ID'][0]['location']")" == "None" ]] \
  && pass "admin cannot see Bob without share" || fail "location visible without share"

req - "$BOB_TOKEN" PUT "/shares/$ANNA_ID" >/dev/null
expect 204 "Bob shares with Anna"
LAT="$(req anna - GET "/families/$FAM/map" | json "[m for m in d['members'] if m['user_id']=='$BOB_ID'][0]['location']['lat']")"
[[ "$LAT" == "47.3769" ]] && pass "Anna sees Bob's real position on the map" || fail "unexpected lat $LAT"

req - "$BOB_TOKEN" DELETE "/shares/$ANNA_ID" >/dev/null
expect 204 "Bob revokes the share"
[[ "$(req anna - GET "/families/$FAM/map" | json "[m for m in d['members'] if m['user_id']=='$BOB_ID'][0]['location']")" == "None" ]] \
  && pass "revocation is effective immediately" || fail "still visible after revoke"

req anna - GET /me/export >/dev/null
expect 200 "data export"
echo "All smoke tests passed."
