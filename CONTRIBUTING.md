# Contributing to FamilyCircle

Thanks for helping! FamilyCircle is a privacy-first, self-hosted family location app licensed under
AGPL-3.0-or-later. By contributing you agree that your contribution is licensed under the same terms.

## Ground rules
* **Privacy first.** Changes must not weaken the sharing model (docs/adr/0005). Every new endpoint that returns
  location data must use the central visibility rule in `services/api/internal/store/visibility.go` and come with
  negative tests (other user, other family, admin without share, revoked share).
* No telemetry, analytics, ads, trackers or mandatory third-party services. No Google Play Services / Firebase in the
  Android app (F-Droid compatibility).
* Never log coordinates, tokens or request bodies.
* API-first: change `packages/api-contract/openapi.yaml` together with the implementation
  (`TestRoutesMatchOpenAPI` enforces it) and regenerate web types with `npm run api:types`.
* Architectural decisions go into `docs/adr/`.

## Development setup
See the README "Entwicklung" section. Short version:
```sh
docker compose -f compose.dev.yaml up -d db                      # PostGIS on localhost:5433
FC_TEST_DATABASE_URL=postgres://familycircle:devpassword@db:5432/postgres?sslmode=disable \
  scripts/go.sh test ./...                                       # Go via Docker, no local Go needed
cd apps/web && npm ci && npm test && npm run dev                 # web app with API proxy
cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## Pull requests
* One topic per PR, small and reviewable. Describe *why*, not only *what*.
* All CI checks must pass (formatting, lint, tests incl. database integration tests, CodeQL, Trivy).
* Add or update tests. Bug fixes start with a failing test.
* Update `CHANGELOG.md` (section "Unreleased") for user-visible changes.
* Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:` …).

## Reporting bugs and security issues
Bugs: GitHub issues using the templates. **Security issues: see [SECURITY.md](SECURITY.md) – never public.**

## Code of Conduct
This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
