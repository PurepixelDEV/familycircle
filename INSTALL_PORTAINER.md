# Installation mit Portainer

Diese Anleitung installiert FamilyCircle als Portainer-Stack aus fertigen Container-Images.
Sie brauchen weder Git noch einen eigenen Build noch zusätzliche Konfigurationsdateien.

> **Hinweis zum Projektstand (0.2.0):** Offizielle Images erscheinen mit dem ersten Release unter
> `ghcr.io/purepixeldev/familycircle` (GitHub Actions). Bis dahin: Image-Archiv importieren, siehe
> [„Ohne GHCR: Image-Archiv importieren“](#ohne-ghcr-image-archiv-importieren). Ein Build ist in keinem Fall nötig.

## Voraussetzungen
* Docker-Host (amd64; ARM siehe Schritt 3) mit Portainer Community Edition 2.x
* Freier Port, standardmässig **9092**
* Für den Betrieb über das Internet: eine Domain und ein Reverse-Proxy mit HTTPS, z. B. Nginx Proxy Manager (Schritt 6)

## 1. Stack anlegen
Portainer → **Stacks** → **Add stack** → Name `familycircle` → **Web editor**.
Fügen Sie den Inhalt von [`compose.yaml`](compose.yaml) unverändert ein.

## 2. Variablen setzen
Unter **Environment variables** → **Advanced mode** (oder „Load variables from .env file“ mit
[`.env.example`](.env.example) als Vorlage):

```env
FC_IMAGE=ghcr.io/purepixeldev/familycircle:0.2.0
FC_HTTP_PORT=9092
FC_DB_PASSWORD=<langer Zufallswert>
FC_PUBLIC_URL=http://192.168.1.10:9092
```

* Immer eine **feste Version** verwenden (`0.1.0`), nie `latest`. So entscheiden Sie selbst, wann aktualisiert wird.
* `FC_DB_PASSWORD`: z. B. mit `openssl rand -hex 32` erzeugen. Sonderzeichen sind erlaubt. Das Passwort wird beim
  **ersten** Start in der Datenbank gesetzt; spätere Änderungen siehe [BACKUP_RESTORE.md](BACKUP_RESTORE.md#datenbank-passwort-ändern).
* `FC_PUBLIC_URL`: Adresse, unter der Browser und Android-App den Server erreichen.

## 3. ARM-Hosts (Raspberry Pi 4/5, ARM-NAS)
Das FamilyCircle-Image gibt es für amd64 und arm64. Das Standard-Datenbankimage `postgis/postgis` gibt es nur für
amd64. Setzen Sie auf ARM zusätzlich:
```env
FC_DB_IMAGE=imresamu/postgis:17-3.5
```

## 4. Deploy
**Deploy the stack** klicken. Nach 20–60 Sekunden zeigen beide Container den Status *healthy*.

## 5. Einrichtungsassistent
1. Portainer → **Containers** → `familycircle-familycircle-1` → **Logs**. Dort steht eine Zeile mit
   `SETUP REQUIRED` und `"setup_code":"XXXX-XXXX"`.
2. `http://<server-ip>:9092` öffnen, den Einrichtungscode eingeben, Ihr Konto und den Familiennamen anlegen.
3. Unter **Familie → Jemanden einladen** Einladungscodes für Familienmitglieder erstellen.

Der Code verhindert, dass eine andere Person in Ihrem Netz die frische Installation vor Ihnen übernimmt.
Er wird bei jedem Neustart neu erzeugt, solange noch kein Konto existiert.

## 6. HTTPS mit Nginx Proxy Manager (empfohlen, für Internetzugriff Pflicht)
1. In NPM einen **Proxy Host** anlegen: Domain `family.example.org`, Scheme `http`, Forward Hostname/IP = IP des
   Docker-Hosts, Port `9092`.
2. **Websockets Support** aktivieren (nötig für Live-Updates).
3. Reiter **SSL**: Let's-Encrypt-Zertifikat anfordern, **Force SSL** und **HTTP/2** aktivieren.
4. Im FamilyCircle-Stack setzen und neu deployen:
   ```env
   FC_PUBLIC_URL=https://family.example.org
   FC_TRUSTED_PROXIES=<Adresse des Proxys aus Sicht des Containers>
   ```
   Die richtige Adresse steht nach dem ersten Aufruf über den Proxy im Log von `familycircle`:
   `received X-Forwarded-For from an untrusted peer … "peer":"172.18.0.1"` → diesen Wert eintragen.
   `FC_TRUSTED_PROXIES` sorgt dafür, dass die Rate-Limits die echte Client-IP verwenden. Ohne diesen Eintrag teilen
   sich alle Clients die IP des Proxys. **Nur die eine Adresse eintragen, kein ganzes Docker-Netz** – sonst könnten
   Clients aus diesem Netz eine falsche Absenderadresse vortäuschen (im HTTPS-Test nachgewiesen).
5. Optional: Port 9092 in der Firewall nur noch für den Proxy freigeben.

Mit `https://` in `FC_PUBLIC_URL` setzt der Server Secure-Cookies und HSTS.
FamilyCircle verändert keine bestehenden Docker-Netzwerke. Wenn NPM im selben Docker-Netz laufen soll,
verbinden Sie die Container bewusst selbst.

## Ohne GHCR: Image-Archiv importieren
Solange es noch kein veröffentlichtes Image gibt (oder der Server keinen Internetzugang hat):
1. Archiv `familycircle-<version>-amd64.tar` (bzw. `-arm64.tar`) beschaffen – erzeugt mit
   `scripts/build-image-archive.sh <version> [linux/arm64]`; Prüfsumme steht in der `.sha256`-Datei.
2. Portainer → **Images** → **Import** → Datei hochladen. Danach erscheint `familycircle:<version>`.
3. Im Stack `FC_IMAGE=familycircle:<version>` setzen und deployen. Alles andere wie oben.

## Datenbank-Passwort als Datei (optional)
Statt `FC_DB_PASSWORD` als Variable (in Portainer und `docker inspect` sichtbar) kann der Server das Passwort aus einer
Datei lesen: Datei auf dem Host anlegen (`chmod 600`), in beide Container mounten und setzen:
`FC_DB_PASSWORD_FILE=/run/secrets/db_password` (FamilyCircle) bzw. `POSTGRES_PASSWORD_FILE=/run/secrets/db_password`
(Datenbank). Das erfordert eine angepasste Compose-Datei; der Standard bleibt bewusst bei einer einzigen Variablen.

## 7. Android-App verbinden
APK aus den GitHub Releases installieren (https://github.com/PurepixelDEV/familycircle/releases). Am einfachsten:
in der Web-App unter **Privatsphäre → Handy verbinden** einen QR-Code anzeigen und in der App „QR-Code scannen“ –
oder Serveradresse eingeben (`https://family.example.org` bzw. im LAN-Test `http://192.168.1.10:9092`) und anmelden.
Neue Familienmitglieder scannen den QR-Code ihrer Einladung.

## Update
1. Release-Notizen lesen (CHANGELOG).
2. **Backup erstellen** ([BACKUP_RESTORE.md](BACKUP_RESTORE.md)).
3. `FC_IMAGE` auf die neue Version setzen → **Update the stack**.
4. Migrationen laufen automatisch. Schlägt eine fehl, startet der Container nicht (Logs prüfen); die Datenbank bleibt
   auf dem letzten konsistenten Stand. Zurück zur alten Version geht nur mit dem Backup, weil der Server einen
   neueren Datenbankstand bewusst nicht mit einer älteren Version öffnet.

Die Daten liegen im Volume `familycircle_db-data` und bleiben bei Updates und Neuerstellung der Container erhalten.

## Fehlersuche
| Symptom | Ursache / Lösung |
|---|---|
| `familycircle` startet neu, Log: `configuration error` | Variable fehlt oder ist ungültig – die Meldung nennt sie. |
| Log: `database not reachable yet` | Datenbank startet noch; nach 2 min Abbruch → `db`-Logs prüfen. |
| Log: `database migration failed` | Siehe Meldung; Backup einspielen oder Issue erstellen. |
| Karte bleibt grau | Browser erreicht den Kartenanbieter nicht (Firewall/Adblocker) oder eigener Style braucht `FC_MAP_EXTRA_ORIGINS`. |
| Live-Updates fehlen hinter Proxy | „Websockets Support“ im Proxy aktivieren. |
| Login klappt, Seite meldet sofort wieder ab | `FC_PUBLIC_URL` ist `https://`, aufgerufen wird aber per `http://` (Secure-Cookie). |
