#!/usr/bin/env bash
# Database upgrade test: start OLD image → seed data → switch to NEW image (same volume) → verify that
# migrations ran, all data survived and the new version works. Uses compose.yaml exactly like Portainer.
#
# Usage: scripts/upgrade-test.sh <old-image> <new-image>
#   e.g. scripts/upgrade-test.sh familycircle:0.1.0-previous familycircle:0.2.0-local
# Requires docker, curl and python3. Uses project name fc-upgradetest and port 9095; removes it afterwards.
set -euo pipefail
OLD="${1:?old image}"; NEW="${2:?new image}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT=9095
URL="http://localhost:$PORT"
ENVF="$(mktemp)"
PY=""
for c in python3 python; do command -v "$c" >/dev/null && "$c" -c 'import sys' 2>/dev/null && { PY="$c"; break; }; done
cat >"$ENVF" <<EOF
FC_IMAGE=$OLD
FC_HTTP_PORT=$PORT
FC_DB_PASSWORD=upgrade-test-only
FC_SETUP_TOKEN=UPGRADE-TEST
EOF
dc() { docker compose -p fc-upgradetest -f "$ROOT/compose.yaml" --env-file "$ENVF" "$@"; }
sql() { dc exec -T db psql -U familycircle -d familycircle -tAc "$1"; }
cleanup() { dc down -v >/dev/null 2>&1 || true; rm -f "$ENVF"; }
trap cleanup EXIT

echo "1) old version: $OLD"
dc up -d --wait >/dev/null
"$PY" "$ROOT/scripts/seed-dev-data.py" --url "$URL" --setup-code UPGRADE-TEST >/dev/null
before="$(sql "select (select count(*) from users)||'/'||(select count(*) from location_shares)||'/'||(select count(*) from locations_latest)||'/'||(select count(*) from sessions)")"
mig_before="$(sql "select max(version) from schema_migrations")"
echo "   data users/shares/latest/sessions = $before, schema version $mig_before"

echo "2) upgrade to: $NEW"
sed -i "s#^FC_IMAGE=.*#FC_IMAGE=$NEW#" "$ENVF"
dc up -d --wait familycircle >/dev/null
after="$(sql "select (select count(*) from users)||'/'||(select count(*) from location_shares)||'/'||(select count(*) from locations_latest)||'/'||(select count(*) from sessions)")"
mig_after="$(sql "select max(version) from schema_migrations")"
echo "   data users/shares/latest/sessions = $after, schema version $mig_after"
[[ "$before" == "$after" ]] || { echo "FAIL: data changed during upgrade"; exit 1; }
[[ "$mig_after" -ge "$mig_before" ]] || { echo "FAIL: schema version went backwards"; exit 1; }

echo "3) existing account still works after upgrade"
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'X-FC-CSRF: 1' -H 'Content-Type: application/json' \
  -X POST "$URL/api/v1/auth/session" -d '{"email":"anna@example.test","password":"demo password 1"}')"
[[ "$code" == "200" ]] || { echo "FAIL: login after upgrade returned $code"; exit 1; }
if dc logs familycircle 2>&1 | grep -q '"level":"ERROR"'; then echo "FAIL: errors in log"; dc logs familycircle | tail -20; exit 1; fi

echo "4) downgrade protection: old image must refuse the newer schema"
if [[ "$mig_after" -gt "$mig_before" ]]; then
  sed -i "s#^FC_IMAGE=.*#FC_IMAGE=$OLD#" "$ENVF"
  dc up -d familycircle >/dev/null 2>&1 || true
  ok=0
  for _ in $(seq 1 30); do
    if dc logs familycircle 2>&1 | grep -q "newer than this binary"; then ok=1; break; fi
    sleep 2
  done
  [[ $ok == 1 ]] || { echo "FAIL: old image did not refuse newer schema"; exit 1; }
  echo "   old image refused to start on the newer schema (expected)"
else
  echo "   skipped (no new migration between the versions)"
fi
echo "Upgrade test passed."
