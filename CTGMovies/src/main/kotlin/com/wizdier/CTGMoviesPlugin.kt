package com.wizdier

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CTGMoviesPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(CTGMovies.PREF_FILE, Context.MODE_PRIVATE)
        registerMainAPI(CTGMovies(prefs))

        openSettings = { ctx ->
            CTGSettingsFragment(prefs).show(
                (ctx as androidx.appcompat.app.AppCompatActivity).supportFragmentManager,
                "ctg_settings"
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  CTGSettingsFragment
//
//  A DialogFragment that renders an elegant dark-themed settings card for
//  CTGMovies — styled to match modern Cloudstream extension settings UIs
//  (phisher98 / StreamPlay style).  All views are built programmatically so
//  no XML resources are required.
// ═══════════════════════════════════════════════════════════════════════════

class CTGSettingsFragment(
    private val prefs: SharedPreferences,
) : DialogFragment() {

    // ── Palette ──────────────────────────────────────────────────────────────
    private val cBg       = 0xFF13141F.toInt()
    private val cCard     = 0xFF1C1E2E.toInt()
    private val cBorder   = 0xFF333652.toInt()
    private val cAccent   = 0xFF7C5CFC.toInt()
    private val cAccent2  = 0xFF4F8BF5.toInt()
    private val cText     = 0xFFE8E8F0.toInt()
    private val cSub      = 0xFF8888A0.toInt()
    private val cHint     = 0xFF5A5A70.toInt()
    private val cRed      = 0xFFFF6B6B.toInt()

    // ── Density helpers ──────────────────────────────────────────────────────
    private val dens get() = resources.displayMetrics.density
    private fun Int.dp(): Int = (this * dens).toInt()
    private fun Float.sp(): Float = this * dens

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
            intArrayOf(cAccent, cAccent2)
        ).apply { cornerRadius = r }

    private fun pressable(normal: GradientDrawable, pressed: GradientDrawable) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }

    // ── Dialog sizing ────────────────────────────────────────────────────────
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val dm = resources.displayMetrics
            val maxW = 460.dp()
            val w = if (dm.widthPixels > maxW) maxW else (dm.widthPixels * 0.92f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(fill(cBg, 24.dp().toFloat()))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  View factories
    // ═════════════════════════════════════════════════════════════════════════

    private fun makeHeader(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = gradient(18.dp().toFloat())
        setPadding(16.dp(), 20.dp(), 16.dp(), 20.dp())
        gravity = Gravity.CENTER

        addView(TextView(ctx).apply {
            text = "🎬"
            textSize = 30f
            gravity = Gravity.CENTER
        })
        addView(TextView(ctx).apply {
            text = "CTGMovies"
            setTextColor(Color.WHITE)
            textSize = 22f.sp()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, 6.dp(), 0, 0)
        })
        addView(TextView(ctx).apply {
            text = "Sign in to unlock protected content"
            setTextColor(0xFFD6D6E8.toInt())
            textSize = 12.5f.sp()
            gravity = Gravity.CENTER
            setPadding(0, 4.dp(), 0, 0)
        })
    }

    private fun makeSection(ctx: Context, label: String): TextView = TextView(ctx).apply {
        text = label
        setTextColor(cAccent)
        textSize = 12f.sp()
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(4.dp(), 16.dp(), 4.dp(), 6.dp())
    }

    private fun makeLabel(ctx: Context, label: String): TextView = TextView(ctx).apply {
        text = label
        setTextColor(cSub)
        textSize = 12f.sp()
        setPadding(4.dp(), 0, 4.dp(), 4.dp())
    }

    private fun makeInput(
        ctx: Context,
        value: String?,
        hint: String,
        isPassword: Boolean = false,
        isMultiLine: Boolean = false,
    ): EditText = EditText(ctx).apply {
        setText(value.orEmpty())
        this.hint = hint
        setHintTextColor(cHint)
        setTextColor(cText)
        textSize = 14f.sp()
        background = fill(cCard, 14.dp().toFloat(), cBorder, 2.dp())
        setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        inputType = when {
            isPassword -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isMultiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (isMultiLine) { minLines = 2; maxLines = 5; isSingleLine = false }
        else setSingleLine(true)
    }

    /** Label + input + optional password toggle, wrapped in a vertical layout. */
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
                    textSize = 16f
                    gravity = Gravity.CENTER
                    val p = 12.dp()
                    setPadding(p, p, p, p)
                }
                toggle.setOnClickListener {
                    val hidden = edit.transformationMethod is PasswordTransformationMethod
                    edit.transformationMethod = if (hidden)
                        HideReturnsTransformationMethod.getInstance()
                    else PasswordTransformationMethod.getInstance()
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

    /** A styled button that looks pressable. */
    private fun makeButton(ctx: Context, label: String, primary: Boolean = false): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 13f.sp()
            typeface = Typeface.create("sans-serif-medium", if (primary) Typeface.BOLD else Typeface.NORMAL)
            isClickable = true
            isFocusable = true
            val p = 12.dp()
            setPadding(10.dp(), p, 10.dp(), p)
            background = if (primary) {
                pressable(gradient(28f), fill(0xFF5A3FCF.toInt(), 28f))
            } else {
                pressable(
                    fill(Color.TRANSPARENT, 28f, cBorder, 2.dp()),
                    fill(cCard, 28f)
                )
            }
            setTextColor(if (primary) Color.WHITE else cSub)
        }

    /** Simple data holder returned by makeField. */
    private class FieldRow(val container: View, val edit: EditText)

    // ═════════════════════════════════════════════════════════════════════════
    //  Build view tree
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()

        // ── Fields ───────────────────────────────────────────────────────────
        val fEmail = makeField(ctx, "Email", prefs.getString(CTGMovies.PREF_EMAIL, ""), "name@example.com")
        val fPass  = makeField(ctx, "Password", prefs.getString(CTGMovies.PREF_PASSWORD, ""), "Account password", isPassword = true)
        val fToken = makeField(ctx, "Token", prefs.getString(CTGMovies.PREF_TOKEN, ""), "Bearer token / ctg.token", isMultiLine = true)
        val fCookie= makeField(ctx, "Cookie", prefs.getString(CTGMovies.PREF_COOKIE, ""), "Optional raw Cookie header", isMultiLine = true)
        val fApi   = makeField(ctx, "API Base", prefs.getString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE), CTGMovies.DEFAULT_API_BASE)

        // ── Info note ────────────────────────────────────────────────────────
        val note = TextView(ctx).apply {
            text = "💡  Enter email/password for auto-login, paste a ctg.token / Bearer token, or a raw Cookie header. Everything is saved locally in Cloudstream's extension settings only."
            setTextColor(0xFF9A9AB4.toInt())
            textSize = 11f.sp()
            background = fill(0xFF1A1C2A.toInt(), 14.dp().toFloat(), 0xFF3A2E5C.toInt(), 1.dp())
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        }

        // ── Buttons ──────────────────────────────────────────────────────────
        val btnClear  = makeButton(ctx, "CLEAR").apply { setTextColor(cRed) }
        val btnCancel = makeButton(ctx, "CANCEL")
        val btnSave   = makeButton(ctx, "✓  SAVE", primary = true)

        btnSave.setOnClickListener {
            prefs.edit {
                putString(CTGMovies.PREF_EMAIL,    fEmail.edit.text?.toString()?.trim().orEmpty())
                putString(CTGMovies.PREF_PASSWORD, fPass.edit.text?.toString().orEmpty())
                putString(CTGMovies.PREF_TOKEN,    fToken.edit.text?.toString()?.trim().orEmpty())
                putString(CTGMovies.PREF_COOKIE,   fCookie.edit.text?.toString()?.trim().orEmpty())
                putString(
                    CTGMovies.PREF_API_BASE,
                    fApi.edit.text?.toString()?.trim()?.ifBlank { CTGMovies.DEFAULT_API_BASE } ?: CTGMovies.DEFAULT_API_BASE
                )
            }
            Toast.makeText(ctx, "✓ Settings saved", Toast.LENGTH_SHORT).show()
            dismiss()
        }
        btnCancel.setOnClickListener { dismiss() }
        btnClear.setOnClickListener {
            prefs.edit {
                remove(CTGMovies.PREF_EMAIL)
                remove(CTGMovies.PREF_PASSWORD)
                remove(CTGMovies.PREF_TOKEN)
                remove(CTGMovies.PREF_COOKIE)
                putString(CTGMovies.PREF_API_BASE, CTGMovies.DEFAULT_API_BASE)
            }
            fEmail.edit.setText(""); fPass.edit.setText("")
            fToken.edit.setText(""); fCookie.edit.setText("")
            fApi.edit.setText(CTGMovies.DEFAULT_API_BASE)
            Toast.makeText(ctx, "Settings cleared", Toast.LENGTH_SHORT).show()
        }

        val btnBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
            setPadding(0, 4.dp(), 0, 0)
            addView(btnClear,  LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6.dp() })
            addView(btnCancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6.dp() })
            addView(btnSave,   LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ── Scrollable form ──────────────────────────────────────────────────
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(0, 0, 0, 16.dp())

            addView(makeHeader(ctx), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 4.dp()) })

            // Inner padded section
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp(), 4.dp(), 16.dp(), 4.dp())

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
                ).apply { topMargin = 14.dp() })
            }
            addView(inner)
            addView(LinearLayout(ctx).apply {
                setPadding(16.dp(), 0, 16.dp(), 0)
                addView(btnBar)
            })
        }

        return ScrollView(ctx).apply {
            setBackgroundColor(cBg)
            addView(form)
        }
    }
}
