# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Checks for podbean_export.py's `marks` step. Run: python3 -m unittest discover -s tools"""

import contextlib
import datetime
import io
import json
import os
import sqlite3
import subprocess
import tempfile
import unittest
from unittest import mock

import podbean_export


def day_ms(text):
    day = datetime.datetime.strptime(text, "%Y-%m-%d").replace(tzinfo=datetime.timezone.utc)
    return int(day.timestamp() * 1000)


class MarksTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.db = os.path.join(self.dir.name, "podplayer.db")
        db = sqlite3.connect(self.db)
        db.execute("CREATE TABLE feeds (id INTEGER PRIMARY KEY, title TEXT, url TEXT)")
        db.execute("CREATE TABLE episodes (id INTEGER PRIMARY KEY, feedId INTEGER, title TEXT, publishedAt INTEGER)")
        db.execute("INSERT INTO feeds VALUES (1, 'Science Hour', 'https://feeds.example.com/science.xml')")
        db.execute("INSERT INTO episodes VALUES (1, 1, 'Black holes', ?)", (day_ms("2026-03-04"),))
        db.execute("INSERT INTO episodes VALUES (2, 1, 'Quarks', ?)", (day_ms("2026-03-11"),))
        db.commit()
        db.close()

    def write(self, name, rows):
        path = os.path.join(self.dir.name, name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(rows, f)
        return path

    def marks(self, history, favorites, stars):
        out = os.path.join(self.dir.name, "marks.json")
        with contextlib.redirect_stdout(io.StringIO()):
            podbean_export.write_marks(self.db, out, history, favorites, stars)
        with open(out, encoding="utf-8") as f:
            return json.load(f)["shows"]

    def test_an_episode_in_history_and_favorites_gets_one_mark_with_both(self):
        # Codex review (PR 6): two marks for one episode; the importer matches each episode once, so the star was lost.
        history = self.write("history.json", [
            {"title": "Black holes", "date": "Mar 4, 2026", "length": "30:00", "played": True, "left": None},
            {"title": "Quarks", "date": "Mar 11, 2026", "length": "40:00", "played": False, "left": 50},
        ])
        favorites = self.write("favorites.json", [
            {"title": "Black holes", "date": "Mar 4, 2026", "length": "30:00", "played": False, "left": None},
            {"title": "Quarks", "date": "Mar 11, 2026", "length": "40:00", "played": False, "left": 50},
        ])
        shows = self.marks(history, favorites, ["Black holes", "Quarks"])
        self.assertEqual(1, len(shows))
        self.assertEqual([
            {"title": "Black holes", "published": "2026-03-04T00:00:00Z", "played": True, "starred": True},
            {"title": "Quarks", "published": "2026-03-11T00:00:00Z", "positionMs": 1_200_000, "starred": True},
        ], shows[0]["episodes"])

    def test_a_played_mark_wins_over_a_position(self):
        history = self.write("history.json", [
            {"title": "Quarks", "date": "Mar 11, 2026", "length": "40:00", "played": False, "left": 50},
            {"title": "Quarks", "date": "Mar 11, 2026", "length": "40:00", "played": True, "left": None},
        ])
        shows = self.marks(history, None, [])
        self.assertEqual([{"title": "Quarks", "published": "2026-03-11T00:00:00Z", "played": True}],
                         shows[0]["episodes"])

    def test_the_mark_carries_the_matched_episodes_own_time(self):
        # CodeRabbit review (PR 6): the screen's date (Jan 1, midnight) is 2 calendar days but more than 48 hours from
        # the episode it matched (Jan 3, 18:00), so with a same-title episode elsewhere the importer matched neither.
        db = sqlite3.connect(self.db)
        late = day_ms("2026-01-03") + 18 * 3600 * 1000 + 250
        db.execute("INSERT INTO episodes VALUES (3, 1, 'Mailbag', ?)", (late,))
        db.execute("INSERT INTO episodes VALUES (4, 1, 'Mailbag', ?)", (day_ms("2026-01-10"),))
        db.commit()
        db.close()
        history = self.write("history.json", [
            {"title": "Mailbag", "date": "Jan 1, 2026", "length": "30:00", "played": True, "left": None},
        ])
        shows = self.marks(history, None, [])
        self.assertEqual([{"title": "Mailbag", "published": "2026-01-03T18:00:00.250Z", "played": True}],
                         shows[0]["episodes"])


class FakeAdb:
    """Stands in for subprocess.run. After `fail_after` pulls, the adb commands named in `failing` (all when None)
    fail: a nonzero exit, or a timeout."""

    SCREEN = ('<hierarchy><node text="Science Hour" bounds="[0,800][100,900]"/>'
              '<node text="by Example Media" bounds="[0,900][100,1000]"/></hierarchy>')

    def __init__(self, fail_after=None, failure="exit", failing=("pull",)):
        self.pulls = 0
        self.fail_after = fail_after
        self.failure = failure
        self.failing = failing

    def __call__(self, cmd, **_):
        args = cmd[1:]
        name = args[1] if args[0] == "shell" else args[0]
        if self.fail_after is not None and self.pulls >= self.fail_after and \
                (self.failing is None or name in self.failing):
            if self.failure == "timeout":
                raise subprocess.TimeoutExpired(cmd, 30)
            return subprocess.CompletedProcess(cmd, 1, "", "error: no devices/emulators found")
        if args[:3] == ["shell", "wm", "size"]:
            return subprocess.CompletedProcess(cmd, 0, "Physical size: 1080x2340\n", "")
        if args[:1] == ["pull"]:
            self.pulls += 1
            with open(args[2], "w", encoding="utf-8") as f:
                f.write(self.SCREEN)
        return subprocess.CompletedProcess(cmd, 0, "", "")


class CollectTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.out = os.path.join(self.dir.name, "library.json")
        patch_sleep = mock.patch.object(podbean_export.time, "sleep")
        patch_sleep.start()
        self.addCleanup(patch_sleep.stop)

    def collect(self, fake):
        with mock.patch.object(podbean_export.subprocess, "run", fake), contextlib.redirect_stdout(io.StringIO()):
            podbean_export.collect("library", self.out)

    def test_a_run_that_reaches_the_end_writes_its_rows(self):
        self.collect(FakeAdb())
        with open(self.out, encoding="utf-8") as f:
            self.assertEqual([{"title": "Science Hour", "author": "Example Media"}], json.load(f))

    def test_a_disconnect_part_way_stops_without_a_complete_looking_file(self):
        # CodeRabbit review (PR 6): failed pulls left the last screen's dump in place, it was reread three times,
        # and the run reported a complete export.
        for failure in ("exit", "timeout"):
            with self.subTest(failure=failure):
                with self.assertRaises(SystemExit) as stop:
                    self.collect(FakeAdb(fail_after=1, failure=failure))
                self.assertIn("Stopped", str(stop.exception))
                self.assertFalse(os.path.exists(self.out))
                with open(self.out + ".partial", encoding="utf-8") as f:
                    self.assertEqual([{"title": "Science Hour", "author": "Example Media"}], json.load(f))

    def test_no_phone_at_all_stops_before_reading_anything(self):
        with self.assertRaises(SystemExit):
            self.collect(FakeAdb(fail_after=0, failing=None))
        self.assertFalse(os.path.exists(self.out))


if __name__ == "__main__":
    unittest.main()
