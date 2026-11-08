# Roadmap after Milestone 1

Each milestone below gets its own `M<n>.md` with numbered sessions, written after the previous milestone's
review so it reflects what was actually built. These outlines fix scope and gates only.

## M2: Ad skipping, sync and audio polish

App
- Ad skipping, off by default. Per-show modes: Off, Skip button, Auto-skip. Global default Off.
- Layer 1: publisher chapters labeled as ads or sponsors (ID3 chapter frames and `podcast:chapters`).
- Layer 2: user-taught sound templates. "Mark ad sound" while listening, trim 1–4 s on a waveform; features are
  log-mel spectrograms of 16 kHz mono; matching by normalized cross-correlation in plain Kotlin; runs after
  download or over the read-ahead cache; break rules per show (sound starts and ends; A starts, B ends;
  starts then ends at next sound or after N minutes); breaks 10 s–10 min; unpaired sounds offer
  "Skip to next chime" instead of auto-skipping.
- UX: hatched bands on the seek bar; "Skipped 1:32 · Undo"; seeking into a break plays it; corrections stay
  on the phone and tune that show's thresholds; categories (paid ads, cross-promos, creator appeals,
  intros/outros) are separate switches; funding links shown on the episode page.
- Chapters UI (publisher chapters), skip first/last N seconds per show, loudness normalization (measured once
  after download, applied as gain), Inbox triage.
- Sync client for the gpodder subscription and episode-action API, using the `episode_actions` log.
- Performance target to measure: under 30 s of background CPU per hour of audio on a mid-range 2022 phone.

Server (new private repository)
- Python service, SQLite, files on disk, Caddy for HTTPS, token auth, SSH keys only, firewall, automatic
  security updates, nightly backups.
- gpodder-compatible subscription and episode-action endpoints.

Gate: on the owner's own shows, no skip removes real content without the owner noticing; two devices stay in sync.

## M3: Translated transcripts

Server
- Jobs: fetch (through a private-address filter), normalize in a sandboxed FFmpeg, transcribe, translate,
  build the manifest. Adapters for providers so they can be swapped.
- Content anchors: fingerprints of about 3 s of audio every 20–30 s, with offsets; every sentence span stored
  as nearest anchor plus offset.
- Monthly API budget, $20 by default, set from the app; per-job estimate; warn at 80%; hold jobs that would
  pass the cap; spend recorded per job and per month. Provider-console limits as a backstop.
- Transcripts treated as untrusted data in prompts; constrained output format; validation.
- Artifact lineage table (id, parents, config hash, model version); idempotency keys.

App
- Translate sheet with the estimate; honest job states; polling while open plus a periodic check.
- Translated rendition: translated text over the original audio, read-along, bilingual toggle, tap a sentence
  to replay its original span from the phone's own copy, offline package.
- Anchor matching on the phone maps server times to local times; MP3 index seeking for exact replay.

Gate: on two downloads with different ad loads, replay and highlighting stay within ±250 ms.

## M4: Dubbed audio and smarter ad detection
- Spanish rendition with 2–3 named delivery presets (not a slider); ad breaks play in the original language.
- Opt-in server ad detection per show, reusing translation transcripts where they exist.
- Cross-episode repetition detection and insertion-seam cues on the phone; copy comparison through anchors.

Gate: a few months of use, then the owner decides whether this has commercial value.

## M5: Only if going public
- Widget, Wear OS, accessibility audit, multi-user server, opt-in crash reporting, F-Droid and store builds,
  billing, rights strategy for other people's feeds, iOS on a Kotlin Multiplatform core.
