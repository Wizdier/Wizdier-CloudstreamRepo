package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object NTVStreamSettings {
    const val PREF_FILE = "NTVStream"

    // Kept for backwards compatibility with the first single-select settings UI.
    const val PREF_SERVER = "selected_server"
    const val PREF_SERVERS = "selected_servers"

    const val SERVER_ALL = "all"
    const val SERVER_KOBRA = "kobra"
    const val SERVER_RAPTOR = "raptor"
    const val SERVER_FALCON = "falcon"
    const val SERVER_PHOENIX = "phoenix"
    const val SERVER_VIPER = "viper"

    val serverOptions = listOf(
        SERVER_KOBRA to "Kobra",
        SERVER_RAPTOR to "Raptor",
        SERVER_FALCON to "Falcon",
        SERVER_PHOENIX to "Phoenix",
        SERVER_VIPER to "Viper",
    )

    val allServerIds = serverOptions.map { it.first }.toSet()

    fun selectedServers(prefs: SharedPreferences): Set<String> {
        val multi = prefs.getString(PREF_SERVERS, null)
        if (!multi.isNullOrBlank()) {
            val parsed = multi.split(",")
                .map { it.trim() }
                .filter { it in allServerIds }
                .toSet()
            if (parsed.isNotEmpty()) return parsed
        }

        return when (val single = prefs.getString(PREF_SERVER, SERVER_ALL)) {
            SERVER_ALL, null -> allServerIds
            in allServerIds -> setOf(single)
            else -> allServerIds
        }
    }

    fun show(context: Context, prefs: SharedPreferences) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val bg = Color.parseColor("#080B12")
        val panel = Color.parseColor("#111827")
        val panel2 = Color.parseColor("#182033")
        val stroke = Color.parseColor("#263047")
        val textColor = Color.parseColor("#F8FAFC")
        val muted = Color.parseColor("#A1A8B8")
        val accent = Color.parseColor("#FACC15")
        val accentRed = Color.parseColor("#FF3B30")

        val selected = selectedServers(prefs).toMutableSet()
        val checkBoxes = linkedMapOf<String, CheckBox>()

        fun rounded(color: Int, radius: Int = 18, strokeColor: Int = stroke, strokeWidth: Int = 1): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(strokeWidth), strokeColor)
            }

        fun label(textValue: String, size: Float, color: Int = textColor, bold: Boolean = false): TextView =
            TextView(context).apply {
                this.text = textValue
                this.textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = true
            }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(bg, radius = 24, strokeColor = Color.parseColor("#334155"), strokeWidth = 1)
        }

        root.addView(label("NTVStream", 24f, textColor, bold = true))
        root.addView(label("Server selector", 13f, accent, bold = true).apply {
            setPadding(0, dp(2), 0, dp(8))
        })
        root.addView(label(
            "Choose exactly the server mix you want in Cloudstream. Kobra is currently the smoothest; keep backups enabled only if you use them.",
            12.5f,
            muted
        ).apply { setPadding(0, 0, 0, dp(14)) })

        fun addServerCard(
            id: String,
            title: String,
            subtitle: String,
            badge: String,
            recommended: Boolean = false,
        ) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(if (recommended) Color.parseColor("#1D2435") else panel, radius = 18)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, dp(10)) }
            }

            val box = CheckBox(context).apply {
                isChecked = id in selected
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(if (recommended) accent else Color.parseColor("#60A5FA"), muted)
                )
            }
            checkBoxes[id] = box

            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, 0, 0)
                }
            }
            texts.addView(label("$badge  $title", 15.5f, textColor, bold = true))
            texts.addView(label(subtitle, 12f, muted))

            card.addView(box)
            card.addView(texts)
            card.setOnClickListener { box.isChecked = !box.isChecked }
            root.addView(card)
        }

        val allCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel2, radius = 20, strokeColor = accent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(14)) }
        }
        val allBox = CheckBox(context).apply {
            isChecked = selected.containsAll(allServerIds)
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, muted)
            )
        }
        val allTexts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, 0, 0)
            }
            addView(label("\u26A1 All servers", 16f, textColor, bold = true))
            addView(label("Show every NTVStream provider after reload", 12f, muted))
        }
        allCard.addView(allBox)
        allCard.addView(allTexts)
        allCard.setOnClickListener { allBox.isChecked = !allBox.isChecked }
        root.addView(allCard)

        addServerCard(SERVER_KOBRA, "Kobra", "Recommended \u2022 stable browser-resolved streams", "\uD83D\uDC0D", recommended = true)
        addServerCard(SERVER_RAPTOR, "Raptor", "EmbedIndia based backup server", "\uD83E\uDD85")
        addServerCard(SERVER_FALCON, "Falcon", "Direct m3u8-style backup channels", "\uD83E\uDD85")
        addServerCard(SERVER_PHOENIX, "Phoenix", "Large list, upstream may be unstable", "\uD83D\uDD25")
        addServerCard(SERVER_VIPER, "Viper", "Alternate broadcaster channels", "\uD83D\uDC0D")

        val status = label("", 12f, muted).apply { setPadding(0, dp(4), 0, 0) }
        root.addView(status)

        var internalUpdate = false
        fun refreshAllState() {
            internalUpdate = true
            allBox.isChecked = checkBoxes.values.all { it.isChecked }
            val count = checkBoxes.values.count { it.isChecked }
            status.text = if (count == 0) {
                "No server selected \u2014 Kobra will be used automatically."
            } else {
                "$count server${if (count == 1) "" else "s"} selected \u2022 reload extension/app to apply"
            }
            status.setTextColor(if (count == 0) accentRed else muted)
            internalUpdate = false
        }

        allBox.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdate) {
                internalUpdate = true
                checkBoxes.values.forEach { it.isChecked = checked }
                internalUpdate = false
                refreshAllState()
            }
        }
        checkBoxes.values.forEach { box ->
            box.setOnCheckedChangeListener { _, _ ->
                if (!internalUpdate) refreshAllState()
            }
        }
        refreshAllState()

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(16), 0, 0) }
        }

        val btnCancel = TextView(context).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            background = rounded(Color.TRANSPARENT, radius = 12, strokeColor = Color.TRANSPARENT)
        }

        val btnKobra = TextView(context).apply {
            text = "Kobra only"
            textSize = 14f
            setTextColor(accent)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            background = rounded(Color.TRANSPARENT, radius = 12, strokeColor = stroke)
        }

        val btnSave = TextView(context).apply {
            text = "Save"
            textSize = 14f
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            isFocusable = true
            background = rounded(accent, radius = 12, strokeColor = accent)
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        buttonRow.addView(btnKobra)
        buttonRow.addView(android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        })
        buttonRow.addView(btnSave)

        root.addView(buttonRow)

        val scroll = ScrollView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(root)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(scroll)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnKobra.setOnClickListener {
            prefs.edit()
                .putString(PREF_SERVERS, SERVER_KOBRA)
                .putString(PREF_SERVER, SERVER_KOBRA)
                .apply()
            Toast.makeText(context, "NTVStream set to Kobra only", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val chosen = checkBoxes.entries
                .filter { it.value.isChecked }
                .map { it.key }
                .ifEmpty { listOf(SERVER_KOBRA) }
            prefs.edit()
                .putString(PREF_SERVERS, chosen.joinToString(","))
                .putString(PREF_SERVER, if (chosen.size == allServerIds.size) SERVER_ALL else chosen.first())
                .apply()
            Toast.makeText(context, "NTVStream settings saved. Reload to apply.", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(Color.TRANSPARENT, radius = 24, strokeColor = Color.TRANSPARENT, strokeWidth = 0))
        }

        dialog.show()
    }
}
