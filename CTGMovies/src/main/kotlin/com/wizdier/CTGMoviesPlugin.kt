package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CTGMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(CTGMovies.PREF_FILE, Context.MODE_PRIVATE)
        registerMainAPI(CTGMovies(prefs))

        openSettings = { ctx ->
            showSettings(ctx, prefs)
        }
    }

    private fun showSettings(context: Context, prefs: SharedPreferences) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val smallPad = (8 * context.resources.displayMetrics.density).toInt()

        fun label(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 13f
            setPadding(0, smallPad, 0, 2)
        }

        fun input(value: String?, hint: String, password: Boolean = false, multiLine: Boolean = false): EditText =
            EditText(context).apply {
                setText(value.orEmpty())
                this.hint = hint
                inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                if (multiLine) {
                    minLines = 2
                    maxLines = 5
                    isSingleLine = false
                } else {
                    setSingleLine(true)
                }
            }

        val email = input(prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com")
        val password = input(prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password", password = true)
        val token = input(prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token", multiLine = true)
        val cookie = input(prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header", multiLine = true)
        val apiBase = input(
            prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE),
            CTGMovies.DEFAULT_API_BASE
        )

        val note = TextView(context).apply {
            text = "For protected content, either enter email/password for auto-login, paste ctg.token/Bearer token, or paste a raw Cookie header. Saved only in Cloudstream local extension settings."
            textSize = 12f
            setPadding(0, 0, 0, smallPad)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(note)
            addView(label("Email")); addView(email)
            addView(label("Password")); addView(password)
            addView(label("Token")); addView(token)
            addView(label("Cookie")); addView(cookie)
            addView(label("API Base")); addView(apiBase)
        }

        val scroll = ScrollView(context).apply { addView(container) }

        val dialog = AlertDialog.Builder(context)
            .setTitle("CTGMovies Settings")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                prefs.edit()
                    .putString(CTGMovies.PREF_EMAIL, email.text?.toString()?.trim().orEmpty())
                    .putString(CTGMovies.PREF_PASSWORD, password.text?.toString().orEmpty())
                    .putString(CTGMovies.PREF_TOKEN, token.text?.toString()?.trim().orEmpty())
                    .putString(CTGMovies.PREF_COOKIE, cookie.text?.toString()?.trim().orEmpty())
                    .putString(
                        CTGMovies.PREF_API_BASE,
                        apiBase.text?.toString()?.trim()?.ifBlank { CTGMovies.DEFAULT_API_BASE }
                            ?: CTGMovies.DEFAULT_API_BASE
                    )
                    .apply()
                Toast.makeText(context, "CTGMovies settings saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                prefs.edit()
                    .remove(CTGMovies.PREF_EMAIL)
                    .remove(CTGMovies.PREF_PASSWORD)
                    .remove(CTGMovies.PREF_TOKEN)
                    .remove(CTGMovies.PREF_COOKIE)
                    .putString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE)
                    .apply()
                email.setText("")
                password.setText("")
                token.setText("")
                cookie.setText("")
                apiBase.setText(CTGMovies.DEFAULT_API_BASE)
                Toast.makeText(context, "CTGMovies settings cleared", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
