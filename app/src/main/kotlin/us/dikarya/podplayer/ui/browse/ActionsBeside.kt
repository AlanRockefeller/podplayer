/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/*
 * Found on the owner's phone at font size 200%: four 48 dp buttons left an episode title "True Crim…" and its date
 * "Nov 1…", Resume left the Continue title "Th / e …", and the Library count read "227 sho / ws". Buttons and artwork
 * keep their size; when the text beside them would be squeezed, they move to their own line instead.
 */

/**
 * [main] (artwork and text) with its [actions] (buttons) beside it at the end, as long as [main] keeps at least
 * [minMainWidth] (null: all of its one-line width) and its longest word; otherwise [main] takes the whole width and
 * the actions go on a line below, at the end.
 */
@Composable
fun ActionsBeside(
    minMainWidth: Dp?,
    modifier: Modifier = Modifier,
    main: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) = BesideOrStacked(minMainWidth, sideFirst = false, modifier, main, actions)

/**
 * [artwork] at the start with [text] beside it, as long as [text] keeps at least [minTextWidth] and its longest
 * word; otherwise the artwork sits above the text, which takes the whole width.
 */
@Composable
fun ArtworkBeside(
    minTextWidth: Dp,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
    text: @Composable () -> Unit,
) = BesideOrStacked(minTextWidth, sideFirst = true, modifier, text, artwork)

/**
 * [ems] times [fontSize] as shown at the current font scale, plus [extra]. The size is converted before
 * multiplying: Android scales large sizes less (nonlinear font scaling), so 80 sp barely grows at 200% while 16 sp
 * nearly doubles.
 */
@Composable
fun textRoom(fontSize: TextUnit, ems: Float, extra: Dp = 0.dp): Dp =
    with(LocalDensity.current) { fontSize.toDp() } * ems + extra

/** How much room a title keeps beside buttons or artwork before they move to their own line. */
const val MIN_TITLE_EMS = 5f

@Composable
private fun BesideOrStacked(
    minMainWidth: Dp?,
    sideFirst: Boolean,
    modifier: Modifier,
    main: @Composable () -> Unit,
    side: @Composable () -> Unit,
) {
    Layout(contents = listOf(main, side), modifier = modifier) { (mainMeasurables, sideMeasurables), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val sidePlaceables = sideMeasurables.map { it.measure(loose) }
        val sideWidth = sidePlaceables.sumOf { it.width }
        val sideHeight = sidePlaceables.maxOfOrNull { it.height } ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        // At least the room asked for (or the one-line width), and never less than the longest word (the minimum
        // intrinsic width of text), so no word is broken while stacking would have kept it whole.
        val asked = minMainWidth?.roundToPx() ?: mainMeasurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val longestWord = mainMeasurables.maxOfOrNull { it.minIntrinsicWidth(Constraints.Infinity) } ?: 0
        val needed = maxOf(asked, longestWord)
        val beside = width - sideWidth >= needed
        val mainWidth = if (beside) width - sideWidth else width
        val mainPlaceables = mainMeasurables.map { it.measure(Constraints(minWidth = mainWidth, maxWidth = mainWidth)) }
        val mainHeight = mainPlaceables.maxOfOrNull { it.height } ?: 0
        val height = if (beside) maxOf(mainHeight, sideHeight) else mainHeight + sideHeight
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            when {
                beside -> {
                    var x = if (sideFirst) 0 else mainWidth
                    sidePlaceables.forEach {
                        it.place(x, (height - it.height) / 2)
                        x += it.width
                    }
                    val mainX = if (sideFirst) sideWidth else 0
                    mainPlaceables.forEach { it.place(mainX, (height - it.height) / 2) }
                }
                sideFirst -> {
                    var x = 0
                    sidePlaceables.forEach {
                        it.place(x, 0)
                        x += it.width
                    }
                    mainPlaceables.forEach { it.place(0, sideHeight) }
                }
                else -> {
                    mainPlaceables.forEach { it.place(0, 0) }
                    var x = width - sideWidth
                    sidePlaceables.forEach {
                        it.place(x, mainHeight)
                        x += it.width
                    }
                }
            }
        }
    }
}
