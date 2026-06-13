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
            CTGSettingsUI.show(ctx, prefs)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  CTGSettingsUI
//
//  Renders an elegant dark-themed settings/login dialog using a standard
//  AlertDialog (no androidx.fragment dependency required — only the Android
//  framework APIs that are guaranteed on Cloudstream's compile classpath).
//  All views are built programmatically, no XML resources needed.
// ═══════════════════════════════════════════════════════════════════════════

object CTGSettingsUI {

    // ── Palette (dark charcoal-grey) ─────────────────────────────────────────
    private const val C_BG      = 0xFF1A1A1E.toInt()
    private const val C_CARD    = 0xFF242429.toInt()
    private const val C_BORDER  = 0xFF383840.toInt()
    private const val C_ACCENT  = 0xFF3A3A44.toInt()
    private const val C_ACCENT2 = 0xFF4A4A55.toInt()
    private const val C_TEXT    = 0xFFD8D8DF.toInt()
    private const val C_SUB     = 0xFF7A7A85.toInt()
    private const val C_HINT    = 0xFF4E4E58.toInt()
    private const val C_RED     = 0xFFE07070.toInt()

    // ── Density helpers ──────────────────────────────────────────────────────
    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun sp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    // ── Drawables ────────────────────────────────────────────────────────────
    private fun fill(color: Int, r: Float, stroke: Int? = null, sw: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = r
            if (stroke != null) setStroke(sw, stroke)
        }

    private fun gradient(r: Float) =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(C_ACCENT, C_ACCENT2)
        ).apply { cornerRadius = r }

    private fun pressable(normal: GradientDrawable, pressed: GradientDrawable) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

    // ═════════════════════════════════════════════════════════════════════════
    //  View factories
    // ═════════════════════════════════════════════════════════════════════════

    private fun makeHeader(ctx: Context): View {
        val d3 = dp(ctx, 3); val d4 = dp(ctx, 4); val d14 = dp(ctx, 14)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(d14.toFloat())
            setPadding(d14, dp(ctx, 14), d14, dp(ctx, 13))
            gravity = Gravity.CENTER

            addView(TextView(ctx).apply {
                text = "🎬"
                textSize = 20f
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = "CTGMovies"
                setTextColor(Color.WHITE)
                textSize = sp(ctx, 16f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(0, d3, 0, 0)
            })
            addView(TextView(ctx).apply {
                text = "Sign in to unlock protected content"
                setTextColor(0xFF9A9AA5.toInt())
                textSize = sp(ctx, 9.5f)
                gravity = Gravity.CENTER
                setPadding(0, d3, 0, 0)
            })
        }
    }

    private fun makeSection(ctx: Context, label: String): TextView {
        val d3 = dp(ctx, 3); val d12 = dp(ctx, 12)
        return TextView(ctx).apply {
            text = label
            setTextColor(0xFF9A9AA5.toInt())
            textSize = sp(ctx, 9.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(d3, d12, d3, d3)
        }
    }

    private fun makeLabel(ctx: Context, label: String): TextView {
        val d3 = dp(ctx, 3)
        return TextView(ctx).apply {
            text = label
            setTextColor(C_SUB)
            textSize = sp(ctx, 9.5f)
            setPadding(d3, 0, d3, d3)
        }
    }

    private fun makeInput(
        ctx: Context,
        value: String?,
        hint: String,
        isPassword: Boolean = false,
        isMultiLine: Boolean = false,
    ): EditText {
        val d8 = dp(ctx, 8); val d10 = dp(ctx, 10)
        return EditText(ctx).apply {
            setText(value.orEmpty())
            this.hint = hint
            setHintTextColor(C_HINT)
            setTextColor(C_TEXT)
            textSize = sp(ctx, 11.5f)
            background = fill(C_CARD, dp(ctx, 12).toFloat(), C_BORDER, dp(ctx, 1))
            setPadding(d10, d8, d10, d8)
            inputType = when {
                isPassword -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isMultiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (isMultiLine) {
                minLines = 2; maxLines = 5; isSingleLine = false
            } else {
                setSingleLine(true)
            }
        }
    }

    private class FieldRow(val container: View, val edit: EditText)

    private fun makeField(
        ctx: Context,
        label: String,
        value: String?,
        hint: String,
        isPassword: Boolean = false,
        isMultiLine: Boolean = false,
    ): FieldRow {
        val edit = makeInput(ctx, value, hint, isPassword, isMultiLine)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(makeLabel(ctx, label))
            if (isPassword) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val toggle = TextView(ctx).apply {
                    text = "👁"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    val p = dp(ctx, 8)
                    setPadding(p, p, p, p)
                }
                toggle.setOnClickListener {
                    val hidden = edit.transformationMethod is PasswordTransformationMethod
                    edit.transformationMethod = if (hidden)
                        HideReturnsTransformationMethod.getInstance()
                    else
                        PasswordTransformationMethod.getInstance()
                    toggle.text = if (hidden) "🙈" else "👁"
                    edit.setSelection(edit.text?.length ?: 0)
                }
                row.addView(toggle)
                addView(row)
            } else {
                addView(edit)
            }
        }
        return FieldRow(container, edit)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main entry point
    // ═════════════════════════════════════════════════════════════════════════

    fun show(ctx: Context, prefs: SharedPreferences) {
        val d4 = dp(ctx, 4); val d6 = dp(ctx, 6); val d12 = dp(ctx, 12)
        val d14 = dp(ctx, 14); val d16 = dp(ctx, 16)

        // ── Fields ───────────────────────────────────────────────────────────
        val fEmail  = makeField(ctx, "Email",    prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com")
        val fPass   = makeField(ctx, "Password", prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password", isPassword = true)
        val fToken  = makeField(ctx, "Token",    prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token", isMultiLine = true)
        val fCookie = makeField(ctx, "Cookie",   prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header", isMultiLine = true)
        val fApi    = makeField(ctx, "API Base", prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE), CTGMovies.DEFAULT_API_BASE)

        // ── Info note ────────────────────────────────────────────────────────
        val note = TextView(ctx).apply {
            text = "💡  Enter email/password for auto-login, paste a ctg.token / Bearer token, or a raw Cookie header. Everything is saved locally in Cloudstream's extension settings only."
            setTextColor(0xFF6A6A75.toInt())
            textSize = sp(ctx, 9f)
            background = fill(0xFF202024.toInt(), dp(ctx, 12).toFloat(), 0xFF333338.toInt(), dp(ctx, 1))
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        }

        // ── Build the scrollable form body ───────────────────────────────────
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)

            addView(makeHeader(ctx), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d14, dp(ctx, 8), d14, dp(ctx, 8))

                addView(makeSection(ctx, "🔐  ACCOUNT"))
                addView(fEmail.container)
                addView(fPass.container)

                addView(makeSection(ctx, "🔑  QUICK ACCESS"))
                addView(fToken.container)
                addView(fCookie.container)

                addView(makeSection(ctx, "⚙️  ADVANCED"))
                addView(fApi.container)

                addView(note, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = d12 })
            }
            addView(inner)
        }

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(C_BG)
            addView(form)
        }

        // ── Build the AlertDialog ────────────────────────────────────────────
        val dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Save", null)   // null → we intercept below to prevent auto-dismiss on validation
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .create()

        dialog.setOnShowListener {
            // Style the built-in buttons with our accent colors
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFFB0B0C0.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setOnClickListener {
                    prefs.edit()
                        .putString(CTGMovies.PREF_EMAIL,    fEmail.edit.text?.toString()?.trim().orEmpty())
                        .putString(CTGMovies.PREF_PASSWORD, fPass.edit.text?.toString().orEmpty())
                        .putString(CTGMovies.PREF_TOKEN,    fToken.edit.text?.toString()?.trim().orEmpty())
                        .putString(CTGMovies.PREF_COOKIE,   fCookie.edit.text?.toString()?.trim().orEmpty())
                        .putString(
                            CTGMovies.PREF_API_BASE,
                            fApi.edit.text?.toString()?.trim()?.ifBlank { CTGMovies.DEFAULT_API_BASE }
                                ?: CTGMovies.DEFAULT_API_BASE
                        )
                        .apply()
                    Toast.makeText(ctx, "✓ Settings saved", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(C_SUB)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(C_RED)
                setOnClickListener {
                    prefs.edit()
                        .remove(CTGMovies.PREF_EMAIL)
                        .remove(CTGMovies.PREF_PASSWORD)
                        .remove(CTGMovies.PREF_TOKEN)
                        .remove(CTGMovies.PREF_COOKIE)
                        .putString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE)
                        .apply()
                    fEmail.edit.setText("")
                    fPass.edit.setText("")
                    fToken.edit.setText("")
                    fCookie.edit.setText("")
                    fApi.edit.setText(CTGMovies.DEFAULT_API_BASE)
                    Toast.makeText(ctx, "Settings cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()

        // Apply our dark rounded background to the dialog window
        dialog.window?.apply {
            setBackgroundDrawable(fill(C_BG, dp(ctx, 24).toFloat()))
            // Cap the height to 85% of screen so it scrolls cleanly on small devices
            val dm = ctx.resources.displayMetrics
            val maxH = (dm.heightPixels * 0.85f).toInt()
            attributes = attributes.apply {
                if (height > maxH) height = maxH
            }
        }
    }
}
