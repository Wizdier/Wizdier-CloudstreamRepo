package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

// ═══════════════════════════════════════════════════════════════════════════
//  NTVSettingsUI — Server toggle settings dialog
//
//  Native Android widgets only (no WebView) — works on all TVs.
//  Design language matches CTGMovies: dark theme, card-based, accent strips.
// ═══════════════════════════════════════════════════════════════════════════

object NTVSettingsUI {

    // ── Palette ──────────────────────────────────────────────────────────────
    private val BG_DARK    = Color.parseColor("#0E1014")
    private val BG_CARD    = Color.parseColor("#14171E")
    private val BORDER     = Color.parseColor("#252836")
    private val ACCENT_A   = Color.parseColor("#FFD700")  // NTV gold
    private val ACCENT_B   = Color.parseColor("#FF8C00")  // NTV orange
    private val TEXT_PRI   = Color.parseColor("#EDEFF8")
    private val TEXT_SEC   = Color.parseColor("#7B82A0")
    private val DIVIDER    = Color.parseColor("#1C1F2E")
    private val GREEN      = Color.parseColor("#3DD68C")
    private val RED        = Color.parseColor("#FF4E6A")

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun roundRect(color: Int, radius: Float, stroke: Int? = null, sw: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = radius; setColor(color)
            if (stroke != null) setStroke(sw, stroke)
        }

    private fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(dp(ctx, 20), 0, dp(ctx, 20), 0) }
        setBackgroundColor(DIVIDER)
    }

    /** Server definitions — must match NTVProvider.allServers */
    private data class ServerToggle(
        val id: String,
        val name: String,
        val emoji: String,
        val prefKey: String,
        val description: String,
    )

    private val servers = listOf(
        ServerToggle("kobra",   "Kobra",   "🐍", NTVProvider.PREF_KOBRA,
            "FIFA World Cup, MLB, Football. Uses embed.st (needs WebView)."),
        ServerToggle("raptor",  "Raptor",  "🦅", NTVProvider.PREF_RAPTOR,
            "CFL, AFL, Baseball, Combat Sports. Uses embedindia.st (needs WebView)."),
        ServerToggle("falcon",  "Falcon",  "🦉", NTVProvider.PREF_FALCON,
            "Direct m3u8 extraction — NO WebView needed! Works on old TVs."),
        ServerToggle("phoenix", "Phoenix", "🔥", NTVProvider.PREF_PHOENIX,
            "MotoGP, Tennis, Tour de France. Uses dlhd.pk (needs WebView)."),
        ServerToggle("titan",   "Titan",   "⚡", NTVProvider.PREF_TITAN,
            "3000+ matches: Soccer, NFL, NBA, NHL, UFC. Uses cdnlivetv.tv (needs WebView)."),
        ServerToggle("viper",   "Viper",   "🐉", NTVProvider.PREF_VIPER,
            "Football, Basketball, Rugby, MMA. Uses sansat.link (needs WebView)."),
    )

    // ── Hero banner ──────────────────────────────────────────────────────────
    private fun buildHero(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 28), dp(ctx, 24), dp(ctx, 22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#1A1500"), BG_DARK)
            )

            // Accent bar
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 44), dp(ctx, 4)).apply {
                    bottomMargin = dp(ctx, 16)
                }
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(ACCENT_A, ACCENT_B)
                ).apply { cornerRadius = 99f }
            })
            // Title
            addView(TextView(ctx).apply {
                text = "NTVStream"; textSize = 20f
                setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRI)
                letterSpacing = -0.02f
            })
            // Subtitle
            addView(TextView(ctx).apply {
                text = "Toggle which sports servers are active"
                textSize = 12f; setTextColor(TEXT_SEC)
                setPadding(0, dp(ctx, 6), 0, 0)
            })
        }
    }

    // ── Server toggle row ────────────────────────────────────────────────────
    private fun buildServerRow(
        ctx: Context,
        server: ServerToggle,
        prefs: SharedPreferences,
    ): View {
        val enabled = prefs.getBoolean(server.prefKey, true)

        val switch = Switch(ctx).apply {
            isChecked = enabled
            isClickable = true
            isFocusable = true
            thumbTextPadding = dp(ctx, 8)
            // Style the switch
            thumbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(ctx, 20), dp(ctx, 20))
                setColor(if (isChecked) ACCENT_A else TEXT_SEC)
            }
            trackDrawable = GradientDrawable().apply {
                cornerRadius = 99f
                setColor(if (isChecked) Color.parseColor("#3A3520") else Color.parseColor("#1E2130"))
            }
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(server.prefKey, checked).apply()
                Toast.makeText(ctx,
                    if (checked) "${server.name} enabled" else "${server.name} disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    ColorDrawable(Color.parseColor("#1E2130")))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }

            // Clicking the row toggles the switch
            setOnClickListener { switch.toggle() }

            // Left: emoji + text block
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(ctx).apply {
                    text = "${server.emoji}  ${server.name}"
                    textSize = 14f; setTypeface(null, Typeface.BOLD)
                    setTextColor(TEXT_PRI)
                })
                addView(TextView(ctx).apply {
                    text = server.description
                    textSize = 11f; setTextColor(TEXT_SEC)
                    setPadding(0, dp(ctx, 4), 0, 0)
                    setLineSpacing(dp(ctx, 2).toFloat(), 1f)
                })
            })

            // Right: toggle switch
            addView(switch)
        }
    }

    // ── Info card ────────────────────────────────────────────────────────────
    private fun buildInfoCard(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val m = dp(ctx, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(m, 0, m, dp(ctx, 16)) }
            background = roundRect(Color.parseColor("#0C1018"), dp(ctx, 12).toFloat(),
                Color.parseColor("#1A2040"), 1)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            gravity = Gravity.TOP

            addView(TextView(ctx).apply {
                text = "\uD83D\uDCA1"; textSize = 13f
                setPadding(0, 0, dp(ctx, 10), 0)
            })
            addView(TextView(ctx).apply {
                text = "If your TV has an outdated WebView, enable ONLY the Falcon server — it uses direct m3u8 extraction and doesn't need WebView. Restart CloudStream after changing settings."
                textSize = 10.5f; setTextColor(TEXT_SEC)
                setLineSpacing(dp(ctx, 2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    // ── Main entry ───────────────────────────────────────────────────────────
    fun show(ctx: Context, prefs: SharedPreferences) {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(BG_DARK)
        }

        root.addView(buildHero(ctx))
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            )
        })

        // Server toggle rows
        for (server in servers) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val m = dp(ctx, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(m, 0, m, dp(ctx, 8)) }
                background = roundRect(BG_CARD, dp(ctx, 14).toFloat())
            }
            card.addView(buildServerRow(ctx, server, prefs))
            root.addView(card)
        }

        root.addView(buildInfoCard(ctx))
        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)
            )
        })

        // Enable all / Disable all buttons
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), dp(ctx, 16))
        }

        val enableAll = TextView(ctx).apply {
            text = "Enable All"; textSize = 11f
            setTypeface(null, Typeface.BOLD); setTextColor(ACCENT_A)
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            background = roundRect(Color.parseColor("#1A1500"), 99f, ACCENT_A, 1)
            isClickable = true; isFocusable = true
            setOnClickListener {
                for (s in servers) prefs.edit().putBoolean(s.prefKey, true).apply()
                Toast.makeText(ctx, "All servers enabled. Restart CloudStream.", Toast.LENGTH_SHORT).show()
            }
        }

        val disableAll = TextView(ctx).apply {
            text = "Disable All"; textSize = 11f
            setTypeface(null, Typeface.BOLD); setTextColor(RED)
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            background = roundRect(Color.parseColor("#1A0A0E"), 99f, RED, 1)
            isClickable = true; isFocusable = true
            setOnClickListener {
                for (s in servers) prefs.edit().putBoolean(s.prefKey, false).apply()
                Toast.makeText(ctx, "All servers disabled. Restart CloudStream.", Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(enableAll)
        btnRow.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 12), 0)
        })
        btnRow.addView(disableAll)
        root.addView(btnRow)

        val scroll = ScrollView(ctx).apply {
            background = ColorDrawable(BG_DARK)
            addView(root)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(scroll)
            .setPositiveButton("Done", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(ACCENT_A); isAllCaps = false
                setTypeface(null, Typeface.BOLD)
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
