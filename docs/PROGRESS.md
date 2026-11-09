# Progress

Claude Code updates this file at the end of every session. Keep entries short and honest:
a reviewer reads it to see what was really done.

## Current state
- Milestone: M1
- Next session: M1-S5
- Build status: `./gradlew check` and `assembleRelease` green locally after M1-S4 (2026-11-08), including a run
  from an empty Gradle home. The CI result is on the M1-S4 PR.

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

### M1-S4 Database, repositories, backups and logs (2026-11-08)
Plan (written before any code; tests first for every rule):
- Scope: `:core:data` (Room, repositories, media paths, backups, settings, logs), domain types in `:core:model`,
  a daily backup worker in `:core:jobs`, wiring and a minimal Backup section plus a "database damaged" dialog in
  `:app`. SPEC §7, §13; LESSONS row "Storage"; PROGRESS "Later" items for S4.
- Dependencies (all on the allowlist and already pinned in the catalog, versions re-checked today): Room runtime,
  KSP compiler and the Room Gradle plugin (schema export to `core/data/schemas/`), DataStore, WorkManager,
  kotlinx-serialization JSON; tests: Robolectric, AndroidX test (ext-junit), Turbine, work-testing. Robolectric's
  Android jar becomes a verified Gradle dependency and Robolectric runs offline, so tests download nothing
  unverified. Verification metadata regenerated from an empty Gradle home.
- `:core:model`: `Feed`, `Episode`, `Enclosure`, `QueueEntry`, `PlaybackRecord`, `EpisodeAction`, `DownloadState`.
  Their `toString()` leaves URLs out (the model can't see `redact`).
- `:core:data`, package by package:
  - `db/`: entities for the SPEC §7 tables (feeds, episodes, media_files, queue_entries, playback_records,
    feed_settings, episode_actions, download_log, refresh_log) with foreign keys and indexes; DAOs with Flows;
    `PodDatabase` (version 1, schema exported); `Migrations` (empty list, the chain every new version extends).
    Feeds store the owner-approved host and the conditional-GET validators (PROGRESS "Later").
  - `db/DatabaseOpener`: on every start, before Room opens: apply a staged restore, run `PRAGMA integrity_check`
    on a separate connection that never deletes a damaged file, read `user_version`, back up before any
    migration, refuse a database newer than the app. Room itself gets an open-helper factory whose corruption
    callback keeps the file (the framework default deletes it). Typed result: Ready, Corrupt (with the backups
    that could restore it), Failed.
  - `repo/`: `FeedRepository` (subscribe with the URL policy, list, unsubscribe, and `applyRefresh`, which runs
    `EpisodeMerge` and applies `FeedChanges` in one transaction: inserts, metadata updates that never touch played
    state, position, queue or downloads, `publishedAt` only when non-null, "no longer in feed", "back in feed"),
    `EpisodeRepository`, `QueueRepository`, `PlaybackRepository`, `FeedSettingsRepository`,
    `EpisodeActionRepository`, `MediaFileRepository`, `LogRepository` (download and refresh logs, capped, redacted).
  - `media/MediaPaths`: generated relative paths (`media/<feedId>/<episodeId>.<ext>`, extension from an
    allowlist) and a resolver that refuses absolute paths, `..` and anything outside the media folder.
  - `settings/`: `AppSettings` (moved from `:app`; user settings, included in backups), `DeviceSettings`
    (backup folder, last backup, device id: never backed up), `PreferencesSnapshot` (DataStore to JSON and back,
    with limits).
  - `backup/`: `DatabaseSnapshot` (`VACUUM INTO` where SQLite has it, API 30+; on API 29 a checkpoint plus file
    copy under Room's write lock), `BackupArchive` (zip with manifest, database and settings; reading has entry,
    size and count limits and validates the database), `BackupFolder` (file and Storage Access Framework
    implementations), `BackupManager` (back up now, daily when due, rotation keeping 7 in the owner's folder and 3
    in app-private storage, export, list restore candidates, stage a restore for the next start after a safety
    backup, reconcile downloads after restore).
  - `logs/`: `AppLog` (rolling files, 5 MiB total, background writer, every message through `scrubUrls`, newlines
    indented so a feed title can't forge a log line), `CrashHandler` (crash file, chained to the previous
    handler), `ExitReasons` (ANR and other exit notes on Android 11+).
  - `DataLayer`: builds all of the above for `AppGraph`.
- `:core:jobs`: `BackupWorker` (daily periodic unique work) and a `WorkerFactory`.
- `:app`: `AppGraph` wiring; crash handler installed first; release invariant handler writes to `AppLog`;
  WorkManager on-demand configuration; Settings gets a minimal "Backup and restore" section (choose folder with a
  persisted permission, back up now, export, restore from a file; restore restarts the app) and a dialog when the
  start-up check finds a damaged database (restore the newest good backup, or start empty keeping the damaged file).
  S9 builds the full Settings.
- Tests first (Robolectric for everything that touches SQLite or Android):
  - DAO tests per table, cascade and set-null rules, Flow updates (Turbine).
  - Merge application: first refresh inserts; second is all unchanged; edited items update; re-post keeps played
    state, position, queue entry and download; removed then returning items; host move with new GUIDs; null
    dates keep `publishedAt`; a failure inside the transaction leaves nothing applied; 10,000 items.
  - Media paths: generated names, traversal, absolute paths, symlinks, unknown extensions.
  - Backup: snapshot by both methods; backup, wipe, restore round trip bringing back subscriptions, queue,
    positions and settings; rotation keeps 7 and never touches other files; hostile archives (not a zip,
    zip bomb, duplicate or unknown entries, missing manifest, newer schema, damaged database, too big);
    restored media rows with missing files reset; restored paths that escape the media folder are refused.
  - Opener: damaged file detected and kept; restore offer lists good backups newest first; pre-migration backup
    taken when `user_version` is older; newer database refused; staged restore applied once.
  - Migration scaffolding: schema JSON exists for exactly versions 1..current; every version has a migration to
    the next; a per-version test builds the database from its schema JSON, seeds it, opens it with Room (which
    validates the identity hash and the migrated schema) and checks the data, and fails if a version has no
    seed and check.
  - Logs: rolling at the size limit, total cap, redaction of URLs in messages and exceptions, forged-line
    newlines, crash file written and previous handler called, exit reasons recorded once.
  - Settings snapshot round trip and hostile snapshots; `AppSettingsTest` moves with `AppSettings`.
  - `BackupWorker` with work-testing.

Result:
- Done (layout as planned, except: integrity check, schema check and snapshot share `backup/SqliteFiles.kt`;
  `PendingRestore` lives in `db/DatabaseOpener.kt`; the small repositories share `repo/Repositories.kt`):
  - `:core:model` `Library.kt`: `Feed`, `Episode`, `Enclosure`, `QueueEntry`, `PlaybackRecord`, `EpisodeAction`
    (+ kind), `MediaFile`, `DownloadState`. Their `toString()` leaves URLs out.
  - Room schema version 1 with the nine SPEC §7 tables, foreign keys (cascade; `episode_actions` set-null) and
    indexes; exported to `core/data/schemas/` (committed). `Migrations.ALL` is empty. Enum columns store names.
    Feeds also store the owner-approved host, conditional-GET validators and refresh status; media files store
    If-Range validators. Funding, persons, license, transcripts, soundbites and alternate enclosures are JSON
    columns (kotlinx-serialization) that read as empty when damaged.
  - `PodDatabases.builder`: file database, WAL, every migration, no destructive fallback, and an open-helper
    factory whose corruption callback keeps the file (the framework default deletes it).
  - Repositories (`Repositories`): feeds (subscribe through `UrlPolicy`, canonical URL, owner-approved host only
    when typed; unsubscribe cascades; `applyRefresh` runs `EpisodeMerge` and applies `FeedChanges` in one
    transaction: inserts, metadata updates via a partial `@Update` that can't touch other tables, `publishedAt`
    and GUID kept when the feed has none, "no longer in feed", "back in feed", channel fields kept when the feed
    leaves them out, IN lists chunked at 500), episodes, queue (dense 0-based positions, add/move/remove/clear,
    re-adding moves), playback (position, played, in-progress list; unknown episodes are not an error), per-show
    speed (clamped 0.5–3.0), episode actions (append-only, device id), media files (relative paths enforced,
    reconcile with disk), refresh and download logs (redacted, 2,000 characters, newest 1,000 rows).
  - `MediaPaths`: `media/<feedId>/<episodeId>.<ext>` with an allowlisted extension (else `audio`); `resolve()`
    refuses absolute paths, `.`/`..`, backslashes, control characters, other top folders, and symlink escapes.
  - Settings: `AppSettings` moved from `:app` (same file path, so the theme carries over) and gained
    `snapshot()`/`restore()`; `DeviceSettings` (backup folder, last backup and failure, device id, exit-reason
    bookkeeping) is never backed up. `PreferencesSnapshot`: typed JSON of a whole DataStore with limits.
  - Backups (`backup/`): `DatabaseSnapshot` (`VACUUM INTO` on SQLite 3.27+, else checkpoint and copy under the
    write lock); `BackupArchive` (zip: manifest, database, settings; reading has entry-count, per-entry and
    decompressed-size limits, never uses entry names as paths); `BackupNames`; `FileBackupFolder` (atomic
    rename) and `SafBackupFolder` (DocumentsContract, lost permission becomes an IOException);
    `BackupManager`: back up now, daily when due (20 h), export, candidates newest first, rotation (7 in the
    owner's folder, 3 in app-private storage, only our names), pre-migration backup of the closed file, and
    staging a restore: full validation (manifest, settings, integrity check, version, migrate older copies,
    structure compared with a fresh database including triggers, views and Room's identity hash, enum values,
    `foreign_key_check`), safety backup, then staged files and a marker written last.
  - Start-up (`DataLayer`, `DatabaseOpener`): apply a staged restore (old database kept aside), integrity check on
    a connection that never deletes, refuse a newer database, back up before migrating, open Room off the main
    thread; after a restore, apply settings and drop download records without files. States: Opening, Ready,
    Damaged (with backups), Failed. `startFresh()` keeps the damaged file in `no_backup/damaged/`.
  - Logs: `AppLog` (5 × 1 MiB rolling files, background writer, `scrubUrls` on every message and stack trace,
    continuation lines indented, control characters replaced, 16 KiB per entry, never throws), `CrashHandler`
    (redacted crash file, newest 10 kept, then the previous handler), `ExitReasons` (Android 11+ exit and ANR
    notes into the log once).
  - `:core:jobs`: `BackupWorker` (periodic unique work, 24 h, battery and storage not low, KEEP),
    `PodplayerWorkerFactory`, `BackupManager.asDailyBackup()`.
  - `:app`: `AppGraph` builds the log, data layer and worker factory without disk work; `PodplayerApp` installs
    the crash handler right after StrictMode, routes release invariants to the app log, provides WorkManager's
    configuration (default initializer removed in the manifest) and starts the data layer, exit-reason logging
    and the backup schedule. Settings now scrolls and has a "Backup and restore" section (choose folder with a
    persisted permission, back up now, export, restore from a file with confirmation, then restart).
    `DataStateDialogs` offers the newest valid backup or an empty library when the database is damaged, and
    explains the Failed states.
  - Build: `podplayer.android.robolectric` convention (Robolectric's Android jar as a verified Gradle dependency,
    offline mode, one `--add-opens` the SDK 37 jar needs on JDK 21). Catalog: `robolectric-android-all` and the
    `androidx.room` plugin. Verification metadata regenerated from an empty Gradle home (the Gradle distribution
    was copied in because its download timed out; it isn't a dependency). Only new components were added; no
    existing checksum changed.
  - LESSONS "Storage" as tests: internal storage only and relative paths (MediaPathsTest, media upsert refuses
    absolute paths); automatic backups (rotation, daily-due tests, worker); backup before every migration
    (DatabaseOpenerTest); migration tests on saved old databases (MigrationTest builds each version from its
    schema file plus a frozen seed); backup, wipe, restore round trip (BackupRoundTripTest).
- Tests added (86 new, plus the 7 moved `AppSettingsTest` tests; 93 in `:core:data` and `:core:jobs`):
  - FeedRepositoryTest (16): URL policy and owner-approved host, duplicates, first refresh, identical refresh
    writes nothing, re-post keeps played state, position, queue entry and download, missing dates, first-seen
    time, removed and returning items, host move with new GUIDs, duplicate GUIDs, extras and damaged JSON, a
    failure mid-transaction applies nothing, unsubscribe cascades, 10,000 items, no tokens in `toString()`.
  - LibraryRepositoriesTest (10): queue add/move/remove/flow, positions and played, in-progress order, speed
    bounds, actions survive episode deletion, media path rules, log redaction, caps and trimming.
  - BackupRoundTripTest (6): backup, wipe, restore brings back subscriptions, owner-approved host, episodes,
    queue order, positions, played flags, per-show speed and theme, not the device id, and applies once;
    download records without files dropped and an escaping path dropped; safety backup before restore; rotation
    7/3 leaving unrelated files alone; daily due; unreachable owner folder reported, local copy kept.
  - HostileBackupTest (13): random bytes, empty file, missing parts, broken manifest or settings, newer format,
    schema or `user_version`, manifest/database mismatch, duplicate entry, too many entries, path-traversal
    names never written, decompression bomb and oversized settings, damaged and truncated databases, an added
    trigger, view or table, a dropped column or index, unknown enum values; a refused restore changes nothing.
  - DatabaseOpenerTest (8): fresh install; damaged file kept byte-for-byte with the backup offered and restorable;
    start fresh keeps the damaged file; newer database refused and untouched; backup before migration (and no
    migration if it fails); the pre-migration backup restores; Room never deletes a damaged file; a staged
    restore applies exactly once.
  - MigrationTest (5): schema file for every version and none beyond, a migration for every step, a seed for
    every version, the newest schema file's identity hash matches the code, every old version migrates with its
    data. DatabaseSnapshotTest (2), MediaPathsTest (5), AppLogTest (10: format, redaction, forged lines, control
    characters, rolling cap, huge message, unwritable folder, crash file and chaining, crash-file cap, exit
    reasons once), PreferencesSnapshotTest (4), AppSettingsTest (+2), BackupWorkerTest (5).
  - The tests were written before the code they exercise, but Kotlin can't compile them without the API, so
    their first run was against finished code. On that run everything passed except a test-class mistake
    (`setUp` returned a value). To check the tests can fail, I put back the framework's file-deleting
    corruption handler (DatabaseOpenerTest failed) and made the schema check ignore triggers and views
    (HostileBackupTest failed), then reverted both.
- Deviations from the spec, and why:
  - `AppSettings` moved to `:core:data` (planned in "Later"): backups need it and `:playback` will need settings.
  - Backups also keep the newest 3 in app-private storage (`no_backup/backups`), so a damaged database can be
    restored even before the owner picks a folder. They never leave the phone.
  - Android 10's SQLite (3.22) has no `VACUUM INTO`; there the snapshot is a full checkpoint plus a file copy
    under Room's write lock (SPEC: "or equivalent"). Both methods are tested by choosing them explicitly
    (Robolectric's SQLite is newer than Android 10's); on a phone the SQLite version picks the method.
  - Restores are staged and applied at the next start (the app restarts itself), so a live database is never
    swapped under open connections. The replaced library is kept in `no_backup/damaged/` as well as in the
    safety backup.
  - A restored backup brings back each feed's owner-approved host. A hand-crafted backup could therefore approve
    a private host for a feed; restoring is the owner's own action, so I kept it (otherwise a home-server feed
    stops working after a restore). Say if you'd rather clear it on restore.
  - Integrity check: `PRAGMA integrity_check` on every start, on a separate connection, before Room opens. A
    0-byte file counts as a fresh install.
  - Added beyond SPEC §7: the backup before a migration must succeed or the database isn't migrated; a database
    newer than the app is refused, not downgraded; logs indent continuation lines; refresh/download log rows are
    capped at 1,000 each; crash files at 10.
  - Settings gained a minimal "Backup and restore" section now (the full Settings screen is S9), because
    automatic backups to the owner's folder need a way to choose it. The app's INTERNET permission is still
    absent; the manifest comment now says it arrives with the first network feature (S5).
  - Robolectric needs `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` for the SDK 37 jar on JDK 21
    (test JVM only).
- Manual checks for the owner (on the phone):
  - Settings › Backup and restore › Choose backup folder: pick a folder (for example Documents/Podplayer). A
    `podplayer-backup-….zip` appears there and "Last backup" shows the time.
  - Back up now, Export a backup file, then Restore from a backup file with that export: the app restarts and
    the theme you had at export time is back. (There are no subscriptions to see until S5's Developer screen.)
  - Rename or delete the chosen folder, tap Back up now: the "Couldn't write to the backup folder" message shows.
  - A day later, check a second backup appeared without opening Settings (the daily worker).
- PR: #5 (`m1-s4-database-backups-logs`).

### M1-S3 Feeds, OPML and show notes (2026-11-08)
Plan (written before any code; tests first for every rule):
- Scope: `:core:feed` (pure Kotlin) and `:core:testing`, plus two forbidden-API rules in `build-logic`.
  SPEC §4, §5, §14 (feed, OPML and notes parts of the hostile corpus); LESSONS rows "Episode identity" and
  "Parser strictness".
- Dependencies (all on the allowlist and already pinned): jsoup for `:core:feed` (notes only); kxml2 as
  `compileOnly` in `:core:feed` (it supplies the `org.xmlpull.v1` interface the parser is written against; on
  Android the platform provides it, so nothing ships) and as `api` of `:core:testing` (the JVM parser in
  tests). `:core:feed` also gets `implementation(project(":core:net"))` to reuse `UrlPolicy` and `redact`
  instead of a second copy of the URL rules. Verification metadata regenerated from an empty Gradle home.
- Files in `core/feed/src/main/kotlin/us/dikarya/podplayer/core/feed/`:
  - `xml/EncodingDetector.kt`: BOM, then XML declaration, then HTTP `charset`, then UTF-8 with replacement.
  - `xml/XmlPrepass.kt`: decode, drop characters illegal in XML 1.0 (and character references to them),
    escape bare `&` (outside CDATA and comments), turn common HTML named entities into character references,
    refuse any DOCTYPE that declares entities, strip other DOCTYPEs and the XML declaration, sniff HTML.
    `HtmlEntities.kt`: the small entity table.
  - `xml/SafeXml.kt`: `XmlParserProvider` (Android passes the platform parser, tests pass kxml2), parser setup
    (namespaces on, DOCTYPE processing off and verified off), `XmlLimits` (20 MiB, 10,000 items, 10 s, depth
    64) and a guarded `next()` that enforces depth and time with an injectable clock.
  - `FeedParser.kt` with `RssReader.kt` and `AtomReader.kt`: SPEC §4 channel and item fields, RSS 2.0 +
    iTunes + Podcasting 2.0, Atom basics. Typed `FeedParseResult` (`Success` with warnings, `Failure` with a
    `FeedError`). Every feed-supplied URL goes through `UrlPolicy.checkFeedUrl`; field lengths and list sizes
    are capped. `ParsedFeed.kt` holds the output types.
  - `FeedDates.kt` (RFC 822 plus deviations, ISO 8601 for Atom; unparseable → null) and `Durations.kt`
    (`itunes:duration` in seconds, `mm:ss`, `hh:mm:ss`, fractions, garbage → null).
  - `identity/EpisodeMerge.kt`: pure function (stored episodes, parsed items, now) → changes: inserts, metadata
    updates, "no longer in feed", "back in feed", and a broken-feed warning for duplicate GUIDs (with
    feed-validator links). GUID first; enclosure URL, then normalized title plus publication day as fallbacks.
  - `opml/OpmlParser.kt` (same XML safety, limits, nested outlines, URL policy, duplicates) and
    `opml/OpmlWriter.kt` (OPML 2.0 export of all subscriptions).
  - `notes/ShowNotes.kt`, `notes/RichText.kt`, `notes/Timestamps.kt`: jsoup allowlist (p, br, ul, ol, li,
    a[href], b, strong, i, em, blockquote, h1–h4 as bold) to a flat rich-text model (text plus style spans:
    bold, italic, link, timestamp, quote, list item), links limited to http/https/mailto, bare http(s) URLs
    linked, timestamps (`12:34`, `1:02:03`) as seek spans, input-size, node-count and time limits.
- `:core:testing`: synthetic fixtures (no copied show notes, no real feeds) under `src/main/resources/`:
  `corpus/feeds`, `corpus/opml`, `corpus/notes`, and realistic `samples/feeds` that mimic common publisher
  patterns (hosted platforms, Podcasting 2.0 tags, Atom, Latin-1 legacy feed). `Fixtures.kt` loads them;
  `SyntheticFeeds.kt` generates the big ones (10,000 items, 25 MB, 65-deep nesting, UTF-16 BOM).
- Tests first, in `core/feed/src/test/`: one per hostile fixture (entity-expansion bomb, external entity,
  DOCTYPE with entities, 25 MB feed, 65-deep nesting, too many items, time limit, bare ampersands, invalid
  dates, wrong declared encoding, BOM, duplicate GUIDs, HTML instead of RSS, `javascript:`/`intent:` links in
  notes, an enclosure on a private address, which the parser keeps and the network gate then refuses);
  encoding detection; pre-pass; dates; durations; RSS, iTunes, Podcasting 2.0 and Atom fields on the samples;
  identity and merge (duplicate GUIDs, re-post, host move with new GUIDs, removed and returning items,
  missing GUIDs); OPML import (nested, bad schemes, duplicates, hostile) and round trip; sanitizer (scripts,
  iframes, forms, styles, images, event handlers, `javascript:`/`intent:`/`data:` links, nesting, limits) and
  timestamps; 10,000-item feed parses under the time limit.
- `build-logic`: forbidden-API rules `jsoup-network` (`Jsoup.connect` and jsoup's HTTP connection, which would
  bypass the gate) and `jsoup-outside-feed` (SPEC §2: jsoup is for show notes only), each with a sample and test.
- Not in scope: storing anything (S4), fetching (S5), rendering `RichText` as `AnnotatedString` (S8).

Result:
- Done (file layout as planned, except that the RSS and Atom readers share `FeedReaders.kt` and OPML import and
  export share `opml/Opml.kt`):
  - Pre-pass (`xml/XmlPrepass.kt`, `xml/EncodingDetector.kt`, `xml/HtmlEntities.kt`): byte limit before
    decoding; encoding from BOM (UTF-8/16/32), UTF-16 without BOM, XML declaration, HTTP `charset`, then UTF-8
    with replacement; illegal XML characters and references to them removed (inside CDATA too); bare `&`
    escaped; about 130 HTML named entities turned into character references; any DOCTYPE that declares an
    entity or uses a parameter entity refused, any other DOCTYPE and the XML declaration removed; markup
    declarations after the prolog refused; web pages recognised by DOCTYPE or root element; at most 256
    attributes per tag; missing declarations for `itunes`, `podcast`, `content`, `atom`, `media`,
    `googleplay` and `dc` added to the root.
  - Safe parsing (`xml/SafeXml.kt`): `XmlParserProvider` (Android passes the platform parser, tests pass
    kxml2), namespaces on, DOCTYPE processing off and checked off, depth checked on every start tag, item count,
    and one 10 s budget for pre-pass plus parse with an injectable `Ticker`. Typed `ParseProblem` results
    with a `logReason` that never quotes content; no exception escapes `parse()`.
  - `FeedParser`: RSS 2.0 + iTunes + Podcasting 2.0 and Atom 1.0 (and the 0.3 namespace) with every SPEC §4
    field, in `ParsedFeed`/`ParsedEpisode`. Every feed URL passes `UrlPolicy.checkFeedUrl`; titles are
    whitespace-collapsed; fields and lists are capped (`FieldLimits`); the audio enclosure wins when an item has
    several. Warnings: repairs made, duplicate GUIDs (with validator links), URLs refused, fields cut.
    `toString()` of every type holding a media URL redacts it.
  - `FeedDates` (RFC 822 with deviations, ISO 8601) and `Durations` (`itunes:duration`, Podcasting 2.0 seconds).
  - `identity/EpisodeMerge`: (stored episodes, parsed items, now) → `FeedChanges` (inserts, updates with the
    match kind and a re-post flag, no longer in feed, back in feed, unchanged count, items skipped for having no
    enclosure, duplicate listings dropped), plus a stable SHA-256 `fingerprint` so unchanged items cost no write.
  - `opml/Opml.kt`: `OpmlParser` (same pre-pass and limits, outlines count as items, nested categories
    flattened, URL policy, duplicates by canonical URL, reasons for every skipped outline) and `OpmlWriter`
    (OPML 2.0, everything escaped, characters XML can't carry dropped).
  - `notes/`: `ShowNotes.sanitize` (jsoup parse, allowlist walk, iterative, limits: 1 MiB input, 50,000
    elements, 2 s), `RichText` (text plus Bold, Italic, Quote, ListItem, Link with its destination host,
    Timestamp spans), `Timestamps.find`.
  - `:core:testing`: 17 hostile feed fixtures, 4 OPML fixtures (2 hostile, 1 bad-URL, 1 web page), 1 hostile
    notes file, 3 sample feeds (hosting-platform style, Podcasting 2.0, Atom), 1 sample OPML, `Fixtures` and
    `SyntheticFeeds` (10,000 items, 25 MB, deep nesting, UTF-16 with and without BOM, Latin-1).
  - `build-logic`: forbidden-API rules `jsoup-network` (`Jsoup.connect`, jsoup's `Connection` and
    `HttpConnection`, `Jsoup.parse(URL…)`) and `jsoup-outside-feed`.
  - LESSONS rows: "Episode identity": fixtures and tests for one GUID used many times, GUIDs changed on a host
    move, re-posts with a new date, removed and returning items. "Parser strictness": bare `&`, bad dates,
    wrong encodings recover; a failed parse is a typed result, so the caller keeps its last good copy (stored
    from S4 on).
- Tests added (161: 158 in `:core:feed`, 3 in `build-logic`):
  - HostileFeedCorpusTest (30): one test per SPEC §14 feed item: entity-expansion bomb (refused in under 2 s),
    external entity, external parameter entity, DOCTYPE with a harmless entity, 25 MB feed, one byte over the
    limit, 65-deep nesting (64 is fine), 200,000-deep nesting without a stack overflow, bare ampersands, invalid
    dates, wrong declared encoding, Latin-1 bytes under a UTF-8 declaration, UTF-8 and UTF-16 BOMs, duplicate
    GUIDs, HTML instead of RSS, `javascript:`/`intent:`/`file:` links in feed fields, an enclosure on a private
    address, not a feed, undeclared prefixes, truncation, illegal characters, too many items, too many attributes,
    garbage input, and every fixture cut off at every seventh byte parsing without an exception.
  - EnclosureGateTest (1): the private-address fixture's enclosures, parsed, are each refused by the real
    `NetGate` before any DNS lookup.
  - FeedParserTest (17): the sample feeds field by field, enclosure choice, whitespace, blank fields, caps,
    season/episode bounds, unknown elements, case-insensitive iTunes namespace, redacted `toString()`, and 3,000
    random byte mutations of a feed without an exception.
  - FeedLimitsTest (6): spec defaults; 10,000 items parse in about 0.2 s here (asserted under 2.5 s, the parser
    enforces 10 s); 10,001 refused; the time limit stops a parse.
  - XmlPrepassTest (20), EncodingDetectorTest (13), FeedDatesTest (7, 67 date strings), DurationsTest (4).
  - EpisodeMergeTest (21), OpmlTest (11: nested import, bad URLs and duplicates, hostile files, limits, OPML 1
    spellings, round trip including tokens and special characters, 2,000-feed round trip, redaction),
    ShowNotesTest (21: scripts, iframes, forms, styles, media, SVG, MathML, templates, images and event handlers
    dropped; `javascript:`, `intent:`, `data:`, `vbscript:`, `file:`, entity-encoded and whitespace-split
    schemes refused; destinations; structure; plain text; limits), TimestampsTest (7).
  - `build-logic` ForbiddenApiCheckTest (+3). I saw the two refusing tests fail before adding the rules.
  - The `:core:feed` tests were written before the code, but Kotlin can't compile them without the API, so
    their first run was against the finished code. 7 of 158 failed on that run: 6 from one bug (the year
    pattern `(\d{2}|\d{4})` took "20" of "2026"), and 1 test mistake (a lone surrogate can't survive UTF-8
    encoding, so that case moved to the pre-pass test, which works on decoded text).
- Deviations from the spec, and why:
  - kxml2 is `compileOnly` in `:core:feed` main code, though SPEC §2 lists it for tests. The parsers must be
    written against the `org.xmlpull.v1` interface (SPEC §4) and a plain Kotlin module has no other source for
    it. Nothing ships: Android provides the interface. Tests use kxml2 through `:core:testing`.
  - `:core:feed` depends on `:core:net` for `UrlPolicy` and `redact`, so there is one copy of the URL rules.
  - Notes and descriptions are stored raw (capped) and sanitized when shown, not at parse time. The sanitizer
    can then improve without a re-fetch, and the raw HTML never reaches the screen except through it. SPEC §4
    says "(sanitized)".
  - The duplicate-GUID warning's validator links are the validators' front pages, without the feed URL:
    CLAUDE.md says feed URLs go nowhere but their own host.
  - Episode identity goes a little beyond "GUID, then enclosure URL, then title plus day when the GUID is
    missing or duplicated": the fallbacks also run for a GUID the library hasn't seen (LESSONS: publishers
    change GUIDs when moving hosts), but they never take a stored episode whose own GUID is still in the feed.
    The enclosure URL is used only when it is unambiguous on both sides; "day" allows one day either way;
    undated items match by a title only one stored episode has. Items without an enclosure aren't episodes.
  - Encoding repairs beyond the spec order: ISO-8859-1 and US-ASCII are read as windows-1252 (as browsers do),
    and a single-byte declaration on bytes that are valid multi-byte UTF-8 is overruled (the "wrong declared
    encoding" fixture). A UTF-16 declaration on single-byte bytes is ignored.
  - Limits added: 256 attributes per tag (pull parsers check duplicate attributes in quadratic time); field caps
    (titles 1,000 characters, notes 512 KiB, lists 100 entries), where a long field is cut and reported, not the
    whole feed refused; whole-document limits still refuse. Dates outside 1970–2200 and durations over 7 days
    are not believed. Unknown alphabetic time zones are read as UTC.
  - Show notes: bare http(s) URLs in the text become links; h5/h6 are plain; table cells keep their text;
    `img` is dropped with its alt text (SPEC §5: no images in M1); notes over the sanitizer limits come back as
    `Rejected`, so the UI shows a message instead. Timestamps: "John 3:16" reads as 3:16 (it only ever seeks
    within the episode); at most 500 per text; ones past the known duration stay text.
  - Not supported: RSS 1.0 (RDF) feeds (reported as not a feed), relative URLs in Atom (`xml:base`), the
    `feed://` scheme in OPML (refused as not http/https). See Later.
  - OPML export writes full feed URLs, tokens included, since import needs them; it only happens when the
    owner asks.
  - Parsers don't log themselves: they return `ParseProblem.logReason` and warnings; the log files arrive in
    S4 and the refresh log in S5.
- Manual checks for the owner: none for this session; there is no UI that parses feeds yet. S5's Developer
  screen will run the parser on the owner's real feeds.
- PR: #4 (`m1-s3-feeds-opml-shownotes`).

### M1-S2 Network gate (2026-11-08)
Plan (written before any code; tests first for every rule):
- Scope: `:core:net` only (pure Kotlin, no `android.*`), plus one forbidden-API rule in `build-logic` and a lazy
  `NetGate` in `AppGraph`. SPEC §3 and the LESSONS "Downloads" row.
- Dependencies (all on the allowlist, already pinned in the catalog): `okhttp` and `kotlinx-coroutines-core` for
  `:core:net`, `mockwebserver3` for its tests. Verification metadata regenerated from an empty Gradle home.
- Files in `core/net/src/main/kotlin/us/dikarya/podplayer/core/net/`:
  - `UrlPolicy.kt`: feed-supplied URL parsing (http/https only, length cap, ambiguous numeric hosts such as
    `127.1` refused), user-visible link check (http, https, mailto), `redact(url)` and `scrubUrls(text)`.
  - `AddressClassifier.kt`: loopback, private, link-local, CGNAT, unique-local, site-local, multicast,
    unspecified, broadcast/reserved, and the IPv4 embedded in IPv4-mapped, IPv4-compatible, NAT64 and 6to4 forms.
  - `AddressPolicy.kt`: the owner-approved host (typed feed URL), matched per host and per request.
  - `GatedDns.kt`: filters resolved addresses. `Guards.kt`: application interceptor for literal-IP hosts and the
    user agent, network interceptor re-checking the connected socket address.
  - `RedirectInterceptor.kt`: manual redirects, at most 5 hops, each re-checked, credentials dropped on a host
    change, a `RedirectChain` (301/308 vs 302/307, the permanent URL) attached to the final request.
  - `NetGate.kt` / `NetConfig.kt`: the one OkHttp client (connect 15 s, read 30 s, no automatic redirects, no
    proxy, no cache, no cookies, explicit connection pool), per-policy clients sharing pool and dispatcher,
    `callFactory(policy)` (a `Call.Factory`, so other modules never hold an `OkHttpClient`), and `fetch()`.
  - `BodyLimit.kt`: size-limited reading (SPEC §3 limits, checked on decompressed bytes) with a per-kind total
    time limit. `ConditionalGet.kt`: `Validators` (ETag, Last-Modified) sanitized so a hostile value can't crash
    the next request. `NetError.kt` / `FetchResult`: typed results, messages scrubbed of URLs.
  - `download/`: `RangeDownloader` (Range + If-Range resume, servers ignoring Range, Content-Range checks,
    416 handling, stall watchdog, progress, cancellation that waits for the writer to stop), `DownloadSink`
    (+ file implementation), typed results with restart reasons.
- Tests first, in `core/net/src/test/` (MockWebServer on 127.0.0.1 behind a fake resolver): redact; URL policy;
  classifier table; DNS filter; redirect to 127.0.0.1, 10.x, 169.254.169.254, [::1], fc00:: refused; loop stops at
  5; typed local host allowed only for that host; 301/308 vs 302/307 reporting; body over the limit (declared,
  chunked, gzip bomb); total time limit; conditional GET and hostile validators; user agent and client settings;
  Range resume; server ignoring Range; changed file; slow drip stall; steady slow server completes; dropped
  connection then resume; cancellation; disk full; redirect to a private address during a download.
- `build-logic`: forbidden-API rule `okhttp-client-outside-net` (resolves the S2 "Later" item), with a sample and
  test.

Result:
- Done (file names differ slightly from the plan: redirects live in `Redirects.kt`, limits in `NetConfig.kt`,
  validators in `Validators.kt`):
  - `NetGate`: the single OkHttp client. Connect 15 s, read 30 s, no call timeout, automatic redirects off,
    `Proxy.NO_PROXY`, no cache, no cookies, explicit connection pool. One client per `AddressPolicy` built with
    `newBuilder()`, so pool and dispatcher are shared, and pooled connections are only reused under an equal
    policy (OkHttp compares the `Dns`). Other modules get a `Call.Factory` wrapper (`callFactory(policy)`), never
    the `OkHttpClient`, so nobody can `newBuilder()` the gate away. `fetch()` returns a typed `FetchResult`
    and never throws for network or server problems.
  - Address filter, three layers: `GatedDns` (drops blocked answers, fails if none are left), `UrlGuard`
    (IP-literal hosts, which OkHttp connects to without asking the Dns; ambiguous numeric hosts; user agent),
    `ConnectionGuard` network interceptor (re-checks the connected socket address before the request is
    written). The owner-approved host bypasses all three, for that host only.
  - `RedirectInterceptor`: at most 5 hops, each going back through all three layers. Relative Locations are
    resolved; non-http(s) or missing Locations are refused. `Authorization`, `Cookie` and
    `Proxy-Authorization` are dropped when scheme, host or port changes. Only GET/HEAD are followed. The
    `RedirectChain` is attached to the final request (`Response.redirectChain`), so ExoPlayer calls get it too.
  - `UrlPolicy.checkFeedUrl`, `UrlPolicy.isAllowedLink` (http, https, mailto; whitespace and control
    characters removed first, as browsers do), `redact(String)`, `redact(HttpUrl)`, `scrubUrls(text)`.
    `NetError.Network` details and every `toString()` of a type that holds a URL are redacted.
  - `BodyLimit` (FEED/OPML 20 MiB, CHAPTERS/TRANSCRIPT 5 MiB, IMAGE 10 MiB) counts decompressed bytes;
    `NetLimits.MEDIA_MAX_BYTES` is 2 GiB. `Validators` handles conditional GET and If-Range.
  - `download/RangeDownloader`: Range + If-Range (strong ETag, else Last-Modified, else restart), 200 to a
    Range request restarts in the same response (`RangeIgnored`, or `FileChanged` when the validator differs),
    Content-Range checked (wrong start or multipart: one retry from zero), 416 with `*/<size on disk>` means
    already complete, `Accept-Encoding: identity`, size cap, stall watchdog (`StallPolicy`), throttled progress,
    storage errors kept apart from network errors, partial bytes flushed and kept on every failure. On
    cancellation it cancels the call and waits until the writer has stopped before rethrowing.
    `DownloadSink` plus `FileDownloadSink` (fsync on close).
  - `AppGraph.netGate` (lazy; user agent from `BuildConfig.VERSION_NAME`, so `buildConfig` is now on in `:app`).
  - Forbidden-API rule `okhttp-client-outside-net`: the `OkHttpClient` token anywhere outside `:core:net`.
  - `:core:net` has no `android.*` imports (the existing `android-in-plain-module` rule checks that).
  - LESSONS "Downloads" row as tests: no Range support, wrong Content-Range, slow drip, silent server,
    dropped connection then resume, tracker redirect chains, no exact-size requirement (chunked bodies are
    accepted). Tracker blockers breaking hosts can't be tested here; the download log arrives in S5.
- Tests added (131: 129 in `:core:net`, 2 in `build-logic`):
  - RedactTest (15), UrlPolicyTest (11), AddressClassifierTest (9: every SPEC range, its neighbours, the
    mapped/compatible/NAT64/6to4 forms, and the literal parser), GatedDnsTest (9).
  - AddressGateTest (18): a redirect to 127.0.0.1, 10.x, 169.254.169.254, [::1], [fc00::1], [::ffff:127.0.0.1],
    a name that resolves to loopback, or `127.1` is refused, and the server sees only the first request. The
    typed host is allowed, and only that host (the same server under another name or literal is refused).
    An enclosure on 192.168.0.1 is refused. The raw `Call.Factory` follows good redirects and refuses bad
    ones. `ConnectionGuard` refuses loopback even behind a Dns that doesn't filter.
  - RedirectTest (14): the loop stops at 5 (6 requests); 5 hops still work; 301/308 vs 302/303/307;
    A-301-B-302-C gives permanent URL B; relative Locations; missing Location; non-http schemes; credentials
    dropped across hosts and kept on the same host; Range survives a redirect; no secrets in `toString()`.
  - BodyLimitTest (12): at the limit OK; declared over the limit; chunked over the limit; gzip bomb (8 MB of
    zeros against a 1 MB limit); slow drip past the total time limit; read timeout; HTTP errors; dropped
    connection; no query strings in error details; unknown host.
  - ConditionalGetTest (8), ClientConfigTest (7: settings, shared pool, `Call.Factory` isn't the client, exact
    user agent replacing ExoPlayer's, version sanitizing).
  - RangeDownloaderTest (26): every case above, plus progress, cancellation (no write after `cancel` returns,
    then resume to a byte-exact file), disk full, a redirect to a private address writing nothing, and
    Content-Range parsing.
  - `build-logic` ForbiddenApiCheckTest (+2): `OkHttpClient` is refused in `:app`, `:playback`,
    `:core:imagesandbox`, `:core:feed` and `:core:jobs`, and allowed in `:core:net`. I saw this one fail before
    adding the rule. The `:core:net` tests were written before the code too, but Kotlin can't compile them
    without the API, so their first run was against the finished code. Two failed on that run because of test
    mistakes (MockWebServer's `url()` says `localhost`, not `127.0.0.1`), and I fixed the tests.
- Deviations from the spec, and why:
  - Owner approval is per host, as SPEC §3 says ("may use its own host"). CLAUDE.md says "unless the owner typed
    that exact feed URL". I read that as the trigger for the approval, not a limit to one URL, so the feed's
    enclosures on its own home server work. `AddressPolicy.ownerTyped(url)` takes the host; S4/S5 store it with
    the feed. If the owner wants it limited to the exact URL, it's a one-line change.
  - Also blocked, beyond the SPEC list: 0.0.0.0/8, 240.0.0.0/4 (including broadcast), fec0::/10 (site-local),
    and the IPv4 inside IPv4-compatible, NAT64 (64:ff9b::/96, used by IPv6-only mobile networks) and 6to4
    addresses. Not blocked: 198.18.0.0/15 (benchmarking), because "fake-IP" VPN and proxy apps answer every
    DNS query from it and blocking it would break all traffic for their users; TEST-NETs (not routable anyway);
    Teredo.
  - A DNS answer that mixes public and private addresses keeps the public ones instead of failing the host.
    OkHttp only connects to what the Dns returns, so this is just as safe and more tolerant of misconfigured
    dual-stack hosts.
  - Numeric hosts that aren't a plain dotted quad (`127.1`, `2130706433`, `0177.0.0.1`) are refused outright.
  - System proxies are ignored (`Proxy.NO_PROXY`). Through a proxy, the gate never sees the target address.
    VPNs still work: they act below the app.
  - Total time limits per kind, which SPEC §3 doesn't list (CLAUDE.md: every parser has time limits): 2 min for
    feeds and OPML, 1 min for chapters, transcripts and images. Downloads have none, as the spec says.
  - "Read stalls 30 s" became two checks: the socket read timeout (30 s) and a stall watchdog (fewer than
    1 KiB in any 30 s window). The watchdog catches a slow drip that keeps the socket busy.
  - "MB" is read as MiB (1,048,576 bytes).
  - Header values from servers (ETag, Last-Modified) longer than 256 characters, or containing anything but
    printable ASCII, are dropped. OkHttp would throw when sending them back.
- Manual checks for the owner: none for this session. There's no UI yet. The acceptance item "a feed you
  control that points an enclosure at a private address is refused" can be tried once S5's Developer screen
  exists.
- PR: #3 (`m1-s2-network-gate`).

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
- Tests added (65, all passing):
  - `build-logic`: HeaderCheckTest (10), ForbiddenApiCheckTest (16), ClosedDependencyCheckTest (6), and
    GuardrailFunctionalTest (10). The functional tests run the real tasks in throwaway Gradle projects. They prove
    that the header, forbidden-API and closed-dependency checks fail the build on bad samples, including a
    transitive banned dependency and one that can't be resolved, and pass on clean code. Bad samples are in
    `build-logic/src/test/resources/samples/*.sample`, outside every scanned set.
  - `:app`: AppSettingsTest (7): fresh install is Dark, Light persists across a store restart, a corrupt file falls
    back to Dark. NightModeMappingTest (3). ColorTokensTest (5): WCAG AA on every surface, tokens match the spec,
    and the XML window backgrounds match the tokens.
  - `:core:model`: InvariantsTest (4).
  - `:app` debug: StrictModeFilterTest (4): our frames crash; vendor-only stacks and look-alike packages only log.
  - Also checked by hand: a `WebView` import added to `:app` fails `./gradlew check`, and a corrupted checksum in
    the verification metadata fails the build. Both were reverted.
  - The first CI run failed: verification metadata generated with a warm local cache missed two POMs/BOMs on the
    `build-logic` plugin classpath. I regenerated it from an empty `GRADLE_USER_HOME` and confirmed with a second
    clean-cache build. The README now says to always regenerate that way.
- Deviations from the spec, and why:
  - Light `textQuiet` is #5C6E80, not #617385. The spec value is 4.26:1 on surfaceElevated, below WCAG AA; the spec
    allows adjusting. All other tokens are as specified.
  - The forbidden-API check goes beyond SPEC §14 with rules taken from CLAUDE.md: other HTTP clients
    (`HttpURLConnection`, `openConnection`, `openStream`), Coil networking, `import android.` in plain modules, and
    hex/named colors in `:app` outside `ui/theme`. It's a strict token scan that includes comments, so even a comment
    naming `WebView` fails it.
  - Settings › Appearance was built now, in minimal form; the full Settings arrive in S9. The "Light persists across
    restart" check needs a way to switch.
  - StrictMode: the thread policy logs every violation, and crashes only when our own package
    (`us.dikarya.podplayer.*`) is on the stack (`StrictModeFilter`). On the owner's Galaxy S24 Ultra (Android 17),
    Samsung's framework `android.app.IdsController` reads SharedPreferences on the main thread during every
    activity resume. The original penaltyDeath killed the app at launch even though no app code was involved.
    The VM policy only logs. Untagged-socket detection is off (OkHttp doesn't tag sockets).
  - Android cloud backup is off (`allowBackup=false`, plus extraction rules that exclude everything from cloud
    backup), because feed URLs can carry tokens. Device-to-device transfer is still allowed. Our own backups are S4.
  - The header check enforces `.kt`, `.kts`, `.java` and `.aidl` (AIDL arrives in S6). XML, TOML and YAML have the
    notice, but it isn't enforced. The six Material Symbols icons are Apache-2.0 and keep no MPL notice (THIRD_PARTY.md).
  - Lint ignores the time-dependent checks (`GradleDependency`, `NewerVersionAvailable`,
    `AndroidGradlePluginVersion`, `OldTargetApi`), so CI doesn't fail just because upstream shipped a release.
  - AndroidX ships two prebuilt native libraries inside its AARs (Compose `libandroidx.graphics.path.so`,
    DataStore `libdatastore_shared_counter.so`). They aren't our native code; they're listed in THIRD_PARTY.md.
- Checked on the owner's phone (Galaxy S24 Ultra, Android 17), signed with the release key: the debug build
  installs, opens dark with Home/Discover/Library/Queue and the Settings icon, and runs with no crash.
- Confirmed by the owner on the phone (2026-11-08): Light persists across a force stop and relaunch, and
  "Use system setting" follows the system dark-mode toggle.
- Manual checks for the owner (still open):
  - TalkBack reads "Settings" on the gear, "Back" on the arrow, and the tab names on the bottom bar.
- PR: #1 (skeleton, guardrails, shell) and #2 (release-key signing, StrictMode fix), both merged.

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
- Signing key (owner, 2026-11-08): it's kept on the owner's machine in `~/keystores/podplayer/` (PKCS12,
  RSA 4096, 50 years, alias `podplayer`), and the owner keeps the offsite backup text file. Certificate SHA-256:
  `0F:C9:66:04:4F:25:30:68:1D:C7:05:09:B6:95:16:CF:A2:D6:DC:55:28:C3:48:B6:4C:99:82:C1:73:DB:27:64`.
  Debug builds are signed with it too, so the M1 daily-use debug install can be updated by a release build
  without losing data. CI has no key and builds unsigned releases.
- Nav icons are Material Symbols vector drawables copied into `res/drawable`, not the Compose icons artifact.
- The closed-dependency check is a denylist (SPEC §2 "Never" list plus named crash, analytics, ad, WebView, FFmpeg and
  native-media groups, and `coil-network-*`). It covers every resolvable classpath of every module (all variants,
  unit and instrumented tests, compiler plugins, lint checks) and the root build script classpath, which holds
  the Gradle plugins.

- Network gate (M1-S2): callers get a `Call.Factory` per `AddressPolicy`, never an `OkHttpClient`. Redirects are
  followed by an application interceptor, not by OkHttp, so the same checks cover `fetch()`, downloads and
  ExoPlayer. A feed's new URL after redirects is `RedirectChain.permanentUrl`: the end of the leading run of
  301/308 hops.

- Feeds (M1-S3): the parsers take an `XmlParserProvider` (the app passes `Xml.newPullParser()`), return typed
  results and never throw for bad input. Episode notes are stored raw and sanitized at display time.
  `EpisodeMerge.fingerprint` is stored per episode so unchanged items cost no write; bump its `VERSION` when
  its fields change.
- Source files hold no invisible characters (BOM, U+FFFE, no-break space): spell them as code points
  (`Char(0xFEFF)`) or regex escapes (`\xA0`). The file-writing tool turns `\uXXXX` escapes into the
  characters themselves, and lint refuses a BOM in the middle of a file.

- Data layer (M1-S4): `AppGraph.data` (`DataLayer`) opens the database in the background; consumers call
  `awaitRepositories()` or watch `state`. Never build Room any other way than `PodDatabases.builder`. A schema
  change needs a version bump, a migration in `Migrations.ALL` and a seed in `MigrationSeeds` (tests enforce
  all three). Settings that must not travel with a backup go in `DeviceSettings`; everything in `AppSettings`
  is backed up. Workers are built by `PodplayerWorkerFactory`.

## Questions for owner
<!-- Blockers or choices that need the owner. Do not work around them. -->
- None open. (Signing key: answered 2026-11-08; see Decisions.)

## Later
<!-- Ideas and fixes outside the current session's scope. -->
- S5: the owner-approved host is stored (`Feed.ownerApprovedHost`); pass
  `AddressPolicy.approvingHosts(host)` for that feed's requests (feed, enclosures, artwork). The columns for
  `Validators` exist (`feeds.etag/lastModified`, `media_files.etag/lastModified`); S5 reads and writes them.
- S5: add the INTERNET permission to the manifest with the first network feature.
- S5: delete a feed's media files on unsubscribe (rows cascade, files stay); `MediaFileRepository.reconcileWithDisk`
  handles the reverse case.
- S9: log export (concatenate `AppLog.files()`, crash files, and the DB logs) and the full Backup settings.
- A test that the release variant of `BuildTypeSetup` logs invariants instead of crashing (only the debug one is
  exercised by tests today).
- S5: private feeds with `user:pass@` in the URL. OkHttp doesn't turn URL userinfo into an `Authorization` header,
  so the refresh worker has to add it. The redirect interceptor already drops it on a cross-host hop.
- S7: ExoPlayer gets `OkHttpDataSource.Factory(netGate.callFactory(policy))`. Map its `IOException`s with
  `NetError.from` for the position/playback log.
- Redaction: some hosts put the token in the path (`/feed/<token>.xml`). The rule only asks to strip query,
  fragment and userinfo. Consider also shortening long path segments in logs.
- The `[::1]` and `[fc00::1]` tests prove refusal, not that a real IPv6 connection would have worked without the
  filter. MockWebServer listens on IPv4 only, since CI may not have IPv6.
- Enforce the allowlist mechanically: direct dependencies must come from catalog entries mapped to SPEC §2.
- Release builds: turn on R8 (minify and resource shrinking) with keep rules. (The signing config is done.)
- A Robolectric Compose test for the shell (four tabs, Settings icon, the theme switch recomposing). Robolectric
  is set up now (`podplayer.android.robolectric`); `:app` doesn't apply it yet.
- Enforce the MPL notice in XML, TOML and YAML files (it's placed there now but not checked).
- S4/S5: run the feed corpus through Android's own parser too (`Xml.newPullParser()` under Robolectric or an
  instrumented test). The JVM tests use kxml2 2.2.2; Android's libcore parser differs in places (it reads
  DOCTYPE internal subsets). The pre-pass removes every DOCTYPE first, so the results should match.
- S5: write `ParseProblem.logReason` and the feed warnings (repairs, broken feed, refused URLs) to the refresh log.
- S8: `RichText` to `AnnotatedString`; tapping a link shows `Link.destination` first; timestamps seek.
- `itunes:new-feed-url` (a feed that moved without a 301) is not in SPEC §4's field list and isn't parsed yet.
- RSS 1.0 (RDF) feeds, Atom `xml:base` relative URLs, and `feed://`/`pcast://` URLs in OPML and Discover
  (convert to https?) if a real subscription needs them.
- S11: seed the Jazzer feed and OPML targets from `core/testing/src/main/resources/corpus`.

## Known issues
<!-- Bugs found but not yet fixed, with how to reproduce. -->
- None known. The app hasn't run on a device yet (see the manual checks above).
