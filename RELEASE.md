# Release-Prozess

Versionierung nach [Semantic Versioning](https://semver.org/lang/de/). Vor 1.0 können Minor-Versionen Breaking
Changes enthalten; sie werden im CHANGELOG markiert.

## Einmalige Einrichtung (Repository)
1. Repository: https://github.com/PurepixelDEV/familycircle (privat).
2. **Actions → General → Workflow permissions**: „Read repository contents“ (der Release-Workflow fordert
   `packages: write` und `contents: write` gezielt pro Job an).
3. Android-Signaturschlüssel erzeugen (einmalig, **sicher aufbewahren** – ohne ihn sind keine Updates der App möglich):
   ```sh
   keytool -genkeypair -v -keystore familycircle-release.jks -alias familycircle -keyalg RSA -keysize 4096 -validity 10000
   ```
4. Repository-Secrets anlegen:
   | Secret | Inhalt |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | `base64 -w0 familycircle-release.jks` |
   | `ANDROID_KEYSTORE_PASSWORD` | Keystore-Passwort |
   | `ANDROID_KEY_ALIAS` | `familycircle` |
   | `ANDROID_KEY_PASSWORD` | Schlüssel-Passwort |
5. Nach dem ersten Release das GHCR-Paket `familycircle` auf **Public** stellen
   (Profil → Packages → familycircle → Package settings), damit Portainer ohne Login pullen kann.

## Release erstellen
1. Version anheben – alle drei Stellen müssen übereinstimmen (der Workflow prüft das):
   * `apps/web/package.json` → `"version"`
   * `apps/android/app/build.gradle.kts` → `versionName` (und `versionCode` +1)
   * `CHANGELOG.md` → neuer Abschnitt `## [X.Y.Z] - JJJJ-MM-TT`
2. Lokal prüfen: CI-Checks grün (`ci.yml`), `scripts/smoke-test.sh` gegen einen frischen Stack.
3. Commit, dann Tag setzen und pushen:
   ```sh
   git tag -s vX.Y.Z -m "FamilyCircle X.Y.Z"
   git push origin main vX.Y.Z
   ```
4. Der Workflow [`release.yml`](.github/workflows/release.yml):
   * baut das Multi-Arch-Image (amd64, arm64) mit SBOM und Provenance und pusht
     `ghcr.io/purepixeldev/familycircle:X.Y.Z` sowie `:X.Y` (bewusst kein `latest`),
   * baut, testet und signiert die APK, erzeugt `SHA256SUMS.txt`,
   * erstellt das GitHub-Release mit dem CHANGELOG-Abschnitt als Text (0.x als Pre-Release).
5. `compose.yaml`/`.env.example` in der Dokumentation auf die neue Version verweisen lassen.

## Verifikation durch Nutzer
* APK: `sha256sum -c SHA256SUMS.txt`; Signatur-Fingerprint mit `apksigner verify --print-certs` prüfen
  (der Fingerprint wird im README veröffentlicht, sobald der Release-Schlüssel existiert).
* Image: `docker buildx imagetools inspect ghcr.io/purepixeldev/familycircle:X.Y.Z` zeigt SBOM/Provenance.

## F-Droid
Vorbereitet, aber noch nicht eingereicht: keine proprietären Abhängigkeiten, Build aus Quellcode möglich.
Offene Punkte (reproduzierbare Builds, Metadaten) siehe `apps/android/README.md` und `docs/RISKS.md`.
