# ADR-0006: Verwaltete Kinderkonten (Entwurf)

Status: **vorgeschlagen – nicht implementiert** · 2026-10-18

## Kontext
Eltern möchten den Standort minderjähriger Kinder sehen. Gleichzeitig darf es keine heimliche Ortung geben,
Android-Schutzmechanismen werden nicht umgangen, und das Modell darf nicht als Überwachungswerkzeug gegen
Erwachsene missbraucht werden können.

## Vorschlag
* `users.account_type = 'managed_child'` (die Spalte existiert bereits mit CHECK nur auf `adult`).
* Ein Kinderkonto wird von einem Familien-Admin angelegt und hat **benannte Erziehungsberechtigte**
  (`guardianships(child_id, guardian_id, created_at)`), nicht automatisch alle Admins.
* Für Erziehungsberechtigte gilt eine **zusätzliche, explizit sichtbare** Sichtbarkeitsregel:
  Guardian sieht Kind, solange die Guardianship besteht. Das Kind sieht in seiner App jederzeit
  „Diese Personen sehen deinen Standort“ – ohne Ausnahme.
* **Transparenz statt Heimlichkeit**: Ortung läuft nur über den normalen, sichtbaren Foreground Service mit
  Benachrichtigung. Keine Device-Admin-/Device-Owner-Funktionen, kein Verstecken des App-Icons, keine
  Deinstallationssperre.
* Pausieren durch das Kind: konfigurierbar (z. B. nur zeitlich begrenzt, Erziehungsberechtigte werden benachrichtigt),
  aber nie unsichtbar unterdrückbar.
* Umwandlung in ein Erwachsenenkonto durch das Kind selbst ab einem konfigurierten Alter bzw. durch Guardian;
  danach gelten ausschliesslich die Regeln aus ADR-0005, alle Guardianships enden.
* Audit-Log, das Kind und Guardians einsehen können (wer hat wann eingesehen/geändert).

## Offene Fragen
Altersgrenzen und Rechtslage (DSGVO Art. 8, nationales Recht) müssen vor der Umsetzung geprüft werden.
