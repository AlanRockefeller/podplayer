#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Reads your shows, play history and favorites out of the Podbean app over adb, for Podplayer's importer.

Podbean keeps its data private (no backup, not debuggable), but its screens can be read with
`uiautomator dump`. This script scrolls a Podbean screen you have open and records its rows. See
docs/IMPORTING.md for the whole method.

    podbean_export.py library   library.json      # Podbean › Library (followed shows), open on the phone
    podbean_export.py history   history.json      # Podbean › Profile › Play History, open on the phone
    podbean_export.py favorites favorites.json    # Podbean › Profile › Favorites, open on the phone
    podbean_export.py names     library.json names.txt
    podbean_export.py marks     podplayer.db marks.json --history history.json \\
                                [--favorites favorites.json --star "Exact episode title" ...]

`names` writes one show name per line, for Discover › Import a list. `marks` matches history and favorite rows
against episodes in a copy of Podplayer's database (debug builds: see docs/IMPORTING.md) and writes an import
file (format v1) with played marks, positions and stars. Every text read from the screen is untrusted: it is
only stored, never run. Python 3.8+, standard library only; needs `adb` on PATH and one phone connected.
"""

import argparse
import datetime
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import unicodedata
import xml.etree.ElementTree as ET

DATE = re.compile(r"^[A-Z][a-z]{2} \d{1,2}, \d{4}$")
LENGTH = re.compile(r"^(\d{1,2}:)?\d{1,2}:\d{2}$")
LEFT = re.compile(r"^(\d{1,3})% left$")
BOUNDS = re.compile(r"\d+")
# Rows between the header and the mini-player/navigation bar, as a share of the screen height.
TOP, BOTTOM = 0.11, 0.85
# A matched episode's date may differ by this many days (time zones, feeds that re-date episodes).
DATE_SLACK_DAYS = 2


class AdbError(Exception):
    """An adb command failed: usually the phone disconnected (Samsung's Auto Blocker, a loose cable)."""


def adb(*args):
    try:
        result = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise AdbError(f"adb {args[0]}: {e}") from e
    if result.returncode != 0:
        raise AdbError(f"adb {args[0]} failed ({result.returncode}): {(result.stderr or result.stdout).strip()[:200]}")
    return result


def screen_height():
    out = adb("shell", "wm", "size").stdout
    sizes = re.findall(r"(\d+)x(\d+)", out)
    return int(sizes[-1][1]) if sizes else 2340


def screen_texts(dump_path, height):
    """The visible texts, top to bottom, inside the list area."""
    # Never reread an earlier screen: the dump is pulled fresh each time, and a failed pull stops collection.
    if os.path.exists(dump_path):
        os.remove(dump_path)
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    adb("pull", "/sdcard/ui.xml", dump_path)
    adb("shell", "rm", "/sdcard/ui.xml")
    found = []
    for node in ET.parse(dump_path).getroot().iter("node"):
        text = node.get("text")
        if not text:
            continue
        x1, y1, _, _ = map(int, BOUNDS.findall(node.get("bounds", "0 0 0 0")))
        if TOP * height < y1 < BOTTOM * height:
            found.append((y1, x1, text))
    return sorted(found)


def library_rows(texts):
    # Library rows: the show's title, then "by <publisher>".
    return [
        {"title": texts[i - 1][2], "author": t[3:]}
        for i, (_, _, t) in enumerate(texts)
        if t.startswith("by ") and i > 0
    ]


def episode_rows(texts):
    # History and Favorites rows: title, date, length, then "played" or "N% left".
    rows = []
    for i, (_, _, t) in enumerate(texts):
        if not DATE.match(t) or i == 0 or DATE.match(texts[i - 1][2]):
            continue
        after = [u for (_, _, u) in texts[i + 1:i + 4]]
        rows.append({
            "title": texts[i - 1][2],
            "date": t,
            "length": next((u for u in after if LENGTH.match(u)), None),
            "played": "played" in after,
            "left": next((int(m.group(1)) for u in after if (m := LEFT.match(u))), None),
        })
    return rows


def collect(kind, out):
    """Scrolls the open screen to its end (three scrolls that add nothing) and saves its rows.

    If adb fails part-way, the rows read so far go to `<out>.partial` and the run stops with an error; `out` is
    only written by a run that reached the end.
    """
    parse = library_rows if kind == "library" else episode_rows
    seen, rows, quiet = set(), [], 0
    try:
        height = screen_height()
        with tempfile.TemporaryDirectory() as tmp:
            dump = os.path.join(tmp, "ui.xml")
            while quiet < 3:
                added = 0
                for row in parse(screen_texts(dump, height)):
                    key = (row["title"], row.get("date") or row.get("author"))
                    if key not in seen:
                        seen.add(key)
                        rows.append(row)
                        added += 1
                quiet = quiet + 1 if added == 0 else 0
                adb("shell", "input", "swipe", "540", str(int(height * 0.72)), "540", str(int(height * 0.34)), "500")
                time.sleep(1.2)
    except AdbError as e:
        partial = out + ".partial"
        with open(partial, "w", encoding="utf-8") as f:
            json.dump(rows, f, indent=1, ensure_ascii=False)
        sys.exit(f"Stopped: {e}\nIncomplete: {len(rows)} {kind} rows so far -> {partial}. "
                 "Reconnect the phone (check `adb devices`) and run this step again.")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rows, f, indent=1, ensure_ascii=False)
    print(f"{len(rows)} {kind} rows -> {out}")


def write_names(library, out):
    with open(library, encoding="utf-8") as f:
        shows = json.load(f)
    names = [s["title"].strip() for s in shows if s["title"].strip()]
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(names) + "\n")
    print(f"{len(names)} show names -> {out}")


def norm(text):
    text = unicodedata.normalize("NFKC", text or "").replace("’", "'")
    return re.sub(r"\s+", " ", text).strip().lower()


def seconds(length):
    total = 0
    for part in length.split(":"):
        total = total * 60 + int(part)
    return total


def iso_utc(epoch_ms):
    """`2026-03-04T08:30:00Z` (with `.123` when there are milliseconds), as the import file takes it."""
    moment = datetime.datetime.fromtimestamp(epoch_ms // 1000, datetime.timezone.utc)
    millis = f".{epoch_ms % 1000:03d}" if epoch_ms % 1000 else ""
    return moment.strftime("%Y-%m-%dT%H:%M:%S") + millis + "Z"


def write_marks(db_path, out, history, favorites, stars):
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    by_title = {}
    query = "SELECT e.title, e.publishedAt, f.title, f.url FROM episodes e JOIN feeds f ON f.id = e.feedId"
    for title, published, show, url in db.execute(query):
        by_title.setdefault(norm(title), []).append((published, show, url))

    rows = []
    if history:
        with open(history, encoding="utf-8") as f:
            rows += [(r, False) for r in json.load(f)]
    if favorites:
        with open(favorites, encoding="utf-8") as f:
            wanted = {norm(s) for s in stars}
            rows += [(r, True) for r in json.load(f) if norm(r["title"]) in wanted]

    # One mark per matched library episode: the importer matches each episode once, so a second mark for the same
    # episode (it is in both history and favorites) would be dropped and its star lost. Fields are merged instead.
    shows, marks, missed = {}, {}, []
    for row, star in rows:
        day = datetime.datetime.strptime(row["date"], "%b %d, %Y").date()
        candidates = [
            c for c in by_title.get(norm(row["title"]), [])
            if c[0] and abs((datetime.datetime.fromtimestamp(c[0] / 1000, datetime.timezone.utc).date() - day).days)
            <= DATE_SLACK_DAYS
        ]
        if len(candidates) != 1:
            missed.append(f"{row['title']} ({row['date']}): {len(candidates)} matches")
            continue
        published, show, url = candidates[0]
        key = (url, norm(row["title"]), published)
        mark = marks.get(key)
        if mark is None:
            # The matched episode's own time, not the screen's date: the importer settles same-title episodes
            # within an exact 48 hours of it, while the match above allows 2 calendar days.
            mark = marks[key] = {"title": row["title"], "published": iso_utc(published)}
            shows.setdefault(url, {"title": show, "feedUrl": url, "episodes": []})["episodes"].append(mark)
        if star:
            mark["starred"] = True
        if row.get("played"):
            mark["played"] = True
            mark.pop("positionMs", None)
        elif not mark.get("played") and "positionMs" not in mark and row.get("left") is not None and row.get("length"):
            mark["positionMs"] = int(seconds(row["length"]) * (1 - row["left"] / 100) * 1000)

    data = {"format": "podplayer-import", "version": 1, "source": "Podbean (read over adb)",
            "shows": list(shows.values())}
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, ensure_ascii=False)
    print(f"{sum(len(s['episodes']) for s in shows.values())} marks in {len(shows)} shows -> {out}")
    for line in missed:
        print("  not matched:", line)
    # The file holds feed addresses, which can carry private tokens: keep it off shared places.
    print("Note: the file contains feed addresses; delete it from the phone after importing.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    for kind in ("library", "history", "favorites"):
        sub.add_parser(kind).add_argument("out")
    names = sub.add_parser("names")
    names.add_argument("library")
    names.add_argument("out")
    marks = sub.add_parser("marks")
    marks.add_argument("db")
    marks.add_argument("out")
    marks.add_argument("--history")
    marks.add_argument("--favorites")
    marks.add_argument("--star", action="append", default=[], help="a favorite's exact title to star (repeatable)")
    args = parser.parse_args()

    if args.cmd in ("library", "history", "favorites"):
        collect(args.cmd, args.out)
    elif args.cmd == "names":
        write_names(args.library, args.out)
    else:
        if not args.history and not args.favorites:
            sys.exit("marks needs --history and/or --favorites")
        write_marks(args.db, args.out, args.history, args.favorites, args.star)


if __name__ == "__main__":
    main()
