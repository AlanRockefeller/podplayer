# Third-party code and dependencies

## Files reused from other projects

Files copied into this repository keep their original license. Pocket Casts files (MPL-2.0) may only
be reused as whole files with their original headers (CLAUDE.md). Never copy GPL code.

| File(s) in this repo | Source | License | Changes |
| --- | --- | --- | --- |
| `app/src/main/res/drawable/ic_home.xml`, `ic_explore.xml`, `ic_library_music.xml`, `ic_queue_music.xml`, `ic_settings.xml`, `ic_arrow_back.xml`; from M1-S8 `ic_play.xml` (`play_arrow`), `ic_pause.xml`, `ic_queue_add.xml` (`playlist_add`), `ic_queue_added.xml` (`playlist_add_check`), `ic_download.xml`, `ic_download_done.xml`, `ic_refresh.xml`, `ic_grid.xml` (`grid_view`), `ic_list.xml` (`view_list`), `ic_search.xml`, `ic_offline.xml` (`cloud_off`), `ic_sort.xml` (`sort`) | [Material Symbols](https://github.com/google/material-design-icons) (`symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml`) | Apache-2.0 | Removed the `android:tint` theme attribute (Compose tints icons itself) |

## Notable dependencies

Only libraries on the SPEC §2 allowlist. Versions are in `gradle/libs.versions.toml`.

| Dependency | Used by | License |
| --- | --- | --- |
| Kotlin standard library, kotlinx-coroutines, kotlinx-serialization | all modules | Apache-2.0 |
| AndroidX core, activity, lifecycle, navigation, DataStore | `:app` | Apache-2.0 |
| Jetpack Compose (BOM) and Material 3 | `:app` | Apache-2.0 |
| OkHttp and Okio | `:core:net` (exposed to the modules that depend on it) | Apache-2.0 |
| OkHttp MockWebServer (`mockwebserver3`) | `:core:net` tests | Apache-2.0 |
| jsoup | `:core:feed` (show-notes sanitizer only; its HTTP client is banned by the forbidden-API check) | MIT |
| kxml2, with the XmlPull API (`xmlpull`) | `:core:feed` compiles against the XmlPull interface (compile-only; Android provides it); `:core:testing` uses kxml2 as the JVM parser in tests | kxml2: BSD-style; XmlPull API: public domain |
| Room (runtime, KSP compiler, Gradle plugin) and AndroidX SQLite | `:core:data` | Apache-2.0 |
| WorkManager (`work-runtime-ktx`; `work-testing` in tests) | `:core:jobs`, `:app` | Apache-2.0 |
| DataStore (Preferences) | `:core:data` (moved from `:app`) | Apache-2.0 |
| Robolectric, with its Android jar and SQLite native runtime | tests of `:core:data` and `:core:jobs` | MIT (Android jar: Apache-2.0) |
| AndroidX Test (ext-junit, core) | tests | Apache-2.0 |
| Compose UI test (`ui-test-junit4`, with `ui-test` and AndroidX Test runner as its dependencies) and Espresso core 3.7.0 (raised from the 3.5.0 it brings, which can't run on the SDK 37 Robolectric jar) | `:app` tests only (Robolectric); `ui-test-manifest` is deliberately not used | Apache-2.0 |
| Lifecycle ViewModel Compose (`lifecycle-viewmodel-compose`) | `:app`: the browsing screens' view models | Apache-2.0 |
| Turbine | `:core:data` and `:core:jobs` tests | Apache-2.0 |
| Media3 ExoPlayer (`media3-exoplayer`), with `media3-extractor`, `media3-common` and the rest of its API, Guava (Android flavour) and AndroidX ExifInterface as its dependencies | `:core:jobs`: only its extractors, to probe downloads (SPEC §9) | Apache-2.0 |
| OkHttp MockWebServer (`mockwebserver3`) | `:core:jobs` and `:playback` tests | Apache-2.0 |
| Coil 3 core (`coil-core`; no network artifact, service loader off) | `:core:imagesandbox`: the one image loader, reading sandbox-produced cache files only | Apache-2.0 |
| Coil 3 Compose (`coil-compose`), with `coil`, `coil-compose-core`, Accompanist Drawable Painter and the JetBrains Compose Multiplatform runtime artifacts as its dependencies | `:app`: `AsyncImage` for artwork | Apache-2.0 |
| Media3 session (`media3-session`), with AndroidX Media (`androidx.media:media`) and Lifecycle Service as its dependencies | `:playback`: the playback service, media session and `MediaController` client; its `ListenableFuture` API uses Guava (already a Media3 dependency) | Apache-2.0 |
| Media3 ExoPlayer and datasource-okhttp (`media3-exoplayer`, `media3-datasource-okhttp`) | `:playback`: the player; streams through the gated OkHttp client | Apache-2.0 |
| Media3 test utilities (`media3-test-utils`, `media3-test-utils-robolectric`), with Truth, Mockito, ByteBuddy, Objenesis and the Media3 transformer, effect and muxer artifacts as their dependencies | `:playback` tests only: fake media sources, renderers and clock | Apache-2.0 (Mockito, Objenesis: MIT; ByteBuddy: Apache-2.0) |
| Robolectric native graphics runtime (part of Robolectric's native runtime) | `:core:imagesandbox` tests: real platform image decoding and encoding on the JVM | MIT / Apache-2.0 |
| JUnit 4 | tests | EPL-1.0 |
| Gradle wrapper | build | Apache-2.0 |
| Android Gradle Plugin, Kotlin Gradle Plugin | build | Apache-2.0 |

Pinned in the catalog for later sessions, not used yet. Confirm each license when it is added:
AndroidX Startup, Jazzer.

Prebuilt native libraries that ship inside AndroidX artifacts (not our own native code):
`libandroidx.graphics.path.so` (Compose graphics) and `libdatastore_shared_counter.so` (DataStore),
both Apache-2.0.
