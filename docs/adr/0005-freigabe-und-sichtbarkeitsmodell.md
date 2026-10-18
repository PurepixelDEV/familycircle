# ADR-0005: Freigabe- und Sichtbarkeitsmodell

Status: akzeptiert · 2026-10-18

## Kontext
Jedes erwachsene Mitglied entscheidet selbst, ob und mit wem es seinen Standort teilt. Familienadministration
darf keine Ortung erzwingen. Standortdaten sind besonders schützenswert (Stalking-Risiko).

## Entscheidung
* **Freigabe pro Person** (`location_shares(owner, viewer)`), nicht pro Familie. „Mit allen teilen“ ist in der UI
  eine Abfolge einzelner Freigaben. Neue Mitglieder erhalten **nichts automatisch** (Privacy by Default).
* **Eine zentrale Regel** (`store/visibility.go`): V sieht O ⇔ Freigabe O→V ∧ O nicht pausiert ∧ gemeinsame Familie.
  Sie wird in der Datenbankabfrage selbst ausgewertet (JOIN-Bedingung); unberechtigte Koordinaten verlassen die DB nie.
* **Rollen** `admin`/`member` steuern nur Verwaltung (Name, Einladungen, Mitglieder). Sie kommen in der
  Sichtbarkeitsregel nicht vor. Admins können Freigaben anderer weder lesen, erzeugen noch Einstellungen ändern.
* **Pause** blendet den Standort für alle aus, ohne Freigaben zu löschen.
* Endet die letzte gemeinsame Familie (Austritt/Entfernung), werden die Freigaben **gelöscht** – ein späterer
  Wiedereintritt aktiviert alte Freigaben nicht still wieder.
* **Verlauf** nur nach Opt-in; Abschalten löscht den gespeicherten Verlauf sofort. Aufbewahrung 1–365 Tage.
* **Akkustand** nur nach Opt-in sichtbar.
* Fremde oder ungültige IDs liefern einheitlich `404` (keine Unterscheidung „existiert nicht“ / „nicht deins“).
* Profilbilder werden serverseitig neu kodiert (entfernt EXIF inkl. GPS) und sind nur für Mitglieder gemeinsamer
  Familien abrufbar.

## Konsequenzen
* Negativtests: Admin-Umgehung, fremde Familien (IDOR), Widerruf live und per REST, Wiedereintritt, Pause.
* Die Mitgliederliste zeigt, ob jemand mit mir teilt – Pause und „nicht freigegeben“ sind nicht unterscheidbar (gewollt).
