# Installation mit Portainer

Diese Anleitung installiert FamilyCircle als Portainer-Stack aus fertigen Container-Images.
Sie brauchen weder Git noch einen eigenen Build noch zusätzliche Konfigurationsdateien.

> **Hinweis zum Projektstand (0.1.0):** Die offiziellen Images werden erst mit dem ersten Release über
> GitHub Actions in die GitHub Container Registry (GHCR) veröffentlicht. Bis dahin muss das Image lokal gebaut
> werden (siehe [README](README.md#entwicklung)). Ab dem ersten Release funktioniert die Anleitung unverändert.

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
FC_IMAGE=ghcr.io/OWNER/familycircle:0.1.0
FC_HTTP_PORT=9092
FC_DB_PASSWORD=<langer Zufallswert>
FC_PUBLIC_URL=http://192.168.1.10:9092
```

* `OWNER` ist der GitHub-Account bzw. die Organisation, unter der die Images veröffentlicht werden.
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
   FC_TRUSTED_PROXIES=<IP von NPM aus Sicht des Containers, z. B. 172.16.0.0/12>
   ```
   `FC_TRUSTED_PROXIES` sorgt dafür, dass die Rate-Limits die echte Client-IP verwenden. Ohne diesen Eintrag teilen
   sich alle Clients die IP des Proxys.
5. Optional: Port 9092 in der Firewall nur noch für den Proxy freigeben.

Mit `https://` in `FC_PUBLIC_URL` setzt der Server Secure-Cookies und HSTS.
FamilyCircle verändert keine bestehenden Docker-Netzwerke. Wenn NPM im selben Docker-Netz laufen soll,
verbinden Sie die Container bewusst selbst.

## 7. Android-App verbinden
APK aus den GitHub Releases installieren, Serveradresse eingeben (`https://family.example.org` bzw. im LAN-Test
`http://192.168.1.10:9092`), anmelden oder Einladungscode verwenden, Standortfreigabe aktivieren.

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
