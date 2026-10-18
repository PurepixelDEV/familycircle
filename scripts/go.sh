#!/usr/bin/env sh
# Runs the Go toolchain in the official golang container (no local Go installation needed).
# Usage: scripts/go.sh test ./...      (from anywhere; runs in services/api)
# Environment: FC_TEST_DATABASE_URL is passed through; the container joins the dev compose network
# "familycircle-dev_default" if it exists so tests can reach the dev database at host "db".
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GO_IMAGE="${GO_IMAGE:-golang:1.27}"
NETWORK_ARGS=""
if docker network inspect familycircle-dev_default >/dev/null 2>&1; then
  NETWORK_ARGS="--network familycircle-dev_default"
fi
# MSYS (Git Bash on Windows) would otherwise rewrite /src paths.
export MSYS_NO_PATHCONV=1
# shellcheck disable=SC2086
exec docker run --rm $NETWORK_ARGS \
  -v "$ROOT:/src" \
  -v familycircle-gomod:/go/pkg/mod \
  -v familycircle-gocache:/root/.cache/go-build \
  -e FC_TEST_DATABASE_URL -e FC_REQUIRE_DB -e FC_TEST_LOG -e GOFLAGS \
  -w /src/services/api \
  "$GO_IMAGE" go "$@"
