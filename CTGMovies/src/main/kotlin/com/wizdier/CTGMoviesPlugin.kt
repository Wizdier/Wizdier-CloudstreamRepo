package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
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
        openSettings = { ctx -> CTGSettingsUI.show(ctx, prefs) }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  CTGSettingsUI — settings dialog
//
//  Design language now mirrors CineStream (SaurabhKaperwan/CSX) exactly:
//    • Hero banner with TL_BR gradient + accent bar + bold title + subtitle
//    • Collapsible cards with accent strip + bold uppercase title + chevron
//    • Same font sizes, spacing, radii, and elevation as CineStream
//    • Same color tokens (BG_DARK, BG_CARD, ACCENT_START, ACCENT_END, etc.)
//
//  Colours are resolved from the host activity's theme attributes so the
//  dialog adapts to Cloudstream's light/dark mode and the user's accent
//  colour automatically, while the spacing/typography are pixel-identical
//  to CineStream.
// ═══════════════════════════════════════════════════════════════════════════

object CTGSettingsUI {

    // ── Color tokens (CineStream palette, with theme-aware overrides) ────────
    // Defaults mirror CineStream's SettingsTheme.kt exactly. At show() time
    // we attempt to resolve them from the host activity's theme so the dialog
    // adapts to Cloudstream's light/dark mode and accent colour.
    private var BG_DARK: Int = 0
    private var BG_CARD: Int = 0
    private var ACCENT_START: Int = 0
    private var ACCENT_END: Int = 0
    private var TEXT_PRIMARY: Int = 0
    private var TEXT_SECONDARY: Int = 0
    private var DIVIDER_COLOR: Int = 0
    private var DANGER_COLOR: Int = 0
    private var INPUT_BG: Int = 0
    private var INPUT_BORDER: Int = 0
    private var INPUT_BORDER_FOCUS: Int = 0

    // ── dp / sp helpers (CineStream convention) ──────────────────────────────
    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun dpF(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    private fun sp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    // ── Theme resolution ─────────────────────────────────────────────────────
    private fun resolveColor(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        val ok = ctx.theme.resolveAttribute(attr, tv, true)
        return if (ok && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) tv.data else fallback
    }

    private fun isDark(c: Int): Boolean {
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return l < 0.5f
    }

    private fun blendColor(a: Int, b: Int, t: Float): Int {
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val b2 = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b2)
    }

    private fun resolveColors(ctx: Context) {
        // CineStream defaults — these are what the dialog falls back to if the
        // host theme doesn't expose a particular attribute.
        val csBgDark = Color.parseColor("#0D0F14")
        val csBgCard = Color.parseColor("#13161E")
        val csAccentStart = Color.parseColor("#6C63FF")
        val csAccentEnd = Color.parseColor("#A855F7")
        val csTextPrimary = Color.parseColor("#F0F2FF")
        val csTextSecondary = Color.parseColor("#7B82A0")
        val csDivider = Color.parseColor("#1F2235")
        val csDanger = Color.parseColor("#FF4E6A")
        val csInputBg = Color.parseColor("#0D1117")
        val csInputBorder = Color.parseColor("#2E2850")

        // Resolve what we can from the host theme.
        BG_DARK = resolveColor(ctx, android.R.attr.colorBackground, csBgDark)
        BG_CARD = resolveColor(ctx, android.R.attr.colorBackgroundFloating, csBgCard)
        // If colorBackgroundFloating wasn't available, derive a slightly
        // elevated surface from BG_DARK.
        if (BG_CARD == BG_DARK) {
            BG_CARD = blendColor(BG_DARK, if (isDark(BG_DARK)) Color.WHITE else Color.BLACK, 0.04f)
        }
        ACCENT_START = resolveColor(ctx, android.R.attr.colorAccent, csAccentStart)
        // ACCENT_END is a complementary accent — if we only have one accent
        // colour from the theme, derive a shifted variant for the gradient.
        ACCENT_END = blendColor(ACCENT_START, Color.parseColor("#A855F7"), 0.5f)
        TEXT_PRIMARY = resolveColor(ctx, android.R.attr.textColorPrimary, csTextPrimary)
        TEXT_SECONDARY = resolveColor(ctx, android.R.attr.textColorSecondary, csTextSecondary)
        DIVIDER_COLOR = blendColor(BG_CARD, if (isDark(BG_DARK)) Color.WHITE else Color.BLACK, 0.08f)
        DANGER_COLOR = resolveColor(ctx, android.R.attr.colorError, csDanger)
        INPUT_BG = blendColor(BG_DARK, if (isDark(BG_DARK)) Color.WHITE else Color.BLACK, 0.03f)
        INPUT_BORDER = blendColor(BG_CARD, ACCENT_START, 0.25f)
        INPUT_BORDER_FOCUS = ACCENT_START
    }

    // ── Drawable factories (CineStream style) ────────────────────────────────
    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun roundRect(color: Int, radius: Float, stroke: Int, sw: Int) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            setStroke(sw, stroke)
        }

    private fun verticalGradient(top: Int, bottom: Int, radius: Float = 99f) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            .apply { cornerRadius = radius }

    private fun stateDrawable(ctx: Context): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            roundRect(blendColor(BG_CARD, ACCENT_START, 0.12f), dpF(ctx, 16f))
        )
        addState(
            intArrayOf(android.R.attr.state_focused),
            roundRect(BG_CARD, dpF(ctx, 16f), ACCENT_START, 2)
        )
        addState(intArrayOf(), roundRect(BG_CARD, dpF(ctx, 16f)))
    }

    private fun inputBackground(ctx: Context) = GradientDrawable().apply {
        cornerRadius = dpF(ctx, 10f)
        setColor(INPUT_BG)
        setStroke(1, INPUT_BORDER)
    }

    private fun inputBackgroundFocused(ctx: Context) = GradientDrawable().apply {
        cornerRadius = dpF(ctx, 10f)
        setColor(INPUT_BG)
        setStroke(2, INPUT_BORDER_FOCUS)
    }

    // ── Animations (CineStream timings) ──────────────────────────────────────
    private fun fadeInSlide(v: View) {
        v.alpha = 0f
        v.translationY = 20f
        v.animate()
            .alpha(1f).translationY(0f)
            .setDuration(300).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animateExpand(v: View, expand: Boolean) {
        if (expand) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(220).start()
        } else {
            v.animate().alpha(0f).setDuration(160).withEndAction {
                v.visibility = View.GONE
                v.alpha = 1f
            }.start()
        }
    }

    // ── Accent bar (left colour strip used in card headers) ──────────────────
    private fun accentBar(ctx: Context, top: Int, bottom: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 14))
            .also { it.marginEnd = dp(ctx, 10) }
        background = verticalGradient(top, bottom)
    }

    // ── Hero banner (CineStream style) ───────────────────────────────────────
    private fun buildHeroBanner(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(blendColor(BG_DARK, ACCENT_START, 0.12f), BG_DARK)
            )

            // Accent bar (3 dp tall, 36 dp wide, gradient ACCENT_START → ACCENT_END).
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 3))
                    .also { it.bottomMargin = dp(ctx, 10) }
                background = verticalGradient(ACCENT_START, ACCENT_END)
            })
            // Title.
            addView(TextView(ctx).apply {
                text = "CTGMovies"
                textSize = sp(ctx, 18f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                letterSpacing = -0.02f
            })
            // Subtitle.
            addView(TextView(ctx).apply {
                text = "Configure login credentials & API access"
                textSize = sp(ctx, 11f)
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        }
    }

    // ── Divider ──────────────────────────────────────────────────────────────
    private fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
        setBackgroundColor(DIVIDER_COLOR)
    }

    // ── Card container ───────────────────────────────────────────────────────
    private fun cardContainer(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val m = dp(ctx, 12)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(m, 0, m, dp(ctx, 10)) }
        background = roundRect(BG_CARD, dpF(ctx, 14f))
        elevation = 4f
    }

    // ── Collapsible card ─────────────────────────────────────────────────────
    private fun buildCollapsibleCard(
        ctx: Context,
        title: String,
        startExpanded: Boolean = false,
        accentA: Int = ACCENT_START,
        accentB: Int = ACCENT_END,
        buildContent: LinearLayout.() -> Unit,
    ): View {
        val card = cardContainer(ctx)
        var expanded = startExpanded
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 4))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        content.buildContent()

        val chevron = TextView(ctx).apply {
            text = if (expanded) "▲" else "▼"
            textSize = sp(ctx, 9f)
            setTextColor(TEXT_SECONDARY)
        }

        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true; isFocusableInTouchMode = false
            background = stateDrawable(ctx)

            addView(accentBar(ctx, accentA, accentB))
            addView(TextView(ctx).apply {
                text = title
                textSize = sp(ctx, 10f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_SECONDARY)
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(chevron)

            setOnClickListener {
                expanded = !expanded
                chevron.text = if (expanded) "▲" else "▼"
                animateExpand(content, expanded)
            }
        })
        card.addView(content)
        fadeInSlide(card)
        return card
    }

    // ── Label ────────────────────────────────────────────────────────────────
    private fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = sp(ctx, 10f)
        setTextColor(TEXT_SECONDARY)
        setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 6))
    }

    // ── Styled input ─────────────────────────────────────────────────────────
    private fun input(
        ctx: Context, value: String?, hint: String,
        isPassword: Boolean = false, isMultiLine: Boolean = false,
        imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    ): EditText = EditText(ctx).apply {
        setText(value.orEmpty())
        this.hint = hint
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        textSize = sp(ctx, 11f)
        background = inputBackground(ctx)
        setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(ctx, 6) }
        inputType = when {
            isPassword -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isMultiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (isMultiLine) {
            minLines = 2; maxLines = 4; isSingleLine = false
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        } else {
            setSingleLine(true)
            imeOptions = imeAction
        }
        setOnFocusChangeListener { _, hasFocus ->
            background = if (hasFocus) inputBackgroundFocused(ctx) else inputBackground(ctx)
        }
    }

    // ── Field row (label + input, optional password toggle) ──────────────────
    private class Field(val edit: EditText, val view: View)

    private fun field(
        ctx: Context, labelText: String, value: String?, hint: String,
        isPassword: Boolean = false, isMultiLine: Boolean = false,
        imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    ): Field {
        if (isPassword) {
            val edit = EditText(ctx).apply {
                setText(value.orEmpty())
                this.hint = hint
                setTextColor(TEXT_PRIMARY)
                setHintTextColor(TEXT_SECONDARY)
                textSize = sp(ctx, 11f)
                background = ColorDrawable(Color.TRANSPARENT)
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 6), dp(ctx, 8))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                imeOptions = imeAction
            }

            val toggle = TextView(ctx).apply {
                text = "👁"
                textSize = sp(ctx, 12f)
                setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
            }

            toggle.setOnClickListener {
                val hidden = edit.transformationMethod is PasswordTransformationMethod
                edit.transformationMethod = if (hidden)
                    HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
                toggle.text = if (hidden) "🙈" else "👁"
                edit.setSelection(edit.text?.length ?: 0)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = inputBackground(ctx)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(ctx, 6) }
                edit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(edit)
                addView(toggle)
            }
            edit.setOnFocusChangeListener { _, hasFocus ->
                row.background = if (hasFocus) inputBackgroundFocused(ctx) else inputBackground(ctx)
            }
            return Field(edit, row)
        } else {
            val edit = input(ctx, value, hint, isPassword, isMultiLine, imeAction)
            return Field(edit, edit)
        }
    }

    // ── Info card ────────────────────────────────────────────────────────────
    private fun infoCard(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val m = dp(ctx, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(m, 0, m, dp(ctx, 10)) }
            background = roundRect(
                blendColor(BG_CARD, ACCENT_START, 0.08f),
                dpF(ctx, 10f),
                blendColor(ACCENT_START, DIVIDER_COLOR, 0.5f),
                1
            )
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            gravity = Gravity.TOP

            addView(TextView(ctx).apply {
                text = "ℹ"
                textSize = sp(ctx, 11f)
                setTextColor(ACCENT_START)
                setPadding(0, 0, dp(ctx, 8), 0)
            })
            addView(TextView(ctx).apply {
                this.text = "Enter email/password for auto-login, paste a ctg.token / Bearer token, or a raw Cookie header. Everything is saved locally on this device only."
                textSize = sp(ctx, 10f)
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(ctx, 2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main entry point
    // ═════════════════════════════════════════════════════════════════════════

    fun show(ctx: Context, prefs: SharedPreferences) {
        resolveColors(ctx)

        val scroll = ScrollView(ctx).apply {
            isScrollbarFadingEnabled = true
            background = ColorDrawable(BG_DARK)
            isFocusable = false
            descendantFocusability = ScrollView.FOCUS_AFTER_DESCENDANTS
        }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 16))
            background = ColorDrawable(BG_DARK)
        }

        // ── Hero banner ────────────────────────────────────────────────────
        layout.addView(buildHeroBanner(ctx))
        layout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 4)
            )
        })

        // ── Fields ─────────────────────────────────────────────────────────
        // IME action chain: Email → Next → Password → Next → Token → Next →
        // Cookie → Next → API Base → Done.
        val fEmail = field(
            ctx, "Email", prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com",
            imeAction = EditorInfo.IME_ACTION_NEXT
        )
        val fPass = field(
            ctx, "Password", prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password",
            isPassword = true, imeAction = EditorInfo.IME_ACTION_NEXT
        )
        val fToken = field(
            ctx, "Token", prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token",
            isMultiLine = true
        )
        val fCookie = field(
            ctx, "Cookie", prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header",
            isMultiLine = true
        )
        val fApi = field(
            ctx, "API Base", prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE),
            CTGMovies.DEFAULT_API_BASE, imeAction = EditorInfo.IME_ACTION_DONE
        )

        // ── Card: Account Login ────────────────────────────────────────────
        layout.addView(buildCollapsibleCard(ctx, "🔐  ACCOUNT LOGIN",
            accentA = ACCENT_START, accentB = ACCENT_END,
            startExpanded = true) {
            addView(label(ctx, "Email"))
            addView(fEmail.view)
            addView(divider(ctx))
            addView(label(ctx, "Password"))
            addView(fPass.view)
        })

        // ── Card: Quick Access ─────────────────────────────────────────────
        layout.addView(buildCollapsibleCard(ctx, "🔑  QUICK ACCESS",
            accentA = ACCENT_END, accentB = ACCENT_START) {
            addView(label(ctx, "Token"))
            addView(fToken.view)
            addView(divider(ctx))
            addView(label(ctx, "Cookie"))
            addView(fCookie.view)
        })

        // ── Card: Advanced ─────────────────────────────────────────────────
        layout.addView(buildCollapsibleCard(ctx, "⚙️  ADVANCED",
            accentA = ACCENT_START, accentB = ACCENT_END) {
            addView(label(ctx, "API Base"))
            addView(fApi.view)
        })

        // ── Info ───────────────────────────────────────────────────────────
        layout.addView(infoCard(ctx))

        scroll.addView(layout)

        // ── Dialog ─────────────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(ACCENT_START)
                isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    prefs.edit()
                        .putString(CTGMovies.PREF_EMAIL, fEmail.edit.text?.toString()?.trim().orEmpty())
                        .putString(CTGMovies.PREF_PASSWORD, fPass.edit.text?.toString().orEmpty())
                        .putString(CTGMovies.PREF_TOKEN, fToken.edit.text?.toString()?.trim().orEmpty())
                        .putString(CTGMovies.PREF_COOKIE, fCookie.edit.text?.toString()?.trim().orEmpty())
                        .putString(
                            CTGMovies.PREF_API_BASE,
                            fApi.edit.text?.toString()?.trim()?.ifBlank { CTGMovies.DEFAULT_API_BASE }
                                ?: CTGMovies.DEFAULT_API_BASE
                        )
                        .apply()
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(TEXT_SECONDARY)
                isAllCaps = false
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(DANGER_COLOR)
                isAllCaps = false
                setOnClickListener {
                    prefs.edit()
                        .remove(CTGMovies.PREF_EMAIL).remove(CTGMovies.PREF_PASSWORD)
                        .remove(CTGMovies.PREF_TOKEN).remove(CTGMovies.PREF_COOKIE)
                        .putString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE)
                        .apply()
                    fEmail.edit.setText(""); fPass.edit.setText("")
                    fToken.edit.setText(""); fCookie.edit.setText("")
                    fApi.edit.setText(CTGMovies.DEFAULT_API_BASE)
                    Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.window?.setBackgroundDrawable(roundRect(BG_DARK, dpF(ctx, 20f)))
        dialog.show()

        // ── Dialog window sizing ───────────────────────────────────────────
        val dm = ctx.resources.displayMetrics
        val widthPx = (dm.widthPixels * 0.82).toInt()
        val maxHPx = (dm.heightPixels * 0.72f).toInt()

        dialog.window?.apply {
            setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { if (height > maxHPx) height = maxHPx }
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }
    }
}
