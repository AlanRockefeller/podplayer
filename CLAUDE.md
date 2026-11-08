# Podplayer (working name)

Open-source (MPL-2.0) Android podcast player, built first for one owner's daily use.
A private translation server arrives in Milestone 3; the app must always work fully without any server.

## Read at the start of every session
1. `docs/PROGRESS.md`: where things stand, open questions, decisions already made.
2. The current session in `docs/milestones/M<n>.md` (the owner's prompt names it).
3. Only the parts of `docs/SPEC.md` and `docs/LESSONS.md` that the session points to.

## Non-negotiable rules

### Security
- Treat every feed, audio file, image, show note and URL as hostile input written by an attacker.
- Never decode untrusted image bytes outside the isolated image process (`:core:imagesandbox`).
  Outside that module: no `BitmapFactory`, `ImageDecoder`, `MediaMetadataRetriever` artwork,
  and no Coil network fetching. Coil may only load cache files the sandbox produced.
  Media3 artwork URIs must point at those cache files, never at remote URLs.
- Never use `WebView`. Show notes go through the sanitizer and render as native text.
- All network traffic goes through the single gated OkHttp client in `:core:net`,
  including ExoPlayer (via `OkHttpDataSource`). No other HTTP clients anywhere.
- Feed-supplied URLs: `http`/`https` only. Links shown to the user: `http`, `https`, `mailto` only.
- Feed-supplied URLs must not reach private, loopback, link-local or unique-local addresses
  (checked after DNS and after every redirect) unless the owner typed that exact feed URL.
- Every parser has byte, item-count and time limits. On breach: reject, keep the last good data, log why.
- Feed URLs are secrets (private feeds carry tokens). Never log them unredacted
  (strip query, fragment and userinfo); never send them anywhere except their own host.
- No native code of our own and no FFmpeg. Decoding uses platform codecs through Media3.
- Never use `android.media.MediaExtractor` or `MediaMetadataRetriever` on untrusted files in the app process;
  use Media3's JVM extractors (e.g. `MetadataRetriever`) for probing.

### Playback
- The Media3 session/player is the single source of truth for playback state.
  UI, notification, widget and Android Auto only observe it and send commands.
- The database queue is the source of truth for Up Next; the player playlist is a projection of it.
- Never lose a position: persist on pause, stop, seek, focus loss, every 10 s while playing, and on service destroy.
- Pause when audio becomes noisy (headphones or Bluetooth disconnect). Never auto-start on Bluetooth connect.
- Every playback rule gets an automated test before its implementation.

### Privacy and openness
- No analytics, telemetry, ad SDKs, Firebase, Google Play services, or any closed-source dependency.
  CI fails if one appears.
- Network calls only to: hosts of feeds/media/images the owner subscribed to, Apple's podcast search
  when the owner searches, and (from M2) the owner's own server.

### Licensing
- Project license: MPL-2.0. Put the MPL-2.0 "Exhibit A" source-file notice (copy it from the license text
  at https://www.mozilla.org/en-US/MPL/2.0/) at the top of every source file.
- Never copy code from GPL projects (AntennaPod, Podcini, TrimPlayer and other AntennaPod forks).
  Reading them to understand a problem is fine; write your own code. A comment linking the issue is welcome.
- Pocket Casts code (MPL-2.0) may be reused only as whole files with their original headers;
  record each one in `docs/THIRD_PARTY.md`.
- New dependencies only from the allowlist in `docs/SPEC.md` (section "Dependencies").
  Anything else: stop and ask under "Questions for owner" in `docs/PROGRESS.md`.

## Architecture (Gradle modules)

| Module | Kind | Holds |
| --- | --- | --- |
| `:app` | Android app | Compose UI, navigation, `AppGraph` (manual DI), settings screens |
| `:core:model` | Plain Kotlin | Domain types: Feed, Episode, Enclosure, QueueEntry, PlaybackRecord, EpisodeAction |
| `:core:feed` | Plain Kotlin | RSS/Atom/Podcasting 2.0 parsing, OPML, show-notes sanitizer, episode identity rules |
| `:core:net` | Plain Kotlin | Gated OkHttp client, URL policy, address filter, conditional GET, range download engine |
| `:core:data` | Android lib | Room database, DAOs, repositories, migrations, backup/restore, logs |
| `:core:imagesandbox` | Android lib | Isolated decode service, client, image cache, Coil setup |
| `:core:jobs` | Android lib | WorkManager refresh and download workers |
| `:playback` | Android lib | `MediaLibraryService`, ExoPlayer setup, queue projection, sleep timer, resumption, Android Auto tree |
| `:core:testing` | Plain Kotlin | Fixtures, hostile corpus, fakes |

Plain Kotlin modules must not import `android.*`, so they can move to Kotlin Multiplatform later.

## Conventions
- Kotlin, coroutines + Flow, Jetpack Compose, Material 3 with semantic color tokens. No hard-coded colors in screens.
- Manual dependency injection through `AppGraph` in `:app`. No DI framework.
- `minSdk 29`; compile and target the latest stable SDK.
- Look up current stable versions before adding or bumping a library; pin them in `gradle/libs.versions.toml`.
  Never guess a version or an API; check the official docs.
- Bad input never crashes the app: return typed results, log with redaction, show a plain message.
- Debug builds: StrictMode on, and crash on violated invariants so problems get noticed.
- The Developer screen (SPEC §12) lives in the `debug` source set only; nothing in release builds may reach it.
- User-facing strings in `strings.xml`.

## Commands (keep this list accurate as the build grows)
- `./gradlew check`: unit tests, lint, closed-dependency check, forbidden-API check
- `./gradlew :app:installDebug`: install on a connected device

## Working rules
- Plan first. Stay inside the current session's scope; put other ideas under "Later" in `docs/PROGRESS.md`.
- Tests with every change. Run `./gradlew check` before finishing; it must pass.
- Never weaken a rule above to make something work. If blocked, stop and explain under "Questions for owner".
- Before building a module, read the matching rows of `docs/LESSONS.md` and turn each into a test or a
  checklist item.
- End of session: update `docs/PROGRESS.md` (done, tests added, deviations from the spec and why, manual checks
  the owner must do, questions), commit on a branch named `m<milestone>-s<session>-<slug>`, and open a PR.
- Keep this file under 150 lines; details belong in `docs/`.
