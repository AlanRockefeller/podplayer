# Podplayer (working name)

An open-source (MPL-2.0) Android podcast player built first for one owner's daily listening:
fast, offline-first and private. It has no analytics, no Google Play services and no closed-source
dependencies, and it never needs a server for ordinary listening.

Status: Milestone 1, session 1. The project skeleton, the guardrail checks and a dark app shell
exist; podcasts don't play yet. See `docs/PROGRESS.md` for where things stand and
`docs/milestones/M1.md` for what comes next.

## Build

You need:
- JDK 21 (the Gradle daemon and every module compile with it; nothing is downloaded automatically)
- The Android SDK with platform `android-37.0` and build tools `36.0.0`, with `ANDROID_HOME` set
  (or `sdk.dir` in `local.properties`)

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

## Install on a phone

1. On the phone, enable Developer options and USB debugging, then connect it by USB and accept the prompt.
2. Check that it shows up: `adb devices`
3. Install and start it:

```sh
./gradlew :app:installDebug
adb shell am start -n us.dikarya.podplayer/.MainActivity
```

Debug builds run with StrictMode on, and they crash on purpose when an internal invariant is
violated, so problems get noticed early.

## Run the checks

```sh
./gradlew check
```

This runs on every push and pull request in GitHub Actions (`.github/workflows/ci.yml`). It includes:

| Check | Task | What fails it |
| --- | --- | --- |
| Unit tests | `test` | Any failing test, including the guardrails' own tests in `build-logic` |
| Lint | `lint` | Any lint warning or error. Plain Kotlin modules are linted against minSdk through `:app` |
| License headers | `checkLicenseHeaders` | A `.kt`, `.kts`, `.java` or `.aidl` file that doesn't start with the MPL-2.0 notice |
| Forbidden APIs | `checkForbiddenApis` | WebView, `MediaExtractor`, `MediaMetadataRetriever`, image decoders outside `:core:imagesandbox`, other HTTP clients, Coil networking, `android.*` in plain Kotlin modules, hex colors in screens |
| Closed dependencies | `checkClosedDependencies` | Anything from the SPEC §2 "Never" list (Google Play services, Firebase, crash, analytics and ad SDKs, WebView libraries, FFmpeg and native media libraries) on any classpath, direct or transitive |
| Dependency verification | (every build) | A downloaded artifact whose SHA-256 doesn't match `gradle/verification-metadata.xml` |

The guardrails live in `build-logic/src/main/kotlin/us/dikarya/podplayer/buildlogic/guardrails/`.
Each one has tests that run it against bad samples (`build-logic/src/test/resources/samples/`) and
against throwaway Gradle projects, proving that it fails the build.

## Dependency verification

Gradle checks the SHA-256 of every dependency and plugin against `gradle/verification-metadata.xml`.
After adding or bumping a dependency (only from the allowlist in `docs/SPEC.md` §2), regenerate it:

```sh
./gradlew --write-verification-metadata sha256 check assembleDebug assembleRelease
```

Then review the diff before committing. New entries should only be the libraries you meant to add
and their transitive dependencies. If an existing checksum changes for the same version, stop:
that means an artifact was replaced upstream.

Source and javadoc jars, which Android Studio downloads for browsing, are trusted without checksums.
The metadata includes Linux-specific artifacts such as `aapt2`. Building on macOS or Windows needs
one regeneration run there.

## Project layout

| Module | Holds |
| --- | --- |
| `:app` | Compose UI, navigation, `AppGraph` (manual DI), settings |
| `:core:model` | Domain types, `assertInvariant` |
| `:core:feed` | Feed and OPML parsing, show-notes sanitizer (M1-S3) |
| `:core:net` | The single gated HTTP client and URL policy (M1-S2) |
| `:core:data` | Database, repositories, backups, logs (M1-S4) |
| `:core:imagesandbox` | Isolated image decoding (M1-S6) |
| `:core:jobs` | Refresh and download workers (M1-S5) |
| `:playback` | Media3 playback service and Android Auto (M1-S7, S10) |
| `:core:testing` | Fixtures and fakes for tests |
| `build-logic` | Convention plugins and the guardrail checks |

Versions are pinned in `gradle/libs.versions.toml`. Project rules are in `CLAUDE.md`, and the
specification is in `docs/SPEC.md`.

## License

Mozilla Public License 2.0; see `LICENSE`. Third-party files and notable dependencies are listed in
`docs/THIRD_PARTY.md`.
