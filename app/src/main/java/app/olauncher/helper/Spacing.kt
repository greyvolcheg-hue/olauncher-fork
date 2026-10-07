package app.olauncher.helper

import android.view.View
import app.olauncher.data.Prefs

/** Row padding from Settings > Line spacing in px, or -1 when the layouts keep their own. */
fun Prefs.rowSpacingPx(): Int = if (rowSpacingDp < 0) -1 else rowSpacingDp.dpToPx()

/** Sets equal top and bottom padding; a negative [px] leaves the view's padding alone. */
fun View.setVerticalPadding(px: Int) {
    if (px >= 0) setPadding(paddingLeft, px, paddingRight, px)
}
