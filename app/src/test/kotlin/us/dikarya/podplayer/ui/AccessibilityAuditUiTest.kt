/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import org.junit.Assert.assertTrue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import us.dikarya.podplayer.PodplayerApp
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.core.model.DownloadState
import us.dikarya.podplayer.core.model.MediaFile
import us.dikarya.podplayer.ui.discover.ShowPreviewScreen
import us.dikarya.podplayer.ui.discover.ShowPreviewViewModel
import us.dikarya.podplayer.ui.discover.rss
import us.dikarya.podplayer.ui.discover.success
import us.dikarya.podplayer.ui.downloads.DownloadsScreen
import us.dikarya.podplayer.ui.downloads.DownloadsViewModel
import us.dikarya.podplayer.playback.PlaybackUiState
import us.dikarya.podplayer.ui.discover.DiscoverScreen
import us.dikarya.podplayer.ui.discover.DiscoverViewModel
import us.dikarya.podplayer.ui.discover.importlist.ListImportScreen
import us.dikarya.podplayer.ui.discover.importlist.ListImportViewModel
import us.dikarya.podplayer.ui.episode.EpisodeScreen
import us.dikarya.podplayer.ui.episode.EpisodeViewModel
import us.dikarya.podplayer.ui.home.HomeScreen
import us.dikarya.podplayer.ui.home.HomeViewModel
import us.dikarya.podplayer.ui.library.LibraryScreen
import us.dikarya.podplayer.ui.library.LibraryViewModel
import us.dikarya.podplayer.ui.player.PlayerScreen
import us.dikarya.podplayer.ui.player.PlayerViewModel
import us.dikarya.podplayer.ui.queue.QueueScreen
import us.dikarya.podplayer.ui.queue.QueueViewModel
import us.dikarya.podplayer.ui.settings.LogsScreen
import us.dikarya.podplayer.ui.settings.LogsViewModel
import us.dikarya.podplayer.ui.settings.SettingsScreen
import us.dikarya.podplayer.ui.settings.SettingsTestEnv
import us.dikarya.podplayer.ui.settings.SettingsViewModel
import us.dikarya.podplayer.ui.shell.AppShell
import us.dikarya.podplayer.ui.show.ShowScreen
import us.dikarya.podplayer.ui.show.ShowViewModel
import us.dikarya.podplayer.ui.starred.StarredScreen
import us.dikarya.podplayer.ui.starred.StarredViewModel
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.PodplayerTheme

/**
 * M1-S11 accessibility basics, on every main screen at the owner's 360 dp width, at font scale 100% and 200%:
 * - every control (anything with a click action, as TalkBack sees it) has a label: text or a content description;
 * - every control's touch target is at least 48 × 48 dp (Home's compact rows overlap Play and Download on
 *   purpose; each target stays 48 dp, and overlap isn't checked);
 * - no control is cut off at the side of the screen;
 * - no text is clipped: text may only overflow where it is meant to end in "…";
 * - no word is broken across lines, and text cut short with "…" keeps room for a few characters (both found on
 *   the owner's phone at 200%, after the first version of this audit passed).
 * Controls scrolled half out of view are skipped; each screen is scrolled through.
 */
@RunWith(AndroidJUnit4::class)
// The real Application: the shell's data-state dialogs read the app graph.
@Config(application = PodplayerApp::class, qualifiers = "w360dp-h640dp")
// Real text measurement (see ShowLayoutUiTest): otherwise every label measures a few dp and nothing ever clips.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityAuditUiTest {
    private val compose = createComposeRule()
    private val tmp = TemporaryFolder()
    private val env by lazy { SettingsTestEnv(tmp.root) }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(tmp).around(CloseAfter { env.close() }).around(HostActivity()).around(compose)

    private val browse get() = env.browse.deps

    /** A show with a long title and episodes, one of them loaded in the player and queued. */
    private fun seedLibrary(): Pair<Long, Long> {
        val feed = env.browse.show("The Extraordinarily Long-Titled Weekly Science Podcast", episodes = 4)
        val episode = env.browse.episodeIds(feed).first()
        runBlocking { env.browse.episodeIds(feed).take(2).forEach { env.browse.repos.queue.add(it) } }
        env.browse.playback.state.value = PlaybackUiState(
            connected = true, episodeId = episode, title = "The Extraordinarily Long-Titled Weekly Science Podcast 1",
            showTitle = "The Extraordinarily Long-Titled Weekly Science Podcast", positionMs = 600_000, durationMs = 1_800_000,
        )
        return feed to episode
    }

    @Test fun shell() = audit { AppShell(themeMode = ThemeMode.DARK, onThemeModeChange = {}, browse = browse, settings = env.deps) }

    @Test fun home() = audit { HomeScreen(remember { HomeViewModel(browse) }, onOpenEpisode = {}, onOpenShow = {}, onDiscover = {}) }

    @Test fun library() = audit { LibraryScreen(remember { LibraryViewModel(browse) }, onOpenShow = {}, onDiscover = {}) }

    @Test fun discover() = audit(withLibrary = false) { DiscoverScreen(remember { DiscoverViewModel(browse) }, onOpenShow = {}) }

    @Test fun showPreview() = audit(withLibrary = false) {
        val vm = remember {
            env.browse.answer = { success(rss("The Extraordinarily Long-Titled Weekly Science Podcast", episodes = 6).toByteArray(), "application/rss+xml") }
            ShowPreviewViewModel(browse, "https://found.example.com/feed.xml")
        }
        ShowPreviewScreen(vm, artworkUrl = null, onOpenShow = {})
    }

    @Test fun importList() = audit(withLibrary = false) { ListImportScreen(remember { ListImportViewModel(browse) }, onOpenShow = {}, onDone = {}) }

    @Test fun starred() = audit { StarredScreen(remember { StarredViewModel(browse) }, onOpenEpisode = {}) }

    @Test fun downloads() = audit { ids ->
        LaunchedEffect(Unit) {
            env.browse.repos.media.upsert(MediaFile(ids.second, "media/${ids.second}.mp3", DownloadState.Complete, 30_000_000, 30_000_000, null, 1L))
        }
        DownloadsScreen(remember { DownloadsViewModel(browse) }, onOpenEpisode = {})
    }

    @Test fun queue() = audit { QueueScreen(remember { QueueViewModel(browse) }, onOpenEpisode = {}) }

    @Test fun player() = audit { PlayerScreen(remember { PlayerViewModel(browse) }, onOpenEpisode = {}, onOpenQueue = {}) }

    @Test fun show() = audit { ids -> ShowScreen(remember { ShowViewModel(browse, ids.first) }, onOpenEpisode = {}, onGone = {}) }

    @Test fun episode() = audit { ids -> EpisodeScreen(remember { EpisodeViewModel(browse, ids.second) }, onOpenShow = {}) }

    @Test fun settings() = audit(withLibrary = false) {
        SettingsScreen(remember { SettingsViewModel(env.deps) }, ThemeMode.DARK, {}, onOpenLogs = {}, onOpenLicenses = {}, backup = {}, appVersion = "1.0-test")
    }

    @Test fun logs() = audit(withLibrary = false) { LogsScreen(remember { LogsViewModel(env.deps) }) }

    /** The audit itself: a screen built to fail each rule must fail each one, so a green audit means something. */
    @Test
    fun theAuditFindsWhatItLooksFor() {
        val problems = collect(withLibrary = false) {
            Column {
                Box(Modifier.size(32.dp).clickable {})
                Box(Modifier.height(12.dp)) { Text("Clipped words") }
                Text("A long label that should wrap but may not", maxLines = 1)
                Button(onClick = {}, modifier = Modifier.offset(x = 300.dp)) { Text("Off the edge") }
                Box(Modifier.width(40.dp)) { Text("Discover") }
                Box(Modifier.width(30.dp)) { Text("A squeezed title", maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }.joinToString("\n")
        for (expected in listOf(
            "no label", "32×32 dp, under 48 dp", "\"Clipped words\" is clipped", "\"A long label that should", "\"Off the edge\" is cut off at the side",
            "\"Discover\" breaks a word", "\"A squeezed title\" is squeezed",
        )) {
            assertTrue("expected \"$expected\" in:\n$problems", expected in problems)
        }
    }

    @Composable
    private fun <T> remember(make: () -> T): T = androidx.compose.runtime.remember(make)

    private fun audit(withLibrary: Boolean = true, content: @Composable (Pair<Long, Long>) -> Unit) {
        assertEquals(emptyList<String>(), collect(withLibrary, content))
    }

    private fun collect(withLibrary: Boolean = true, content: @Composable (Pair<Long, Long>) -> Unit): List<String> {
        val ids = if (withLibrary) seedLibrary() else 0L to 0L
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PodplayerTheme(DarkTokens) {
                    Box(Modifier.width(360.dp).fillMaxHeight()) { content(ids) }
                }
            }
        }
        val problems = sortedSetOf<String>()
        for (scale in listOf(1f, 2f)) {
            fontScale = scale
            // Let the library load and the screen settle.
            compose.waitInRealTime { compose.onAllNodesClickable().isNotEmpty() }
            Thread.sleep(SETTLE_MS)
            compose.waitForIdle()
            var previous: List<String>? = null
            for (step in 0 until MAX_SCROLLS) {
                problems += check(scale)
                val seen = compose.onAllNodesClickable().map { it.label() + it.boundsInRoot.top.toInt() }
                if (seen == previous) break
                previous = seen
                compose.onRoot().performTouchInput { swipeUp() }
                compose.waitForIdle()
            }
            // Back to the top for the next scale.
            repeat(MAX_SCROLLS) { compose.onRoot().performTouchInput { swipeDown() } }
            compose.waitForIdle()
        }
        return problems.toList()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesClickable(): List<SemanticsNode> =
        onAllNodes(hasClickAction()).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun SemanticsNode.label(): String {
        val description = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        val text = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        val editable = config.getOrNull(SemanticsProperties.EditableText)?.text
        return listOfNotNull(description, text, editable).joinToString(" ").trim()
    }

    private fun check(scale: Float): List<String> {
        val problems = mutableListOf<String>()
        val root = compose.onRoot().fetchSemanticsNode()
        val rootBounds = root.boundsInRoot
        val minPx = with(Density(root.layoutInfo.density.density)) { 48.dp.toPx() } - 0.5f
        val at = "at ${(scale * 100).toInt()}%"
        for (node in compose.onAllNodesClickable()) {
            val bounds = node.boundsInRoot
            val label = node.label()
            val name = label.ifEmpty { "a control with no label (${node.config.getOrNull(SemanticsProperties.Role) ?: "no role"})" }
            // Half scrolled out of view at the top or bottom: checked when scrolled into view.
            if (bounds.isEmpty || node.partlyScrolledAway()) continue
            // A link inside a block of text (show notes): read and activated through its text, exempt from target size.
            if (node.parent?.config?.contains(SemanticsActions.GetTextLayoutResult) == true) continue
            // Scrolled away sideways in a scrolling row (the Logs tabs): checked when scrolled into view.
            if (node.inHorizontalScroller() && bounds.width < node.size.width - 1) continue
            if (label.isEmpty()) problems += "$name has no label $at (at ${dp(bounds.left, root)},${dp(bounds.top, root)} dp, ${dp(bounds.width, root)}×${dp(bounds.height, root)} dp)"
            // The control's layout size, which includes Material's minimumInteractiveComponentSize (a 40 dp icon
            // button takes 48 dp). Not touchBoundsInRoot: that also counts Compose's hit-test widening of any small
            // target, which only works where nothing else is near.
            val touch = androidx.compose.ui.unit.IntSize(node.layoutInfo.width, node.layoutInfo.height)
            if (touch.width < minPx || touch.height < minPx) {
                problems += "\"$name\" touch target ${dp(touch.width.toFloat(), root)}×${dp(touch.height.toFloat(), root)} dp, under 48 dp $at"
            }
            if (bounds.width < node.size.width - 1 || bounds.right > rootBounds.right + 1) problems += "\"$name\" is cut off at the side $at"
        }
        for (node in compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)) {
            val bounds = node.boundsInRoot
            if (bounds.isEmpty) continue
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.firstOrNull() ?: continue
            val text = layout.layoutInput.text.text.take(40)
            val ellipsized = layout.layoutInput.overflow == TextOverflow.Ellipsis
            // Measured directly: Compose's didOverflowWidth is also true when a short line was laid out in a wider box.
            // Line widths, not right edges: centred text sits in a paragraph laid out wider than the text.
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
            val tooWide = widest > layout.size.width + 1
            val tooTall = layout.multiParagraph.height > layout.size.height + 1
            val cutLines = layout.multiParagraph.didExceedMaxLines && !ellipsized
            if ((tooWide || tooTall) && !ellipsized || cutLines) problems += "text \"$text\" is clipped $at"
            // Found on the owner's phone at 200%: "Discov / er", "227 sho / ws". A line that starts inside a word
            // means the box was narrower than the word. A word longer than most of the screen can't help breaking.
            val full = layout.layoutInput.text.text
            // The width it wrapped in, not its own: a wrapped line can be much shorter than its box.
            val wideBox = layout.layoutInput.constraints.maxWidth >= rootBounds.width * WIDE_BOX
            val brokenWord = !wideBox && (1 until layout.lineCount).any { line ->
                val start = layout.getLineStart(line)
                start in 1 until full.length && full[start - 1].isLetterOrDigit() && full[start].isLetterOrDigit()
            }
            if (brokenWord) problems += "text \"$text\" breaks a word across lines $at"
            // Also from the phone: a title squeezed beside its buttons to "True Crim…" and a date to "Nov 1…". Text that
            // is cut short must get room for at least a few characters of its own size.
            val cutShort = layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            val emPx = with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() }
            if (cutShort && layout.size.width < MIN_EMS * emPx) {
                problems += "text \"$text\" is squeezed to ${dp(layout.size.width.toFloat(), root)} dp and cut short $at"
            }
            // Its parent cut it off: narrower than its own layout, or shorter without being scrolled to an edge.
            val cutSideways = bounds.width < node.size.width - 1 && !node.inHorizontalScroller()
            val cutVertically = bounds.height < node.size.height - 1 && !node.partlyScrolledAway()
            if (cutSideways || cutVertically) {
                problems += "text \"$text\" is cut off by its container $at (shows ${dp(bounds.width, root)}×${dp(bounds.height, root)} " +
                    "of ${dp(node.size.width.toFloat(), root)}×${dp(node.size.height.toFloat(), root)} dp at ${dp(bounds.left, root)},${dp(bounds.top, root)})"
            }
        }
        return problems
    }

    /** Shorter than its layout because it sits at the edge of the window or of the list it scrolls in. */
    private fun SemanticsNode.partlyScrolledAway(): Boolean {
        val bounds = boundsInRoot
        if (bounds.height >= size.height - 1) return false
        val viewports = generateSequence(parent) { it.parent }
            .filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .map { it.boundsInRoot } + compose.onRoot().fetchSemanticsNode().boundsInRoot
        return viewports.any { bounds.top <= it.top + 1 || bounds.bottom >= it.bottom - 1 }
    }

    private fun SemanticsNode.inHorizontalScroller(): Boolean =
        generateSequence(parent) { it.parent }.any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }

    private fun dp(px: Float, root: SemanticsNode) = (px / root.layoutInfo.density.density).toInt()

    private companion object {
        const val MAX_SCROLLS = 6
        const val SETTLE_MS = 300L

        /** Cut-short text gets at least this many times its font size in width (about six or seven characters). */
        const val MIN_EMS = 4

        /** A text box at least this share of the screen wide may break a word: the word is longer than the box. */
        const val WIDE_BOX = 0.66f
    }
}
