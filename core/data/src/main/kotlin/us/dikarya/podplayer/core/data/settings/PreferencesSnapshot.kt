/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.core.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One setting from a snapshot: its key and a value of a DataStore-supported type. */
class SnapshotEntry internal constructor(internal val key: Preferences.Key<*>, internal val value: Any)

/**
 * Settings in backups (SPEC §7): a Preferences DataStore as JSON, typed, so any setting added later is backed
 * up without code here. A backup can be any file the owner picks, so reading is defensive: size and count
 * limits, a key pattern, and entries of unknown or wrong type are skipped rather than fatal.
 */
object PreferencesSnapshot {
    const val FORMAT = 1
    const val MAX_BYTES = 256 * 1024
    const val MAX_ENTRIES = 500
    const val MAX_STRING = 8 * 1024
    private const val MAX_SET = 200
    private val keyPattern = Regex("^[A-Za-z0-9_.-]{1,100}$")

    fun encode(preferences: Preferences): String {
        val root = buildJsonObject {
            put("format", FORMAT)
            put(
                "entries",
                buildJsonArray {
                    for ((key, value) in preferences.asMap().entries.sortedBy { it.key.name }) {
                        val (type, json) = when (value) {
                            is String -> "string" to JsonPrimitive(value)
                            is Boolean -> "bool" to JsonPrimitive(value)
                            is Int -> "int" to JsonPrimitive(value)
                            is Long -> "long" to JsonPrimitive(value)
                            is Float -> "float" to JsonPrimitive(value)
                            is Double -> "double" to JsonPrimitive(value)
                            is Set<*> -> "string_set" to JsonArray(value.map { JsonPrimitive(it.toString()) })
                            else -> continue
                        }
                        add(
                            buildJsonObject {
                                put("k", key.name)
                                put("t", type)
                                put("v", json)
                            },
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    /** The entries of a snapshot, or null if it isn't one (or is from a newer format). */
    fun decode(json: String): List<SnapshotEntry>? {
        if (json.length > MAX_BYTES) return null
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        if ((root["format"] as? JsonPrimitive)?.intOrNull != FORMAT) return null
        val entries = root["entries"] as? JsonArray ?: return null
        if (entries.size > MAX_ENTRIES) return null
        return entries.mapNotNull { (it as? JsonObject)?.let(::entry) }
    }

    fun toPreferences(entries: List<SnapshotEntry>): Preferences = mutablePreferencesOf().apply {
        @Suppress("UNCHECKED_CAST")
        for (entry in entries) set(entry.key as Preferences.Key<Any>, entry.value)
    }

    private fun entry(obj: JsonObject): SnapshotEntry? {
        val name = (obj["k"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!keyPattern.matches(name)) return null
        val type = (obj["t"] as? JsonPrimitive)?.content ?: return null
        val value: JsonElement = obj["v"] ?: return null
        val primitive = value as? JsonPrimitive
        return when (type) {
            "string" -> primitive?.takeIf { it.isString && it.content.length <= MAX_STRING }
                ?.let { SnapshotEntry(stringPreferencesKey(name), it.content) }
            "bool" -> primitive?.takeUnless { it.isString }?.booleanOrNull?.let { SnapshotEntry(booleanPreferencesKey(name), it) }
            "int" -> primitive?.takeUnless { it.isString }?.intOrNull?.let { SnapshotEntry(intPreferencesKey(name), it) }
            "long" -> primitive?.takeUnless { it.isString }?.longOrNull?.let { SnapshotEntry(longPreferencesKey(name), it) }
            "float" -> primitive?.takeUnless { it.isString }?.floatOrNull?.takeIf { it.isFinite() }
                ?.let { SnapshotEntry(floatPreferencesKey(name), it) }
            "double" -> primitive?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
                ?.let { SnapshotEntry(doublePreferencesKey(name), it) }
            "string_set" -> (value as? JsonArray)?.takeIf { it.size <= MAX_SET }?.let { array ->
                val items = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length <= MAX_STRING }?.content }
                if (items.any { it == null }) null else SnapshotEntry(stringSetPreferencesKey(name), items.filterNotNull().toSet())
            }
            else -> null
        }
    }
}
