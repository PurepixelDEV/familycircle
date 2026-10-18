#!/usr/bin/env bash
# HTTPS reverse-proxy test: compose.yaml + nginx (configured like Nginx Proxy Manager) with a
# self-signed certificate. Verifies TLS, Secure cookie, HSTS, WebSocket over wss, origin checks and
# that a client cannot spoof its address via X-Forwarded-For. Test only; port 9443; cleans up.
# Usage: scripts/https-proxy-test.sh <image>     e.g. familycircle:0.2.0-local
set -euo pipefail
IMAGE="${1:?image}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="https://localhost:9443"
# Inside the repository (git-ignored): Docker Desktop on Windows cannot mount Git-Bash /tmp paths.
CERTS="$ROOT/deploy/test/https-proxy/.certs"
mkdir -p "$CERTS"
CERTS_HOST="$CERTS"
command -v cygpath >/dev/null && CERTS_HOST="$(cygpath -m "$CERTS")"
ENVF="$CERTS/test.env"
cat >"$ENVF" <<ENV
FC_IMAGE=$IMAGE
FC_HTTP_PORT=9096
FC_DB_PASSWORD=https-test-only
FC_SETUP_TOKEN=HTTPS-TEST
FC_PUBLIC_URL=$URL
FC_TRUSTED_PROXIES=10.231.99.10
FC_TEST_CERT_DIR=$CERTS_HOST
ENV
dc() { docker compose -p fc-httpstest -f "$ROOT/compose.yaml" -f "$ROOT/deploy/test/https-proxy/compose.https-test.yaml" --env-file "$ENVF" "$@"; }
cleanup() { dc down -v >/dev/null 2>&1 || true; rm -rf "$CERTS"; }
trap cleanup EXIT

MSYS_NO_PATHCONV=1 docker run --rm -v "$CERTS_HOST:/c" alpine:3.20 sh -c 'apk add -q --no-cache openssl >/dev/null && openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost" -keyout /c/key.pem -out /c/cert.pem 2>/dev/null && chmod 644 /c/*.pem'
dc up -d --wait >/dev/null 2>&1 || { dc logs --tail 30; exit 1; }
echo "HTTPS proxy test against $URL"

FC_URL="$URL" FC_SETUP_CODE=HTTPS-TEST FC_CURL_INSECURE=1 "$ROOT/scripts/smoke-test.sh" | sed 's/^/ /'

hdr="$(curl -sk -D - -o /dev/null -H 'X-FC-CSRF: 1' -H 'Content-Type: application/json' -X POST "$URL/api/v1/auth/session" \
  -d '{"email":"anna@smoke.test","password":"smoke test password 1"}')"
echo "$hdr" | grep -qi '^set-cookie: fc_session=.*; Secure' && echo "  ✓ session cookie has the Secure flag" || { echo "  ✗ Secure cookie missing"; echo "$hdr"; exit 1; }
echo "$hdr" | grep -qi '^strict-transport-security: max-age=' && echo "  ✓ HSTS header present" || { echo "  ✗ HSTS missing"; exit 1; }

NODE_TLS_REJECT_UNAUTHORIZED=0 node "$ROOT/scripts/ws-check.mjs" "$URL" bob@smoke.test "smoke test password 1" 2>&1 | grep -v "NODE_TLS_REJECT_UNAUTHORIZED"

# Spoofed X-Forwarded-For must not create fresh rate-limit buckets (proxy appends the real address).
limited=0
for i in $(seq 1 30); do
  st="$(curl -sk -o /dev/null -w '%{http_code}' -H "X-Forwarded-For: 203.0.113.$i" -H 'X-FC-CSRF: 1' -H 'Content-Type: application/json' \
    -X POST "$URL/api/v1/auth/session" -d "{\"email\":\"nobody$i@smoke.test\",\"password\":\"wrong password\"}")"
  [[ "$st" == "429" ]] && { limited=1; break; }
done
[[ $limited == 1 ]] && echo "  ✓ spoofed X-Forwarded-For does not bypass per-IP rate limits" || { echo "  ✗ rate limit bypassed via X-Forwarded-For"; exit 1; }

if dc logs familycircle 2>&1 | grep -q "FC_TRUSTED_PROXIES is empty"; then echo "  ✗ unexpected proxy warning"; exit 1; fi
dc logs familycircle 2>&1 | grep -q '"level":"ERROR"' && { echo "  ✗ errors in server log"; exit 1; }
echo "HTTPS proxy test passed."
