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
//  CTGSettingsUI — CineStream-inspired card-based settings dialog
//
//  Design language borrowed from SaurabhKaperwan/CSX CineStream:
//    • Hero banner with subtle gradient + accent bar
//    • Collapsible card sections with accent strips & chevrons
//    • State-aware row backgrounds (pressed feedback)
//    • Pill-style buttons with scale animation
//    • Compact typography, fade-in entrance
//
//  Built entirely from framework widgets (no androidx dependency).
// ═══════════════════════════════════════════════════════════════════════════

object CTGSettingsUI {

    // ── Palette (deep charcoal with indigo accents) ──────────────────────────
    private val BG_DARK    = Color.parseColor("#0E1014")
    private val BG_CARD    = Color.parseColor("#14171E")
    private val BG_INPUT   = Color.parseColor("#0C0E12")
    private val BORDER     = Color.parseColor("#252836")
    private val BORDER_FOC = Color.parseColor("#3A3D5C")
    private val ACCENT_A   = Color.parseColor("#6C63FF")
    private val ACCENT_B   = Color.parseColor("#A855F7")
    private val TEXT_PRI   = Color.parseColor("#EDEFF8")
    private val TEXT_SEC   = Color.parseColor("#7B82A0")
    private val DIVIDER    = Color.parseColor("#1C1F2E")
    private val DANGER     = Color.parseColor("#FF4E6A")
    private val GREEN      = Color.parseColor("#3DD68C")

    // ── dp / sp ──────────────────────────────────────────────────────────────
    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    // ── Drawables ────────────────────────────────────────────────────────────
    private fun roundRect(color: Int, radius: Float, stroke: Int? = null, sw: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = radius; setColor(color)
            if (stroke != null) setStroke(sw, stroke)
        }

    private fun accentBar(top: Int, bottom: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            .apply { cornerRadius = 99f }

    private fun stateBg(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), roundRect(Color.parseColor("#1E2130"), 0f))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    // ── Animations ───────────────────────────────────────────────────────────
    private fun fadeInSlide(v: View) {
        v.alpha = 0f; v.translationY = 16f
        v.animate().alpha(1f).translationY(0f)
            .setDuration(280).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animateExpand(v: View, expand: Boolean) {
        if (expand) {
            v.visibility = View.VISIBLE; v.alpha = 0f
            v.animate().alpha(1f).setDuration(200).start()
        } else {
            v.animate().alpha(0f).setDuration(140)
                .withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
        }
    }

    // ── Hero banner ──────────────────────────────────────────────────────────
    private fun buildHero(ctx: Context): View {
        val d6 = dp(ctx, 6); val d16 = dp(ctx, 16); val d24 = dp(ctx, 24)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d24, dp(ctx, 28), d24, dp(ctx, 22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#16142B"), BG_DARK)
            )

            // Accent bar
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 4)).apply {
                    bottomMargin = d16
                }
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(ACCENT_A, ACCENT_B)
                ).apply { cornerRadius = 99f }
            })
            // Title
            addView(TextView(ctx).apply {
                text = "CTGMovies"; textSize = 20f
                setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRI)
                letterSpacing = -0.02f
            })
            // Subtitle
            addView(TextView(ctx).apply {
                text = "Configure login credentials & API access"
                textSize = 12f; setTextColor(TEXT_SEC)
                setPadding(0, d6, 0, 0)
            })
        }
    }

    // ── Divider ──────────────────────────────────────────────────────────────
    private fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
        setBackgroundColor(DIVIDER)
    }

    // ── Card container ───────────────────────────────────────────────────────
    private fun cardContainer(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val m = dp(ctx, 16)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(m, 0, m, dp(ctx, 12)) }
        background = roundRect(BG_CARD, dp(ctx, 16).toFloat())
    }

    // ── Collapsible card ─────────────────────────────────────────────────────
    private fun buildCard(
        ctx: Context,
        title: String,
        accentA: Int,
        accentB: Int,
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
            text = if (expanded) "\u25B2" else "\u25BC"; textSize = 10f; setTextColor(TEXT_SEC) // ▲ : ▼
        }

        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = stateBg()

            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 18)).apply {
                    marginEnd = dp(ctx, 12)
                }
                background = accentBar(accentA, accentB)
            })
            addView(TextView(ctx).apply {
                this.text = title; textSize = 12f
                setTypeface(null, Typeface.BOLD); setTextColor(TEXT_SEC)
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
        this.text = text; textSize = 11f; setTextColor(TEXT_SEC)
        setPadding(dp(ctx, 20), 0, dp(ctx, 20), dp(ctx, 4))
    }

    // ── Styled input ─────────────────────────────────────────────────────────
    private fun input(
        ctx: Context, value: String?, hint: String,
        isPassword: Boolean = false, isMultiLine: Boolean = false,
    ): EditText = EditText(ctx).apply {
        setText(value.orEmpty())
        this.hint = hint
        setHintTextColor(Color.parseColor("#3E4255"))
        setTextColor(TEXT_PRI)
        textSize = 13f
        background = roundRect(BG_INPUT, dp(ctx, 10).toFloat(), BORDER, 1)
        setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), dp(ctx, 12)) }
        inputType = when {
            isPassword -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isMultiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (isMultiLine) { minLines = 2; maxLines = 4; isSingleLine = false }
        else setSingleLine(true)
    }

    // ── Field row (label + input, optional password toggle) ──────────────────
    private class Field(val edit: EditText, val view: View)

    private fun field(
        ctx: Context, labelText: String, value: String?, hint: String,
        isPassword: Boolean = false, isMultiLine: Boolean = false,
    ): Field {
        if (isPassword) {
            val edit = EditText(ctx).apply {
                setText(value.orEmpty())
                this.hint = hint
                setHintTextColor(Color.parseColor("#3E4255"))
                setTextColor(TEXT_PRI)
                textSize = 13f
                background = ColorDrawable(Color.TRANSPARENT)
                setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 8), dp(ctx, 11))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }

            val toggle = TextView(ctx).apply {
                text = "\uD83D\uDC41" // 👁
                textSize = 14f
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
                toggle.text = if (hidden) "\uD83D\uDE48" else "\uD83D\uDC41" // 🙈 : 👁
                edit.setSelection(edit.text?.length ?: 0)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(BG_INPUT, dp(ctx, 10).toFloat(), BORDER, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), dp(ctx, 12)) }
                edit.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(edit)
                addView(toggle)
            }
            return Field(edit, row)
        } else {
            val edit = input(ctx, value, hint, isPassword, isMultiLine)
            return Field(edit, edit)
        }
    }

    // ── Pill button ──────────────────────────────────────────────────────────
    private fun pillBtn(
        ctx: Context, text: String, textColor: Int, bgColor: Int, borderColor: Int,
        onClick: () -> Unit,
    ) = TextView(ctx).apply {
        this.text = text; textSize = 11f
        setTypeface(null, Typeface.BOLD); setTextColor(textColor)
        setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
        background = roundRect(bgColor, 99f, borderColor, 1)
        isClickable = true; isFocusable = true
        setOnClickListener {
            animate().scaleX(0.88f).scaleY(0.88f).setDuration(60).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }.start()
            onClick()
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
            background = roundRect(Color.parseColor("#0C1018"), dp(ctx, 12).toFloat(), Color.parseColor("#1A2040"), 1)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            gravity = Gravity.TOP

            addView(TextView(ctx).apply {
                text = "\uD83D\uDCA1"; textSize = 13f // 💡
                setPadding(0, 0, dp(ctx, 10), 0)
            })
            addView(TextView(ctx).apply {
                this.text = "Enter email/password for auto-login, paste a ctg.token / Bearer token, or a raw Cookie header. Everything is saved locally only."
                textSize = 10.5f; setTextColor(TEXT_SEC)
                setLineSpacing(dp(ctx, 2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Main entry point
    // ═════════════════════════════════════════════════════════════════════════

    fun show(ctx: Context, prefs: SharedPreferences) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(BG_DARK)
        }

        // ── Hero ─────────────────────────────────────────────────────────────
        root.addView(buildHero(ctx))
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            )
        })

        // ── Fields ───────────────────────────────────────────────────────────
        val fEmail  = field(ctx, "Email",    prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com")
        val fPass   = field(ctx, "Password", prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password", isPassword = true)
        val fToken  = field(ctx, "Token",    prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token", isMultiLine = true)
        val fCookie = field(ctx, "Cookie",   prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header", isMultiLine = true)
        val fApi    = field(ctx, "API Base", prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE), CTGMovies.DEFAULT_API_BASE)

        // ── Card: Account Login ──────────────────────────────────────────────
        root.addView(buildCard(ctx, "\uD83D\uDD12  ACCOUNT LOGIN",
            accentA = Color.parseColor("#6C63FF"), accentB = Color.parseColor("#8B5CF6"),
            startOpen = true,
        ) {
            addView(label(ctx, "Email"))
            addView(fEmail.view)
            addView(divider(ctx))
            addView(label(ctx, "Password"))
            addView(fPass.view)
        })

        // ── Card: Quick Access ───────────────────────────────────────────────
        root.addView(buildCard(ctx, "\uD83D\uDD11  QUICK ACCESS",
            accentA = Color.parseColor("#A855F7"), accentB = Color.parseColor("#C084FC"),
        ) {
            addView(label(ctx, "Token"))
            addView(fToken.view)
            addView(divider(ctx))
            addView(label(ctx, "Cookie"))
            addView(fCookie.view)
        })

        // ── Card: Advanced ───────────────────────────────────────────────────
        root.addView(buildCard(ctx, "\u2699\uFE0F  ADVANCED",
            accentA = Color.parseColor("#38BDF8"), accentB = Color.parseColor("#0EA5E9"),
        ) {
            addView(label(ctx, "API Base"))
            addView(fApi.view)
        })

        // ── Info ─────────────────────────────────────────────────────────────
        root.addView(infoCard(ctx))
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            )
        })

        val scroll = ScrollView(ctx).apply {
            background = ColorDrawable(BG_DARK)
            addView(root)
        }

        // ── Dialog ───────────────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(ACCENT_A); isAllCaps = false
                setTypeface(null, Typeface.BOLD)
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
                    Toast.makeText(ctx, "\u2713 Saved", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(TEXT_SEC); isAllCaps = false
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(DANGER); isAllCaps = false
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
        dialog.window?.apply {
            setBackgroundDrawable(roundRect(BG_DARK, dp(ctx, 20).toFloat()))
            setLayout(
                (ctx.resources.displayMetrics.widthPixels * 0.94).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            val maxH = (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt()
            attributes = attributes.apply { if (height > maxH) height = maxH }
        }
    }
}
