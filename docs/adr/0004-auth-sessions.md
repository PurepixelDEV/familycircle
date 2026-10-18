# ADR-0004: Authentifizierung und Sitzungen

Status: akzeptiert · 2026-10-18

## Entscheidung
* **Passwörter**: Argon2id (m=64 MiB, t=3, p=2), PHC-Format, automatisches Re-Hashing bei Parameteränderung.
  Max. 4 gleichzeitige Hash-Berechnungen (Speicherschutz). Unbekannte Konten durchlaufen eine Dummy-Verifikation
  (kein Timing-Orakel). Passwortregel nach NIST SP 800-63B: nur Länge (10–256).
* **Opake, serverseitige Tokens** (256 Bit, Präfix `fcw_`/`fca_`/`fcr_` für Secret-Scanner), gespeichert nur als SHA-256.
  Keine JWTs: Widerruf muss sofort wirken, und jede Anfrage prüft die DB ohnehin auf Berechtigungen.
* **Web**: Cookie `fc_session` (HttpOnly, SameSite=Lax, Secure bei `https://`-`FC_PUBLIC_URL`), gleitend 30 Tage.
  **CSRF**: Jede schreibende Anfrage ohne Bearer-Token braucht `X-FC-CSRF: 1` (erzwingt CORS-Preflight, keine
  CORS-Freigaben) und – falls vorhanden – passenden `Origin` bzw. `Sec-Fetch-Site`.
* **Geräte**: Access-Token 1 h + Refresh-Token 90 Tage, Rotation bei jeder Nutzung. Wiederverwendung eines
  rotierten Refresh-Tokens widerruft die ganze Sitzung (Diebstahlerkennung). Ausnahme: Innerhalb von 2 Minuten
  nach einer Rotation darf das vorige Token einmal erneut verwendet werden (verlorene Antwort im Mobilfunk);
  das Fenster verlängert sich dabei nicht.
* **Ersteinrichtung**: Solange kein Konto existiert, verlangt `POST /setup` einen Setup-Code aus dem Container-Log
  (oder `FC_SETUP_TOKEN`). Verhindert die Übernahme einer frisch gestarteten Instanz durch Dritte im Netz.
* **Registrierung** nur mit Einladungscode (60 Bit, Crockford-Base32, Ablauf ≤ 7 Tage, Standard einmalig,
  zeilengesperrt eingelöst → nebenläufige Einlösung getestet).
* **Rate Limits** (In-Memory-Token-Bucket): global pro IP, Login pro IP und pro E-Mail, sensible Endpunkte pro IP,
  Uploads pro Benutzer. Hinter Proxys wird `X-Forwarded-For` nur von `FC_TRUSTED_PROXIES` akzeptiert.

## Konsequenzen
* Passwortänderung meldet alle anderen Sitzungen ab; Gerät entfernen widerruft dessen Tokens sofort.
* Rate-Limit-Zustand geht bei Neustart verloren (akzeptiert).
* Offen: Zwei-Faktor-Authentifizierung, Passwort-Zurücksetzen per E-Mail (kein SMTP im MVP; Admin-Reset geplant).
