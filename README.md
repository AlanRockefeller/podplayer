# Podplayer (working name)

An open-source (MPL-2.0) Android podcast player built first for one owner's daily listening:
fast, offline-first and private. It has no analytics, no Google Play services and no closed-source
dependencies, and it never needs a server for ordinary listening.

Status: Milestone 1 is built and in the owner's daily use (M1-S11, hardening and handoff, 2026-11-17).
Subscribe by search, feed address, OPML or a pasted list; refresh, download and auto-download with clean-up;
play with a queue, per-show speed, sleep timer and resumption; stars, backups and logs. Android Auto (M1-S10)
is deferred by the owner. Debug builds also have a Developer screen (the "Developer" button in the top bar).
`docs/PROGRESS.md` has the M1 acceptance checklist results and the manual checks left for the owner.

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
violated, so problems get noticed early. They also have the Developer screen, which lives in the `debug`
source set only: release builds don't contain it (`app/src/testRelease` checks that every class and resource
declared under `app/src/debug` is missing from the release variant, and the forbidden-API check refuses any
mention of the screen in `app/src/main` or `app/src/release`).

## Run the checks

```sh
./gradlew check
```

This runs on every push and pull request in GitHub Actions (`.github/workflows/ci.yml`). It includes:

| Check | Task | What fails it |
| --- | --- | --- |
| Unit tests | `test` | Any failing test, including the guardrails' own tests in `build-logic`; `:app` runs its unit tests for the release variant too |
| Lint | `lint` | Any lint warning or error. Plain Kotlin modules are linted against minSdk through `:app` |
| License headers | `checkLicenseHeaders` | A `.kt`, `.kts`, `.java` or `.aidl` file that doesn't start with the MPL-2.0 notice |
| Forbidden APIs | `checkForbiddenApis` | WebView, `MediaExtractor`, `MediaMetadataRetriever`, image decoders outside `:core:imagesandbox`'s isolated-process `sandbox` package, a second Coil image loader, Media3 artwork bytes or Media3's own bitmap loaders, other HTTP clients, `OkHttpClient` outside `:core:net`, jsoup's HTTP client, jsoup outside `:core:feed`, Coil networking, `android.*` in plain Kotlin modules, hex colors in screens, the Developer screen named outside the debug source set |
| Closed dependencies | `checkClosedDependencies` | Anything from the SPEC §2 "Never" list (Google Play services, Firebase, crash, analytics and ad SDKs, WebView libraries, FFmpeg and native media libraries) on any classpath, direct or transitive |
| Dependency verification | (every build) | A downloaded artifact whose SHA-256 doesn't match `gradle/verification-metadata.xml` |
| Fuzz regression | `:core:feed:fuzzRegression` | The feed or OPML parser throwing, or breaking a limit, on a seed or a saved finding |

The guardrails live in `build-logic/src/main/kotlin/us/dikarya/podplayer/buildlogic/guardrails/`.
Each one has tests that run it against bad samples (`build-logic/src/test/resources/samples/`) and
against throwaway Gradle projects, proving that it fails the build.

CI also fuzzes each parser for 60 s after `check` (see "Fuzzing").

## Fuzzing

The feed parser (RSS, Atom, Podcasting 2.0) and the OPML parser have Jazzer fuzz targets in `:core:feed`'s
`fuzz` source set (`core/feed/src/fuzz/`, JUnit 5; every other test is JUnit 4). Each target feeds arbitrary
bytes to the parser and fails if it throws, returns more items than its limit, or lets a non-http(s) URL
through. Seeds are the hostile corpus and samples in `core/testing/src/main/resources`, plus saved findings.

```sh
./gradlew :core:feed:fuzzFeed                       # fuzz the feed parser for 60 s
./gradlew :core:feed:fuzzOpml -PfuzzDuration=30m    # a longer local run
./gradlew :core:feed:fuzz -PfuzzDuration=2h         # both, one after the other
./gradlew :core:feed:fuzzRegression                 # replay seeds and findings only (part of check)
```

Jazzer fuzzes one target per JVM, so each target has its own task. The corpus it grows is kept in `core/feed/build/fuzz/.cifuzz-corpus/` between local runs (delete it
to start over). A finding fails the task and writes the input to `core/feed/build/fuzz/crash-<hash>`; in CI
it is in the uploaded "reports" artifact. To keep it as a regression test, copy the file to
`core/feed/src/fuzz/findings/feed/` or `.../opml/`, fix the bug with a named unit test, and commit both.

## Dependency verification

Gradle checks the SHA-256 of every dependency and plugin against `gradle/verification-metadata.xml`.
After adding or bumping a dependency (only from the allowlist in `docs/SPEC.md` §2), regenerate it:

```sh
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-configuration-cache \
    --write-verification-metadata sha256 check assembleDebug assembleRelease
```

Use an empty `GRADLE_USER_HOME` as shown. With a warm local cache, Gradle skips some plugin and
BOM resolutions and leaves them out of the file, and CI, which starts with no cache, then fails.

Then review the diff before committing. New entries should only be the libraries you meant to add
and their transitive dependencies. If an existing checksum changes for the same version, stop:
that means an artifact was replaced upstream.

Robolectric's Android jar (`org.robolectric:android-all-instrumented`) is a Gradle dependency too:
the `podplayer.android.robolectric` convention fetches it, so it is verified like everything else,
and runs Robolectric offline. Bump its version (catalog `robolectricAndroidAll`) with Robolectric.

Source and javadoc jars, which Android Studio downloads for browsing, are trusted without checksums.
The metadata includes Linux-specific artifacts such as `aapt2`. Building on macOS or Windows needs
one regeneration run there.

## Release signing

The signing key never enters the repository. It lives on the owner's machine:

| File | What |
| --- | --- |
| `~/keystores/podplayer/podplayer-release.p12` | The keystore (PKCS12, RSA 4096, alias `podplayer`) |
| `~/keystores/podplayer/signing.properties` | What Gradle reads: `storeFile`, `storePassword`, `keyAlias`, `keyPassword` |
| `~/keystores/podplayer/podplayer-signing-BACKUP.txt` | Offsite backup: password, fingerprints, base64 keystore, restore steps |

When `signing.properties` exists, debug and release builds are both signed with this key. That
way the debug build used during M1 can be updated in place by a release build, keeping its data.
Point Gradle at another location with `-Ppodplayer.signing.file=/path/to/signing.properties`
(or set it in `~/.gradle/gradle.properties`). Without the file, as on CI, release builds come out
unsigned and debug builds use the standard Android debug key.

The certificate's SHA-256 fingerprint is
`0F:C9:66:04:4F:25:30:68:1D:C7:05:09:B6:95:16:CF:A2:D6:DC:55:28:C3:48:B6:4C:99:82:C1:73:DB:27:64`.
Check an APK with `apksigner verify --print-certs app-release.apk`.

If the key is lost, installed copies can never be updated again. Keep the backup file offsite.

## Project layout

| Module | Holds |
| --- | --- |
| `:app` | Compose UI, navigation, `AppGraph` (manual DI), settings |
| `:core:model` | Domain types, `assertInvariant` |
| `:core:feed` | RSS/Atom/Podcasting 2.0 parsing with the XML pre-pass and limits, episode identity and merge, OPML import and export, show-notes sanitizer |
| `:core:net` | The single gated HTTP client (`NetGate`), URL policy and redaction, address filter, range download engine |
| `:core:data` | Room database (schemas in `core/data/schemas/`), repositories, start-up integrity check, backups and restore, settings, rolling redacted logs and crash files |
| `:core:imagesandbox` | The isolated decoder service (`android:isolatedProcess`, shared memory in and out), its client (5 s limit, crash and hang handling), the check of what it sends back, the artwork pipeline (gated fetch, sandbox, cache, failure memory), the app's only Coil loader, and the read-only artwork content provider for Media3 and Android Auto |
| `:core:jobs` | WorkManager work: the daily backup, feed refresh (periodic and on request, per-feed backoff), downloads (resume, storage cap, Wi-Fi only, foreground `dataSync`) and the Media3-extractor probe that accepts a finished download |
| `:playback` | Media3 playback service and Android Auto (M1-S7, S10); the artwork bitmap loader (M1-S6) |
| `:core:testing` | Fixtures for tests: the hostile corpus and synthetic sample feeds (`src/main/resources/corpus`, `samples`), generators for the big ones |
| `build-logic` | Convention plugins and the guardrail checks |

Versions are pinned in `gradle/libs.versions.toml`. Project rules are in `CLAUDE.md`, and the
specification is in `docs/SPEC.md`.

## License

Mozilla Public License 2.0; see `LICENSE`. Third-party files and notable dependencies are listed in
`docs/THIRD_PARTY.md`.
