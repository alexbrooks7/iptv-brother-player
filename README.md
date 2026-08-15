# IPTV Brother Player — Android TV & Fire TV

A D-pad-first IPTV player for Android TV and Amazon Fire TV. The user supplies
their own playlist — an M3U/M3U8 URL, a local `.m3u` file, or an Xtream Codes
login — and the app plays Live TV, movies and series from it, with an XMLTV
programme guide.

The app ships with **no channels, no content and no provider**. It is a player,
in the same category as VLC or Kodi.

**Distribution is two channels from one codebase.** The `sideload` build is a
single universal APK on GitHub Releases, with internet-bandwidth sharing built
in to fund development. The `store` build — for the Play Store and the Amazon
Appstore, both of which prohibit apps that route other users' traffic through
a device — has that SDK not merely turned off but not present in the APK at
all. Both run identically otherwise on Android TV, Google TV and Fire TV;
nothing else in the build varies by platform. See [Build](#build) and
[Store policy](#store-policy).

---

## Status

Built against the phased plan in the brief. What is implemented versus deferred:

| Phase | Scope | Status |
|---|---|---|
| **1 — MVP** | M3U + Xtream import, Live TV, categories, favourites, settings | **Done** |
| **2** | EPG grid, VOD & series browsing, resume, search | **Done** |
| **3** | Catch-up/timeshift, multi-audio & subtitles, parental PIN, multi-profile | **Done except multi-profile** |
| **4** | Low-end tuning, store submission | Build config, assets and a store-clean build variant done; the actual Play Console / Amazon Developer Console upload is yours |
| **5** | Companion mobile app, mosaic multi-view, licensing | **Not started** — see [Deliberately not built](#deliberately-not-built) |

Everything below "Done" has been exercised on a running Android TV emulator, not
just compiled — see [Testing](#testing).

---

## Build

Requires JDK 17 (Android Studio's bundled JBR is fine) and the Android SDK.

```bash
./gradlew :app:assembleSideloadDebug
```

One `distribution` flavour dimension, two flavours:

| Flavour | applicationId | Has the Pawns SDK | For |
|---|---|---|---|
| `sideload` | `com.iptv.player` | Yes | GitHub Releases, direct download |
| `store` | `com.iptv.player.store` | **No — not on the classpath at all** | Play Store, Amazon Appstore |

Crossed with the three build types (`debug` / `release` / `benchmark`), so
Gradle task names need the flavour: `assembleSideloadDebug`,
`testStoreDebugUnitTest`, `bundleStoreRelease`, and so on — the unqualified
`assembleDebug` now fails with "task is ambiguous" rather than picking one.

**This is not the old `play`/`amazon` split**, which existed over a single
manifest flag with no functional effect outside Play's own submission filter
and was retired for exactly that reason — see [Fire TV / no-GMS](#fire-tv--no-gms).
This one exists because `store` genuinely cannot ship a dependency that
`sideload` needs, which no build-type or runtime flag can express: Play and
Amazon scan a submission for the SDK's own classes, so disabling the feature
in a build that still links the library would not satisfy either policy. The
mechanism is a real Gradle source-set split —
`sideloadImplementation(libs.pawns.sdk)` rather than an unconditional
`implementation`, `PawnsManager` living in `src/sideload` with a no-op
same-shaped stand-in in `src/store`, and the peer service's manifest entry and
`FOREGROUND_SERVICE_SPECIAL_USE` permission only declared in
`src/sideload/AndroidManifest.xml`. `main` code (`MainScreen`, `SettingsScreen`)
calls `PawnsManager` and reads the app-owned `SharingState` type either way and
never imports anything under `com.pawns.sdk` — see `sharing/SharingState.kt`.

Both CI (`build.yml`, on every push) and the release job grep the compiled dex
of a `store` build for `com/pawns/sdk` and fail if it is found — a regression
guard against the dependency migrating back to `main` by accident, checked on
every push, not only when someone remembers to look.

PostHog analytics is **not** flavour-gated — it stays in both builds. It is a
conventional client analytics SDK, not the thing either store's policy targets;
the prohibition is specifically about routing other users' network traffic
through a device.

**Never judge performance from a debug build.** `debuggable=true` stops ART
using its optimising compiler, and without the R8 pass every Compose call stays
a real call. On the reference device that is roughly an 8× difference — cold
start measured 5.7 s debug against 736 ms optimised — and it is uneven enough
to point at the wrong bottleneck, not merely pessimistic. The `benchmark`
variant exists for this: release-optimised and non-debuggable, but sharing the
debug `applicationId` so it installs over an existing app and profiles against
a real imported playlist instead of an empty database.

```bash
./gradlew :app:assembleSideloadBenchmark
adb install -r app/build/outputs/apk/sideload/benchmark/app-sideload-benchmark.apk
```

Two opt-in switches help when profiling:

```bash
./gradlew :app:assembleSideloadBenchmark -PbenchmarkSymbols   # skip R8, keep symbols
./gradlew :app:assembleSideloadDebug -PcomposeMetrics         # skippability report
```

Install a debug build:

```bash
adb install -r app/build/outputs/apk/sideload/debug/app-sideload-debug.apk
```

Unit tests (parsers, URL normalisation, classification — no device needed).
Both flavours, since they now diverge in what is on the classpath:

```bash
./gradlew :app:testSideloadDebugUnitTest :app:testStoreDebugUnitTest
```

### Signing & release builds

`assembleRelease` falls back to the debug keystore so a fresh clone builds
without setup — that APK is **sideloadable, but its signature is not stable
across machines or CI runs**, so it cannot receive in-place updates once
someone has it installed. For a real, distributable build, create
`keystore.properties` in the project root (gitignored):

```properties
storeFile=release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

```bash
./gradlew :app:assembleSideloadRelease            # app/build/outputs/apk/sideload/release/
./gradlew :app:assembleStoreRelease                # app/build/outputs/apk/store/release/  (Amazon)
./gradlew :app:bundleStoreRelease                  # app/build/outputs/bundle/storeRelease/ (Play — .aab)
```

The same release keystore signs all three; Play re-signs whatever you upload
with its own app-signing key regardless (Play App Signing), and Amazon
distributes the APK you give it as-is, the same as sideloading.

### Publishing a release

Sideload downloads live on GitHub Releases. This URL always serves the newest
release and never changes:

**`https://github.com/alexbrooks7/iptv-brother-player/releases/latest/download/iptv-brother-player.apk`**

One universal APK — the same file works on Android TV, Google TV and Fire TV.

Publishing is tag-triggered — `.github/workflows/release.yml` tests both
flavours, builds all three release artifacts, and attaches only the sideload
APK to the GitHub Release:

```bash
git tag v1.0.1 && git push origin v1.0.1
```

**The `store` APK and AAB are not attached to the release** — that page's
"one universal APK" description and permanent link are for sideloaders, and a
second, differently-branded APK sitting next to it would just confuse that
audience. They are uploaded instead as a workflow run artifact named
`store-submission-artifacts` (Actions tab → the run for that tag → Artifacts),
for you to download and upload manually to Play Console and the Amazon
Developer Console — actual store submission needs a developer account, a
content rating questionnaire and a store listing, none of which this pipeline
can do for you.

**One-time setup: the signing key.** Android refuses to upgrade an installed
app whose signature changed, so every release must be signed with the *same*
key or users have to uninstall — losing their playlists, favourites and watch
history — just to take an update. Without a real key the build falls back to
the debug keystore, which a CI runner regenerates on every run, giving every
release a different signature. The release job therefore refuses to run until
four repository secrets exist (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | your `release.keystore`, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |
| `PAWNS_API_KEY` | same value as `pawns.apiKey` in `local.properties` |
| `POSTHOG_API_KEY` | same value as `posthog.apiKey` in `local.properties` |
| `POSTHOG_HOST` | optional; defaults to `https://us.i.posthog.com` |

**Why the two API keys are required here but absent from `build.yml`.** They
are deliberately opposite, and v1.0.0 shipped broken because that distinction
was missed. `build.yml` builds with no keys on purpose, proving a fresh clone
still compiles with sharing and analytics disabled. The release job needs the
reverse: `local.properties` is gitignored and does not exist on a runner, so
without these secrets the released APK bundles both SDKs with blank keys —
`PawnsManager.available` is `false`, no consent prompt ever appears, no
bandwidth is shared, no analytics are sent, and the app looks completely
healthy while doing none of it. The release job now refuses to build without
them, and then greps the compiled dex to confirm both keys are genuinely
embedded before publishing, because "the build succeeded" was exactly the
evidence that proved insufficient the first time.

Note these keys are extractable from any published APK, as all client-side
SDK keys are — the same threat model as an embedded analytics measurement ID.
Keeping them out of tracked source is about fresh clones and contributors, not
about the binary.

To create a key and encode it:

```bash
keytool -genkey -v -keystore release.keystore -alias iptv \
        -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore    # paste into KEYSTORE_BASE64
```

Back up `release.keystore` somewhere safe and permanent. Losing it means you
can never ship an upgrade to anyone who installed a previous build, and it
cannot be regenerated or recovered.

---

## Architecture

| Layer | Choice | Why |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose + `androidx.tv:tv-material` | One UI that satisfies both Android TV and Fire TV. **Not Leanback** — Fire OS does not use it. Leanback's *conventions* (focus rail, content to the right) are implemented directly in Compose. |
| Playback | Media3 / ExoPlayer (HLS, DASH, RTSP, progressive, MPEG-TS) | Industry standard; the extension modules give protocol coverage without native builds. |
| Persistence | Room + DataStore | Room for the catalogue and guide, DataStore for settings. |
| Networking | OkHttp, shared with ExoPlayer's data source | One connection pool, so a channel change reuses a warm TLS connection. |
| Xtream API | Hand-rolled on OkHttp + `kotlinx.serialization` `JsonElement` | See below. |
| Background work | WorkManager | GMS-free; falls back to `AlarmManager` on Fire OS. |
| DI | A hand-written `ServiceLocator` | Ten singletons, no scopes. A DI framework would add a second annotation processor and cold-start cost for nothing. |
| Navigation | A sealed `Screen` type in `MainScreen.kt` | Navigation-Compose would tear down and rebuild the player on every navigation, black-framing a live stream. |

### Decisions worth knowing before you change something

**No typed models for the Xtream API.** There is no specification and no
consistency between panels: `stream_id` is `12345` or `"12345"`, `rating` is a
number, a string, `""` or `null`, and an empty catalogue is `[]`, `{}`, `""` or
an HTML error page with a 200. Strict deserialisation turns each of those into a
crash on somebody's provider, so everything is read through tolerant accessors
in `XtreamClient.kt` that return null rather than throw.

**Large responses are streamed, never materialised.** A 10,000-channel Xtream
catalogue is 10–30 MB of JSON and a week of XMLTV for that many channels is
hundreds of MB. Both are consumed incrementally (`decodeToSequence`, a pull
parser) and written to Room in chunks. The target device is a Fire TV Stick Lite
with 1 GB of RAM shared with the system.

**The import is not one big transaction.** Wrapping it would hold SQLite's write
lock across minutes of network I/O, blocking every other writer — the UI freezes
whenever a background refresh runs. Chunks commit individually; an interrupted
refresh leaves a partial catalogue plus a `lastSyncError` the Playlists screen
renders with a Retry action. The M3U path *is* transactional, because there the
parse finishes before the first write.

**Guide data is fetched for what is on screen only.** The grid reports its
visible rows and `GuideViewModel` queries exactly those channels over a window
wider than the viewport. The channel list shows now/next for the *focused*
channel only — joining the programme table against 10,000 rows to label a list
is the most expensive query the app could run, and it would rerun on every
category change.

**Stream URLs are built at play time, not stored.** Xtream URLs embed the
account password in the path. Persisting them would put a plaintext copy of the
credentials on all 10,000 channel rows and quietly defeat the Keystore
encryption. Rows store only the stream id; `StreamUrlResolver` assembles the URL
on demand.

**Favourites and history key off a stable `streamKey`**, derived from the
provider's own id or an FNV-1a hash of name+URL — so a provider renumbering its
line-up does not wipe a user's favourites. `stableHash` is a persisted format,
not an implementation detail; there is a unit test pinning its output.

---

## D-pad behaviour

The brief singles out focus quality, and most of the non-obvious code in the UI
is about it.

- **Text fields have two modes.** A Compose text field requests the keyboard as
  soon as it takes focus, and while the TV IME is up it is a separate window
  that owns every arrow key. Built the phone way, a form becomes a trap: focus
  lands in the first field, the keyboard covers the screen, and the next Down
  types a letter instead of moving on. So a field is normally a focusable row
  showing its value; centre-press enters editing, and Back or Done leaves it.
- **Focus requests wait for layout.** `requestFocus()` on a node that has not
  been placed is a no-op, and `LaunchedEffect` frequently runs before layout —
  the symptom is a screen where nothing is selected and the remote does nothing.
  `requestFocusWhenReady()` retries across frames.
- **Back retreats, it does not quit.** Detail → section → Live TV → exit. In the
  player, Back closes an overlay first.
- **Up/Down with no overlay changes channel**, walking the list the user was
  browsing (`PlaybackQueue` carries it across the navigation boundary), and
  `CHANNEL_UP`/`DOWN` and the media transport keys are wired for remotes that
  have them.
- **Panes are proportional, not fixed-width.** Fixed dp that looks right at
  1080p squeezes the channel column once the rail expands or the user raises the
  interface scale, truncating the names the column exists to show.

---

## Fire TV / no-GMS

There is no dependency on Google Play Services anywhere in the project, so the
one build runs on Fire TV as-is. Specifically: WorkManager (not Firebase
JobDispatcher or GCM), no Firebase Analytics or Crashlytics, no Google Sign-In,
no Cast, no Maps.

The manifest declares `android.software.leanback` as **not required**
(`app/src/main/AndroidManifest.xml`). That attribute only ever mattered for
Play Store submission filtering — required=true restricts *Play distribution*
to devices advertising the feature, but it is not enforced by the OS at
install time, so it has no effect on a sideloaded APK either way. There used
to be a `play`/`amazon` build flavour split over exactly this one flag, kept
apart because Play wanted it `true` and Amazon's submission process had
historically excluded Fire TV devices when it was set. Neither store was in
the picture at the time, so the flag was fixed at the safer, more compatible
value and that flavour split was retired.

A flavour split exists again now — `sideload`/`store`, over the Pawns SDK, not
this flag — see [Build](#build). It is not a reversal of the reasoning above:
this `leanback` value still needs no per-store variance, so both flavours
carry the same fixed setting; the new split exists solely because `store`
cannot link a dependency that `sideload` needs.

---

## Security

**Provider credentials** are encrypted with an AES-256/GCM key in the Android
Keystore (`CredentialCrypto.kt`), never plaintext. The key is non-exportable and
on TEE-equipped hardware — all current Fire TV and Google TV devices — never
enters app memory. It deliberately does *not* require user authentication: a TV
has no fingerprint reader and background refresh runs with nobody in the room.
The threat model is "someone pulls the app's data directory", which happens on
the rooted cheap boxes common in this market.

**Cleartext HTTP is permitted** (`res/xml/network_security_config.xml`). This is
a deliberate, documented trade-off, not an oversight: IPTV providers
overwhelmingly serve playlists, APIs and streams over plain `http`, frequently
on a bare IP with no certificate. With the API 28+ default in place, the very
first thing a user does — paste the URL from their subscription email — fails
with a message they cannot act on. The cost is confidentiality against an
attacker on the same network; it is mitigated where possible (https URLs are
never downgraded, credentials are encrypted at rest, the diagnostics log
redacts them) but cannot be removed without breaking the app's core function.

**The parental PIN** is stored as a salted SHA-256 digest. It guards nothing of
monetary value and there is no server to attack, so a memory-hard KDF would be
theatre — but four plaintext digits next to a setting called "adult categories"
would be indefensible.

**Backups never contain credentials.** The encrypted blob is device-bound and
useless on another box, and the only way to make an export "complete" for an
Xtream source would be to write the account password in plain text — not
something to do to a file that can end up on a shared USB stick or a
cloud-synced folder. There is no flag to turn this back on. An imported Xtream
source lands in Playlists with a **Sign in** row instead of its login; entering
it there triggers a sync immediately. Everything else — server/EPG URL, user
agent, favourites — restores as part of the same import.

---

## Diagnostics

Settings → Diagnostics log is an in-memory ring buffer of the last 300 network
and playback events, with credentials redacted. It is the app's answer to the
brief's "GMS-independent crash/analytics" line, and a deliberate substitute for
wiring in Sentry for v1.

The reasoning: the dominant support case for an IPTV player is not a crash, it
is *"channel 402 doesn't play and my provider says it works"*. What resolves
that is the HTTP status and the codec the device refused, on screen, readable
off the TV by the person holding the remote. A crash reporter catches none of
it. This paid for itself during development — see [Testing](#testing).

Adding Sentry later is a drop-in: implement the same three calls in
`Diagnostics.kt`, keep the redaction, and gate it behind an opt-in toggle.

---

## Store policy

### Bandwidth sharing

Both the Play Store and the Amazon Appstore prohibit apps that route other
users' internet traffic through a device without being a disclosed VPN/proxy
product in their own right — Pawns.app is exactly that. The `store` build
exists to satisfy this: see [Build](#build) for the flavour split, and
[Internet sharing](#internet-sharing-pawnsapp) for the feature itself, which
only ever ships in `sideload`.

The bar this build was designed against is "the SDK's classes are not in the
APK", not "the feature is switched off" — both stores' review can and does
scan a submission for known SDK signatures, so a `store` build that still
linked Pawns with the feature merely disabled at runtime would not have
satisfied either policy. This is checked mechanically, not just by design: CI
and the release job both grep a `store` build's compiled dex for
`com/pawns/sdk` and fail if it is found.

### Unlicensed content

Both stores also have policies about apps that facilitate access to unlicensed
content. A generic player that accepts user-supplied playlist URLs is standard
and widely approved; what neither store permits is bundling or advertising
pirate sources. This app ships with no channels, no content and no provider —
see the top of this document — which is the same posture VLC and Kodi take.

This app is built to sit clearly on the right side of that line:

- No playlists, channels, URLs or providers ship with it. There is no directory,
  no "popular playlists", no default source.
- The icon and banner say "video player", not "TV channels".
- Settings states plainly: *"This app plays only the playlists you provide."*
- Parental controls are implemented and pre-classify adult categories, since a
  user-supplied playlist may contain them.

**Have a lawyer review the listing copy and screenshots before submission.**
Enforcement in this category has tightened, and the listing — not the code — is
usually what gets an app rejected. Do not use screenshots showing real
broadcaster logos or channel names.

---

## Internet sharing (Pawns.app)

**`sideload` builds only.** The `store` flavour does not link this SDK at
all — see [Build](#build) and [Store policy](#store-policy) — so everything
below describes `sideload` behaviour; a `store` build never shows any of this
UI because `PawnsManager.available` is unconditionally `false` there.

The app can route internet traffic for [Pawns.app](https://pawns.app) and its
clients using a share of the device's bandwidth, as a way to fund development.
It is **off until the viewer explicitly opts in**.

### How it is wired

| Piece | File |
|---|---|
| SDK wrapper (real), `sideload` only | `src/sideload/.../sharing/PawnsManager.kt` |
| No-op stand-in, `store` only | `src/store/.../sharing/PawnsManager.kt` |
| App-owned service-state type both call sites share | `src/main/.../sharing/SharingState.kt` |
| Disclosure and opt-in dialog | `ui/screens/ConsentDialog.kt` |
| Shown on app open; re-openable | `ui/MainScreen.kt` |
| On/off control and live status | `ui/screens/SettingsScreen.kt` |
| "Asked once" flag | `Settings.sharingConsentAsked` |
| "Should be running" flag | `Settings.sharingEnabled` |

**The prompt appears on app open, not buried in Settings.** A feature that
routes strangers' traffic through someone's home connection has to be an
active, informed choice, and a setting nobody opens is not one. It is asked
*once*: the answer is recorded in `sharingConsentAsked`, so a decline is
respected permanently rather than re-asked every launch. This flag is separate
from the SDK's own consent flag on purpose — that one is a bare boolean that
reads `false` whether the prompt was declined or never shown, so prompting off
it alone would nag forever after a decline.

**It can be turned on or off at any time** from Settings → Internet sharing.
That row reads the SDK's live service state rather than the consent flag,
because consent being granted does not prove the service is running — it can be
paused on low battery or failing — and reporting "On" over an erroring service
would be a claim the viewer cannot check. The add-playlist screen has a Settings
button for the same reason: it is where a first-run viewer lands, it has no side
navigation, and Back deliberately keeps them there until a playlist exists, so
without it the dialog's "you can turn it off at any time" would not be true yet.

**The choice survives a restart**, which needs a third flag. The SDK's service
does not outlive the process, so `MainScreen` restarts it on launch — but it
gates that on `sharingEnabled`, not on the SDK's consent bit. Consent stays
granted when someone merely switches sharing off, so resuming from it would
quietly overturn a deliberate opt-out on the next launch; resuming from nothing
at all was the original bug, where the service only ever ran in the session it
was switched on in and every later launch shared nothing while Settings honestly
reported "Off". It is started from composition rather than `IptvApp.onCreate`
because it is a foreground service, and `Application.onCreate` also runs when
WorkManager wakes the app to refresh a playlist — starting one from that path
throws `ForegroundServiceStartNotAllowedException` on API 31+.

**The SDK's own consent Activity is not used for the on-open prompt.** Custom
implementations are permitted, and the bundled one is built for phones: every
hyperlink paragraph is its own focus stop so the buttons are around a dozen
D-pad presses away, the buttons draw no focus indicator, and it is a full-page
white scroll. `ConsentDialog` has two focus stops and an unmistakable focus ring.

### Configuration

No key, no feature. `PawnsManager.available` is false when `pawns.apiKey` is
absent from `local.properties`, and then the app never prompts, never shows the
Settings section, and never starts a service — a fresh clone builds a plain
player. Add the key to enable it:

```properties
pawns.apiKey=your-key-here
```

> **The key currently in `local.properties` is a placeholder** added to verify
> the dialog renders. Replace it with a real one before shipping; with a
> placeholder the prompt appears but the service cannot authenticate.

This forced `compileSdk` to 36 and AGP to 8.9.1, because the SDK depends on
`androidx.core` 1.17.0. `targetSdk` stays at 35 and **`minSdk` stays at 24**, so
no device loses support — verified in the merged manifest.

### Before you publish this

Read this section alongside [Store policy](#store-policy); the two interact.

- **Both stores require prominent disclosure and consent for traffic routing**,
  and treat an SDK that proxies third-party traffic as a serious matter rather
  than an ordinary ad SDK. The flow here is built to meet that bar — disclosure
  before any traffic, an equally reachable decline, a persistent notification
  while active, and off at any time — but *the listing must say so too*.
  Undisclosed bandwidth sharing is a removal-grade violation.
- **The consent copy states what Pawns.app actually receives** — IP address and
  approximate location — and what it costs. Pawns' own terms put responsibility
  for this disclosure on the app owner, so softening it is your liability, not
  theirs. Do not reword it to sound more harmless than it is.
- **This is a legal question as much as a technical one** in some
  jurisdictions, and the app's data-protection disclosures need to cover it.
  Have the same lawyer who reviews the listing look at this feature.

---

## Analytics (PostHog)

Product analytics, off by default and fully inert with no key configured —
same pattern as internet sharing above, down to reading its key from the same
`local.properties` file.

### How it is wired

| Piece | File |
|---|---|
| SDK wrapper, fails closed when unconfigured | `analytics/IptvAnalytics.kt` |
| Initialised | `IptvApp.onCreate()` |
| Screen tracking | `ui/MainScreen.kt` (`Screen.analyticsName()` + one `LaunchedEffect`) |
| Product events | scattered at the handful of places listed below |

```properties
posthog.apiKey=phc_your_project_key
posthog.host=https://us.i.posthog.com   # optional, this is the default
```

**Screens are tracked manually, not via PostHog's Activity-based auto
capture.** This is a single-Activity app — the whole UI lives inside Compose,
switching over a sealed `Screen` type in `MainScreen.kt` — so Activity-based
tracking would only ever report one screen for the entire app. A
`LaunchedEffect` keyed on the current screen's name calls `IptvAnalytics.screen()`
instead, which is the direct equivalent of what Drift does for the same reason.

**The event list is short on purpose.** A toggle in Settings or a channel-zap
does not get an event; a dashboard that fires on everything tells you nothing
about what matters. What is tracked:

| Event | Fired from | Why this one |
|---|---|---|
| `source_added` | `SourcesViewModel` (all three add paths) | Funnel start: does anyone actually get through the add form? |
| `source_synced` | `SourcesViewModel.syncNow`, `RefreshWorker` | Tagged `trigger: add / manual / scheduled` — whether a playlist can sync at all is the single biggest churn risk in an app like this |
| `content_played` | `PlaybackQueue.play()` | One funnel for channels, movies, series and catch-up alike — see its doc comment for why this is the one place that needed the call |
| `playback_failed` | `PlayerEngine` (both the immediate-failure and retries-exhausted paths) | Fires once per error a viewer actually saw, tagged with the stable string-resource name of the message shown, e.g. `player_error_timeout` — not a free-text string, so it stays a stable, low-cardinality property |
| `favorite_toggled` | `LiveViewModel.toggleFavorite` | |
| `parental_pin_set` | `SettingsViewModel.setPin` / `clearPin` | Records only `enabled: true/false` — the PIN itself never reaches this call |
| `sharing_consent` | `MainScreen`'s `ConsentDialog` callbacks | Opt-in rate for the sharing feature above, complementary to Pawns' own internal tracking |
| `sharing_toggle` | Settings → Internet sharing row | Later opt-outs and opt-ins. `sharing_consent` only ever records the *first* answer, so without this the funnel shows opt-ins and never opt-outs, and active sharers look permanently overstated |

### Verifying it on a device with no browser

PostHog's own SDK-level `debug` logging did not surface anything readable over
`adb logcat` in testing. Every call through `IptvAnalytics.event()` /
`.screen()` is mirrored into the app's existing Settings → Diagnostics log
instead (same ring buffer documented under **Diagnostics** below), which is
exactly the surface this app already has for confirming what it just did on a
TV box with no dev tools. Verified end-to-end on a real Fire TV Stick this
way: screen changes and a `source_synced` failure both showed up correctly
tagged, live, from adb-driven remote input.

### Privacy

`sessionReplay` is unconditionally off. The screen this app spends most of its
time on is a full-screen video player; recording it would be a proxy for
someone's actual viewing habits — which channels, for how long — well beyond
what the events above already capture in aggregate, and IPTV content is
exactly the category most likely to carry copyright or privacy sensitivity.

---

## Focus behaviour (and why `focusRestorer` is not used)

Losing focus is the worst failure mode a TV app has. There is no pointer to
recover with: if nothing is focused, every button on the remote does nothing and
the app is indistinguishable from frozen. Two bugs of exactly that kind were
found and fixed on hardware.

**`Modifier.focusRestorer()` was the cause and has been removed everywhere.** It
is the obvious tool for "come back to the row I was on", and on this Compose
version it loses focus outright. With two adjacent restorer groups — precisely
the Live screen's category column beside its channel column — pressing left out
of the channel list left *nothing* focused. Confirmed with `uiautomator dump`:
nodes reporting `focused="true"` went from one to zero on that keypress and
stayed at zero. Its `onRestoreFailed` parameter is worse still; pointed at a row
inside a lazy list it crashed with `IllegalStateException: Release should only
be called once`, because the restorer pins the item it intends to restore and
the failure path can release that pin twice.

`Modifier.tvFocusGroup()` is now a plain `focusGroup()`. Compose's focus search
is geometric, so moving left from a channel lands on the category at the same
height and moving right comes back to a channel at the same height — which is
what a viewer expects from a two-column layout anyway.

Two related fixes came out of the same investigation:

- **The channel column is wrapped in `key(selectedCategory)`.** Its scroll
  position used to carry across categories despite a comment claiming otherwise,
  so switching from a 4,000-channel group to a 20-channel one left the list
  scrolled past the end of the new content.
- **The channel list requests focus when it appears.** Opening a channel
  destroys this screen's composition — the player replaces it rather than
  stacking over it, to keep the video surface alive — so returning built it
  fresh with focus nowhere. The remote ignored the first press after every
  channel watched.

**Back from playback takes one press, not three.** The rule is that Back closes
a *menu* if one is open and otherwise leaves playback. The transport bar is not
a menu: it appears by itself whenever a channel opens and fades on its own, so
counting it as a layer to dismiss meant Back never exited first time, and from
the channel overlay it took three presses.

---

## Performance

Profiled on the reference device — a Thomson 240G: 2 GB RAM (≈138 MB free),
256 MB heap cap, Amlogic SoC, Android 14 — against a real 4,091-channel
playlist with a 336,000-programme EPG. All figures below are from the
`benchmark` variant, since debug numbers are meaningless (see **Build**).

Method: `dumpsys gfxinfo <pkg> framestats`, parsed per frame. The useful split
is `AnimationStart → PerformTraversalsStart`, which is where Compose does its
recomposition *and* its measure/layout, versus the render thread. Two traps
worth knowing about, both of which produced confidently wrong answers first
time round:

- **`adb shell input keyevent` costs ~137 ms per invocation** on this class of
  device, because each one spawns a process. A `for` loop of them measures
  process startup, not the app. Send every key in a single `input keyevent
  20 20 20 …` instead.
- **Check which list you are actually scrolling.** The first round of
  measurements moved focus into the 39-item *category* column rather than the
  4,091-row channel list, which understated the real cost by half and made two
  separate fixes look like no-ops.

### What was actually costing time

| Change | UI thread, p50 | Verdict |
|---|---|---|
| Baseline (channel list, held D-pad) | ~40 ms | 2.4× over the 16.7 ms budget |
| Narrow the focused-channel state read to the detail panel | 43.6 → 39.9 ms | ~8%. Real but minor — strong skipping already stopped the list recomposing |
| Memoise + explicitly size the Coil `ImageRequest` | within noise | No measurable gain; kept on principle |
| **Suspend artwork loading while the list scrolls** | **39.9 → 26.5 ms** | **~33%, reproducible across runs** |

Removing per-row artwork *entirely* bottomed out at 25.3 ms, so suspending it
during scroll captures nearly all of the available win while keeping the
images. It is only acceptable because the fallback is real: rows keep their
initials-over-a-tinted-block, so a fast scroll reads as deliberate.

Scrolling is still over budget at ~26 ms. The remainder is spread thinly across
tv-material's `Surface` and the LazyColumn's own work rather than sitting in
any one place, so the next honest step is a Perfetto trace, not another guess.

### Storage

The guide table was the single largest resource problem, and nothing about it
was visible from the UI:

| | Before | After |
|---|---|---|
| Database file | 156 MB | 56 MB |
| Programme rows | 336,617 | 100,879 |
| Rows already expired | 232,993 (69%) | 47 |

XMLTV feeds ship several days of *history* — the reference feed spanned 4.6
days, three of them already over — and the app stored all of it. None of it can
ever be displayed. Expired rows are now dropped at import rather than written
and deleted, the purge is scoped per source so it can use the
`(sourceId, endUtc)` index instead of scanning the table, and it runs at
start-up and from the refresh worker rather than only at the tail of a
successful import. `DatabaseMaintenance` then compacts the file, since deleting
rows in SQLite frees pages without shrinking anything.

Channels, favourites, watch history and all current/future listings are
untouched: 2,638 programmes were on air at the moment of verification.

### Other measured facts

- Cold start: **736 ms** (budget was 3 s).
- PSS during live 1080p playback: **110 MB** (budget was 150 MB idle).
- The video surface is a `SurfaceView` again. It had been switched to a
  `TextureView` while chasing a black-picture bug whose real cause turned out
  to be an unattached surface, so it was paying for a full-screen GPU composite
  per frame to work around something it never fixed. Settings →
  *Compatibility video surface* restores the TextureView for the genuine
  firmware cases.
- Coil is configured rather than left on defaults: a 24 MB memory cache instead
  of 25% of the heap cap (≈64 MB on this device), a 64 MB disk cache, and
  `respectCacheHeaders(false)` — provider logo hosts routinely send `no-cache`,
  which meant re-fetching hundreds of images on every cold start.

---

## Testing

Unit tests cover the parsing and matching logic, which is where the real
complexity is: `M3uParserTest` is a catalogue of malformed input observed in
real playlists, plus `XmltvTimeTest`, `XtreamBaseUrlTest`,
`CategoryClassifierTest`, `ChannelNameMatchingTest`, `StableHashTest`.

```bash
./gradlew :app:testSideloadDebugUnitTest      # 43 tests — same suite passes identically on :app:testStoreDebugUnitTest
```

The app was also driven end to end on an Android TV emulator (API 34, 1080p)
against a local server serving a deliberately malformed playlist and a generated
XMLTV feed. Verified: cold start **1.27 s** (budget: 3 s); import of a playlist
with a missing URL, a bad scheme and a duplicate → **5 channels kept, 2 skipped,
1 duplicate removed**, reported to the user; EPG auto-discovered from the
`url-tvg` header; 72 programmes parsed and matched to channels *by display name*
for entries with no `tvg-id`; now/next with progress; category import including
adult classification; catch-up flag; and the playback error path showing
*"The provider returned 'not found' for this stream (HTTP 404)"* with Try
again / Next channel / Back rather than a crash or a spinner.

Bugs found by running it that compiling would never have surfaced, all fixed:
cleartext HTTP blocked by the platform default (the app was completely
unusable), every cold start redirecting to the add-playlist form because the
redirect fired before Room's first emission, text-field focus being inescapable
with a D-pad, and — the worst of them — **video never appearing while audio
played perfectly**.

That last one is worth reading before touching `PlayerScreen.kt`. The surface
was attached by reading `service.player()` inside `AndroidView`'s `update`
block. That is a plain method call, not snapshot state, so when it returned
null — which it does until the engine lazily builds its `ExoPlayer` on the
first `open()` — the block never re-ran and the surface was never attached to
anything. Frames were decoded correctly and thrown away, and
`SurfaceFlinger` showed the giveaway: a correctly-sized video layer with
`activeBuffer=[0x0]` and `queued-frames=0`. The fix is
`PlayerEngine.playerInstance`, a `StateFlow<Player?>` the UI collects, so the
attach re-runs whenever the player is created or rebuilt.

It presented exactly like a device driver fault, and on the box it was found on
there was a real one underneath it, which is what made it expensive to
diagnose. Two lessons are baked into the code now: `onRenderedFirstFrame` and
the selected decoder are logged to the diagnostics ring buffer (see
`PlayerEngine`), because "did a frame ever reach the surface" is the single
question that separates an app bug from a device bug; and anything the player
needs from the service is exposed as observable state rather than a getter.

**Still to do — real hardware.** The device matrix in the brief has not been
run, and an emulator does not represent it. Priorities:

| Device | What it proves |
|---|---|
| Fire TV Stick Lite | The 1 GB RAM baseline; the memory budget and decoder fallback |
| Fire TV Stick 4K Max, Fire TV Cube | Fire OS remote mapping, HEVC/4K |
| Chromecast with Google TV | Google TV launcher integration, the certification baseline |
| NVIDIA Shield | High-end baseline |
| A budget generic Android TV box | Older Android, weak CPU, non-standard launchers, frequently no document picker |

**Verified on hardware:** a Thomson 240G (SEI Robotics, Amlogic, 32-bit ARM) on
Android 14. Live playback works with the hardware decoder
(`c2.amlogic.avc.decoder`) at 1080p, playlist and EPG import work against a real
provider (4,091 channels, 435,347 programmes), and cold start is ~3.3 s on that
class of hardware.

One caveat worth knowing: on the same box running **Android 12**, video could
not play at all — its Codec2 HAL failed surface negotiation outright
(`setOutputSurface -- failed to set consumer usage (6/BAD_INDEX)`), for every
app, with both hardware and software decoders. The vendor's Android 14 update
fixed it. If a user reports no picture, check the diagnostics log first: a
missing "First video frame rendered" alongside a healthy decoder line points at
the device, and their firmware version is the first thing to ask for.

Test cases to prioritise there: a playlist with 10,000+ channels (the parser is
tested at that size, the UI is not, on hardware); a stream dropped mid-playback
(exercises the reconnect backoff, which has only been reasoned about); app
resume after days idle; and remote button mapping on both platforms' remotes.

---

## Known gaps

- **No multi-user profiles.** Phase 3's optional item. Favourites, history and
  settings are per-device.
- **`ACTION_OPEN_DOCUMENT` is unavailable on some TV devices.** Bare AOSP boxes
  frequently ship no document picker, and launching one throws. Both call sites
  are guarded: export falls back to the app's external files directory and
  reports the path, import and file-picking explain themselves and point at the
  URL option. There is no built-in file browser; adding one for USB sticks is a
  reasonable follow-up for Fire TV.
- **External subtitle files (SRT/VTT) are not wired to a source.** Embedded
  audio and subtitle tracks — which is what IPTV streams actually carry — are
  fully supported and selectable.
- **Stalker/MAC portals** are not implemented (flagged optional/phase 2 in the
  brief).
- **The guide pages rather than scrolls horizontally.** Deliberate; see the
  class doc in `GuideScreen.kt`.
- **Focus order on the add-playlist form** puts Down from the last field on
  Cancel rather than Save. Cosmetic, one `focusProperties` away.

## Deliberately not built

Phase 5's licensing/activation module is out of scope and was not started. It
changes the backend scope significantly — device activation, trial periods, a
license validation server — and none of it should be built before the commercial
model is decided. The companion mobile app is likewise unstarted; note that
`ConfigBackup` already gives you playlist transfer between devices, which is the
main pain it was meant to solve.

---

## Assets

Launcher icons, the TV banner and store artwork are generated, not hand-cut:

```bash
java tools/GenerateAssets.java
```

Writes `app/src/main/res/mipmap-*`, `drawable-xhdpi/tv_banner.png` and `store/`
(Play 512 icon and 1280×720 TV banner; Amazon 114/512 icons and feature
graphic). Do not hand-edit those files — change the generator.

The wordmark it draws is a constant (`GenerateAssets.WORDMARK`) checked against
`res/values/strings.xml`'s `app_name`, sized to fit rather than assumed to —
the two drifted once already: everything under `store/` and the TV banner
itself kept shipping "IPTV Player" for months after the app was renamed to
"IPTV Brother Player", because the original code was a `drawString` sized for
that specific, shorter string. Nothing failed when the name changed elsewhere;
it just silently kept drawing the old one.

`store/screenshots/` holds four 1920×1080 captures from the TV emulator —
Live TV, playback, the EPG guide, Settings — taken against synthetic demo
content (generic channel names, initials-only logos, public-domain test video)
built solely for this purpose and not shipped or committed anywhere. Real
broadcaster names and logos never appear in anything under `store/`, on
purpose: this app ships with no content or provider of its own, and depicting
real channels in its own marketing material would misrepresent that.
