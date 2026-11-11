package com.semput.imgsampahsorter

import android.app.Activity
import android.app.AlertDialog
import android.os.Environment
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Dialog for choosing a folder on the phone. Shows hidden (.name) folders too. */
class FolderPicker(
    private val act: Activity,
    private val title: String,
    start: File?,
    private val onPick: (File) -> Unit,
) {
    private val storage = Environment.getExternalStorageDirectory()
    private var current: File = start?.takeIf { it.isDirectory }
        ?: start?.parentFile?.takeIf { it.isDirectory }
        ?: storage
    private val pathText = Ui.text(act, "", 13f, Ui.TEXT_DIM)
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var dialog: AlertDialog

    private fun dp(v: Int) = Ui.dp(act, v)

    fun show() {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        box.addView(pathText)
        box.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(360)
        ).apply { topMargin = dp(6) })
        dialog = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Use this folder", null)
            .setNeutralButton("＋ New folder", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onPick(current)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { newFolder() }
        }
        render()
        dialog.show()
    }

    private fun render() {
        pathText.text = current.absolutePath.replace(storage.absolutePath, "Phone")
        list.removeAllViews()
        fun row(label: String, onClick: () -> Unit) {
            list.addView(TextView(act).apply {
                text = label
                textSize = 16f
                setTextColor(Ui.TEXT)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = Ui.rounded(act, Ui.SURFACE, 8)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) })
        }
        if (current.absolutePath != storage.absolutePath) {
            current.parentFile?.let { p -> row("⬆  Up") { current = p; render() } }
        }
        val subs = FileOps.listSubfolders(current)
        subs.forEach { s -> row((if (s.name.startsWith(".")) "👁‍🗨  " else "📁  ") + s.name) { current = s; render() } }
        if (subs.isEmpty()) list.addView(Ui.text(act, "No folders inside.", 14f, Ui.TEXT_DIM))
    }

    private fun newFolder() {
        val input = EditText(act).apply {
            hint = "Name (start with . to hide from the gallery)"
            isSingleLine = true
        }
        AlertDialog.Builder(act)
            .setTitle("New folder in ${current.name.ifEmpty { "Phone" }}")
            .setView(FrameLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) })
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().replace("/", "_")
                if (name.isEmpty() || name == "." || name == "..") return@setPositiveButton
                val f = File(current, name)
                if (f.isDirectory || f.mkdirs()) { current = f; render() }
                else Toast.makeText(act, "Couldn't create folder", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
