# MG4 Browser

MG4 Browser is a **service browser** for the MG4 head unit (pre-facelift, AAOS 9 /
API 28, 1920×720, landscape). It exists to do four things and nothing else: open a
page, read it, get through a captive portal, and download a file — usually an APK —
and hand it to the system installer.

That last one is the point. Without Play Services, getting an app into this car is
otherwise awkward, and there is no decent browser on board to fetch one with.

It is not a general-purpose browser and does not try to be. There are no tabs, no
sync, no profiles, no extensions, no search-engine picker and no settings screen.
The interface and palette follow
[MG4 Simple Launcher](https://github.com/Tommasov/MG4_Simple_Launcher), so the two
look like they belong to the same car.

## The engine

The engine is the head unit's **system WebView**, and on this car it is never
updated: without Play Services it stays whatever shipped with the firmware. On the
Android 9 emulator that is **Chromium 69.0.3497.100**, from 2018, and the car is the
same generation.

Two consequences, both accepted rather than worked around:

- Modern web apps will not work. That is expected, and no dependency in this project
  assumes a recent Chromium — AppCompat is the only library.
- The engine has not had a security patch in years. The app says so on its own home
  screen, with the real version number, rather than leaving you to find out.

That version line doubles as the answer to "which WebView does this car actually
have?", which is otherwise awkward to establish from anywhere but the driver's seat.

## Features

- **One address field** that takes an address or a search phrase. Anything that is
  not recognisably a host becomes a Google search; `192.168.1.1` and `nas:5000` are
  treated as addresses, `3.14` is not.
- **Back, forward, reload/stop, home** as large round targets. Reload and stop share
  a button — they are never both meaningful at once.
- **Favourites as tiles**, not a list: a coloured initial, the page title and the
  host. Tap to open, **long-press to remove**. Saved in `SharedPreferences`.
- **Loading progress bar** across the full width of the screen. The car's connection
  is slow enough that "is it doing anything?" is a real question.
- **Downloads straight to the installer**: a fetched APK is offered for installation
  as soon as it lands, and Android's "install unknown apps" permission is requested
  in place if it has not been granted yet.
- **Captive portals**: cleartext HTTP is allowed (a Wi-Fi login page is HTTP by
  definition) and a certificate that will not verify raises a prompt rather than a
  dead end — that case is normal on a portal and a warning sign anywhere else, so
  the choice is put to the user.
- **Day / night**, following the system.
- **Five languages**: English, Italian, German, Spanish, French.
- **Dialogs and toasts enlarged** by a font-scale override, so the two messages that
  actually ask you to decide something are legible from the driver's seat.

## Screenshots

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_WebBrowser/04-favorites.png" alt="Home: favourites as tiles, with the engine version at the foot of the screen" width="800" />
</p>

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_WebBrowser/02-page.png" alt="A page open, with the address bar and navigation buttons above it" width="800" />
</p>

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_WebBrowser/05-keyboard.png" alt="The address bar with the system keyboard open, still visible above it" width="800" />
</p>

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_WebBrowser/07-night.png" alt="The same home screen in night mode" width="800" />
</p>

<p align="center">
  <img src="https://ws2.tommasovietina.it/mg4/MG4_WebBrowser/08-remove.png" alt="Removing a favourite: a dialog at the enlarged font scale used throughout" width="800" />
</p>

## Installing an APK

Open the page the APK is on and start the download as you would anywhere else. The
browser fetches it with the system `DownloadManager`, into its own private storage,
and offers to install it when it is done.

The first time, Android will not yet allow this browser to install apps. The app
says so, sends you to the system screen where you grant it, and picks the install
back up when you return — the file is not downloaded twice.

Downloads carry the page's cookies and user agent, so a file behind a login arrives
as the file rather than as the login page.

## Build

Standard Android project (Java, AGP 8.6, Gradle 8.7, `minSdk 28` / `targetSdk 34`).

```
./gradlew assembleDebug
```

The debug APK is produced under `app/build/outputs/apk/debug/`.

**JDK**: Gradle 8.7 runs on JDK 17–21 and fails on newer ones with a bare
`IllegalArgumentException: <version>` from the Kotlin DSL compiler. Recent Android
Studio releases bundle a JBR newer than that, so set *Settings → Build Tools →
Gradle → Gradle JDK* (or `JAVA_HOME` on the command line) to a JDK 17 or 21.

**Release signing**: credentials are read from a git-ignored `keystore.properties`
at the project root. Without it the project still builds — the release type is
simply left unsigned rather than failing to configure.

## Disclaimer (English)

This project is provided **for study and educational purposes only**. It is an
experimental, non-commercial project and is not affiliated with, endorsed by, or
supported by SAIC, MG, or any vehicle manufacturer.

The software is provided "as is", without warranty of any kind, express or implied.
The author accepts **no liability** for any direct, indirect, incidental, or
consequential damage of any kind — including but not limited to damage to the
vehicle, its infotainment system, software, or data, loss of functionality, or
safety-related consequences — arising from the installation or use of this app. You
use it entirely **at your own risk**. Do not interact with the app while driving.

### Browsing on an unpatched engine

This is worth stating plainly rather than burying. The browser runs on the vehicle's
own WebView, which is part of the firmware and receives no updates: on this head unit
that is a Chromium from 2018, years behind on security fixes. Treat it as a tool for
reaching a known page, a Wi-Fi login or a download — not as somewhere to sign in to
anything you care about.

### Installing apps

The browser can hand a downloaded APK to the system installer. It cannot tell you
whether that APK is safe: there is no signature or checksum to check it against,
unlike the launcher's own catalogue. Install what you fetched only if you trust
where you fetched it from.

### Graphic resources

No artwork from the factory SAIC launcher is reproduced here. The app draws its own
surfaces as shapes; only the colour values are shared with MG4 Simple Launcher, so
the two sit together on the same screen.

## Avvertenze (Italiano)

Progetto **a scopo di studio**, sperimentale e non commerciale, non affiliato né
supportato da SAIC, MG o altri costruttori. Il software è fornito "così com'è",
senza garanzie di alcun tipo: l'autore **non risponde** di alcun danno diretto o
indiretto — al veicolo, al sistema di infotainment, al software o ai dati —
derivante dall'installazione o dall'uso dell'app. L'uso è **interamente a tuo
rischio**. Non interagire con l'app durante la guida.

**Motore non aggiornato**: il browser gira sulla WebView di sistema del veicolo, che
fa parte del firmware e non riceve aggiornamenti — su questa testata è un Chromium
del 2018, senza patch di sicurezza da anni. Usalo per raggiungere una pagina nota,
un portale Wi-Fi o un download, non per accedere a servizi a cui tieni.

**Installazione di app**: il browser passa l'APK scaricato all'installer di sistema,
ma non può dirti se quell'APK è sicuro — non c'è firma né checksum da verificare, a
differenza del catalogo del launcher. Installa solo ciò di cui ti fidi.
