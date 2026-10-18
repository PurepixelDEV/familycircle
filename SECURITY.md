# Security Policy

FamilyCircle processes highly sensitive data (real-time locations of family members). We take reports seriously.

## Reporting a vulnerability
**Please do not open public issues for security problems.** Use GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
("Security" tab → "Report a vulnerability") of
[PurepixelDEV/familycircle](https://github.com/PurepixelDEV/familycircle/security).

Please include affected version, configuration, reproduction steps and impact. We aim to acknowledge reports within
7 days and to publish a fix and advisory as soon as possible, crediting you unless you prefer otherwise.

## Supported versions
Until 1.0, only the latest released minor version receives security fixes.

## Security model (summary)
Details: [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) and ADRs [0004](docs/adr/0004-auth-sessions.md) and
[0005](docs/adr/0005-freigabe-und-sichtbarkeitsmodell.md).

* **Privacy by default**: locations are visible only through an explicit per-person share; pause and revoke
  take effect immediately, including open live connections. Family admins have **no** access to locations.
* History is stored only after opt-in, with a retention period. Battery level only after opt-in.
* Server-side authorization on every location read, evaluated in a single SQL rule; foreign IDs return 404.
* Argon2id passwords, opaque server-side sessions (only SHA-256 hashes stored), rotating device refresh tokens with
  reuse detection, CSRF header + origin checks, strict CSP, rate limiting, setup code for fresh instances.
* No telemetry, analytics or trackers. Coordinates, tokens and request bodies are never logged.
* Uploaded avatars are re-encoded (EXIF/GPS metadata removed).

## Operator responsibilities
* Run behind HTTPS (`FC_PUBLIC_URL=https://…`) for anything beyond a trusted LAN test.
* Keep the database private (the provided `compose.yaml` publishes no database port).
* Encrypt backups – they contain location data.
* Update regularly; images are versioned and never published as `latest`.

## Known limitations (0.1.0)
No two-factor authentication yet, no audit log, no e-mail based password reset. See [docs/RISKS.md](docs/RISKS.md).
