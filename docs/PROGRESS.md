# Progress

Claude Code updates this file at the end of every session. Keep entries short and honest:
a reviewer reads it to see what was really done.

## Current state
- Milestone: M1
- Next session: M1-S2
- Build status: `./gradlew check` and `assembleRelease` green locally (2026-11-08). The CI result is on the M1-S1 PR.

## Session log
<!-- Newest first. Copy this block for each session. -->
<!--
### M1-S<n> <title> (<date>)
- Done:
- Tests added:
- Deviations from the spec, and why:
- Manual checks for the owner:
- PR:
-->

### M1-S1 Project skeleton and guardrails (2026-11-08)
- Done:
  - Gradle 9.9.0 wrapper (distribution SHA-256 pinned), AGP 9.5.1 with built-in Kotlin, Kotlin 2.4.21, version
    catalog with every allowlisted library at its current stable version (looked up 2026-11-08).
  - All nine modules from CLAUDE.md. They're empty except `:core:model` (`assertInvariant`) and `:app`. Module
    dependencies are declared now: feed/net → model; data → model, feed; jobs → data, net, feed; playback → data,
    net, model; imagesandbox → net; app → all.
  - Convention plugins in `build-logic/`: compileSdk/targetSdk 37, minSdk 29, JDK 21 toolchain and Java 17 bytecode
    for every module, set in one place (`Conventions.kt`). Plain modules compile with `-Xjdk-release=17` and are
    linted for NewApi through `:app` (`checkDependencies`).
  - `LICENSE` (MPL-2.0 text from mozilla.org). The Exhibit A notice is on every `.kt`/`.kts`, the XML resources,
    TOML, properties and the CI YAML.
  - Guardrails in `./gradlew check`: `checkLicenseHeaders` (root), `checkForbiddenApis` and
    `checkClosedDependencies` (every module plus root), Gradle dependency verification (`gradle/verification-metadata.xml`,
    401 components, sha256), lint with warnings as errors.
  - GitHub Actions: `check` plus `assembleRelease` on every push and PR. Actions are pinned by commit SHA and the
    wrapper jar is validated.
  - `:app`: semantic color tokens and the Material 3 mapping; theme setting (Dark default, Light, Use system) in
    DataStore. The first frame waits for the saved theme (1 s cap). Bottom navigation (Home, Discover, Library,
    Queue placeholders), a Settings icon leading to Settings › Appearance, an empty mini-player slot, and edge-to-edge.
  - Debug builds: StrictMode, and `assertInvariant` crashes. Release builds: logs only. Both live in per-build-type
    source sets, so StrictMode code is not in release.
  - `README.md` (build, install, checks, verification regeneration), `docs/THIRD_PARTY.md`.
- Tests added (61, all passing):
  - `build-logic`: HeaderCheckTest (10), ForbiddenApiCheckTest (16), ClosedDependencyCheckTest (6), and
    GuardrailFunctionalTest (10). The functional tests run the real tasks in throwaway Gradle projects. They prove
    that the header, forbidden-API and closed-dependency checks fail the build on bad samples, including a
    transitive banned dependency and one that can't be resolved, and pass on clean code. Bad samples are in
    `build-logic/src/test/resources/samples/*.sample`, outside every scanned set.
  - `:app`: AppSettingsTest (7): fresh install is Dark, Light persists across a store restart, a corrupt file falls
    back to Dark. NightModeMappingTest (3). ColorTokensTest (5): WCAG AA on every surface, tokens match the spec,
    and the XML window backgrounds match the tokens.
  - `:core:model`: InvariantsTest (4).
  - Also checked by hand: a `WebView` import added to `:app` fails `./gradlew check`, and a corrupted checksum in
    the verification metadata fails the build. Both were reverted.
- Deviations from the spec, and why:
  - Light `textQuiet` is #5C6E80, not #617385. The spec value is 4.26:1 on surfaceElevated, below WCAG AA; the spec
    allows adjusting. All other tokens are as specified.
  - The forbidden-API check goes beyond SPEC §14 with rules taken from CLAUDE.md: other HTTP clients
    (`HttpURLConnection`, `openConnection`, `openStream`), Coil networking, `import android.` in plain modules, and
    hex/named colors in `:app` outside `ui/theme`. It's a strict token scan that includes comments, so even a comment
    naming `WebView` fails it.
  - Settings › Appearance was built now, in minimal form; the full Settings arrive in S9. The "Light persists across
    restart" check needs a way to switch.
  - StrictMode: the thread policy crashes (penaltyDeath). The VM policy only logs, because framework and library
    internals trigger it. Untagged-socket detection is off (OkHttp doesn't tag sockets).
  - Android cloud backup is off (`allowBackup=false`, plus extraction rules that exclude everything from cloud
    backup), because feed URLs can carry tokens. Device-to-device transfer is still allowed. Our own backups are S4.
  - The header check enforces `.kt`, `.kts`, `.java` and `.aidl` (AIDL arrives in S6). XML, TOML and YAML have the
    notice, but it isn't enforced. The six Material Symbols icons are Apache-2.0 and keep no MPL notice (THIRD_PARTY.md).
  - Lint ignores the time-dependent checks (`GradleDependency`, `NewerVersionAvailable`,
    `AndroidGradlePluginVersion`, `OldTargetApi`), so CI doesn't fail just because upstream shipped a release.
  - AndroidX ships two prebuilt native libraries inside its AARs (Compose `libandroidx.graphics.path.so`,
    DataStore `libdatastore_shared_counter.so`). They aren't our native code; they're listed in THIRD_PARTY.md.
- Manual checks for the owner (not run: no phone or emulator image was available in this session):
  - CI is green on the PR.
  - `./gradlew :app:installDebug`: the app opens dark, with Home/Discover/Library/Queue and a Settings icon.
    Watch for a StrictMode crash on start (`adb logcat -s StrictMode AndroidRuntime`).
  - Settings › Light → force stop → relaunch: still Light. On Android 12+ the starting window should already be
    light from the second launch on.
  - Settings › Use system setting: toggling dark mode in the system quick settings switches the app while it's open.
  - TalkBack reads "Settings" on the gear, "Back" on the arrow, and the tab names on the bottom bar.
- PR: see the `m1-s1-skeleton` pull request.

## Decisions made during the build
- applicationId and package root: `us.dikarya.podplayer` (owner's domain dikarya.us). No `.debug` suffix, so M1 data
  carries over to a release build signed with the same key. (Owner, M1-S1 planning.)
- JDK 21 compiles everything; bytecode is Java 17. JDK 21 is what's installed. Java 17 is the safe level for
  Android consumers, and `-Xjdk-release=17` keeps plain modules off JDK 21-only APIs that crash on old Android.
- No toolchain auto-download (no foojay plugin): the daemon is pinned to JDK 21 in `gradle/gradle-daemon-jvm.properties`.
- JUnit 4 for now (Robolectric and Android tests use it). Jazzer (S11) may bring JUnit 5 for the fuzz targets only.
- compileSdk 37 (major release). Minor SDK levels 37.1/37.2 aren't needed.
- Theme settings live in `:app` (`settings/AppSettings.kt`, DataStore file `datastore/settings.preferences_pb`).
- No flash of the wrong theme: no splash library. An `OnPreDrawListener` holds the first frame until DataStore has
  answered, and `UiModeManager.setApplicationNightMode` (API 31+) makes the system starting window match the saved
  theme from the second launch on. On API 29–30, and on the very first launch, the starting window follows the
  system theme for a moment.
- Nav icons are Material Symbols vector drawables copied into `res/drawable`, not the Compose icons artifact.
- The closed-dependency check is a denylist (SPEC §2 "Never" list plus named crash, analytics, ad, WebView, FFmpeg and
  native-media groups, and `coil-network-*`). It covers every resolvable classpath of every module (all variants,
  unit and instrumented tests, compiler plugins, lint checks) and the root build script classpath, which holds
  the Gradle plugins.

## Questions for owner
<!-- Blockers or choices that need the owner. Do not work around them. -->
- None blocking. Before the first release build you'll install: how do you want to keep the signing key? It must
  stay the same forever, or the app can't update over itself.

## Later
<!-- Ideas and fixes outside the current session's scope. -->
- S2: add a forbidden-API rule that `OkHttpClient(` / `OkHttpClient.Builder(` may only appear in `:core:net`.
- Enforce the allowlist mechanically: direct dependencies must come from catalog entries mapped to SPEC §2.
- S4: settings are part of backups, so `AppSettings` may move to `:core:data`.
- Release builds: turn on R8 (minify and resource shrinking) with keep rules, and add a signing config once the key
  question is answered.
- A Robolectric Compose test for the shell (four tabs, Settings icon, the theme switch recomposing), once
  Robolectric arrives with S4.
- Enforce the MPL notice in XML, TOML and YAML files (it's placed there now but not checked).

## Known issues
<!-- Bugs found but not yet fixed, with how to reproduce. -->
- None known. The app hasn't run on a device yet (see the manual checks above).
