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
import tempfile
import unittest

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
            {"title": "Black holes", "published": "2026-03-04", "played": True, "starred": True},
            {"title": "Quarks", "published": "2026-03-11", "positionMs": 1_200_000, "starred": True},
        ], shows[0]["episodes"])

    def test_a_played_mark_wins_over_a_position(self):
        history = self.write("history.json", [
            {"title": "Quarks", "date": "Mar 11, 2026", "length": "40:00", "played": False, "left": 50},
            {"title": "Quarks", "date": "Mar 11, 2026", "length": "40:00", "played": True, "left": None},
        ])
        shows = self.marks(history, None, [])
        self.assertEqual([{"title": "Quarks", "published": "2026-03-11", "played": True}], shows[0]["episodes"])


if __name__ == "__main__":
    unittest.main()
