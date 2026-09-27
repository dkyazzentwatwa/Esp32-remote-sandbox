package org.espsketchide.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Edge-to-edge is enforced from targetSdk 35: the app draws behind the status bar, navigation
 * bar and keyboard, and `adjustResize` no longer shrinks the window. These helpers put the
 * insets back where each screen needs them.
 */
object Insets {

    private val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    /** Pads the app bar under the status bar (so the toolbar colour fills it) and cutouts. */
    fun applyToAppBar(appBar: View) {
        val start = appBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { view, insets ->
            val i = insets.getInsets(bars)
            view.updatePadding(left = i.left, top = start + i.top, right = i.right)
            insets
        }
    }

    /** Bottom padding that follows the navigation bar or the keyboard, whichever is taller. */
    fun applyBottomIncludingKeyboard(view: View) {
        val start = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val nav = insets.getInsets(bars)
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = nav.left, right = nav.right, bottom = start + maxOf(nav.bottom, ime.bottom))
            insets
        }
    }

    /** Adds the navigation bar height to a scrolling list's bottom padding. */
    fun applyBottomPadding(view: View) {
        val start = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val i = insets.getInsets(bars)
            v.updatePadding(left = i.left, right = i.right, bottom = start + i.bottom)
            insets
        }
    }

    /** Adds the navigation bar to a floating view's margins. */
    fun applyBottomMargin(view: View) {
        val lp = view.layoutParams as ViewGroup.MarginLayoutParams
        val startBottom = lp.bottomMargin
        val startEnd = lp.marginEnd
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val i = insets.getInsets(bars)
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = startBottom + i.bottom
                marginEnd = startEnd + maxOf(i.right, i.left)
            }
            insets
        }
    }
}
