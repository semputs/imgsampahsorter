package com.semput.imgsampahsorter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    ).apply { topMargin = Ui.dp(this@MainActivity, dp) }

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

    // ---------------------------------------------------------------- merge mode state
    private var mergeMode = false
    private val selected = LinkedHashSet<String>()

    private fun renderBrowser() {
        root.gravity = Gravity.NO_GRAVITY
        val dir = current

        if (mergeMode) renderMergeHeader(dir) else renderSortHeader(dir)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(label: String, folder: File?, onClick: () -> Unit) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(this@MainActivity, Ui.SURFACE, 10)
            }
            line.addView(TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(Ui.TEXT)
                val p = Ui.dp(this@MainActivity, 14)
                setPadding(p, p, p, p)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (mergeMode && folder != null) {
                val on = folder.absolutePath in selected
                line.addView(Ui.button(this, if (on) "☑ Selected" else "☐ Select", if (on) Ui.GREEN else Ui.GREY).apply {
                    textSize = 14f
                    val p = Ui.dp(this@MainActivity, 10)
                    setPadding(p, p, p, p)
                    setOnClickListener {
                        if (!selected.remove(folder.absolutePath)) selected.add(folder.absolutePath)
                        render()
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = Ui.dp(this@MainActivity, 8) })
            }
            list.addView(line, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ui.dp(this@MainActivity, 6) })
        }

        if (dir == null) {
            volumes().forEach { v -> row("💾  ${volumeLabel(v)}", null) { go(v) } }
        } else {
            val parent = parentOf(dir)
            if (parent != null) row("⬆  Up", null) { go(parent) }
            else if (volumes().size > 1) row("⬆  Up", null) { go(null) }
            val subs = FileOps.listSubfolders(dir)
            subs.forEach { s -> row("📁  ${s.name}", s) { go(s) } }
            if (subs.isEmpty()) {
                list.addView(Ui.text(this, "No subfolders here.", 14f, Ui.TEXT_DIM), gap(12))
            }
        }

        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = Ui.dp(this@MainActivity, 12) })
    }

    private fun renderSortHeader(dir: File?) {
        root.addView(Ui.text(this, "Pick a folder to sort", 24f))

        val last = prefs.getString("lastSorted", null)?.let { File(it) }?.takeIf { it.isDirectory }
        if (last != null) {
            root.addView(Ui.button(this, "▶  Continue: ${last.name}", Ui.GREEN).apply {
                setOnClickListener { openSorter(last) }
            }, gap(16))
        }

        root.addView(Ui.text(this, dir?.absolutePath ?: "Storage", 13f, Ui.TEXT_DIM), gap(20))

        if (dir != null) {
            val count = FileOps.listImages(dir).size
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(Ui.button(
                this,
                if (count > 0) "Sort this folder ($count)" else "No images here",
                if (count > 0) Ui.BLUE else Ui.GREY
            ).apply {
                if (count > 0) setOnClickListener { openSorter(dir) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(Ui.button(this, "🔀 Merge…", Ui.GREY).apply {
                setOnClickListener { mergeMode = true; selected.clear(); render() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(this@MainActivity, 8) })
            root.addView(buttons, gap(8))
        }
    }

    private fun renderMergeHeader(dir: File?) {
        root.addView(Ui.text(this, "Merge folders", 24f))
        root.addView(Ui.text(
            this,
            "1. Tap ☐ Select on each folder to merge (e.g. your \"good\" folders).\n" +
                "2. Go to the folder they should all go into, or make a new one.\n" +
                "3. Tap Merge. Same names get _1, _2… added.",
            13f, Ui.TEXT_DIM
        ), gap(8))

        val names = selected.map { File(it).let { f -> "${f.parentFile?.name ?: ""}/${f.name}" } }
        root.addView(Ui.text(
            this,
            if (names.isEmpty()) "Nothing selected yet" else "Selected (${names.size}): " + names.joinToString(", "),
            14f, if (names.isEmpty()) Ui.TEXT_DIM else Ui.GREEN
        ), gap(10))

        root.addView(Ui.text(this, "Merge into: " + (dir?.absolutePath ?: "(open a folder)"), 13f, Ui.TEXT_DIM), gap(10))

        val sources = selected.map { File(it) }.filter { dir != null && it.absolutePath != dir.absolutePath }
        val canMerge = dir != null && sources.isNotEmpty()
        root.addView(Ui.button(
            this,
            if (canMerge) "Merge ${sources.size} folder${if (sources.size == 1) "" else "s"} into \"${dir!!.name}\""
            else "Merge",
            if (canMerge) Ui.GREEN else Ui.GREY
        ).apply {
            if (canMerge) setOnClickListener { confirmMerge(sources, dir!!) }
        }, gap(8))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun half() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = Ui.dp(this@MainActivity, 3); rightMargin = Ui.dp(this@MainActivity, 3)
        }
        if (dir != null) {
            buttons.addView(Ui.button(this, "＋ New folder", Ui.BLUE).apply {
                setOnClickListener { newFolderDialog(dir) }
            }, half())
        }
        buttons.addView(Ui.button(this, "Cancel", Ui.GREY).apply {
            setOnClickListener { mergeMode = false; selected.clear(); render() }
        }, half())
        root.addView(buttons, gap(8))
    }

    private fun newFolderDialog(parent: File) {
        val input = EditText(this).apply {
            setText(if (File(parent, FileOps.GOOD).exists()) "good_merged" else FileOps.GOOD)
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("New folder in ${parent.name}")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().replace("/", "_")
                if (name.isEmpty()) return@setPositiveButton
                val f = File(parent, name)
                if (f.exists() || f.mkdirs()) go(f)
                else Toast.makeText(this, "Couldn't create folder", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmMerge(sources: List<File>, dest: File) {
        val count = sources.sumOf { FileOps.listImages(it).size }
        AlertDialog.Builder(this)
            .setTitle("Merge $count images?")
            .setMessage(
                "Move all images from:\n" + sources.joinToString("\n") { "• " + it.absolutePath } +
                    "\n\ninto:\n" + dest.absolutePath +
                    "\n\nFiles with the same name get _1, _2… added. Emptied folders are removed."
            )
            .setPositiveButton("Merge") { _, _ -> runMerge(sources, dest) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runMerge(sources: List<File>, dest: File) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Merging…")
            .setMessage("Starting")
            .setCancelable(false)
            .show()
        val ui = Handler(Looper.getMainLooper())
        Thread {
            val result = FileOps.merge(sources, dest) { done, total ->
                if (done % 10 == 0 || done == total) ui.post { progress.setMessage("$done / $total images") }
            }
            FileOps.scan(this, result.changed)
            ui.post {
                progress.dismiss()
                mergeMode = false
                selected.clear()
                AlertDialog.Builder(this)
                    .setTitle("Merge done")
                    .setMessage(
                        "Moved ${result.moved} images into ${dest.name}." +
                            (if (result.renamed > 0) "\n${result.renamed} were renamed because the name was taken." else "") +
                            (if (result.failed > 0) "\n${result.failed} couldn't be moved." else "")
                    )
                    .setPositiveButton("OK", null)
                    .show()
                go(dest)
            }
        }.start()
    }

    private fun openSorter(dir: File) {
        prefs.edit().putString("lastSorted", dir.absolutePath).apply()
        startActivity(Intent(this, SorterActivity::class.java).putExtra("dir", dir.absolutePath))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val dir = current
        if (!hasAccess()) { super.onBackPressed(); return }
        if (dir == null) {
            if (mergeMode) { mergeMode = false; selected.clear(); render() } else super.onBackPressed()
            return
        }
        val parent = parentOf(dir)
        when {
            parent != null -> go(parent)
            volumes().size > 1 -> go(null)
            mergeMode -> { mergeMode = false; selected.clear(); render() }
            else -> super.onBackPressed()
        }
    }
}
