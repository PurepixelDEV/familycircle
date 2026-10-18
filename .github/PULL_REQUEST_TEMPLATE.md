## What and why

<!-- What changes, and which problem does it solve? Link issues. -->

## How it was tested

<!-- Automated tests added/changed; manual checks (browser, device model + Android version). -->

## Checklist
- [ ] Tests added/updated; CI is green
- [ ] Location-related reads use the central visibility rule and have negative tests (other user, other family, admin without share, revoked share)
- [ ] No coordinates, tokens or request bodies are logged
- [ ] `openapi.yaml` updated together with API changes (`npm run api:types` in `apps/web`)
- [ ] Database changes are a **new** migration file (applied migrations are never edited)
- [ ] `CHANGELOG.md` updated for user-visible changes
- [ ] Docs/ADR updated where behaviour or architecture changed
