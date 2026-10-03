package ru.devkz.apsvault.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import ru.devkz.apsvault.SecretItem

/** Login, password and one-time-code fields of a screen, and whom the screen belongs to. */
data class Form(
    val username: AutofillId?,
    val password: AutofillId?,
    val otp: AutofillId?,
    val webDomain: String?,
    val packageName: String,
) {
    val ids: List<AutofillId> get() = listOfNotNull(username, password, otp)
    val isEmpty get() = username == null && password == null && otp == null
}

object FormParser {
    private val USER_WORDS = Regex("user|login|e-?mail|account|логин|почт", RegexOption.IGNORE_CASE)
    private val OTP_WORDS = Regex("otp|totp|2fa|one.?time|verification|code|код", RegexOption.IGNORE_CASE)

    fun parse(structure: AssistStructure): Form {
        var user: AutofillId? = null
        var pass: AutofillId? = null
        var otp: AutofillId? = null
        var domain: String? = null
        var lastText: AutofillId? = null

        fun visit(n: AssistStructure.ViewNode) {
            n.webDomain?.takeIf { it.isNotBlank() }?.let { if (domain == null) domain = it }
            val id = n.autofillId
            if (id != null && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.visibility == View.VISIBLE) {
                val hints = n.autofillHints?.map { it.lowercase() }.orEmpty()
                val words = listOfNotNull(n.idEntry, n.hint, n.htmlInfo?.attributes?.joinToString(" ") { "${it.first}=${it.second}" })
                    .joinToString(" ")
                val variation = n.inputType and InputType.TYPE_MASK_VARIATION
                val isPassword = hints.any { "password" in it } ||
                    (n.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
                    n.htmlInfo?.attributes?.any { it.first == "type" && it.second == "password" } == true
                when {
                    hints.any { "otp" in it || "smsotpcode" in it } -> if (otp == null) otp = id
                    isPassword -> if (pass == null) { pass = id; if (user == null) user = lastText }
                    hints.any { "username" in it || "emailaddress" in it || it == "email" } -> if (user == null) user = id
                    OTP_WORDS.containsMatchIn(words) && pass != null -> if (otp == null) otp = id
                    USER_WORDS.containsMatchIn(words) -> if (user == null) user = id
                    else -> lastText = id
                }
            }
            for (i in 0 until n.childCount) visit(n.getChildAt(i))
        }

        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
        return Form(user, pass, otp, domain, structure.activityComponent.packageName)
    }

    /**
     * Which secrets fit the form: by web domain (same host or a sub/super-domain), or for a
     * native app by the second-level name of the secret's host appearing in the package name
     * (github.com fits com.github.android).
     */
    fun matches(form: Form, secrets: List<SecretItem>): List<SecretItem> = secrets.filter { s ->
        if (s.machineOnly) return@filter false
        val h = s.host.lowercase().removePrefix("www.")
        if (h.isEmpty()) return@filter false
        val d = form.webDomain?.lowercase()?.removePrefix("www.")
        if (d != null) return@filter d == h || d.endsWith(".$h") || h.endsWith(".$d")
        val label = h.split('.').let { if (it.size >= 2) it[it.size - 2] else it[0] }
        label.length >= 3 && form.packageName.lowercase().split('.').contains(label)
    }
}
