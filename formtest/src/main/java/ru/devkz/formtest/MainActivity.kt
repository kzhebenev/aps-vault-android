package ru.devkz.formtest

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.dataString
        if (url != null) {
            setContentView(WebView(this).apply { settings.javaScriptEnabled = true; loadUrl(url) })
            return
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 160, 48, 48) }
        root.addView(EditText(this).apply { hint = "Login"; setAutofillHints("username") })
        root.addView(EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        })
        setContentView(root)
    }
}
