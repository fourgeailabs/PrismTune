# PrismTune

PrismTune plays your Plex music library in the car. It's an audio-only player for Android Automotive OS: no video, no feeds, nothing to scroll through. Sign in with your Plex account, pick your server, and drive.

Unofficial project. Not affiliated with or endorsed by Plex Inc.

## Full disclosure

This app was written entirely by AI. Every line of code came out of a language model, working from plain-English requests and photos of a car screen. A human tests it on real hardware and decides what ships. It works, but you should know what it is before you run it.

## What it does

- Sign in with your Plex username and password (a plex.tv/link code option is in there too). The password is never stored. The sign-in token is stored encrypted on the device and wiped on sign-out.
- Connections are tried remote first, then local, then relay. It's a car app, so it assumes you're not at home.
- Browse artists, albums, tracks, and playlists. Search while parked. Recently Played is on the Home screen.
- Moods are the real Mood and Style tags from your own server, counted per song. There's no genre guessing — if a song isn't tagged with a mood in Plex, it doesn't show up under that mood.
- A Recommended playlist and a Curated by Me! card, both built from what you play and what you thumb up or down. Thumbs toggle: tap a selected thumb again to undo it.
- Downloads that are honest about themselves. The Download button asks first, with a big pop-up. A song only counts as downloaded if you actually asked for it — songs sitting in the playback buffer don't pretend to be downloads. Settings has a downloads manager with per-song delete and a delete-all that keeps the song currently playing.
- Offline Top Picks builds an offline set from your most-played and highest-rated music.
- Playback runs through Media3 with a proper media session, so steering-wheel buttons and the instrument cluster display work.
- Colour themes pulled from the album artwork. If you land on a colourway you like, Keep current colours in Settings locks it in across restarts and track changes.
- A live waveform on Now Playing and the mini player, because it looks good.

## Driving safety

Audio only — there is no video code in the project. The app declares `distractionOptimized`, and every touch target is at least 76dp.

The layout is built around a 2025 Chevy Equinox EV. On that car the temperature readout sits in the top-right corner of the screen, so PrismTune keeps every control inside the left 80% of the display. The right strip is display-only: artwork, track info, mini player. The split is adjustable in Settings from 55% to 85%, hard-capped at 85% so controls can never reach the gauge. It should behave on other Android Automotive screens too, but the Equinox is what it's tuned and tested on.

The usable area it was tuned against measures 13.125 in across by 4.875 in high. On that screen the temperature sits about 89% across and the clock about 83%, which is where the 80% default comes from.

## Status

Current version is 1.5.9. It runs daily on a real Equinox EV and on a Pixel phone. 181 unit tests, all passing.

Distribution right now is Google Play internal testing only. Getting to a public Play release needs a closed test with more testers first, which is one reason this repo is public.

Known gaps are in the GitHub Issues, not hidden. If something doesn't work with your server, that's a bug report worth filing.

## Want to test it?

Open an issue with the title "Tester request" and say hello. Play internal testing needs the email address of the Google account you use on the Play Store to add you to the list, so don't post it in the issue itself — we'll sort that out with you.

## Build it

You need JDK 17 to build (JDK 21 to run the unit tests), the Android SDK with API 36, and the Gradle wrapper in the repo.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Release builds are signed with a key that is not in this repo, so a build you make yourself won't update over the Play build. For just trying the app, the internal test is easier.

## Privacy

Everything stays on the device. The app talks to plex.tv (for sign-in) and to your own Plex server, and nowhere else. No analytics, no ads, no tracking, nothing sent to the developer. The full policy is in [PRIVACY.md](PRIVACY.md).

## License

See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
