/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesSnapshotTest {
    private val sample = mutablePreferencesOf(
        stringPreferencesKey("theme_mode") to "light",
        booleanPreferencesKey("wifi_only") to true,
        intPreferencesKey("skip_back_s") to 15,
        longPreferencesKey("storage_cap") to 10_000_000_000L,
        floatPreferencesKey("speed") to 1.25f,
        doublePreferencesKey("ratio") to 0.5,
        stringSetPreferencesKey("tags") to setOf("a", "b"),
    )

    @Test
    fun everyTypeRoundTrips() {
        val json = PreferencesSnapshot.encode(sample)
        val decoded = PreferencesSnapshot.decode(json)!!
        assertEquals(sample.asMap(), PreferencesSnapshot.toPreferences(decoded).asMap())
    }

    @Test
    fun structuralProblemsAreRefused() {
        assertNull(PreferencesSnapshot.decode("not json"))
        assertNull(PreferencesSnapshot.decode("[]"))
        assertNull(PreferencesSnapshot.decode("{\"format\": 2, \"entries\": []}"))
        assertNull(PreferencesSnapshot.decode("{\"format\": 1}"))
        assertNull(PreferencesSnapshot.decode("x".repeat(PreferencesSnapshot.MAX_BYTES + 1)))
        val tooMany = (0..PreferencesSnapshot.MAX_ENTRIES).joinToString(",") { "{\"k\":\"k$it\",\"t\":\"bool\",\"v\":true}" }
        assertNull(PreferencesSnapshot.decode("{\"format\":1,\"entries\":[$tooMany]}"))
    }

    @Test
    fun badEntriesAreSkippedNotFatal() {
        val json = """{"format":1,"entries":[
            {"k":"good","t":"string","v":"yes"},
            {"k":"bad key!","t":"string","v":"x"},
            {"k":"wrong_type","t":"int","v":"seven"},
            {"k":"unknown_type","t":"blob","v":"AAAA"},
            {"k":"long_string","t":"string","v":"${"y".repeat(PreferencesSnapshot.MAX_STRING + 1)}"},
            {"k":"no_value","t":"bool"},
            {"k":"int_overflow","t":"int","v":99999999999}
        ]}"""
        val decoded = PreferencesSnapshot.decode(json)!!
        assertEquals(mutablePreferencesOf(stringPreferencesKey("good") to "yes").asMap(), PreferencesSnapshot.toPreferences(decoded).asMap())
    }

    @Test
    fun emptySnapshotIsValid() {
        val decoded = PreferencesSnapshot.decode(PreferencesSnapshot.encode(mutablePreferencesOf()))!!
        assertTrue(PreferencesSnapshot.toPreferences(decoded).asMap().isEmpty())
    }
}
