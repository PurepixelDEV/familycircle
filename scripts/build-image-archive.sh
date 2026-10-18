#!/usr/bin/env bash
# Builds the FamilyCircle server image and saves it as a .tar archive that can be uploaded in
# Portainer (Images → Import) – a bridge until official images are published on GHCR.
# The end user does not build anything: they only import the archive.
#
# Usage: scripts/build-image-archive.sh <version> [platform]
#   scripts/build-image-archive.sh 0.2.0              # linux/amd64 (default)
#   scripts/build-image-archive.sh 0.2.0 linux/arm64  # e.g. Raspberry Pi 4/5 (cross-compiled, no emulation)
# Output: dist/familycircle-<version>-<arch>.tar (+ .sha256), image tag familycircle:<version>
set -euo pipefail
VERSION="${1:?version, e.g. 0.2.0}"
PLATFORM="${2:-linux/amd64}"
ARCH="${PLATFORM#linux/}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/dist"
mkdir -p "$OUT"
TAG="familycircle:$VERSION"
docker buildx build --platform "$PLATFORM" --load \
  -f "$ROOT/deploy/docker/Dockerfile" --build-arg "VERSION=$VERSION" -t "$TAG" "$ROOT"
FILE="$OUT/familycircle-$VERSION-$ARCH.tar"
docker save -o "$FILE" "$TAG"
( cd "$OUT" && sha256sum "$(basename "$FILE")" > "$(basename "$FILE").sha256" )
echo "Image archive: $FILE ($(du -h "$FILE" | cut -f1))"
echo "Portainer: Images → Import → upload the file; then set FC_IMAGE=$TAG in the stack."
