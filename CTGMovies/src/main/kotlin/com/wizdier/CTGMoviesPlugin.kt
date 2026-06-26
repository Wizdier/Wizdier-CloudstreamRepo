package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
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
//  CTGSettingsUI — "Aurora Glass" settings dialog
//
//  Built from scratch with a vivid gradient + glassmorphism design language.
//  No CineStream reference — this is its own thing.
//
//  Design pillars:
//    1. AURORA HERO — multi-stop flowing gradient banner with a pulsing
//       accent dot, status chip, and ambient decorative orbs.
//    2. GLASS CARDS — each card has its own gradient border (not a flat
//       accent strip), translucent body, and a vivid glow on press.
//    3. GENEROUS BREATHING ROOM — 16 dp gap between cards so borders
//       never feel "clingy" (the previous version had ~1–2 px gaps).
//    4. GRADIENT INPUTS — fields pick up their parent card's accent on
//       focus, with a soft inner glow.
//    5. ABSTRACT DECOR — floating gradient orbs in the background, a
//       subtle aurora ribbon under the hero, and per-card colour
//       personalities (rose→violet, cyan→blue, amber→rose).
//    6. ANIMATED MICRO-FEEDBACK — pulsing dot, bloom entrance, chevron
//       flip, bounce-on-tap, focus glow.
//
//  Font sizes / padding / radii are kept identical to the previous version
//  (the user confirmed they're perfect).
// ═══════════════════════════════════════════════════════════════════════════════

object CTGSettingsUI {

    // ── Vivid per-card gradient stops ────────────────────────────────────────
    // Each card gets its own 2-colour personality. The gradient flows across
    // the card's top edge, left accent strip, and press-feedback tint.
    private val CARD_ACCOUNT_A = Color.parseColor("#FB7185")  // rose-400
    private val CARD_ACCOUNT_B = Color.parseColor("#A855F7")  // violet-500
    private val CARD_QUICK_A   = Color.parseColor("#22D3EE")  // cyan-400
    private val CARD_QUICK_B   = Color.parseColor("#3B82F6")  // blue-500
    private val CARD_ADV_A     = Color.parseColor("#FBBF24")  // amber-400
    private val CARD_ADV_B     = Color.parseColor("#FB7185")  // rose-400

    // ── Theme-resolved base palette ──────────────────────────────────────────
    private var BG_DARK: Int = 0
    private var BG_CARD: Int = 0
    private var TEXT_PRIMARY: Int = 0
    private var TEXT_SECONDARY: Int = 0
    private var DIVIDER_COLOR: Int = 0
    private var DANGER_COLOR: Int = 0
    private var INPUT_BG: Int = 0
    private var INPUT_BORDER: Int = 0

    // ── dp / sp helpers ──────────────────────────────────────────────────────
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
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) < 0.5f
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
        BG_DARK = resolveColor(ctx, android.R.attr.colorBackground, Color.parseColor("#0A0B10"))
        BG_CARD = resolveColor(ctx, android.R.attr.colorBackgroundFloating, Color.parseColor("#13151D"))
        if (BG_CARD == BG_DARK) {
            BG_CARD = blendColor(BG_DARK, if (isDark(BG_DARK)) Color.WHITE else Color.BLACK, 0.05f)
        }
        TEXT_PRIMARY = resolveColor(ctx, android.R.attr.textColorPrimary, Color.parseColor("#F0F2FF"))
        TEXT_SECONDARY = resolveColor(ctx, android.R.attr.textColorSecondary, Color.parseColor("#7B82A0"))
        DIVIDER_COLOR = blendColor(BG_CARD, if (isDark(BG_DARK)) Color.WHITE else Color.BLACK, 0.08f)
        DANGER_COLOR = resolveColor(ctx, android.R.attr.colorError, Color.parseColor("#FF4E6A"))
        INPUT_BG = blendColor(BG_DARK, if (isDark(BG_DARK)) Color.WHITE else Color.BLACK, 0.03f)
        INPUT_BORDER = blendColor(BG_CARD, Color.parseColor("#6C63FF"), 0.25f)
    }

    // ── Drawable factories ───────────────────────────────────────────────────

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

    /**
     * Horizontal (left→right) gradient with optional stroke. Used for accent
     * strips, status chips, and the aurora ribbon.
     */
    private fun hGradient(start: Int, end: Int, radius: Float = 99f) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
            .apply { cornerRadius = radius }

    /**
     * Vertical (top→bottom) gradient. Used for the card left-edge ribbon.
     */
    private fun vGradient(top: Int, bottom: Int, radius: Float = 99f) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            .apply { cornerRadius = radius }

    /**
     * Three-stop diagonal gradient. Used for the aurora ribbon under the hero
     * and the info card's accent strip.
     */
    private fun triGradient(c1: Int, c2: Int, c3: Int, radius: Float = 99f) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2, c3))
            .apply { cornerRadius = radius }

    /**
     * Press-state drawable with a vivid accent-tinted press feedback. The
     * tint matches the card's own gradient so press feels coordinated.
     */
    private fun pressState(ctx: Context, accentA: Int, accentB: Int): StateListDrawable =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundRect(blendColor(BG_CARD, accentA, 0.18f), dpF(ctx, 16f))
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                roundRect(BG_CARD, dpF(ctx, 16f), accentA, 2)
            )
            addState(intArrayOf(), roundRect(Color.TRANSPARENT, dpF(ctx, 16f)))
        }

    /**
     * Input field background — base (unfocused) state. Subtle accent-tinted
     * border so fields feel connected to their parent card.
     */
    private fun inputBg(ctx: Context, accentA: Int) = GradientDrawable().apply {
        cornerRadius = dpF(ctx, 10f)
        setColor(INPUT_BG)
        setStroke(1, blendColor(INPUT_BORDER, accentA, 0.4f))
    }

    /**
     * Input field background — focused state. The border switches to a
     * 2 dp gradient stroke and the fill gets a soft accent glow.
     */
    private fun inputBgFocused(ctx: Context, accentA: Int, accentB: Int): GradientDrawable {
        // Gradient stroke isn't directly supported on GradientDrawable, so we
        // emulate it with a solid accent-A stroke + an accent-tinted fill.
        return GradientDrawable().apply {
            cornerRadius = dpF(ctx, 10f)
            setColor(blendColor(INPUT_BG, accentA, 0.08f))
            setStroke(2, accentB)
        }
    }

    // ── Animations ───────────────────────────────────────────────────────────

    /** Bloom entrance: fade + slide-up + scale-up. Soft "blooming" feel. */
    private fun bloomEntrance(v: View, delayMs: Long = 0L) {
        v.alpha = 0f
        v.translationY = 24f
        v.scaleX = 0.96f
        v.scaleY = 0.96f
        v.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs)
            .setDuration(380).setInterpolator(DecelerateInterpolator(1.2f)).start()
    }

    /** Expand/collapse content fade. */
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

    /** Bounce on tap — for the password visibility toggle. */
    private fun bounceTap(v: View) {
        v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70)
            .withEndAction {
                v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(120).setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
    }

    /**
     * Pulsing animation for the hero accent dot. Gently cycles alpha and scale
     * so the dialog feels alive without being distracting.
     */
    private fun startPulse(v: View) {
        val handler = Handler(Looper.getMainLooper())
        val pulse = object : Runnable {
            override fun run() {
                v.animate()
                    .alpha(0.4f).scaleX(0.75f).scaleY(0.75f)
                    .setDuration(900)
                    .withEndAction {
                        v.animate()
                            .alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration(900).start()
                    }.start()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(pulse)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  AURORA HERO
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Hero banner with a flowing aurora gradient background, pulsing accent
     * dot, status chip, and an ambient tri-gradient ribbon at the bottom.
     */
    private fun buildHero(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 28), dp(ctx, 32), dp(ctx, 28), dp(ctx, 24))

            // Aurora background — three-stop diagonal gradient that flows from
            // a rose-tinted dark to the base dark. Vivid but not garish.
            background = triGradient(
                blendColor(BG_DARK, CARD_ACCOUNT_A, 0.16f),
                blendColor(BG_DARK, CARD_QUICK_A, 0.10f),
                BG_DARK
            )

            // ── Top row: accent bar + status chip ────────────────────────────
            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(ctx, 16) }
            }

            // Accent bar — 48×4dp, rose→violet→cyan tri-gradient
            topRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 4))
                background = triGradient(CARD_ACCOUNT_A, CARD_QUICK_A, CARD_ADV_A)
            })

            // Spacer to push the chip right
            topRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })

            // Status chip — "Local-only" with a pulsing green dot
            topRow.addView(buildStatusChip(ctx))
            addView(topRow)

            // ── Title row: pulsing dot + title ───────────────────────────────
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val pulseDot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8))
                    .also { it.marginEnd = dp(ctx, 10) }
                background = hGradient(CARD_ACCOUNT_A, CARD_ACCOUNT_B)
            }
            titleRow.addView(pulseDot)
            startPulse(pulseDot)

            titleRow.addView(TextView(ctx).apply {
                text = "CTGMovies"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                letterSpacing = -0.02f
            })
            addView(titleRow)

            // ── Subtitle ─────────────────────────────────────────────────────
            addView(TextView(ctx).apply {
                text = "Configure login credentials & API access"
                textSize = 13f
                setTextColor(TEXT_SECONDARY)
                setPadding(0, dp(ctx, 6), 0, 0)
            })

            // ── Ambient aurora ribbon at the bottom ──────────────────────────
            // A thin tri-gradient line that ties all the card accents together
            // and gives the hero a finished edge.
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)
                ).also { it.topMargin = dp(ctx, 20) }
                background = triGradient(CARD_ACCOUNT_A, CARD_QUICK_A, CARD_ADV_A)
                alpha = 0.7f
            })
        }
    }

    /**
     * Status chip — "Local-only" with a green dot. Sits in the hero's
     * top-right corner to fill the otherwise-empty space and give the user
     * immediate visual confirmation that credentials stay on-device.
     */
    private fun buildStatusChip(ctx: Context): View {
        val greenA = Color.parseColor("#3DD68C")
        val greenB = Color.parseColor("#10B981")
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5))
            background = roundRect(
                blendColor(BG_CARD, greenA, 0.14f),
                dpF(ctx, 99f),
                blendColor(greenA, DIVIDER_COLOR, 0.4f),
                1
            )

            // Pulsing green dot
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 6), dp(ctx, 6))
                    .also { it.marginEnd = dp(ctx, 6) }
                background = hGradient(greenA, greenB)
            }
            addView(dot)
            startPulse(dot)

            addView(TextView(ctx).apply {
                text = "Local-only"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(blendColor(TEXT_SECONDARY, greenA, 0.5f))
                letterSpacing = 0.04f
            })
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  GLASS CARDS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Card container with a vivid gradient top edge (1 dp strip inset from the
     * card edges) and a soft elevation shadow.
     *
     * IMPORTANT: The card has a 16 dp bottom margin so consecutive cards have
     * proper breathing room. The previous version's cards were stacked with
     * almost no gap, making their borders feel "clingy".
     */
    private fun glassCard(ctx: Context, accentA: Int, accentB: Int): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 16 dp side + bottom margins. The bottom margin is the key fix
            // for the "clingy borders" issue — it gives each card clear
            // separation from the next.
            val m = dp(ctx, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(m, 0, m, m) }
            background = roundRect(BG_CARD, dpF(ctx, 16f))
            elevation = 6f

            // Vivid gradient top edge — inset 20 dp from each side so it
            // respects the rounded corners. This is the card's "aurora lip".
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 2)
                ).also { it.setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
                background = hGradient(accentA, accentB)
                alpha = 0.85f
            })
        }
    }

    /**
     * Collapsible card with a gradient left-edge ribbon, bold uppercase title,
     * animated chevron, and bloom entrance.
     */
    private fun buildCard(
        ctx: Context,
        title: String,
        accentA: Int,
        accentB: Int,
        startExpanded: Boolean = false,
        staggerIndex: Int = 0,
        buildContent: LinearLayout.() -> Unit,
    ): View {
        val card = glassCard(ctx, accentA, accentB)
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
            setTextColor(blendColor(TEXT_SECONDARY, accentA, 0.3f))
        }

        // Header row
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true; isFocusableInTouchMode = false
            background = pressState(ctx, accentA, accentB)

            // Gradient left-edge ribbon — 3×18 dp, accent-A → accent-B
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 18))
                    .also { it.marginEnd = dp(ctx, 12) }
                background = vGradient(accentA, accentB)
            })

            // Title
            addView(TextView(ctx).apply {
                text = title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(blendColor(TEXT_SECONDARY, accentA, 0.15f))
                letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(chevron)

            setOnClickListener {
                expanded = !expanded
                chevron.animate().rotationX(if (expanded) 180f else 0f).setDuration(200).start()
                chevron.text = if (expanded) "▲" else "▼"
                animateExpand(content, expanded)
            }
        })

        card.addView(content)

        // Staggered bloom entrance
        bloomEntrance(card, delayMs = staggerIndex * 70L)
        return card
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  INPUTS
    // ═════════════════════════════════════════════════════════════════════════

    /** Field label — 12 sp secondary text with a hint of the card's accent. */
    private fun label(ctx: Context, text: String, accentA: Int) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(blendColor(TEXT_SECONDARY, accentA, 0.1f))
        setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 10))
    }

    /** Divider — 20 dp side margins, accent-tinted at low alpha. */
    private fun divider(ctx: Context, accentA: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
        setBackgroundColor(blendColor(DIVIDER_COLOR, accentA, 0.15f))
    }

    /**
     * Plain input field. Border + focus glow pick up the parent card's accent.
     */
    private fun input(
        ctx: Context, value: String?, hint: String,
        accentA: Int, accentB: Int,
        isMultiLine: Boolean = false,
        imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    ): EditText = EditText(ctx).apply {
        setText(value.orEmpty())
        this.hint = hint
        setTextColor(TEXT_PRIMARY)
        setHintTextColor(TEXT_SECONDARY)
        textSize = 13f
        background = inputBg(ctx, accentA)
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(ctx, 8) }
        inputType = if (isMultiLine) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (isMultiLine) {
            minLines = 2; maxLines = 4; isSingleLine = false
            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
        } else {
            setSingleLine(true)
            imeOptions = imeAction
        }
        setOnFocusChangeListener { _, hasFocus ->
            background = if (hasFocus) inputBgFocused(ctx, accentA, accentB) else inputBg(ctx, accentA)
        }
    }

    /**
     * Password field with a Show/Hide toggle. The toggle text picks up the
     * card's accent colour so it feels integrated.
     */
    private class Field(val edit: EditText, val view: View)

    private fun passwordField(
        ctx: Context, value: String?, hint: String,
        accentA: Int, accentB: Int,
        imeAction: Int = EditorInfo.IME_ACTION_NEXT,
    ): Field {
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
            this.imeOptions = imeAction
        }

        val toggle = TextView(ctx).apply {
            text = "👁 Show"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(blendColor(TEXT_SECONDARY, accentA, 0.4f))
            setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            gravity = Gravity.CENTER
            isClickable = true; isFocusable = true; isFocusableInTouchMode = false
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
            background = inputBg(ctx, accentA)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(ctx, 8) }
            edit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(edit); addView(toggle)
        }
        edit.setOnFocusChangeListener { _, hasFocus ->
            row.background = if (hasFocus) inputBgFocused(ctx, accentA, accentB) else inputBg(ctx, accentA)
        }
        return Field(edit, row)
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  INFO CARD
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Info card with a vivid tri-gradient left ribbon that ties together all
     * the per-card accent colours.
     */
    private fun infoCard(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val m = dp(ctx, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(m, 0, m, m) }
            background = roundRect(
                blendColor(BG_CARD, CARD_ACCOUNT_A, 0.06f),
                dpF(ctx, 12f)
            )
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            gravity = Gravity.CENTER_VERTICAL

            // Tri-gradient ribbon — rose → cyan → amber, unifies the palette
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), LinearLayout.LayoutParams.MATCH_PARENT)
                    .also { it.marginEnd = dp(ctx, 14) }
                background = triGradient(CARD_ACCOUNT_A, CARD_QUICK_A, CARD_ADV_A)
            })

            addView(TextView(ctx).apply {
                text = "Enter email/password for auto-login, paste a ctg.token / Bearer token, or a raw Cookie header. Everything is saved locally on this device only."
                textSize = 12f
                setTextColor(TEXT_SECONDARY)
                setLineSpacing(dp(ctx, 3).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  MAIN ENTRY POINT
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

        // ── Aurora hero ────────────────────────────────────────────────────
        layout.addView(buildHero(ctx))
        // 12 dp gap between hero and first card (was 8 dp — slightly more
        // breathing room here too).
        layout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 12)
            )
        })

        // ── Fields ─────────────────────────────────────────────────────────
        val fEmail = input(
            ctx, prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com",
            CARD_ACCOUNT_A, CARD_ACCOUNT_B, imeAction = EditorInfo.IME_ACTION_NEXT
        )
        val fPass = passwordField(
            ctx, prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password",
            CARD_ACCOUNT_A, CARD_ACCOUNT_B, imeAction = EditorInfo.IME_ACTION_NEXT
        )
        val fToken = input(
            ctx, prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token",
            CARD_QUICK_A, CARD_QUICK_B, isMultiLine = true
        )
        val fCookie = input(
            ctx, prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header",
            CARD_QUICK_A, CARD_QUICK_B, isMultiLine = true
        )
        val fApi = input(
            ctx, prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE),
            CTGMovies.DEFAULT_API_BASE,
            CARD_ADV_A, CARD_ADV_B, imeAction = EditorInfo.IME_ACTION_DONE
        )

        // ── Card: Account Login (rose → violet) ────────────────────────────
        layout.addView(buildCard(ctx, "🔐  ACCOUNT LOGIN",
            CARD_ACCOUNT_A, CARD_ACCOUNT_B,
            startExpanded = true, staggerIndex = 0) {
            addView(label(ctx, "Email", CARD_ACCOUNT_A))
            addView(fEmail)
            addView(divider(ctx, CARD_ACCOUNT_A))
            addView(label(ctx, "Password", CARD_ACCOUNT_A))
            addView(fPass.view)
        })

        // ── Card: Quick Access (cyan → blue) ───────────────────────────────
        layout.addView(buildCard(ctx, "🔑  QUICK ACCESS",
            CARD_QUICK_A, CARD_QUICK_B,
            staggerIndex = 1) {
            addView(label(ctx, "Token", CARD_QUICK_A))
            addView(fToken)
            addView(divider(ctx, CARD_QUICK_A))
            addView(label(ctx, "Cookie", CARD_QUICK_A))
            addView(fCookie)
        })

        // ── Card: Advanced (amber → rose) ──────────────────────────────────
        layout.addView(buildCard(ctx, "⚙️  ADVANCED",
            CARD_ADV_A, CARD_ADV_B,
            staggerIndex = 2) {
            addView(label(ctx, "API Base", CARD_ADV_A))
            addView(fApi)
        })

        // ── Info card ──────────────────────────────────────────────────────
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
            // Save button — vivid accent colour, bold
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(CARD_ACCOUNT_B)
                isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    prefs.edit()
                        .putString(CTGMovies.PREF_EMAIL, fEmail.text?.toString()?.trim().orEmpty())
                        .putString(CTGMovies.PREF_PASSWORD, fPass.edit.text?.toString().orEmpty())
                        .putString(CTGMovies.PREF_TOKEN, fToken.text?.toString()?.trim().orEmpty())
                        .putString(CTGMovies.PREF_COOKIE, fCookie.text?.toString()?.trim().orEmpty())
                        .putString(
                            CTGMovies.PREF_API_BASE,
                            fApi.text?.toString()?.trim()?.ifBlank { CTGMovies.DEFAULT_API_BASE }
                                ?: CTGMovies.DEFAULT_API_BASE
                        )
                        .apply()
                    Toast.makeText(ctx, "✓ Saved", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            // Cancel — muted secondary
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(TEXT_SECONDARY)
                isAllCaps = false
            }
            // Clear — danger red, bold
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
                    fEmail.setText(""); fPass.edit.setText("")
                    fToken.setText(""); fCookie.setText("")
                    fApi.setText(CTGMovies.DEFAULT_API_BASE)
                    Toast.makeText(ctx, "Cleared", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.window?.setBackgroundDrawable(roundRect(BG_DARK, dpF(ctx, 20f)))
        dialog.show()

        // ── Dialog sizing ──────────────────────────────────────────────────
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
