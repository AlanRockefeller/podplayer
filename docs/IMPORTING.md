# Importing from another podcast app

Podplayer imports two things:

- **Shows:** Discover › Import a list. Paste or share show names or feed addresses, one per line. Each name is
  looked up on Apple's podcast search, and you check every match before anything is subscribed. An OPML file
  works too (Discover › Import OPML file).
- **Episode marks:** an import file (below) sets played, a resume position or a star on episodes. Open it from
  Import a list › Open a file, or share its text into the app.

Nothing is subscribed or changed until you confirm. Marks never overwrite progress Podplayer already has.

## The import file (format v1)

```json
{
  "format": "podplayer-import",
  "version": 1,
  "source": "where it came from",
  "shows": [
    {
      "title": "Show name",
      "feedUrl": "https://example.com/feed.xml",
      "episodes": [
        { "title": "Episode title", "published": "2026-11-15", "played": true },
        { "title": "Another one", "published": "2026-11-14", "positionMs": 1102360 },
        { "title": "A favorite", "published": "2026-08-19", "starred": true }
      ]
    }
  ]
}
```

- A show is found by `feedUrl`, else by `title` through Apple's search. Episodes are matched by title, then by a
  `published` date within 2 days. Two episodes with the same title less than 2 days apart can't be told apart,
  so neither gets the mark; the summary lists it under "Episodes that didn't match".
- Limits: 4 MiB, 500 shows, 20,000 episodes, 300 characters per text. A file over a limit is refused as a whole.
- The file holds feed addresses, and private feeds carry tokens in them. Delete the file after importing, and
  don't share it.

## From Podbean, over adb

Podbean keeps its data private: the app isn't debuggable, it doesn't allow backups, and its account website
doesn't list favorites. What it shows on screen can still be read over USB debugging, so
`tools/podbean_export.py` scrolls Podbean's screens and records their rows. Tested with Podbean on Android 17
(Galaxy S24 Ultra, 2026-11-16).

What you can get:

| Podbean screen | What it gives | Notes |
| --- | --- | --- |
| Library | Followed shows: name and publisher | All of them |
| Profile › Play History | Title, date, length, "played" or "N% left" | Only the last week or so; rows don't name their show |
| Profile › Favorites | Title, date, length | Rows don't name their show |

Podbean's downloads (`Android/data/com.podbean.app.podcast/media_files/`) are named by number with few tags, so
they aren't worth importing; Podplayer downloads episodes again as needed.

### 1. Connect the phone

1. Turn on Developer options and USB debugging (Settings › About phone › Software information › tap Build number
   seven times; then Settings › Developer options › USB debugging).
2. Samsung phones: turn off **Auto Blocker** (Settings › Security and privacy › Auto Blocker). It blocks USB
   debugging, and it **turns itself back on after about 30 minutes**, which drops the connection mid-run. If
   `adb devices` comes back empty, turn it off again. A step that loses the phone stops with an error and saves
   the rows it read to `<file>.partial`; reconnect and run the step again.
3. Plug in the phone, accept the debugging prompt, and check `adb devices` lists it.
4. Keep the screen on while a step runs (Settings › Display › Screen timeout, or Developer options › Stay awake
   while charging).

### 2. Import your shows

1. On the phone, open Podbean › Library, scrolled to the top. Then:

   ```sh
   python3 tools/podbean_export.py library library.json
   python3 tools/podbean_export.py names library.json names.txt
   ```

   Look through `names.txt` and delete lines that aren't shows (Podbean's "All Podcasts" header, stray rows).

2. Share the names into Podplayer (or copy them to the phone and paste them into Discover › Import a list):

   ```sh
   adb push names.txt /sdcard/Download/names.txt
   adb shell 'am start -a android.intent.action.SEND -t text/plain \
     --es android.intent.extra.TEXT "$(cat /sdcard/Download/names.txt)" us.dikarya.podplayer'
   adb shell rm /sdcard/Download/names.txt
   ```

   Import a list opens with the names filled in. Tap **Find shows**.

3. Matching asks Apple one name every few seconds, so 230 shows take 15 minutes or more. Keep Podplayer open on
   that screen with the screen on.

4. Check the matches before you tap **Subscribe**:
   - "The search didn't work" means Apple throttled the searches. Wait a minute and tap **Search again** on
     each.
   - Short or common names often match the wrong show (in our run: "15", "Risky Business", "The War on Drugs",
     and season shows like "The Mile Zero Murders from Uncover"). Tap **Change** to pick another result, or
     **Skip**.
   - "Not secure" only means the feed uses http. It still works.
   - "No match found": add that show by hand later (search with fewer words, or paste its feed address).

5. Tap **Subscribe to N shows**. Let the shows refresh before the next step, so their episodes are in the library.

### 3. Import history and favorites (optional)

History and favorite rows don't name their show, so they are matched against the episodes now in Podplayer.
This needs a copy of Podplayer's database, which only a **debug build** allows (`run-as`):

1. On the phone, open Podbean › Profile › Play History; then Favorites. For each:

   ```sh
   python3 tools/podbean_export.py history history.json
   python3 tools/podbean_export.py favorites favorites.json
   ```

2. Copy Podplayer's database (both files, so recent changes are included):

   ```sh
   adb exec-out run-as us.dikarya.podplayer cat databases/podplayer.db > podplayer.db
   adb exec-out run-as us.dikarya.podplayer cat databases/podplayer.db-wal > podplayer.db-wal
   ```

3. Build the import file. Played history rows become played; "N% left" rows become a resume position. Favorites
   are starred only for the titles you name with `--star` (Podbean favorites are easy to tap by accident):

   ```sh
   python3 tools/podbean_export.py marks podplayer.db marks.json --history history.json \
     --favorites favorites.json --star "Exact episode title"
   ```

   It prints any row it couldn't match to exactly one episode.

4. Share `marks.json` into Podplayer as above (`adb push`, then the `am start … SEND` command with
   `marks.json`), tap **Find shows**, then **Subscribe**. Shows you already have stay as they are; their episodes
   get the marks. The summary lists episodes that didn't match.

5. Delete the copies: `marks.json`, `podplayer.db*` on the computer and the pushed file on the phone. The
   database and the import file both contain your feed addresses.

### What happened in our run (2026-11-15/16)

- 230 followed shows: 211 added, 12 already there, 5 wrong matches skipped, 2 not found (one wrong match after a
  retry, one not on Apple). 21 searches were throttled and needed "Search again".
- History: 27 rows, all matched. Podbean listed two "Episode 376" rows for one show whose feed has a single
  one; that mark didn't apply.
- Favorites: 11 items going back to April 2024, only one wanted. Older stars the owner remembered were in
  neither the app nor podbean.com.
