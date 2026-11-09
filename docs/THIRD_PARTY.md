# Third-party code and dependencies

## Files reused from other projects

Files copied into this repository keep their original license. Pocket Casts files (MPL-2.0) may only
be reused as whole files with their original headers (CLAUDE.md). Never copy GPL code.

| File(s) in this repo | Source | License | Changes |
| --- | --- | --- | --- |
| `app/src/main/res/drawable/ic_home.xml`, `ic_explore.xml`, `ic_library_music.xml`, `ic_queue_music.xml`, `ic_settings.xml`, `ic_arrow_back.xml` | [Material Symbols](https://github.com/google/material-design-icons) (`symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml`) | Apache-2.0 | Removed the `android:tint` theme attribute (Compose tints icons itself) |

## Notable dependencies

Only libraries on the SPEC §2 allowlist. Versions are in `gradle/libs.versions.toml`.

| Dependency | Used by | License |
| --- | --- | --- |
| Kotlin standard library, kotlinx-coroutines, kotlinx-serialization | all modules | Apache-2.0 |
| AndroidX core, activity, lifecycle, navigation, DataStore | `:app` | Apache-2.0 |
| Jetpack Compose (BOM) and Material 3 | `:app` | Apache-2.0 |
| JUnit 4 | tests | EPL-1.0 |
| Gradle wrapper | build | Apache-2.0 |
| Android Gradle Plugin, Kotlin Gradle Plugin | build | Apache-2.0 |

Pinned in the catalog for later sessions, not used yet. Confirm each license when it is added:
Media3, Room, WorkManager, AndroidX Startup, OkHttp and MockWebServer, jsoup, Coil 3, Robolectric,
Turbine, kxml2, Jazzer.

Prebuilt native libraries that ship inside AndroidX artifacts (not our own native code):
`libandroidx.graphics.path.so` (Compose graphics) and `libdatastore_shared_counter.so` (DataStore),
both Apache-2.0.
