package com.earhop.logger

import android.content.Context
import android.widget.Button
import android.widget.TextView

/**
 * Text views that ignore setting the same text again. The screen refreshes once a second and sets every text; a plain
 * TextView treats each set as a change (layout, redraw and an accessibility "content changed" event), so the screen
 * never settled: it redrew about twice a second and test tools could never read it ("could not get idle state").
 */
object Quiet {
    /** Set when a quiet view really changed since the flag was last cleared (MainActivity redraws its blur then). */
    @Volatile var changed = true
}

class QuietText(ctx: Context) : TextView(ctx) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        val old: CharSequence? = getText()
        if (old != null && text != null && old.toString() == text.toString() && (text !is android.text.Spanned || old is android.text.Spanned && old == text)) return
        Quiet.changed = true
        super.setText(text, type)
    }
}

class QuietButton(ctx: Context) : Button(ctx) {
    override fun setText(text: CharSequence?, type: BufferType?) {
        val old: CharSequence? = getText()
        if (old != null && text != null && old.toString() == text.toString() && (text !is android.text.Spanned || old is android.text.Spanned && old == text)) return
        Quiet.changed = true
        super.setText(text, type)
    }
}
