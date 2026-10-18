# FamilyCircle auf echten Android-Handys testen

Diese Anleitung ist für Familienmitglieder, die die App vor dem ersten Release ausprobieren.
Dauer der Einrichtung: etwa 5 Minuten pro Handy.

## Was du brauchst
* Ein Android-Handy mit Android 8 oder neuer.
* Die Datei `familycircle-0.2.0-debug.apk` (von der Person, die FamilyCircle betreibt).
* Die Web-App von FamilyCircle auf einem Computer (zum Anzeigen des QR-Codes).

## 1. App installieren
1. Die APK auf das Handy kopieren (z. B. per Messenger an dich selbst oder USB-Kabel) und antippen.
2. Android fragt, ob Installationen aus dieser Quelle erlaubt sind → für diese eine Installation erlauben.
3. Die App heisst **„FamilyCircle Debug“**. Sie kann später neben der offiziellen Version installiert bleiben.

## 2. Mit dem Server verbinden
**Du hast schon ein Konto:** Am Computer in der Web-App **Privatsphäre → Handy verbinden → QR-Code anzeigen**.
In der App **„QR-Code scannen“** antippen und den Code scannen. „Dieses Handy verbinden“ bestätigen.

**Du bist neu eingeladen:** Die einladende Person zeigt dir in der Web-App unter **Familie → Jemanden einladen**
einen QR-Code. Scanne ihn in der App und erstelle dein Konto.

Ohne Kamera: Serveradresse von Hand eingeben und mit E-Mail und Passwort anmelden.

## 3. Standort freigeben
1. Die App erklärt, warum sie den Standort braucht. „Genauer Standort“ und **„Bei Nutzung der App“** wählen.
2. Benachrichtigungen erlauben – daran siehst du immer, dass gerade geteilt wird.
3. Unter **Teilen** einen Modus wählen (für den Test: **Ausgewogen**).
4. Unter **Familie** einschalten, mit wem du deinen Standort teilst.

Du kannst jederzeit pausieren: in der Benachrichtigung oder unter **Teilen**.

## 4. Was wir testen möchten
Die Liste mit allen Fällen steht in [HARDWARE_TESTS.md](HARDWARE_TESTS.md). Am wichtigsten:
* Seht ihr euch gegenseitig auf der Karte in der App – auch nach 30 Minuten mit ausgeschaltetem Bildschirm?
* Kommen Positionen nach einer Zeit ohne Internet (Flugmodus) wieder an?
* Wie viel Akku braucht die App an einem normalen Tag (Einstellungen → Akku)?
* Verschwindet jemand sofort von deiner Karte, wenn er die Freigabe für dich ausschaltet?

## 5. Ergebnis melden
Bitte für jeden Test: Datum, Handy-Modell, Android-Version, was du gemacht hast, was passiert ist.
Dazu in der App **Einstellungen → Diagnose → „Diagnose kopieren“** und den Text mitschicken.
Der Text enthält **keine** Standorte, Passwörter oder E-Mail-Adressen.

Bitte **keine Screenshots der Karte** mit echten Wohnorten öffentlich teilen.

## Bekannte Einschränkungen dieser Testversion
* Grosse Datei (~70 MB), weil die Kartenkomponente für alle Prozessortypen enthalten ist.
* Nach einem Update der App muss das Teilen einmal fortgesetzt werden.
* Steht das Handy lange still, prüft „Ausgewogen“ nur etwa alle 5 Minuten, ob es sich wieder bewegt.
* Verbindungen über `http://` sind unverschlüsselt – nur im eigenen WLAN verwenden.
