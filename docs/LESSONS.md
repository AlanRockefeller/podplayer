# Lessons from other open-source players

Each row is a failure that AntennaPod or Pocket Casts users hit, and the rule or test it implies for us.
Before building a module, find its rows here and turn each into a test or a manual check.
AntennaPod is GPL-3.0: read it to understand, never copy its code.

| Area | What went wrong elsewhere | Our rule | Test or check |
| --- | --- | --- | --- |
| Playback rewrite | AntennaPod's 2026 Media3 rewrite shipped betas missing pause-on-Bluetooth-disconnect (old code not ported), with intro skipping broken, the widget dead after idle, and UI out of sync with the service | Every playback rule exists as a test before code; the session is the only source of truth | One test per rule in SPEC §10; UI state derived only from the session |
| Resume after service death | Play from the notification and lock screen failed for a long time; the old media session API did not handle resuming after eviction | Implement Media3 playback resumption from persisted state | Kill the process, then play from lock screen, notification, headset and Auto; reboot and repeat |
| Audio focus | Playback not resuming after calls, navigation prompts or workout announcements; headset buttons starting a different player after a pause | Speech content pauses (never ducks); auto-resume after transient loss; session stays active while paused | Manual: phone call, navigation prompt, 60-second interruption, headset pause then play after 5 minutes |
| Bluetooth | Audio from the phone speaker after earbuds reconnect; auto-start on reconnect had to be removed | Never auto-start on connect; pause on becoming noisy; check output route before resuming | Manual: disconnect mid-play, reconnect, press play |
| Android Auto | Not resuming the last episode; a "For you" list instead of the queue; jumping to the queue at the next episode; emoji in titles reported to cause a disconnect loop | Root starts with Continue, then Queue; clean and cap titles | Desktop Head Unit: connect, play Continue, finish an episode, long and emoji titles |
| Episode identity | Publishers reuse GUIDs (one feed used one GUID 100 times), change them when moving hosts, or re-post with a new date | GUID scoped to feed with fallbacks; re-posts keep state; host moves keep state | Fixtures for each case |
| Parser strictness | A stricter parser stopped a feed with an unescaped `&` from updating at all | Recover from common publisher mistakes inside security limits; keep last good copy | Fixtures: bare `&`, bad dates, wrong encodings |
| Downloads | Servers ignoring Range; "size does not equal expected size" failures; slow servers timing out; tracker blockers breaking some hosts | Resume when possible; stall timeouts; accept by probing duration, not by exact size; readable download log | Fake server tests: no Range support, wrong Content-Length, slow drip, dropped connection |
| Refresh | Refresh loops that never stop and drain the battery; overnight scheduled downloads failing while manual ones work | One refresh at a time, bounded and cancelable; per-feed backoff; network constraints; show last/next refresh | Test: concurrent refresh requests collapse into one; failing feed backs off |
| Storage | Downloads orphaned after SD-card or Android path changes (absolute paths stored); subscriptions lost on an upgrade | Internal storage only; relative paths; automatic backups; backup before every migration; migration tests | Migration tests on saved old databases; backup, wipe, restore round trip |
| Position jumps | Pocket Casts has tracked playback skipping or repeating mid-episode, often streaming over Bluetooth | Log every position change with its cause; read-ahead cache; prefer downloads | Log review after a long streamed session |
| Platform updates | An Android security update made Pocket Casts' playback of added files crash the whole phone until fixed | Run tests on each Android beta and monthly update; avoid platform tricks | Owner checklist after each Android update |
| State-dependent actions | Tapping a chapter while a stream loaded crashed AntennaPod; Pocket Casts crashed when reopened after process death | Model player states explicitly; every action valid in every state | Tests: each command in idle, buffering, playing, paused, ended, error; reopen after process death |
| Sleep timer | "Stop at end of episode" requested for years; timer lived in a service Android could kill | Timer owned by the playback session: minutes, end of episode, fade-out | Tests: timer survives UI death; end-of-episode stops at the boundary |

AntennaPod's debug builds deliberately crash on non-fatal problems so they get noticed; ours do too.

Sources: AntennaPod forum threads and GitHub issues (#6576, #6608, #6792, #7145, #7165, #2146, PR #4535),
Pocket Casts forum threads and PR #2812. Links are in the owner's spec doc, "Lessons" section.
