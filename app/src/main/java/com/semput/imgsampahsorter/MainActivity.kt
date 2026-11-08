package com.semput.imgsampahsorter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Start screen: asks for file access, then lets you browse to a folder to sort. */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }

    /** Folder being browsed. null = the list of storage volumes (phone / SD card). */
    private var current: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            val p = Ui.dp(this@MainActivity, 16)
            setPadding(p, p, p, p)
        }
        setContentView(root)
        current = prefs.getString("browse", null)?.let { File(it) }?.takeIf { it.isDirectory }
            ?: Environment.getExternalStorageDirectory()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        root.removeAllViews()
        if (hasAccess()) renderBrowser() else renderPermission()
    }

    private fun gap(dp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = Ui.dp(this, dp) }

    private fun renderPermission() {
        root.gravity = Gravity.CENTER
        root.addView(Ui.text(this, "ImgSampah Sorter", 26f).apply { gravity = Gravity.CENTER })
        root.addView(
            Ui.text(
                this,
                "To move pictures into \"good\" and \"trash\" folders, this app needs permission to manage " +
                    "files on your phone.\n\nTap the button, then switch on \"Allow access to manage all files\" " +
                    "and come back.",
                16f, Ui.TEXT_DIM
            ).apply { gravity = Gravity.CENTER },
            gap(16)
        )
        root.addView(Ui.button(this, "Allow file access", Ui.BLUE).apply {
            setOnClickListener { requestAccess() }
        }, gap(24))
    }

    private fun volumes(): List<File> {
        val list = mutableListOf(Environment.getExternalStorageDirectory())
        getExternalFilesDirs(null).forEach { f ->
            val path = f?.absolutePath ?: return@forEach
            if (!path.contains("/Android/data")) return@forEach
            val r = File(path.substringBefore("/Android/data"))
            if (list.none { it.absolutePath == r.absolutePath }) list.add(r)
        }
        return list
    }

    private fun volumeLabel(v: File): String =
        if (v.absolutePath == Environment.getExternalStorageDirectory().absolutePath) "Phone storage"
        else "SD card (${v.name})"

    private fun go(dir: File?) {
        current = dir
        prefs.edit().putString("browse", dir?.absolutePath).apply()
        render()
    }

    private fun parentOf(dir: File): File? {
        val vols = volumes()
        if (vols.any { it.absolutePath == dir.absolutePath }) return null
        return dir.parentFile
    }

    private fun renderBrowser() {
        root.gravity = Gravity.NO_GRAVITY
        val dir = current

        root.addView(Ui.text(this, "Pick a folder to sort", 24f))

        val last = prefs.getString("lastSorted", null)?.let { File(it) }?.takeIf { it.isDirectory }
        if (last != null) {
            root.addView(Ui.button(this, "▶  Continue: ${last.name}", Ui.GREEN).apply {
                setOnClickListener { openSorter(last) }
            }, gap(16))
        }

        root.addView(
            Ui.text(this, dir?.absolutePath ?: "Storage", 13f, Ui.TEXT_DIM),
            gap(20)
        )

        if (dir != null) {
            val count = FileOps.listImages(dir).size
            root.addView(Ui.button(
                this,
                if (count > 0) "Sort this folder ($count images)" else "No images in this folder",
                if (count > 0) Ui.BLUE else Ui.GREY
            ).apply {
                if (count > 0) setOnClickListener { openSorter(dir) }
            }, gap(8))
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(label: String, onClick: () -> Unit) {
            list.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(Ui.TEXT)
                val p = Ui.dp(this@MainActivity, 14)
                setPadding(p, p, p, p)
                background = Ui.rounded(this@MainActivity, Ui.SURFACE, 10)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(this@MainActivity, 6) })
        }

        if (dir == null) {
            volumes().forEach { v -> row("💾  ${volumeLabel(v)}") { go(v) } }
        } else {
            val parent = parentOf(dir)
            if (parent != null) row("⬆  Up") { go(parent) }
            else if (volumes().size > 1) row("⬆  Up") { go(null) }
            val subs = FileOps.listSubfolders(dir)
            subs.forEach { s -> row("📁  ${s.name}") { go(s) } }
            if (subs.isEmpty()) {
                list.addView(Ui.text(this, "No subfolders here.", 14f, Ui.TEXT_DIM), gap(12))
            }
        }

        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = Ui.dp(this@MainActivity, 12) })
    }

    private fun openSorter(dir: File) {
        prefs.edit().putString("lastSorted", dir.absolutePath).apply()
        startActivity(Intent(this, SorterActivity::class.java).putExtra("dir", dir.absolutePath))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val dir = current
        if (!hasAccess() || dir == null) { super.onBackPressed(); return }
        val parent = parentOf(dir)
        when {
            parent != null -> go(parent)
            volumes().size > 1 -> go(null)
            else -> super.onBackPressed()
        }
    }
}
