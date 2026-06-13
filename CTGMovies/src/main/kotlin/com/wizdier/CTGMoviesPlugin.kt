package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

// ─────────────────────────────────────────────────────────────────────────────
// ── Color palette (elegant cinema-dark theme) ────────────────────────────────
private const val C_BG          = 0xFF0E0F1A.toInt()
private const val C_SURFACE     = 0xFF181A28.toInt()
private const val C_SURFACE_HI  = 0xFF21243A.toInt()
private const val C_BORDER      = 0xFF2E3148.toInt()
private const val C_TEXT        = 0xFFEAEAF0.toInt()
private const val C_TEXT_SUB    = 0xFF999AB0.toInt()
private const val C_TEXT_HINT   = 0xFF5C5E73.toInt()
private const val C_ACCENT      = 0xFF8B6CFF.toInt()
private const val C_ACCENT_2    = 0xFF5B8DEF.toInt()
private const val C_RED         = 0xFFFF6B6B.toInt()
private const val C_INFO_BG     = 0xFF1B1E30.toInt()

@CloudstreamPlugin
class CTGMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(CTGMovies.PREF_FILE, Context.MODE_PRIVATE)
        registerMainAPI(CTGMovies(prefs))

        openSettings = { ctx ->
            showSettings(ctx, prefs)
        }
    }

    // ── dp / sp helpers ──────────────────────────────────────────────────────
    private fun Context.dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun Context.sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // ── Drawable factories ───────────────────────────────────────────────────

    /** Rounded-rectangle fill drawable. */
    private fun roundedRect(color: Int, radius: Float, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadii = FloatArray(8) { radius }
            if (strokeColor != null) setStroke(strokeWidth, strokeColor)
        }

    /** Diagonal accent gradient. */
    private fun accentGradient(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(C_ACCENT, C_ACCENT_2)
        ).apply { cornerRadii = FloatArray(8) { 28f } }

    /** Outlined (ghost) button background. */
    private fun outlineButtonBg(stroke: Int, fill: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundedRect(fill, 28f))
        addState(intArrayOf(), roundedRect(Color.TRANSPARENT, 28f, stroke, 3))
    }

    // ── View factories ───────────────────────────────────────────────────────

    private fun Context.headerView(): View {
        val dp16 = dp(16); val dp24 = dp(24)

        val icon = TextView(this).apply {
            text = "🎬"
            textSize = sp(30f)
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "CTGMovies"
            setTextColor(Color.WHITE)
            textSize = sp(22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val subtitle = TextView(this).apply {
            text = "Sign in to unlock protected content"
            setTextColor(0xFFCBCBE0.toInt())
            textSize = sp(12.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = accentGradient()
            setPadding(dp24, dp(22), dp24, dp(20))
            gravity = Gravity.CENTER
            addView(icon)
            addView(title)
            addView(subtitle)
        }
    }

    private fun Context.sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(C_ACCENT)
        textSize = sp(12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(4), dp(18), dp(4), dp(8))
    }

    private fun Context.fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(C_TEXT_SUB)
        textSize = sp(12f)
        typeface = Typeface.DEFAULT
        setPadding(dp(4), 0, dp(4), dp(5))
    }

    private fun Context.styledInput(
        value: String?,
        hint: String,
        password: Boolean = false,
        multiLine: Boolean = false,
    ): EditText = EditText(this).apply {
        setText(value.orEmpty())
        this.hint = hint
        setHintTextColor(C_TEXT_HINT)
        setTextColor(C_TEXT)
        textSize = sp(14f)
        background = roundedRect(C_SURFACE, 22f, C_BORDER, 2)
        setPadding(dp(16), dp(13), dp(16), dp(13))
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

    /**
     * Wraps a password EditText inside a horizontal layout with an eye toggle
     * button so the user can reveal / hide the password.
     */
    private fun Context.passwordField(
        value: String?,
        hint: String,
    ): FrameLayout {
        val edit = styledInput(value, hint, password = true)
        val toggle = TextView(this).apply {
            text = "👁"
            textSize = sp(16f)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true
        }
        toggle.setOnClickListener {
            val showing = edit.transformationMethod !is PasswordTransformationMethod
            if (showing) {
                edit.transformationMethod = PasswordTransformationMethod.getInstance()
                toggle.text = "👁"
            } else {
                edit.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggle.text = "🙈"
            }
            edit.setSelection(edit.text?.length ?: 0)
        }
        return FrameLayout(this).apply {
            addView(edit, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(toggle, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ))
        }.also { it.tag = edit }  // keep a ref to the EditText
    }

    private fun Context.infoCard(text: String): View {
        val icon = TextView(this).apply {
            this.text = "💡"
            textSize = sp(14f)
            setPadding(0, 0, dp(8), 0)
        }
        val body = TextView(this).apply {
            this.text = text
            setTextColor(C_TEXT_SUB)
            textSize = sp(11.5f)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedRect(C_INFO_BG, 20f, 0xFF3A2E5C.toInt(), 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(icon)
            addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Returns the EditText held inside the password FrameLayout. */
    private fun passwordEditText(wrapper: FrameLayout): EditText =
        wrapper.tag as EditText

    // ── Main settings dialog ─────────────────────────────────────────────────

    private fun showSettings(context: Context, prefs: SharedPreferences) {
        // Build all the fields up front
        val email = context.styledInput(prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com")
        val passWrapper = context.passwordField(prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password")
        val token = context.styledInput(prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token", multiLine = true)
        val cookie = context.styledInput(prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header", multiLine = true)
        val apiBase = context.styledInput(
            prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE),
            CTGMovies.DEFAULT_API_BASE
        )

        val dp16 = context.dp(16)

        // ── Form body (goes inside the ScrollView) ───────────────────────────
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp16)

            addView(context.sectionLabel("🔐  ACCOUNT LOGIN"))
            addView(context.fieldLabel("Email"))
            addView(email)
            addView(context.fieldLabel("Password").apply { setPadding(0, context.dp(10), 0, context.dp(5)) })
            addView(passWrapper)

            addView(context.sectionLabel("🔑  QUICK ACCESS"))
            addView(context.fieldLabel("Token"))
            addView(token)
            addView(context.fieldLabel("Cookie").apply { setPadding(0, context.dp(10), 0, context.dp(5)) })
            addView(cookie)

            addView(context.sectionLabel("⚙️  ADVANCED"))
            addView(context.fieldLabel("API Base"))
            addView(apiBase)

            addView(context.infoCard(
                "For protected content, either enter email/password for auto-login, " +
                "paste a ctg.token / Bearer token, or paste a raw Cookie header. " +
                "Everything is saved locally in Cloudstream's extension settings only."
            ), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(20) })
        }

        val scroll = ScrollView(context).apply {
            addView(form)
            isFillViewport = true
        }

        // ── Custom action button bar ─────────────────────────────────────────
        val clearBtn = TextView(context).apply {
            text = "CLEAR"
            setTextColor(C_RED)
            textSize = context.sp(13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = outlineButtonBg(0xFF3A2030.toInt(), 0xFF2A1820.toInt())
            setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(12))
            isClickable = true
        }
        clearBtn.setOnClickListener {
            prefs.edit()
                .remove(CTGMovies.PREF_EMAIL)
                .remove(CTGMovies.PREF_PASSWORD)
                .remove(CTGMovies.PREF_TOKEN)
                .remove(CTGMovies.PREF_COOKIE)
                .putString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE)
                .apply()
            email.setText("")
            passwordEditText(passWrapper).setText("")
            token.setText("")
            cookie.setText("")
            apiBase.setText(CTGMovies.DEFAULT_API_BASE)
            Toast.makeText(context, "Settings cleared", Toast.LENGTH_SHORT).show()
        }

        val cancelBtn = TextView(context).apply {
            text = "CANCEL"
            setTextColor(C_TEXT_SUB)
            textSize = context.sp(13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = outlineButtonBg(C_BORDER, C_SURFACE_HI)
            setPadding(context.dp(10), context.dp(12), context.dp(10), context.dp(12))
            isClickable = true
        }
        cancelBtn.setOnClickListener { /* dismiss wired below once dialog is built */ }

        val saveBtn = TextView(context).apply {
            text = "  ✓  SAVE  "
            setTextColor(Color.WHITE)
            textSize = context.sp(13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = accentGradient()
            setPadding(context.dp(6), context.dp(12), context.dp(6), context.dp(12))
            isClickable = true
        }
        // dialog is assigned below; declared as var so listeners can capture it.
        var dialog: AlertDialog? = null

        saveBtn.setOnClickListener {
            prefs.edit()
                .putString(CTGMovies.PREF_EMAIL, email.text?.toString()?.trim().orEmpty())
                .putString(CTGMovies.PREF_PASSWORD, passwordEditText(passWrapper).text?.toString().orEmpty())
                .putString(CTGMovies.PREF_TOKEN, token.text?.toString()?.trim().orEmpty())
                .putString(CTGMovies.PREF_COOKIE, cookie.text?.toString()?.trim().orEmpty())
                .putString(
                    CTGMovies.PREF_API_BASE,
                    apiBase.text?.toString()?.trim()?.ifBlank { CTGMovies.DEFAULT_API_BASE }
                        ?: CTGMovies.DEFAULT_API_BASE
                )
                .apply()
            Toast.makeText(context, "✓ Settings saved", Toast.LENGTH_SHORT).show()
            dialog?.dismiss()
        }

        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp16, context.dp(6), dp16, dp16)
            weightSum = 3f
            addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(8) })
            addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = context.dp(8) })
            addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ── Root: header + scrollable form + button bar ──────────────────────
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            addView(context.headerView())
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f })
            addView(btnBar)
        }

        // Cap the dialog height so the button bar is always reachable on
        // smaller screens: ScrollView takes whatever's left after header + bar.
        val screenHeight = context.resources.displayMetrics.heightPixels
        val dlg = AlertDialog.Builder(context)
            .setView(root)
            .create().apply {
                window?.setBackgroundDrawable(roundedRect(Color.TRANSPARENT, 0f))
            }
        dialog = dlg

        cancelBtn.setOnClickListener { dlg.dismiss() }

        // Apply max height after show so the window is measured
        dlg.setOnShowListener {
            val lp = dlg.window?.attributes
            if (lp != null) {
                lp.height = (screenHeight * 0.82f).toInt()
                dlg.window?.attributes = lp
            }
        }

        dlg.show()
        // Apply rounded corners to the outermost container
        root.background = roundedRect(C_BG, 32f)
    }
}
