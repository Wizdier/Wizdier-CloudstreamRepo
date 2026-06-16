package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
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

        // Migrate previous single-server setting automatically.
        return when (val single = prefs.getString(PREF_SERVER, SERVER_ALL)) {
            SERVER_ALL, null -> allServerIds
            in allServerIds -> setOf(single)
            else -> allServerIds
        }
    }

    fun show(context: Context, prefs: SharedPreferences) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val smallPad = (8 * density).toInt()
        val selected = selectedServers(prefs)

        fun textView(text: String, size: Float, bold: Boolean = false): TextView =
            TextView(context).apply {
                this.text = text
                textSize = size
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, smallPad)
            }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(textView("Pick the NTVStream servers you want Cloudstream to show. Kobra is the most stable right now; enable multiple servers if you want more backups.", 13f))
            addView(textView("Servers", 16f, bold = true))
        }

        val allBox = CheckBox(context).apply {
            text = "All servers"
            textSize = 15f
            isChecked = selected.containsAll(allServerIds)
            setPadding(0, smallPad / 2, 0, smallPad / 2)
        }
        container.addView(allBox)

        val checkBoxes = linkedMapOf<String, CheckBox>()
        serverOptions.forEach { (value, label) ->
            val box = CheckBox(context).apply {
                text = when (value) {
                    SERVER_KOBRA -> "$label  • recommended"
                    SERVER_RAPTOR -> "$label  • browser embed"
                    SERVER_FALCON -> "$label  • direct m3u8 backups"
                    SERVER_PHOENIX -> "$label  • upstream may be unstable"
                    SERVER_VIPER -> "$label  • alternate channels"
                    else -> label
                }
                textSize = 15f
                isChecked = value in selected
                setPadding((12 * density).toInt(), smallPad / 2, 0, smallPad / 2)
            }
            checkBoxes[value] = box
            container.addView(box)
        }

        var internalUpdate = false
        fun refreshAllState() {
            internalUpdate = true
            allBox.isChecked = checkBoxes.values.all { it.isChecked }
            internalUpdate = false
        }

        allBox.setOnCheckedChangeListener { _, checked ->
            if (!internalUpdate) {
                internalUpdate = true
                checkBoxes.values.forEach { it.isChecked = checked }
                internalUpdate = false
            }
        }
        checkBoxes.values.forEach { box ->
            box.setOnCheckedChangeListener { _, _ ->
                if (!internalUpdate) refreshAllState()
            }
        }

        val hint = textView(
            "Tip: after saving, reload Cloudstream or reload the extension so the provider list refreshes.",
            12f
        ).apply { setPadding(0, smallPad, 0, 0) }
        container.addView(hint)

        val scroll = ScrollView(context).apply {
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(context)
            .setTitle("NTVStream Settings")
            .setView(scroll)
            .setPositiveButton("Save") { dialog, _ ->
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
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Kobra only") { dialog, _ ->
                prefs.edit()
                    .putString(PREF_SERVERS, SERVER_KOBRA)
                    .putString(PREF_SERVER, SERVER_KOBRA)
                    .apply()
                Toast.makeText(context, "NTVStream set to Kobra only", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }
}
