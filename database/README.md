# Datenbank

* **Schema/Migrationen:** [`services/api/migrations/`](../services/api/migrations/) – nummerierte SQL-Dateien
  (`NNNN_beschreibung.sql`), die ins Server-Binary eingebettet werden (`go:embed` kann nur innerhalb des Go-Moduls
  einbetten, daher nicht hier; siehe ADR-0001).
* **Migrator:** [`services/api/internal/db/db.go`](../services/api/internal/db/db.go) – läuft bei jedem Start:
  Advisory-Lock, eine Transaktion pro Datei, Checksummenprüfung bereits angewandter Dateien, Start-Verweigerung bei
  neuerem DB-Schema (Downgrade-Schutz).
* **Regeln für neue Migrationen:** angewandte Dateien nie ändern (Checksumme!), nur neue Dateien hinzufügen;
  Migrationen müssen auf einer gefüllten Produktionsdatenbank laufen (Defaults/Backfill bedenken).
* **Datenmodell:** siehe Kommentare in `0001_init.sql` und [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Tabellen (0001)
| Tabelle | Zweck |
|---|---|
| `instance_settings` | Instanzweite Einstellungen (Registrierungsmodus) |
| `users`, `user_settings` | Konten (Argon2id-Hash, Profilbild als JPEG) und Privatsphäre-Einstellungen |
| `devices`, `sessions` | Geräte und serverseitige Sitzungen (nur Token-Hashes) |
| `families`, `family_members` | Familien, Mitgliedschaft, Rolle (`admin`/`member`), Farbe |
| `invites` | Einladungscodes (Hash, Ablauf, Nutzungszähler) |
| `location_shares` | Explizite Freigaben Besitzer → Betrachter |
| `locations_latest` | Letzte Position pro Benutzer (+ Kandidat für Sprungbestätigung) |
| `location_history` | Verlauf, nur bei Opt-in; eindeutig pro (Benutzer, Client-Event-ID) |
| `schema_migrations` | Angewandte Migrationen mit Checksumme |
