package ru.devkz.apsvault

import android.content.Context

/**
 * The process-wide session, shared by the app's screens and the autofill service so that one
 * unlock serves both. It lives in memory only; the biometric-protected copy is SessionStore.
 */
object Vault {
    @Volatile var api: VaultApi? = null
    @Volatile private var lastUsed = 0L

    fun settings(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun base(ctx: Context): String? = settings(ctx).getString("base", null)

    fun biometricEnabled(ctx: Context) = settings(ctx).getBoolean("biometric", false)

    fun autolockMs(ctx: Context) = settings(ctx).getInt("autolock_min", 5) * 60_000L

    /** The API for the configured vault; drops the session after the idle time. */
    fun current(ctx: Context): VaultApi? {
        val b = base(ctx) ?: return null
        val a = api?.takeIf { it.base == b } ?: VaultApi(b).also { api = it }
        if (a.sid != null && lastUsed != 0L && System.currentTimeMillis() - lastUsed > autolockMs(ctx)) a.sid = null
        return a
    }

    fun unlocked(ctx: Context) = current(ctx)?.sid != null

    fun touch() { lastUsed = System.currentTimeMillis() }
}
