package com.wizdier

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

object NTVStreamSettings {
    const val PREF_FILE = "NTVStream"
    const val PREF_SERVER = "selected_server"

    const val SERVER_ALL = "all"
    const val SERVER_KOBRA = "kobra"
    const val SERVER_RAPTOR = "raptor"
    const val SERVER_FALCON = "falcon"
    const val SERVER_PHOENIX = "phoenix"
    const val SERVER_VIPER = "viper"

    val serverOptions = listOf(
        SERVER_ALL to "All servers",
        SERVER_KOBRA to "Kobra",
        SERVER_RAPTOR to "Raptor",
        SERVER_FALCON to "Falcon",
        SERVER_PHOENIX to "Phoenix",
        SERVER_VIPER to "Viper",
    )

    fun selectedServer(prefs: SharedPreferences): String =
        prefs.getString(PREF_SERVER, SERVER_ALL)
            ?.takeIf { value -> serverOptions.any { it.first == value } }
            ?: SERVER_ALL

    fun show(context: Context, prefs: SharedPreferences) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val smallPad = (8 * context.resources.displayMetrics.density).toInt()
        val current = selectedServer(prefs)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val note = TextView(context).apply {
            text = "Choose which NTVStream server provider should be registered. Select All to show every server. Reload Cloudstream or reload the extension after saving for the provider list to refresh."
            textSize = 13f
            setPadding(0, 0, 0, smallPad)
        }
        container.addView(note)

        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        serverOptions.forEachIndexed { index, (value, label) ->
            val radio = RadioButton(context).apply {
                id = index + 1000
                tag = value
                text = label
                textSize = 15f
                setPadding(0, smallPad / 2, 0, smallPad / 2)
                isChecked = value == current
            }
            radioGroup.addView(radio)
        }
        container.addView(radioGroup)

        AlertDialog.Builder(context)
            .setTitle("NTVStream Server")
            .setView(container)
            .setPositiveButton("Save") { dialog, _ ->
                val selected = radioGroup.findViewById<RadioButton>(radioGroup.checkedRadioButtonId)
                    ?.tag
                    ?.toString()
                    ?: SERVER_ALL
                prefs.edit().putString(PREF_SERVER, selected).apply()
                Toast.makeText(
                    context,
                    "NTVStream server saved. Reload extension/app to apply.",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset") { dialog, _ ->
                prefs.edit().putString(PREF_SERVER, SERVER_ALL).apply()
                Toast.makeText(context, "NTVStream reset to All servers", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }
}
