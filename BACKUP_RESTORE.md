# Backup und Wiederherstellung

Alle Daten von FamilyCircle (Konten, Familien, Freigaben, Standorte) liegen in PostgreSQL im Volume `db-data`.
Der App-Container ist zustandslos.

> Backups enthalten Standortdaten. Legen Sie sie **verschlüsselt** ab (z. B. `age`, `gpg`, verschlüsseltes NAS-Share)
> und begrenzen Sie die Aufbewahrung, entsprechend den Aufbewahrungsfristen Ihrer Familie.

Die folgenden Befehle wurden am 2026-10-18 gegen den Stack aus `compose.yaml` getestet: Dump, Volume gelöscht,
Wiederherstellung, Login erfolgreich, keine erneute Migration.

## Backup (Docker-CLI auf dem Host)
Projektname = Name des Portainer-Stacks (hier `familycircle`):
```sh
docker compose -p familycircle exec -T db \
  pg_dump -U familycircle -d familycircle -Fc > familycircle-$(date +%F).dump
```
Ohne Compose-Datei auf dem Host (typisch bei Portainer) funktioniert dasselbe direkt über den Container:
```sh
docker exec familycircle-db-1 pg_dump -U familycircle -d familycircle -Fc > familycircle-$(date +%F).dump
```
Das Format `-Fc` ist komprimiert und für `pg_restore` geeignet. Für einen Cronjob den Befehl regelmässig ausführen
und alte Dateien rotieren.

## Wiederherstellung
1. App stoppen (Datenbank läuft weiter):
   `docker stop familycircle-familycircle-1`
2. Dump einspielen (ersetzt vorhandene Objekte):
   ```sh
   docker exec -i familycircle-db-1 pg_restore -U familycircle -d familycircle --clean --if-exists --no-owner < familycircle-2026-10-18.dump
   ```
3. App starten: `docker start familycircle-familycircle-1`

Auf einem **neuen** Server: Stack mit identischem `FC_DB_PASSWORD` deployen, warten bis `db` healthy ist,
dann Schritte 1–3. Die App erkennt das wiederhergestellte Schema und führt nur fehlende Migrationen aus.

## Downgrade nach fehlgeschlagenem Update
Der Server verweigert den Start, wenn die Datenbank neuer ist als das Binary (Schutz vor Datenverlust).
Zurück zur alten Version: alte `FC_IMAGE`-Version setzen und das **vor dem Update** erstellte Backup einspielen.

## Datenbank-Passwort ändern
`POSTGRES_PASSWORD` wirkt nur bei der ersten Initialisierung des Volumes. Zum Ändern:
```sh
docker exec -it familycircle-db-1 psql -U familycircle -d familycircle -c "ALTER USER familycircle PASSWORD 'neues-passwort';"
```
Danach `FC_DB_PASSWORD` im Stack anpassen und neu deployen.

## PostgreSQL-Hauptversion wechseln
Nie das Image eines bestehenden Volumes auf eine andere Hauptversion umstellen (z. B. 17 → 18). Stattdessen:
Backup → neuer Stack mit neuem Volume und neuer Version → Wiederherstellung.
