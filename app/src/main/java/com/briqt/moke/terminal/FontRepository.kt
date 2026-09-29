package com.briqt.moke.terminal

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.briqt.moke.R

/** Loads the sole bundled terminal typeface, including its CJK and Nerd Font glyphs. */
class FontRepository(private val context: Context) {
    private val mapleTypeface: Typeface by lazy {
        requireNotNull(ResourcesCompat.getFont(context, R.font.maple_mono)) {
            "Bundled Maple Mono font is unavailable"
        }
    }

    fun resolveTypeface(): Typeface = mapleTypeface
}
