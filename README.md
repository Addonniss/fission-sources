# INFUSION — Android TV

INFUSION is a personal-media application for **Android TV**, built around a
remote-first, D-pad-driven television interface. It plays your own media and the
media your connected services expose, and it keeps metadata in one canonical model
that every screen reads, whoever supplied it.

Android TV is the primary platform. The interface is designed for a ten-foot
display and a hardware remote: focus moves deterministically, the back stack stays
shallow, and every surface is reachable without a pointer.

## What the application does

The capabilities below are implemented in the source in this repository, in the
`:app-tv` shell and the modules it composes.

**Browsing and discovery**

- Five top-level destinations — Home, Jellyfin (when a server is connected),
  Discover, Search and Settings — behind a shared tab bar.
- Home for *your* media: unified Continue Watching across local, add-on and
  Jellyfin progress, an optional spotlight, configurable catalog widgets, and a
  per-profile widget builder.
- Discover for *finding* media: curated and add-on-backed shelves with genre, year,
  rating, provider and network filters.
- Search as a dedicated surface with grouped, provider-independent results.
- Details pages for movies, shows, seasons and episodes, with cast, studios,
  collections, person pages, trailers and artwork.

**Sources**

- **Jellyfin**: connect a server over the LAN, browse its libraries, and use its
  catalog, artwork and streams. Cleartext HTTP is supported for user-consented LAN
  servers; the application's own destination policy still validates every host and
  every resolved address at connect time.
- **Stremio-compatible add-ons**: install by URL or `stremio://` deep link, review
  and consent, enable/disable per profile, and expose the runtime resources
  (catalog, meta, stream, subtitles) through the shared aggregation layer.
- **Acquisition**: Radarr and Sonarr connections for library requests.
- Provider-neutral metadata: TMDB for canonical metadata and artwork, with
  community ratings from several suppliers layered on top.

**Playback**

- Media3/ExoPlayer playback with resume, seek feedback, next-episode handling,
  source selection and bounded fallback, subtitle and audio-track preferences, and
  Dolby Vision display handling.
- A background media session for music playback with the platform media
  notification.

**Personal state**

- Profiles with per-profile watch state, watchlist, personal ratings, and an
  optional "hide watched" presentation policy.
- Watch tracking through **Simkl**, **Trakt** and **MDBList**, all behind one
  provider-neutral tracking boundary.
- A phone-based setup surface: the television exposes a short-lived LAN session so
  a phone can drive configuration without a keyboard.

**Delivery and upkeep**

- A startup-only update check against published releases, with a verified download
  and a hand-off to the system installer — the user always confirms the install.

## Technical stack

| Area | Choice |
| --- | --- |
| Language | Kotlin 2.4.10 (JVM target 11), Java 11 source/target |
| Build | Gradle 8.14.5 (wrapper pinned, distribution checksum verified), AGP 8.13.2 |
| SDK | `compileSdk` 36, `minSdk` 26, `targetSdk` 34 |
| UI | Jetpack Compose with `androidx.tv:material` |
| Media | Media3 1.11.0 (ExoPlayer, Session, UI) |
| Persistence | Room 2.8.4 with explicit migrations |
| HTTP | OkHttp 4.12.0 behind a hardened transport layer |

The module graph is deliberate: `core/*` holds domain models, persistence,
transport and the shared seams; `source/*` holds provider adapters; `tracking/*`
holds the tracker adapters; `feature/*` holds shared product state; `app-tv` is the
shell that composes them. Only `:app-tv` is an application module.

## Repository scope

This repository contains the **production** Android TV application source and the
modules and build files it needs to compile. It is a source snapshot, not a mirror
of the project's development history: the history here is a clean publication
history, and development-only material (research notes, audits, device-lab
harnesses, media corpora, roadmap documents and the mobile shell) is not part of
it.

The snapshot carries the application's executable source, resources and build
configuration without development commentary: source comments are not published.
Code, string literals, resource values and build configuration are unchanged, and
the third-party notices that are legally part of the source are preserved — see
[THIRD-PARTY-SOURCE-NOTICES.md](THIRD-PARTY-SOURCE-NOTICES.md).

Included:

- `app-tv/` — the Android TV shell
- `core/`, `feature/`, `source/`, `tracking/` — the modules in `:app-tv`'s
  dependency closure
- the Gradle wrapper, version catalog, dependency verification metadata, root
  build files and the release version file
- the application's resources, manifests and the project's licence and
  third-party notices

Not included: the Android mobile shell, qualification harnesses, internal
documentation, release engineering tooling, unit and instrumentation test source
sets, and anything device- or operator-specific. This is a production-source
repository: it builds the application, it does not carry its test suites.

## Building

Prerequisites:

- JDK 21
- Android SDK with platform 36 and build-tools 35.x installed
- `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) pointing at the SDK

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app-tv:assembleDebug
```

The debug APK is written to `app-tv/build/outputs/apk/debug/app-tv-debug.apk`.

Every build-time credential seam is optional and fails honestly when absent: an
APK built without them starts, and the sections that need a registration say so
instead of pretending to be connected.

| Variable | Effect when set |
| --- | --- |
| `TMDB_API_KEY` | Enables canonical TMDB metadata and artwork |
| `SIMKL_CLIENT_ID` | Enables the Simkl device-code registration |
| `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` | Enables the Trakt device-code registration |
| `MDBLIST_CLIENT_ID` | Enables the MDBList device-code registration |
| `INFUSION_KEYSTORE_PATH`, `INFUSION_KEYSTORE_PASSWORD`, `INFUSION_KEY_ALIAS`, `INFUSION_KEY_PASSWORD` | Signs debug and release builds with one identity (v2 + v3). When any of the four is missing, `debug` keeps the default debug signer and `release` stays unsigned |

Tests are not part of this repository, so there is no test task to run here.

Verification gates:

```bash
./gradlew verifyPackageIdentity            # applicationId / version identity
./gradlew verifyReleaseVersion             # versionCode monotonicity
```

## Status

Pre-alpha, under active development. The current source snapshot corresponds to
version **0.1.1** (`versionCode` 101). Behaviour, interface details and internal
structure can still change between snapshots; there is no stability guarantee yet.

## Licence

INFUSION's original application code, branding, artwork and release materials are
proprietary. Publishing this source does not grant permission to copy, modify,
redistribute or create derivative works, except where expressly permitted by the
rights holder or applicable law. See [LICENSE](LICENSE).

Third-party components retain their own licences and attributions; see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md),
[THIRD-PARTY-SOURCE-NOTICES.md](THIRD-PARTY-SOURCE-NOTICES.md) and the
[public notices page](https://infusionplayer.stream/notices/).

## Related repositories

- **[infusion-releases](https://github.com/Addonniss/infusion-releases)** — signed,
  installable Android TV builds, release notes and the update channel the
  application checks.
- **[infusion-website](https://github.com/Addonniss/infusion-website)** — the
  source of the public site at <https://infusionplayer.stream>, including the
  download page.

This repository is the **source**; it does not host installable builds. Use
`infusion-releases` for those.

## Security

Please do not report a vulnerability in a public issue. See [SECURITY.md](SECURITY.md)
for the private reporting channel and the scope of what is in scope.
