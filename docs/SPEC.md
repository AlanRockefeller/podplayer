# Engineering spec

The owner's full spec, with rationale and sources, is the "Podcast Player — Revised Spec v0.3" doc.
This file is the build-facing summary. When they disagree, ask in `docs/PROGRESS.md`.

## 1. Product and milestones

A fast, stable, offline-first podcast player for one owner, who uses it daily on a phone, sometimes a second
device, and in a car with Android Auto. Ad skipping (M2) and private translation (M3–M4) come later and must
never be required for ordinary listening.

| Milestone | Outcome | Gate |
| --- | --- | --- |
| M1 Daily driver | Subscribe, refresh, download, play, queue, Android Auto, backups, security foundations | Owner moves daily listening to it; a week passes with nothing lost |
| M2 Ad skipping, sync, audio polish | Chime templates and publisher ad chapters (off by default), owner's server online with gpodder-style sync, skip intro/outro, loudness, chapters, Inbox | No skip removes real content unnoticed |
| M3 Translated transcripts | Translation jobs on owner's server under a monthly budget; translated text over original audio; content anchors; sentence replay; offline packages | Replay lands on the right sentence across copies with different ads |
| M4 Dubbed audio, smarter ad detection | Spanish rendition with 2–3 delivery presets; opt-in server ad detection; cross-episode repetition detection | A few months of use |
| M5 Only if public | Widget, Wear OS, accessibility pass, multi-user server, store and billing | Owner decides |

## 2. Dependencies (allowlist)

Allowed without asking (use current stable versions, pinned in the version catalog):
- Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization (JSON only)
- AndroidX: core, activity, lifecycle, Compose (BOM), Material 3, navigation-compose, Room (with KSP),
  DataStore, WorkManager, startup, test libraries
- Media3: exoplayer, session, datasource-okhttp, inspector (`MetadataRetriever` for probing), test-utils
- OkHttp
- jsoup (show-notes sanitizing only)
- Coil 3 core and compose (no network artifacts)
- Tests: JUnit, Robolectric, Turbine, OkHttp MockWebServer, kxml2 (JVM XmlPullParser for tests),
  Jazzer (fuzzing)

Never: anything under `com.google.android.gms`, `com.google.firebase`, `com.google.android.play`,
crash or analytics SDKs, ad SDKs, WebView-based libraries, native image or media libraries, FFmpeg.

## 3. Network and URL policy (`:core:net`)

One OkHttp client for the whole app, built in `:core:net` and passed everywhere (including ExoPlayer).

- Schemes: feed-supplied URLs `http` or `https` only. Cleartext HTTP is allowed for feeds, media and images
  (many feeds still use it) and the UI marks such feeds "not secure". Our own server is HTTPS only (M2+).
- Address filter: a custom `Dns` rejects any resolved address that is loopback, private (10/8, 172.16/12,
  192.168/16), link-local (169.254/16, fe80::/10), CGNAT (100.64/10), unique-local (fc00::/7), multicast,
  unspecified, or IPv4-mapped forms of these. Literal IP hosts are checked the same way.
  Exception: a feed whose URL the owner typed may use its own host; record that host as owner-approved.
- Redirects: handled manually, at most 20 hops (podcast tracker chains reach 7+), each hop re-checked. A 301 or 308 on a feed URL updates the
  stored feed URL; 302/307 do not.
- Timeouts: connect 15 s; read stalls 30 s (no total-time limit on downloads).
- Size limits: feed or OPML 20 MB; chapters or transcript files 5 MB; images 10 MB; episode media 2 GB.
- User agent: `Podplayer/<version> (Android)`. No other identifying headers.
- Conditional GET for feeds: send `If-None-Match` / `If-Modified-Since` from the last good response.
- Redaction helper: `redact(url)` drops query, fragment and userinfo; every log call uses it.

## 4. Feeds (`:core:feed`)

Parsing
- Written against the `XmlPullParser` interface (platform implementation on Android, kxml2 in JVM tests).
- Namespaces on. DOCTYPE processing off; any DOCTYPE that declares entities rejects the feed.
- Pre-pass before parsing: detect encoding (BOM, XML declaration, HTTP header, then UTF-8 with replacement);
  remove characters illegal in XML; escape bare `&` that do not start a valid entity or character reference.
- Limits: 20 MB, 10,000 items, 10 s parse time, element depth 64.
- Content sniffing: if the body is HTML rather than RSS/Atom, report "the server returned a web page".
- Dates: RFC 822 plus common deviations; unparseable dates become null and the first-seen time is used.

Fields to keep
- Channel: title, link, description (sanitized), author, image (`itunes:image`, then `image/url`),
  categories, `podcast:guid`, `podcast:funding`, `podcast:person`, `podcast:license`, `podcast:locked` (ignore).
- Item: guid, title, pubDate, enclosure (url, type, length), `itunes:duration`, description or
  `content:encoded` (sanitized), `itunes:image`, season/episode numbers, `podcast:chapters` URL,
  `podcast:transcript` (metadata only; never fetched or shown in standard playback), `podcast:soundbite`,
  `podcast:alternateEnclosure` (stored for later).

Episode identity
- Primary key within a feed: GUID. Fallback when GUID is missing or duplicated within the feed: enclosure URL,
  then normalized title plus publication day.
- Duplicate GUIDs in one feed: keep the items apart by enclosure URL and show a "broken feed" warning that
  includes feed-validator links.
- Re-posted item (same GUID, newer date): update metadata; do not reset played state or re-download.
- Items that disappear from the feed stay in the library, marked "no longer in feed".
- A feed moving hosts (301/308) keeps all episodes and play state.

OPML: same XML safety rules; import adds feeds with a preview and confirmation; export writes all subscriptions.

## 5. Show notes
- jsoup with an allowlist (p, br, ul, ol, li, a[href], b, strong, i, em, blockquote, h1–h4 mapped to bold).
- Output a neutral rich-text model in `:core:feed`; `:app` turns it into `AnnotatedString`.
- Links: http, https, mailto only; tapping shows the destination domain and opens the browser.
- No images in show notes in M1 (remote images are tracking pixels as often as content).
- Timestamps like `12:34` in notes become seek links within the same episode.

## 6. Images (`:core:imagesandbox`)
- An `android:isolatedProcess="true"`, non-exported service decodes images. It has no permissions,
  no network and no file access; bytes arrive through a `ParcelFileDescriptor` or `SharedMemory`.
- Inside the sandbox: read the header first (`ImageDecoder` header callback) and refuse images over
  10 MB or larger than 8,000 px on a side; decode with a target size (max 1,024 px); first frame only for
  animated images; refuse SVG. Re-encode to JPEG (opaque) or PNG (alpha) and return the bytes.
- The main process writes the re-encoded bytes to the image cache and only ever decodes those files.
- If the sandbox crashes or times out (5 s), show a placeholder and remember the failure for that URL.
- Images are fetched by the gated client in the main process, then handed to the sandbox.
- Coil is configured with no network fetcher; it loads cache files only.

## 7. Storage and database (`:core:data`)
- Media and images live in app-private internal storage. Paths in the database are relative
  (e.g. `media/<feedId>/<episodeId>.mp3`), never absolute. File names are generated, never taken from the server.
- Room with schema export to `schemas/`; every version bump has a migration and a migration test.
- Tables (initial): feeds, episodes, media_files, queue_entries, playback_records (position, duration,
  played flag, updated time), feed_settings (per-show speed now; skip intro/outro and ad-skip settings later),
  episode_actions (append-only log of play/pause/seek/played/downloaded/deleted events with device id and a
  synced flag, used by M2 sync), download_log, refresh_log.
- Added (owner, 2026-11-15): a starred flag and starred time per episode (in backups; M2 sync carries it as an
  episode action), and per-show auto-download settings in feed_settings.
- Backups: automatic daily backup of the database (SQLite `VACUUM INTO` or equivalent) plus settings into a
  folder the owner chooses through the Storage Access Framework; keep the last 7. Manual export and import.
  Always back up before running a migration.
- Integrity check on start; if the database is corrupt, offer restore from the newest backup.

## 8. Feed refresh (`:core:jobs`)
- Periodic WorkManager job (default every 4 hours, network required) plus manual pull-to-refresh.
- One refresh at a time (unique work); cancelable; per-feed exponential backoff on errors.
- Refresh log per feed: last success, last error (plain language), next scheduled time.

## 9. Downloads (`:core:jobs`)
- WorkManager unique work per episode. Owner-started downloads run in the foreground with type `dataSync`
  and handle the Android 15+ six-hours-per-day limit (`onTimeout`: stop cleanly, reschedule).
- Resume with `Range` + `If-Range` when the server supports it; otherwise restart and say so in the log.
- A finished file is accepted when the transfer ended cleanly and Media3's extractors can read its duration.
  Do not require the byte count to equal the declared length (dynamic ad insertion changes it).
- Declared MIME types are hints only (publishers mislabel them as audio/mp3, application/octet-stream and so on).
  Accept a file when Media3's extractors recognize an audio format; refuse video and unknown formats in M1 with a
  plain message.
- Storage cap setting (default 10 GB); Wi-Fi-only setting for manual downloads (default on); disk full fails cleanly and leaves
  no file marked complete.
- Per-download log the owner can read.
- Auto-download (owner, 2026-11-15):
  - Per show: "Download new episodes" on or off; a global default for new subscriptions (default off).
  - Only episodes published after it was turned on for that show are downloaded (no flood on the first refresh).
  - Its own network setting: any connection (default) or Wi-Fi only. Manual downloads keep their own Wi-Fi-only
    setting. The storage cap applies; at the cap, auto-downloads stop and the app says so; it never deletes
    to make room.
  - Runs after each refresh, as background downloads (not foreground; the owner didn't start them).
- Clean-up of downloaded files (owner, 2026-11-15; every number configurable in Settings):
  - Played episodes (98%, as in §10) are deleted 3 days after they were finished.
  - Unplayed downloads are deleted 14 days after they finished downloading.
  - Never deleted automatically: starred episodes, episodes in the queue, the episode loaded in the player.
  - Runs once a day and after playback marks an episode played; every deletion goes in the download log.

## 10. Playback (`:playback`)
- `MediaLibraryService` with ExoPlayer and a `MediaLibrarySession`.
- ExoPlayer: audio attributes usage MEDIA, content type SPEECH, `handleAudioFocus = true` (speech pauses
  instead of ducking); `setHandleAudioBecomingNoisy(true)`; network wake mode while streaming;
  HTTP through `OkHttpDataSource` with the gated client; MP3 index seeking for downloaded files.
- Queue: database is the truth; the player holds the current item plus the next; rebuild on every queue change.
  Finishing an episode marks it played, removes it from the queue, and advances. Reaching 98% of an episode
  counts as played (owner, 2026-11-15); it stays loaded until it ends or the owner moves on.
- Positions persisted as in `CLAUDE.md`. No unintended rewind on resume; an optional "rewind N seconds after a
  pause longer than M minutes" setting, default off.
- Speed: global default plus per-show override; pitch preserved. Skip intervals: back 15 s, forward 30 s,
  configurable.
- Sleep timer lives in the service: 5–120 minutes or end of episode; 10-second fade-out; survives the UI dying.
- Notification: Media3 default provider with custom back/forward buttons.
- Playback resumption: declare Media3's `MediaButtonReceiver` and implement `onPlaybackResumption`
  so lock-screen, notification, headset and system media controls can resume after the service was killed
  or the phone rebooted.
- Streaming goes through a read-ahead cache; every position change is logged with its cause.

## 11. Android Auto
- Browse tree from `MediaLibrarySession.Callback`: root items Continue (last played, playable at its
  position), Queue, Downloads, Latest episodes, Starred, Podcasts (browsable to episodes).
- At most 50 children per node; titles cleaned of control characters and capped at 120 characters.
- Artwork: content URIs of sandbox-produced cache files only.
- Test in the Desktop Head Unit. Sideloaded builds appear in Auto only after enabling Developer mode and
  "Unknown sources" in the Android Auto app's developer settings.

## 12. UI for M1 (`:app`)
- Bottom navigation: Home, Discover, Library, Queue. Settings from a top-right icon. Mini-player above the
  navigation when something is loaded.
- Home: Continue card, New episodes (compact rows with play, queue, download), Your shows row.
- Discover: one field for search terms or a pasted feed URL. Search uses Apple's podcast search
  (`https://itunes.apple.com/search?media=podcast&entity=podcast&term=...`); results show art, title, publisher,
  subscribe. Typed URLs get a preview before subscribing.
- Library: subscriptions grid (list toggle); show page with episodes, sort, per-show speed.
- Episode page: sanitized notes, play, queue, download, mark played.
- Queue: drag handle plus Move up / Move down actions for accessibility; play next; remove.
- Player: artwork, title, show, seek bar with elapsed/remaining, back/play/forward, speed, sleep timer,
  queue, download. The seek bar marks how far a stream has buffered; a downloaded episode shows as all there
  (owner, 2026-11-18).
- Downloads list (owner, 2026-11-18): Library has a Downloads list for listening offline: downloads on their way
  first, then finished ones newest first, with how many are ready and the space they take. Rows play, queue and
  delete as anywhere else.
- Settings: Appearance (Dark default on first launch, Light, Use system), Playback, Downloads and storage,
  Backup and restore, Logs (app log, download log, refresh log, crash log; export via share), About and licenses.
- Starred episodes (owner, 2026-11-15):
  - A star on the episode page, episode rows and the full player. Tapping it stars or unstars at once, with an
    Undo bar; no confirmation dialog. No star on the lock screen or notification (no way to confirm there, and a
    pocket tap would star silently).
  - Library has a Starred list (searchable by episode or show name); Home has a Starred row; a show page can
    filter to its starred episodes.
  - Starred episodes are never deleted by clean-up (§9); an optional setting keeps them downloaded.
  - Starring a played episode keeps it played; playing it again starts from the beginning.
  - Share starred: a plain-text list of titles with their episode links (the feed's item link, never the
    enclosure URL, which can carry tokens).
- Import a list of shows (owner, 2026-11-15), in Discover:
  - The owner pastes, or shares into the app, a list of show names (one per line) or links.
  - Each line is matched through Apple's podcast search (feed URLs and links go to the typed-URL preview).
    A preview lists every line with its best match, lets the owner pick another result or skip it, and
    subscribes only after confirmation (`ownerTyped = false` for search matches).
  - Lines are untrusted input: limits on count (500) and length, no fetches until the owner confirms the preview
    except the search requests themselves, which are paced.
  - The same importer accepts the file a one-off adb export from another app writes (M1-S9c): show names, and
    optionally starred episodes, played episodes and positions, each matched by show, then episode title and date.
- No transcript UI, no translation UI, no ad-skip UI in M1.
- Debug builds only: a Developer screen (add feed by URL, refresh, list episodes, download, artwork, play/pause/
  seek/speed/sleep timer, logs) so features can be tested on a phone before the real UI exists. It lives in the
  `debug` source set and must not exist in release builds.

Color tokens (semantic; adjust for WCAG AA contrast if needed):

| Token | Dark (default) | Light |
| --- | --- | --- |
| background | #0D1117 | #F7F9FC |
| surface | #151B23 | #FFFFFF |
| surfaceElevated | #202A36 | #EBF0F6 |
| textPrimary | #F4F7FB | #14202D |
| textSecondary | #B8C5D3 | #48596B |
| textQuiet | #8797A8 | #617385 |
| accent | #73B8FF | #176DB5 |
| accentSecondary | #79D2C1 | #176F64 |
| divider | #303D4B | #D5DFE9 |
| error | #FF9B9B | #B42318 |
| warning | #F4C77F | #885500 |

Touch targets at least 48 dp; content descriptions on every control; respect font scaling.
The saved theme applies before the first frame (no flash of the wrong theme).

## 13. Logs and diagnostics
- Rolling local log files (total 5 MB), crash handler writing a crash file, ANR notes where available.
- Everything redacted with `redact(url)`. Export only when the owner taps Export.

## 14. Testing
- JVM unit tests for all plain Kotlin modules, with fixtures in `:core:testing`.
- Hostile corpus (each a test): entity-expansion bomb, external entity, DOCTYPE with entities, 25 MB feed,
  65-deep nesting, bare ampersands, invalid dates, wrong declared encoding, BOM, duplicate GUIDs,
  HTML instead of RSS, `javascript:` and `intent:` links in notes, enclosure on a private address,
  redirect to a private address, redirect loop, images that are truncated, oversized, mislabeled, or
  decompression bombs.
- Forbidden-API check: a test or Gradle task that fails if `BitmapFactory` or `ImageDecoder` appear outside
  `:core:imagesandbox`, or if `WebView`, `MediaExtractor` or `MediaMetadataRetriever` appear anywhere.
- Closed-dependency check: fails if any group in the "Never" list of section 2 is on a classpath.
- Room migration tests; playback rule tests with Robolectric and Media3 test utilities.
- Jazzer fuzz targets for the feed and OPML parsers (run locally; short runs in CI).

## 15. Later milestones (summaries; detailed session files come after each review)
- M2 ad skipping: on-device, off by default, per-show modes Off / Skip button / Auto-skip. Layers: publisher
  chapters labeled as ads; user-taught sound templates (log-mel spectrogram, normalized cross-correlation,
  plain Kotlin); break rules per show; breaks 10 s–10 min; Undo after every skip; seeking into a break plays it;
  hatched bands on the seek bar.
- M2 sync and server: owner's rented server (HTTPS, token auth) implements the gpodder subscription and
  episode-action API; positions also carry content anchors once they exist.
- M3 translation: content anchors (fingerprints of about 3 s every 20–30 s) map positions between copies with
  different ads; the manifest carries anchors and source spans; the phone replays its own original audio;
  monthly API budget ($20 default, adjustable from the app) enforced on the server.
- M4: dubbed rendition with presets; ad breaks stay in the original language; opt-in server ad detection;
  cross-episode repetition detection.
