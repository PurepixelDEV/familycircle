# syntax=docker/dockerfile:1
# FamilyCircle server image: Go binary with the embedded web app on a distroless base.
# Build context: repository root.
#   docker build -f deploy/docker/Dockerfile --build-arg VERSION=0.1.0 -t familycircle:0.1.0 .
# Multi-arch (amd64 + arm64): stages run on the build platform and cross-compile, so no emulation is needed.

ARG NODE_IMAGE=node:24-alpine
ARG GO_IMAGE=golang:1.27-alpine
ARG RUNTIME_IMAGE=gcr.io/distroless/static-debian13:nonroot

# ---- Web app ----
FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS web
WORKDIR /src/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY packages/api-contract/ /src/packages/api-contract/
COPY apps/web/ ./
RUN npm run build

# ---- Server ----
FROM --platform=$BUILDPLATFORM ${GO_IMAGE} AS server
WORKDIR /src/services/api
COPY services/api/go.mod services/api/go.sum ./
RUN go mod download
COPY services/api/ ./
COPY --from=web /src/apps/web/dist/ ./internal/webui/dist/
ARG VERSION=dev
ARG TARGETOS
ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/familycircle ./cmd/familycircle

# ---- Runtime ----
FROM ${RUNTIME_IMAGE}
ARG VERSION=dev
LABEL org.opencontainers.image.title="FamilyCircle" \
      org.opencontainers.image.description="Self-hosted, privacy-first family location sharing" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.version="${VERSION}"
COPY --from=server /out/familycircle /familycircle
USER nonroot:nonroot
ENV FC_HTTP_ADDR=:8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["/familycircle", "healthcheck"]
ENTRYPOINT ["/familycircle"]
CMD ["serve"]
