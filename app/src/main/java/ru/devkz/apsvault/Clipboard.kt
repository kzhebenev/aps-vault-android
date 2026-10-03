package ru.devkz.apsvault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** Copy, then clear after 30 s if the clipboard still holds our value (contract, section 8). */
object Clipboard {
    private const val TTL_MS = 30_000L
    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        // Android 13+ hides sensitive clips from the copy preview and keyboards' suggestions.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val current = runCatching { cm.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (current == null || current == text) cm.clearPrimaryClip()
        }, TTL_MS)
    }
}
