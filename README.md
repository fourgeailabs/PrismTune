# PrismTune

**PrismTune** is an unofficial, audio-only music player for Android Automotive
that plays music from your own Plex Media Server. It is the app Plex Amp
never shipped for the car: sign in once with a TV-style link code, browse
your artists, albums, playlists and recently added music, and listen —
with big, driving-safe controls and nothing to watch.

> **Unofficial. Not affiliated with, endorsed by, or connected to Plex Inc.**
> PrismTune uses Plex's published account/server APIs with the user's own
> token, the same way third-party Plex clients do. It uses its own name,
> its own prism logo, and its own amber-on-charcoal theme. It does not use
> the word "Plex" in its name, Plex's `>` logo, or Plex artwork.

## What it does

- **TV / console-style sign-in (no password typed in the car)**
  1. PrismTune calls `POST https://plex.tv/api/v2/pins` with
     `X-Plex-Product: PrismTune`, `X-Plex-Version`, and a per-car
     `X-Plex-Client-Identifier`.
  2. The car screen shows a 4-character code **and a QR code**.
  3. Scan the QR code with your phone (or open `plex.tv/link` by hand),
     sign in to Plex on the phone, and type the code.
  4. PrismTune polls `GET https://plex.tv/api/v2/pins/{id}` every 2 seconds
     until an `authToken` arrives (timeout: 15 minutes, with Cancel/Retry).
  5. The token is stored with `EncryptedSharedPreferences` (with a private
     fallback where the KeyStore is unavailable) and wiped on Sign out.
- **Servers:** `GET https://plex.tv/api/v2/resources` lists your servers;
  PrismTune prefers a local connection and falls back to remote/HTTPS.
  Switch servers in Settings.
- **Browsing:** music library sections → artists → albums → tracks,
  playlists, recently added, and title search — all over Plex's JSON APIs
  (`Accept: application/json`).
- **Playback:** Media3 ExoPlayer + a `MediaSession` (`PrismTuneMediaService`),
  so steering-wheel and car-system play/pause/next/previous work. Queue,
  shuffle, repeat off/all/one, seek, recently played.
- **Audio quality (Settings):** Original (direct play) or a transcode
  ceiling of 320 / 192 / 128 kbps via Plex's universal transcode endpoint.
  Applies to the next track started.
- **Look:** dark charcoal theme, amber accent, the PrismTune prism mark on
  the sign-in screen, Now Playing, and the launcher icon.

## Driving safety — and the Equinox EV screen

This is an **audio-only** app. There is no video playback, no video
surface, and no video code anywhere in the project.

- The activity declares `distractionOptimized=true` in the manifest.
- Every interactive control is at least **76dp** tall.
- Browsable lists are paged at **6 rows per page** with large Prev/Next
  buttons, instead of endless scrolling.
- Text search is labelled for parked use; while driving, use Home,
  Library, Queue, or Now Playing.

### The top-right gauge zone (2025 Chevrolet Equinox EV)

The Equinox EV's 17.7" infotainment display has a temperature / climate
overlay in the **top-right corner**, and the screen is wider than a normal
tablet. PrismTune handles this in exactly one place:

- `app/src/main/java/com/fourgeailabs/prismtune/ui/SafeDrivingLayout.kt`
- `SafeAreaConfig.DEFAULT_FRACTION = 0.80f`
  (`app/src/main/java/com/fourgeailabs/prismtune/data/PlexModels.kt`)

All tappable controls — navigation, lists, playback buttons, sliders, the
sign-in Retry/Cancel buttons — live in the **left 80%** of the width.
The top bar is drawn inside that zone too, so no interactive element
ever sits in the top-right quadrant (x > 80%, top ~12% of height).
The remaining right strip is **display only**: large album artwork,
now-playing text, and a decorative equalizer glow. It contains no buttons,
no sliders, and no text fields, so no control can ever sit under the
temperature gauge or require a glance at the top-right corner.

**Tuning it:** Settings → "Equinox EV safe area" has a slider from
55% to 85% (persisted, hard-capped at 85% — controls can never be pushed
past 85%). If the default ever feels off on the real car, move that
slider — or change `DEFAULT_FRACTION` and rebuild.

### Measured usable area (final figure)

Eric tape-measured the top of the usable area of the display:

- **Usable area: 13.125 × 4.875 in** (13 and 1/8 in across, 4 and 7/8 in high)
- **Aspect ratio: 2.692:1** — very wide, very short
- **Usable diagonal: about 14.00 in**
- **80% interactive width = 10.50 in** from the left
- **Right non-interactive reserve = 2.625 in**
- **Temperature at ~89% across = about 11.68 in** from the left, so the
  80% limit leaves about a **1.18 in buffer** before the temp centre
- **Clock at ~83% across = about 10.89 in** from the left

This is the **app-usable area he measured, not the advertised
full-glass spec** — PrismTune is coded to this usable area (see
`EquinoxUsableArea` in `PlexModels.kt`, covered by unit tests in
`PlexLogicTest.kt`). The temp
position itself is still the photo-derived ~89% (he has not yet given a
tape-measured distance to the temp gauge itself); if he sends that, the
~89% figure is replaced by the measured one.

Because the usable area is only 4.875 in tall — even tighter than the
earlier 5 in estimate — layouts are checked at this wide, short ratio:
the main layout is a wide left (interactive) pane + a non-interactive
right pane, rows and controls are kept low and wide, Now Playing keeps
its controls in one horizontal row, and the browsable screens scroll
vertically inside the left pane rather than stacking tall fixed
columns that could overflow a 4.875-inch-tall screen. No tall vertical
stacks: if a screen cannot fit in 4.875 in of height, it scrolls
instead of overflowing.

### Calibration note — photo, 2026-10-25

Eric sent a photo of the display with a standard 12 oz soda can
(2.6 in diameter, 4.83 in tall) pressed flush against the screen.
Measured in the photo (0–1000 image coordinates):

- Infotainment content screen: x 284–880, y 407–845
- Soda can: x 622–768, y 449–835 — about **57% to 81% across** the
  content screen
- Temperature reading (91°): point x 812, y 427 — about **88–89% across**
  the content screen, about 5% down from its top
- Clock (11:54): x 781 — about **83% across**

**Honest uncertainty:** the photo is angled, not straight-on, and the
can's centre sits ~1.3 in in front of the glass, so absolute-inch maths
from the can is approximate, not exact. The **relative** position is the
reliable part: the top-right temp/clock zone starts around 83% across
and the temperature itself is at ~89%.

That is why the default is **80%**: it gives more usable room than the
original conservative 70%, while still leaving a clear margin before
the clock at ~83% and the temperature at ~89%. If a future straight-on
photo (credit card or ruler against the screen edge) pins the gauge
edge more precisely, the default changes in the single constant above.

## Screens

| Screen | Contents |
|---|---|
| Sign-in | 4-char code, QR code for `plex.tv/link`, wait/cancel/retry, disclaimer |
| Home | Recently added, playlists, server name, browse button |
| Library | Artists (paged) → Albums (paged) → Tracks (paged, Play all) |
| Search | Artist/album/song search (parked use) |
| Now Playing | Artwork, seek, prev/play/next, shuffle, repeat, queue link |
| Queue | Up Next (tap to jump), Recently played |
| Settings | Server choice + connection, audio quality, safe-area slider, sign-out, about |

## Branding

- Name: **PrismTune** — prism + tune. No "Plex" in the name.
- Logo: a faceted prism refracting a beam into sound-wave / equalizer
  beams, amber-orange gradient on deep charcoal. Distinct from Plex.
- Files:
  - `assets/branding/prismtune-logo.svg` — vector logo source
  - `assets/branding/prismtune-icon-1024.svg` — 1024px icon source (SVG)
  - `assets/branding/prismtune-icon-1024.png` — 1024×1024 icon render (PNG)
  - `app/src/main/res/drawable/prism_logo.xml` — in-app VectorDrawable
  - `app/src/main/res/drawable/ic_launcher_foreground.xml` +
    `ic_launcher_background.xml` + `mipmap-anydpi-v26/ic_launcher*.xml`
    — adaptive launcher icon, wired in `AndroidManifest.xml`

## Build

Requirements: JDK 17+ (JDK 21 for unit tests), Android SDK with
API 36, Gradle 9.3.1 (wrapper included).

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

GitHub Actions (`.github/workflows/build.yml`) builds the debug APK and
uploads it as the `prismtune-debug-apk` artifact. **APKs are delivered via
CI artifacts only — never as chat attachments** (project policy).

## Project layout

```
app/src/main/java/com/fourgeailabs/prismtune/
  MainActivity.kt            # Automotive entry, SafeDrivingLayout wiring
  data/
    PlexModels.kt            # SafeAreaConfig, PlexUrls, ConnectionPicker, models
    PlexClient.kt            # PIN auth, resources, browsing, search (JSON APIs)
    TokenStore.kt            # Encrypted token store + SettingsStore
  playback/
    PlaybackManager.kt       # Media3 ExoPlayer queue/shuffle/repeat/seek
    PrismTuneMediaService.kt # MediaSession for car controls
  qr/QrCode.kt               # QR rendering for plex.tv/link
  ui/
    Theme.kt                 # Dark amber theme
    SafeDrivingLayout.kt     # THE safe-area rule (see above)
    PrismLogo.kt             # Logo drawn in Compose
    Screens.kt               # Sign-in, Home, Library, Search
    NowPlayingScreens.kt     # Now Playing, Queue, Settings
  viewmodel/PrismViewModel.kt
app/src/test/.../PlexLogicTest.kt   # Safe-area, URL, connection-picker tests
```

## What has NOT been tested yet (honest status)

- Nothing in this repo has run on a real 2025 Equinox EV yet.
- Still owed, on the car: install from the CI artifact, complete the QR
  link-code sign-in, play a track end-to-end, check steering-wheel
  controls, and confirm no control sits under the top-right gauge at the
  80% default (or move the Settings slider and report the right value).
- Plex server behaviour (local vs. remote, transcoding, artwork) varies
  by server version and network; the first in-car run is the real test.

## License

FOURGEAI LABS PROPRIETARY SOFTWARE LICENSE v1.0 — see `LICENSE`
and `NOTICE.md`. Forking, modifying, or redistributing requires the
author's direct written consent (see `NOTICE.md`).
