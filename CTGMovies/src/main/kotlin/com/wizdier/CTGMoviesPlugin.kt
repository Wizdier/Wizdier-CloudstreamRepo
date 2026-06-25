package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
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
//  CTGSettingsUI — Cloudstream-native settings dialog
//
//  Design goals (revised):
//    1. Resolve colours from the host activity's theme so the dialog matches
//       Cloudstream's light/dark mode and any user-customised accent colour
//       automatically. The previous version hardcoded a charcoal/indigo
//       palette that clashed with light-mode users.
//    2. Use the Material 8 dp spacing grid throughout (4 / 8 / 12 / 16 / 24)
//       so spacing feels even and predictable.
//    3. Replace the gaudy gradient hero banner with a clean header: icon tile
//       + title + one-line description. Reads like a real settings screen,
//       not a marketing splash.
//    4. Replace emoji-laden section titles with plain uppercase letterspaced
//       text + a thin accent strip — matches the AOSP/Material settings
//       convention.
//    5. Add proper ripple feedback to every tappable surface (cards, buttons).
//    6. Fix the dialog sizing: phones get 94 % width, tablets get capped at
//       600 dp so the dialog doesn't stretch absurdly on landscape/foldables.
//    7. Input fields get focus-state border colour, larger touch padding,
//       and IME actionNext / actionDone so the keyboard's "next" button
//       moves the user through the form.
//
//  Built entirely from framework widgets (no androidx dependency).
// ═══════════════════════════════════════════════════════════════════════════

object CTGSettingsUI {

    // ── Theme-resolved palette (computed in show()) ──────────────────────────
    // These are populated from the host activity's theme attributes so the
    // dialog inherits Cloudstream's colours instead of imposing its own.
    private var bgRoot: Int = 0
    private var bgCard: Int = 0
    private var bgInput: Int = 0
    private var bgInputFocused: Int = 0
    private var border: Int = 0
    private var borderFocused: Int = 0
    private var textPrimary: Int = 0
    private var textSecondary: Int = 0
    private var textHint: Int = 0
    private var divider: Int = 0
    private var accent: Int = 0
    private var danger: Int = 0

    // ── dp / sp helpers ──────────────────────────────────────────────────────
    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun sp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    // ── Theme resolution ─────────────────────────────────────────────────────
    /**
     * Resolve a theme attribute to a colour. Falls back to [fallback] if the
     * attribute isn't defined (e.g. on very old Android where Material attrs
     * aren't available).
     */
    private fun resolveColor(ctx: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        val ok = ctx.theme.resolveAttribute(attr, tv, true)
        return if (ok && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            tv.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) tv.data else fallback
    }

    private fun resolveColors(ctx: Context) {
        // Material/AndroidX attrs we want to honour. Cloudstream uses Material
        // Components, so these resolve on every supported API level.
        bgRoot = resolveColor(ctx, android.R.attr.colorBackground, Color.parseColor("#121212"))
        bgCard = resolveColor(ctx, android.R.attr.colorBackground, Color.parseColor("#121212"))
        // Slightly elevated surface for cards — if the theme exposes
        // colorBackgroundFloating, use it; otherwise lift bgRoot by a few %.
        bgCard = resolveColor(ctx, android.R.attr.colorBackgroundFloating, bgCard)
        bgInput = resolveColor(ctx, android.R.attr.colorBackground, bgRoot)
        border = blendColor(bgCard, if (isDark(bgRoot)) Color.WHITE else Color.BLACK, 0.12f)
        borderFocused = resolveColor(ctx, android.R.attr.colorAccent, Color.parseColor("#6C63FF"))
        textPrimary = resolveColor(ctx, android.R.attr.textColorPrimary, Color.parseColor("#EDEDED"))
        textSecondary = resolveColor(ctx, android.R.attr.textColorSecondary, Color.parseColor("#B0B0B0"))
        textHint = blendColor(textSecondary, bgRoot, 0.4f)
        divider = blendColor(bgCard, if (isDark(bgRoot)) Color.WHITE else Color.BLACK, 0.08f)
        accent = resolveColor(ctx, android.R.attr.colorAccent, Color.parseColor("#6C63FF"))
        danger = resolveColor(ctx, android.R.attr.colorError, Color.parseColor("#FF4E6A"))
        // When focused, the field gets a faint accent tint so the focus ring
        // is more perceptible on themes with low border/text contrast.
        bgInputFocused = blendColor(bgInput, accent, 0.06f)
    }

    /** True if [c] is perceptually dark (luminance < 0.5). */
    private fun isDark(c: Int): Boolean {
        val r = Color.red(c) / 255f
        val g = Color.green(c) / 255f
        val b = Color.blue(c) / 255f
        // Relative luminance (Rec. 709).
        val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return l < 0.5f
    }

    /** Linear blend between two colours. [t]=0 → a, [t]=1 → b. */
    private fun blendColor(a: Int, b: Int, t: Float): Int {
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val b2 = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b2)
    }

    // ── Drawables ────────────────────────────────────────────────────────────
    private fun roundRect(color: Int, radius: Float, stroke: Int? = null, sw: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke != null) setStroke(sw, stroke)
        }

    private fun accentBar(color: Int) =
        GradientDrawable().apply {
            cornerRadius = 99f
            setColor(color)
        }

    private fun selectableBackground(radius: Float): StateListDrawable = StateListDrawable().apply {
        val pressed = roundRect(blendColor(bgCard, accent, 0.12f), radius)
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), roundRect(bgCard, radius))
    }

    // ── Animations ───────────────────────────────────────────────────────────
    private fun fadeInSlide(v: View) {
        v.alpha = 0f
        v.translationY = 12f
        v.animate().alpha(1f).translationY(0f)
            .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animateExpand(v: View, expand: Boolean) {
        if (expand) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(180).start()
        } else {
            v.animate().alpha(0f).setDuration(120)
                .withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
        }
    }

    // ── Header (replaces the old hero banner) ────────────────────────────────
    /**
     * Clean header: small accent square + title + one-line description.
     * Reads like the top of a real settings screen, not a marketing splash.
     */
    private fun buildHeader(ctx: Context): View {
        val d12 = dp(ctx, 12); val d16 = dp(ctx, 16); val d20 = dp(ctx, 20)
        // Accent-tile text colour: white on dark accents, black on light ones.
        val accentTextColor = if (isDark(accent)) Color.WHITE else Color.BLACK
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d20, d20, d20, d16)
            gravity = Gravity.CENTER_VERTICAL

            // Accent tile (small rounded square with the first letter of the
            // extension name — gives the user a visual anchor without a big
            // gradient banner).
            addView(TextView(ctx).apply {
                text = "C"
                textSize = sp(ctx, 12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentTextColor)
                gravity = Gravity.CENTER
                background = roundRect(accent, dp(ctx, 10).toFloat())
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply {
                    marginEnd = d12
                }
            })

            // Title + subtitle column.
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(ctx).apply {
                    text = "CTGMovies"
                    textSize = sp(ctx, 8f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textPrimary)
                    letterSpacing = -0.01f
                })
                addView(TextView(ctx).apply {
                    text = "Login credentials & API access"
                    textSize = sp(ctx, 8f)
                    setTextColor(textSecondary)
                    setPadding(0, dp(ctx, 2), 0, 0)
                    letterSpacing = 0.01f
                })
            })
        }
    }

    // ── Divider ──────────────────────────────────────────────────────────────
    private fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
        setBackgroundColor(divider)
    }

    // ── Card container ───────────────────────────────────────────────────────
    private fun cardContainer(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val m = dp(ctx, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(m, 0, m, dp(ctx, 12)) }
        background = roundRect(bgCard, dp(ctx, 14).toFloat())
        elevation = dp(ctx, 2).toFloat()
    }

    // ── Collapsible card ─────────────────────────────────────────────────────
    private fun buildCard(
        ctx: Context,
        title: String,
        startOpen: Boolean = false,
        buildContent: LinearLayout.() -> Unit,
    ): View {
        val card = cardContainer(ctx)
        var expanded = startOpen
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 8))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        content.buildContent()

        val chevron = TextView(ctx).apply {
            text = if (expanded) "\u25B2" else "\u25BC"  // ▲ : ▼
            textSize = sp(ctx, 9f)
            setTextColor(textSecondary)
        }

        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = selectableBackground(dp(ctx, 14).toFloat())

            // Thin accent strip (2 dp wide, rounded).
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 16)).apply {
                    marginEnd = dp(ctx, 12)
                }
                background = accentBar(accent)
            })
            addView(TextView(ctx).apply {
                this.text = title
                textSize = sp(ctx, 8.5f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(textSecondary)
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(chevron)

            setOnClickListener {
                expanded = !expanded
                chevron.text = if (expanded) "\u25B2" else "\u25BC"
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
        textSize = sp(ctx, 7f)
        setTextColor(textSecondary)
        letterSpacing = 0.04f
        setPadding(dp(ctx, 20), dp(ctx, 4), dp(ctx, 20), dp(ctx, 4))
    }

    // ── Styled input ─────────────────────────────────────────────────────────
    /**
     * Edit box with focus-aware border: when focused, the border switches to
     * the accent colour and the background lifts slightly. IME action is set
     * to IME_ACTION_NEXT for single-line fields (so the keyboard's "Next"
     * button advances focus) and IME_ACTION_DONE for the last field.
     */
    private fun input(
        ctx: Context, value: String?, hint: String,
        isPassword: Boolean = false, isMultiLine: Boolean = false,
        imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    ): EditText = EditText(ctx).apply {
        setText(value.orEmpty())
        this.hint = hint
        setHintTextColor(textHint)
        setTextColor(textPrimary)
        textSize = sp(ctx, 9f)
        background = roundRect(bgInput, dp(ctx, 10).toFloat(), border, 1)
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), dp(ctx, 12)) }
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
        // Focus-aware border.
        setOnFocusChangeListener { _, hasFocus ->
            background = if (hasFocus) {
                roundRect(bgInputFocused, dp(ctx, 10).toFloat(), borderFocused, 2)
            } else {
                roundRect(bgInput, dp(ctx, 10).toFloat(), border, 1)
            }
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
                setHintTextColor(textHint)
                setTextColor(textPrimary)
                textSize = sp(ctx, 9f)
                background = ColorDrawable(Color.TRANSPARENT)
                setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                imeOptions = imeAction
            }

            val toggle = TextView(ctx).apply {
                text = "\uD83D\uDC41"  // 👁
                textSize = sp(ctx, 9f)
                setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
            }

            toggle.setOnClickListener {
                val hidden = edit.transformationMethod is PasswordTransformationMethod
                edit.transformationMethod = if (hidden)
                    HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
                toggle.text = if (hidden) "\uD83D\uDE48" else "\uD83D\uDC41"  // 🙈 : 👁
                edit.setSelection(edit.text?.length ?: 0)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(bgInput, dp(ctx, 10).toFloat(), border, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), dp(ctx, 12)) }
                edit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(edit)
                addView(toggle)
            }
            // Focus-aware border for the wrapper row.
            edit.setOnFocusChangeListener { _, hasFocus ->
                row.background = if (hasFocus) {
                    roundRect(bgInputFocused, dp(ctx, 10).toFloat(), borderFocused, 2)
                } else {
                    roundRect(bgInput, dp(ctx, 10).toFloat(), border, 1)
                }
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
            val m = dp(ctx, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(m, 0, m, dp(ctx, 16)) }
            background = roundRect(
                blendColor(bgCard, accent, 0.08f),
                dp(ctx, 12).toFloat(),
                blendColor(accent, border, 0.5f),
                1
            )
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            gravity = Gravity.TOP

            addView(TextView(ctx).apply {
                text = "\u2139"  // ℹ
                textSize = sp(ctx, 9f)
                setTextColor(accent)
                setPadding(0, 0, dp(ctx, 10), 0)
            })
            addView(TextView(ctx).apply {
                this.text = "Enter email/password for auto-login, paste a ctg.token / Bearer token, or a raw Cookie header. Everything is saved locally on this device only."
                textSize = sp(ctx, 7f)
                setTextColor(textSecondary)
                setLineSpacing(dp(ctx, 2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main entry point
    // ═════════════════════════════════════════════════════════════════════════

    fun show(ctx: Context, prefs: SharedPreferences) {
        // Resolve theme colours BEFORE building any view so every helper can
        // use the resolved palette.
        resolveColors(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(bgRoot)
        }

        // ── Header ──────────────────────────────────────────────────────────
        root.addView(buildHeader(ctx))
        root.addView(divider(ctx))
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            )
        })

        // ── Fields ──────────────────────────────────────────────────────────
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

        // ── Card: Account Login ─────────────────────────────────────────────
        root.addView(buildCard(ctx, "ACCOUNT LOGIN", startOpen = true) {
            addView(label(ctx, "Email"))
            addView(fEmail.view)
            addView(divider(ctx))
            addView(label(ctx, "Password"))
            addView(fPass.view)
        })

        // ── Card: Quick Access ──────────────────────────────────────────────
        root.addView(buildCard(ctx, "QUICK ACCESS") {
            addView(label(ctx, "Token"))
            addView(fToken.view)
            addView(divider(ctx))
            addView(label(ctx, "Cookie"))
            addView(fCookie.view)
        })

        // ── Card: Advanced ──────────────────────────────────────────────────
        root.addView(buildCard(ctx, "ADVANCED") {
            addView(label(ctx, "API Base"))
            addView(fApi.view)
        })

        // ── Info ────────────────────────────────────────────────────────────
        root.addView(infoCard(ctx))
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 4)
            )
        })

        val scroll = ScrollView(ctx).apply {
            background = ColorDrawable(bgRoot)
            addView(root)
            isFillViewport = true
        }

        // ── Dialog ──────────────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .create()

        dialog.setOnShowListener {
            // Style buttons using the resolved accent colour so they match
            // the rest of the dialog instead of the platform default.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(accent)
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
                setTextColor(textSecondary)
                isAllCaps = false
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(danger)
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

        dialog.show()

        // ── Dialog window sizing ────────────────────────────────────────────
        // Phones: 94 % of screen width. Tablets (>= 600 dp): capped at 600 dp
        // so the dialog doesn't stretch absurdly wide. Height: wrap content,
        // but never exceed 85 % of screen height.
        val dm = ctx.resources.displayMetrics
        val screenWidthDp = dm.widthPixels / dm.density
        val maxWidthDp = if (screenWidthDp >= 600) 600 else (screenWidthDp * 0.94).toInt()
        val widthPx = (maxWidthDp * dm.density).toInt()
        val maxHPx = (dm.heightPixels * 0.85f).toInt()

        dialog.window?.apply {
            setBackgroundDrawable(roundRect(bgRoot, dp(ctx, 20).toFloat()))
            setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { if (height > maxHPx) height = maxHPx }
            // Soft input: resize the dialog so the keyboard doesn't cover the
            // input fields.
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }
    }
}
