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
import android.view.animation.OvershootInterpolator
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

// ═══════════════════════════════════════════════════════════════════════════════
//  CTGSettingsUI — settings dialog
//
//  Design language mirrors CineStream (SaurabhKaperwan/CSX):
//    • Hero banner with TL_BR gradient + accent bar + bold title + subtitle
//    • Collapsible cards with accent strip + bold uppercase title + chevron
//    • Same font sizes (raw SP), spacing, radii, and elevation as CineStream
//
//  Custom polishing on top of the CineStream base:
//    • Staggered card entrance — cards cascade in with a 60ms delay
//    • Password toggle with bounce — micro-scale animation on tap
//    • Smooth chevron flip — animated rotation on expand/collapse
//    • Gradient left border on info card — elegant vertical strip, no emoji
//    • Softer input focus glow — accent border highlight on focus
//    • Theme-aware colours — adapts to Cloudstream's light/dark + accent
// ═══════════════════════════════════════════════════════════════════════════════

object CTGSettingsUI {

    // ── Color tokens (CineStream palette, with theme-aware overrides) ────────
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

    // ── dp helper (CineStream convention — used for layout pixels) ───────────
    // NOTE: textSize uses raw float SP values (e.g. textSize = 12f), NOT this
    // helper. Using sp() for textSize causes double-scaling (SP→px then
    // Android reads the px value as SP again). This is the bug that made the
    // original dialog appear oversized.
    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun dpF(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

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
        val csBgDark = Color.parseColor("#0D0F14")
        val csBgCard = Color.parseColor("#13161E")
        val csAccentStart = Color.parseColor("#6C63FF")
        val csAccentEnd = Color.parseColor("#A855F7")
        val csTextPrimary = Color.parseColor("#F0F2FF")
        val csTextSecondary = Color.parseColor("#7B82A0")
        val csDivider = Color.parseColor("#1F2235")
        val csDanger = Color.parseColor("#FF4E6A")

        BG_DARK = resolveColor(ctx, android.R.attr.colorBackground, csBgDark)
        BG_CARD = resolveColor(ctx, android.R.attr.colorBackgroundFloating, csBgCard)
        if (BG_CARD == BG_DARK) {
            BG_CARD = blendColor(BG_DARK, if (isDark(BG_DARK)) Color.WHITE else Color.BLACK, 0.04f)
        }
        ACCENT_START = resolveColor(ctx, android.R.attr.colorAccent, csAccentStart)
        ACCENT_END = blendColor(ACCENT_START, csAccentEnd, 0.5f)
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

    private fun horizontalGradient(start: Int, end: Int, radius: Float = 99f) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
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
        addState(intArrayOf(), roundRect(Color.TRANSPARENT, dpF(ctx, 16f)))
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

    // ── Animations ───────────────────────────────────────────────────────────

    // CineStream-style entrance: fade + slide up
    private fun fadeInSlide(v: View, delayMs: Long = 0L) {
        v.alpha = 0f
        v.translationY = 20f
        v.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(300).setInterpolator(DecelerateInterpolator()).start()
    }

    // CineStream-style expand/collapse
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

    // Custom polish: bounce animation for toggle tap
    private fun bounceTap(v: View) {
        v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(120).setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
    }

    // ── Accent bar (left colour strip used in card headers) ──────────────────
    // Matches CineStream: 3×18dp, marginEnd 12dp
    private fun accentBar(ctx: Context, top: Int, bottom: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 18))
            .also { it.marginEnd = dp(ctx, 12) }
        background = verticalGradient(top, bottom)
    }

    // ── Hero banner (CineStream style) ───────────────────────────────────────
    // Matches CineStream exactly: 28/32/28/24 padding, 48×4 accent bar,
    // textSize 22f title, 13f subtitle, TL_BR gradient
    private fun buildHeroBanner(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 32), dp(ctx, 28), dp(ctx, 24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(blendColor(BG_DARK, ACCENT_START, 0.12f), BG_DARK)
            )

            // Accent bar — CineStream: 48×4dp, bottom margin 16dp
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 4))
                    .also { it.bottomMargin = dp(ctx, 16) }
                background = horizontalGradient(ACCENT_START, ACCENT_END)
            })
            // Title — CineStream: 22f, bold
            addView(TextView(ctx).apply {
                text = "CTGMovies"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                letterSpacing = -0.02f
            })
            // Subtitle — CineStream: 13f
            addView(TextView(ctx).apply {
                text = "Configure login credentials & API access"
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(ctx, 6), 0, 0)
            })
        }
    }

    // ── Divider ──────────────────────────────────────────────────────────────
    // Matches CineStream: 20dp horizontal margins
    private fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
        setBackgroundColor(DIVIDER_COLOR)
    }

    // ── Card container ───────────────────────────────────────────────────────
    // Matches CineStream: 16dp margins, 16dp corner radius, elevation 4
    private fun cardContainer(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val m = dp(ctx, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(m, 0, m, m) }
        background = roundRect(BG_CARD, dpF(ctx, 16f))
        elevation = 4f
    }

    // ── Collapsible card ─────────────────────────────────────────────────────
    // Matches CineStream layout exactly; adds stagger delay
    private fun buildCollapsibleCard(
        ctx: Context,
        title: String,
        startExpanded: Boolean = false,
        accentA: Int = ACCENT_START,
        accentB: Int = ACCENT_END,
        staggerIndex: Int = 0,
        buildContent: LinearLayout.() -> Unit,
    ): View {
        val card = cardContainer(ctx)
        var expanded = startExpanded
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 8))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        content.buildContent()

        val chevron = TextView(ctx).apply {
            text = if (expanded) "▲" else "▼"
            textSize = 11f
            setTextColor(TEXT_SECONDARY)
        }

        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true; isFocusableInTouchMode = false
            background = stateDrawable(ctx)

            addView(accentBar(ctx, accentA, accentB))
            addView(TextView(ctx).apply {
                text = title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_SECONDARY)
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(chevron)

            setOnClickListener {
                expanded = !expanded
                // Polish: smooth chevron rotation instead of instant swap
                chevron.animate().rotationX(if (expanded) 180f else 0f).setDuration(200).start()
                chevron.text = if (expanded) "▲" else "▼"
                animateExpand(content, expanded)
            }
        })
        card.addView(content)

        // Polish: staggered entrance — each card fades in 60ms after the previous
        fadeInSlide(card, delayMs = staggerIndex * 60L)
        return card
    }

    // ── Label ────────────────────────────────────────────────────────────────
    // CineStream uses 12f for section labels
    private fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(TEXT_SECONDARY)
        setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 10))
    }

    // ── Styled input ─────────────────────────────────────────────────────────
    // CineStream: textSize 13f, padding 14/12/14/12dp
    private fun input(
        ctx: Context, value: String?, hint: String,
        isPassword: Boolean = false, isMultiLine: Boolean = false,
        imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    ): EditText = EditText(ctx).apply {
        setText(value.orEmpty())
        this.hint = hint
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        textSize = 13f
        background = inputBackground(ctx)
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(ctx, 8) }
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
        ctx: Context, value: String?, hint: String,
        isPassword: Boolean = false, isMultiLine: Boolean = false,
        imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    ): Field {
        if (isPassword) {
            val edit = EditText(ctx).apply {
                setText(value.orEmpty())
                this.hint = hint
                setTextColor(TEXT_PRIMARY)
                setHintTextColor(TEXT_SECONDARY)
                textSize = 13f
                background = ColorDrawable(Color.TRANSPARENT)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                imeOptions = imeAction
            }

            // Polish: text-based toggle with bounce animation
            val toggle = TextView(ctx).apply {
                text = "👁 Show"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_SECONDARY)
                setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = false
            }

            toggle.setOnClickListener {
                val hidden = edit.transformationMethod is PasswordTransformationMethod
                edit.transformationMethod = if (hidden)
                    HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
                toggle.text = if (hidden) "🙈 Hide" else "👁 Show"
                edit.setSelection(edit.text?.length ?: 0)
                bounceTap(toggle)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = inputBackground(ctx)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(ctx, 8) }
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
    // Custom polish: gradient left border instead of emoji icon for a cleaner look
    private fun infoCard(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val m = dp(ctx, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(m, 0, m, m) }
            background = roundRect(
                blendColor(BG_CARD, ACCENT_START, 0.06f),
                dpF(ctx, 12f)
            )
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            gravity = Gravity.CENTER_VERTICAL

            // Polish: gradient accent strip on the left (replaces emoji)
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), LinearLayout.LayoutParams.MATCH_PARENT)
                    .also { it.marginEnd = dp(ctx, 14) }
                background = verticalGradient(ACCENT_START, ACCENT_END)
            })

            addView(TextView(ctx).apply {
                this.text = "Enter email/password for auto-login, paste a ctg.token / Bearer token, or a raw Cookie header. Everything is saved locally on this device only."
                textSize = 12f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(ctx, 3).toFloat(), 1f)
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
            setPadding(0, 0, 0, dp(ctx, 24))
            background = ColorDrawable(BG_DARK)
        }

        // ── Hero banner ────────────────────────────────────────────────────
        layout.addView(buildHeroBanner(ctx))
        layout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            )
        })

        // ── Fields ─────────────────────────────────────────────────────────
        val fEmail = field(
            ctx, prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com",
            imeAction = EditorInfo.IME_ACTION_NEXT
        )
        val fPass = field(
            ctx, prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password",
            isPassword = true, imeAction = EditorInfo.IME_ACTION_NEXT
        )
        val fToken = field(
            ctx, prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token",
            isMultiLine = true
        )
        val fCookie = field(
            ctx, prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header",
            isMultiLine = true
        )
        val fApi = field(
            ctx, prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE),
            CTGMovies.DEFAULT_API_BASE, imeAction = EditorInfo.IME_ACTION_DONE
        )

        // ── Card: Account Login (stagger index 0) ─────────────────────────
        layout.addView(buildCollapsibleCard(ctx, "🔐  ACCOUNT LOGIN",
            accentA = ACCENT_START, accentB = ACCENT_END,
            startExpanded = true, staggerIndex = 0) {
            addView(label(ctx, "Email"))
            addView(fEmail.view)
            addView(divider(ctx))
            addView(label(ctx, "Password"))
            addView(fPass.view)
        })

        // ── Card: Quick Access (stagger index 1) ──────────────────────────
        layout.addView(buildCollapsibleCard(ctx, "🔑  QUICK ACCESS",
            accentA = ACCENT_END, accentB = ACCENT_START,
            staggerIndex = 1) {
            addView(label(ctx, "Token"))
            addView(fToken.view)
            addView(divider(ctx))
            addView(label(ctx, "Cookie"))
            addView(fCookie.view)
        })

        // ── Card: Advanced (stagger index 2) ──────────────────────────────
        layout.addView(buildCollapsibleCard(ctx, "⚙️  ADVANCED",
            accentA = ACCENT_START, accentB = ACCENT_END,
            staggerIndex = 2) {
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
                    Toast.makeText(ctx, "✓ Saved", Toast.LENGTH_SHORT).show()
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
                setTypeface(null, Typeface.BOLD)
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
        // CineStream uses 95% width. Custom polish: 92% for a slightly more
        // centred, balanced look — especially on larger screens.
        val dm = ctx.resources.displayMetrics
        val widthPx = (dm.widthPixels * 0.92).toInt()
        val maxHPx = (dm.heightPixels * 0.80f).toInt()

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
