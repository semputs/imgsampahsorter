package com.semput.imgsampahsorter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Full-screen sorter.
 *  swipe down  -> move to "trash" folder (hidden from gallery, can be undone)
 *  swipe up    -> move to "good" folder
 *  swipe left  -> next image, swipe right -> previous image
 *  tap         -> hide / show the buttons
 */
class SorterActivity : Activity() {

    private data class Move(val src: File, val dest: File, val atIndex: Int, val toTrash: Boolean)

    private lateinit var dir: File
    private var images = mutableListOf<File>()
    private var index = 0
    private val undoStack = ArrayList<Move>()
    private var goodCount = 0
    private var trashCount = 0
    private var busy = false
    private var barsVisible = true

    private lateinit var loader: ImageLoader
    private lateinit var root: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var stamp: TextView
    private lateinit var message: TextView
    private lateinit var info: TextView
    private lateinit var trashButton: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var doneView: LinearLayout
    private lateinit var doneText: TextView
    private lateinit var doneTrashButton: TextView

    private val prefs by lazy { getSharedPreferences("app", MODE_PRIVATE) }
    private val posKey get() = "pos:" + dir.absolutePath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dir = File(intent.getStringExtra("dir") ?: run { finish(); return })
        val dm = resources.displayMetrics
        loader = ImageLoader(min(dm.widthPixels, dm.heightPixels))
        buildUi()
        reload(prefs.getString(posKey, null))
    }

    override fun onRestart() {
        super.onRestart()
        // Folder may have changed while the app was in the background.
        reload(images.getOrNull(index)?.name)
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putString(posKey, images.getOrNull(index)?.name).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::loader.isInitialized) loader.shutdown()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = Ui.dp(this, v)

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT

        imageView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(imageView, FrameLayout.LayoutParams(match, match))

        message = Ui.text(this, "", 16f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER))

        stamp = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(8), dp(24), dp(8))
            alpha = 0f
            rotation = -10f
        }
        root.addView(stamp, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))

        // Invisible layer that catches swipes. It never moves, so gesture maths stay simple.
        val touchLayer = View(this)
        touchLayer.setOnTouchListener { _, e -> onTouch(e); true }
        root.addView(touchLayer, FrameLayout.LayoutParams(match, match))

        // Top bar: position + file name, trash button
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(dp(16), dp(10), dp(12), dp(10))
        }
        info = Ui.text(this, "", 14f)
        topBar.addView(info, LinearLayout.LayoutParams(0, wrap, 1f))
        trashButton = Ui.button(this, "Trash (0)", Ui.GREY).apply {
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { trashDialog() }
        }
        topBar.addView(trashButton)
        root.addView(topBar, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        // Bottom bar: hint + Trash / Undo / Good buttons
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        bottomBar.addView(
            Ui.text(this, "↓ trash    ↑ good    ← → browse    tap: hide buttons", 12f, Ui.TEXT_DIM)
                .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(match, wrap)
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowParams() = LinearLayout.LayoutParams(0, wrap, 1f).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        }
        row.addView(Ui.button(this, "🗑  Trash", Ui.RED).apply {
            setOnClickListener { decide(toTrash = true) }
        }, rowParams())
        row.addView(Ui.button(this, "↶  Undo", Ui.GREY).apply {
            setOnClickListener { undo() }
        }, rowParams())
        row.addView(Ui.button(this, "✓  Good", Ui.GREEN).apply {
            setOnClickListener { decide(toTrash = false) }
        }, rowParams())
        bottomBar.addView(row, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
        root.addView(bottomBar, FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM))

        // "All done" screen
        doneView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        doneText = Ui.text(this, "", 18f).apply { gravity = Gravity.CENTER }
        doneView.addView(doneText)
        doneTrashButton = Ui.button(this, "", Ui.RED).apply { setOnClickListener { trashDialog() } }
        doneView.addView(doneTrashButton, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(24) })
        doneView.addView(Ui.button(this, "Back to folders", Ui.BLUE).apply {
            setOnClickListener { leave() }
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        root.addView(doneView, FrameLayout.LayoutParams(match, match))

        setContentView(root)
    }

    private fun reload(keepName: String?) {
        images = FileOps.listImages(dir)
        index = images.indexOfFirst { it.name == keepName }.coerceAtLeast(0)
        show()
    }

    private fun trashTotal() = FileOps.trashFiles(dir).size

    private fun show() {
        val total = trashTotal()
        trashButton.text = "Trash ($total)"
        trashButton.background = Ui.rounded(this, if (total > 0) Ui.RED else Ui.GREY)

        if (images.isEmpty()) {
            imageView.setImageDrawable(null)
            message.visibility = View.GONE
            info.text = "0 images left\n${dir.name}"
            doneText.text = "All done! 🎉\n\nNo images left in \"${dir.name}\".\n\n" +
                "✓ $goodCount moved to good this time\n🗑 $total waiting in trash"
            doneTrashButton.text = "Empty trash ($total)"
            doneTrashButton.visibility = if (total > 0) View.VISIBLE else View.GONE
            doneView.visibility = View.VISIBLE
            setBars(true)
            return
        }
        doneView.visibility = View.GONE
        index = index.coerceIn(0, images.size - 1)
        val f = images[index]
        info.text = "${index + 1} / ${images.size}   ·   ✓ $goodCount  🗑 $trashCount\n${f.name}"

        message.visibility = View.GONE
        val cached = loader.cached(f)
        if (cached != null) {
            imageView.setImageBitmap(cached)
        } else {
            imageView.setImageDrawable(null)
            loader.load(f) { bmp ->
                if (images.getOrNull(index) != f) return@load
                if (bmp != null) imageView.setImageBitmap(bmp)
                else {
                    message.text = "Can't preview this file\n${f.name}\n\nYou can still sort it."
                    message.visibility = View.VISIBLE
                }
            }
        }
        for (o in intArrayOf(1, -1, 2)) images.getOrNull(index + o)?.let { loader.prefetch(it) }
    }

    private fun setBars(visible: Boolean) {
        barsVisible = visible
        for (v in listOf(topBar, bottomBar)) {
            v.animate().cancel()
            if (visible) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(150).start()
            } else {
                v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = View.GONE }.start()
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun buzz() {
        root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    // ---------------------------------------------------------------- gestures

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical
    private var tracker: VelocityTracker? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val flingVelocity by lazy { 1000 * resources.displayMetrics.density }

    private fun onTouch(e: MotionEvent) {
        if (busy) return
        if (images.isEmpty()) {
            if (e.actionMasked == MotionEvent.ACTION_UP) setBars(!barsVisible)
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                downTime = SystemClock.uptimeMillis()
                axis = 0
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (axis == 0 && hypot(dx, dy) > slop) axis = if (abs(dx) > abs(dy)) 1 else 2
                if (axis == 1) {
                    val atEdge = (dx < 0 && index >= images.size - 1) || (dx > 0 && index <= 0)
                    imageView.translationX = if (atEdge) dx * 0.25f else dx
                } else if (axis == 2) {
                    imageView.translationY = dy
                    val progress = min(1f, abs(dy) / verticalThreshold())
                    showStamp(toTrash = dy > 0, progress = progress)
                    imageView.alpha = 1f - 0.4f * progress
                    val s = 1f - 0.08f * progress
                    imageView.scaleX = s; imageView.scaleY = s
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                when (axis) {
                    0 -> if (SystemClock.uptimeMillis() - downTime < 300) setBars(!barsVisible)
                    1 -> {
                        val hThr = root.width * 0.2f
                        when {
                            (dx < -hThr || (vx < -flingVelocity && dx < 0)) && index < images.size - 1 -> slide(+1)
                            (dx > hThr || (vx > flingVelocity && dx > 0)) && index > 0 -> slide(-1)
                            else -> springBack()
                        }
                    }
                    2 -> {
                        val vThr = verticalThreshold()
                        val minDrag = slop * 3
                        when {
                            dy > vThr || (vy > flingVelocity && dy > minDrag) -> decide(toTrash = true)
                            dy < -vThr || (vy < -flingVelocity && dy < -minDrag) -> decide(toTrash = false)
                            else -> springBack()
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle(); tracker = null
                springBack()
            }
        }
    }

    private fun verticalThreshold() = root.height * 0.18f

    private fun showStamp(toTrash: Boolean, progress: Float) {
        val color = if (toTrash) Ui.RED else Ui.GREEN
        stamp.text = if (toTrash) "TRASH" else "GOOD"
        stamp.setTextColor(color)
        stamp.background = GradientDrawable().apply {
            setStroke(dp(4), color)
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#99000000"))
        }
        stamp.alpha = progress
    }

    private fun springBack() {
        imageView.animate().translationX(0f).translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160).start()
        stamp.animate().alpha(0f).setDuration(160).start()
    }

    private fun resetImageView() {
        imageView.animate().cancel()
        imageView.translationX = 0f; imageView.translationY = 0f
        imageView.scaleX = 1f; imageView.scaleY = 1f
        imageView.alpha = 1f
    }

    private fun slide(step: Int) {
        busy = true
        val w = root.width.toFloat()
        imageView.animate().translationX(-step * w).alpha(0f).setDuration(130).withEndAction {
            index += step
            show()
            imageView.translationX = step * w * 0.25f
            imageView.alpha = 0f
            imageView.animate().translationX(0f).alpha(1f).setDuration(130)
                .withEndAction { busy = false }.start()
        }.start()
    }

    /** Fade the (new) current image in after a move or undo. */
    private fun popIn() {
        resetImageView()
        show()
        imageView.alpha = 0f
        imageView.scaleX = 0.92f; imageView.scaleY = 0.92f
        imageView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
            .withEndAction { busy = false }.start()
    }

    // ---------------------------------------------------------------- actions

    private fun decide(toTrash: Boolean) {
        if (busy || images.isEmpty()) return
        busy = true
        val f = images[index]
        showStamp(toTrash, 1f)
        stamp.animate().alpha(0f).setStartDelay(120).setDuration(200)
            .withEndAction { stamp.animate().setStartDelay(0) }.start()
        val h = root.height.toFloat()
        imageView.animate().translationY(if (toTrash) h else -h).alpha(0f).setDuration(180).withEndAction {
            if (moveCurrent(f, toTrash)) {
                buzz()
            } else {
                toast("Couldn't move ${f.name}")
            }
            popIn()
        }.start()
    }

    private fun moveCurrent(f: File, toTrash: Boolean): Boolean {
        val targetDir = if (toTrash) FileOps.ensureDir(dir, FileOps.TRASH, hideFromGallery = true)
        else FileOps.ensureDir(dir, FileOps.GOOD, hideFromGallery = false)
        val dest = FileOps.uniqueTarget(targetDir, f.name)
        if (!FileOps.move(f, dest)) return false
        undoStack.add(Move(f, dest, index, toTrash))
        images.removeAt(index)
        if (index >= images.size) index = (images.size - 1).coerceAtLeast(0)
        if (toTrash) trashCount++ else goodCount++
        FileOps.scan(this, listOf(f, dest))
        return true
    }

    private fun undo() {
        if (busy) return
        val m = undoStack.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
        if (!m.dest.exists()) {
            toast("That image was already deleted forever")
            return
        }
        val back = if (m.src.exists()) FileOps.uniqueTarget(dir, m.src.name) else m.src
        if (!FileOps.move(m.dest, back)) {
            undoStack.add(m)
            toast("Couldn't undo")
            return
        }
        val at = m.atIndex.coerceIn(0, images.size)
        images.add(at, back)
        index = at
        if (m.toTrash) trashCount-- else goodCount--
        FileOps.scan(this, listOf(m.dest, back))
        busy = true
        popIn()
        toast(if (m.toTrash) "Back from trash" else "Back from good")
    }

    private fun trashDialog() {
        if (busy) return
        val files = FileOps.trashFiles(dir)
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Trash is empty")
                .setMessage("Swipe down on an image to put it in the trash.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Trash: ${files.size} images")
            .setMessage("These are in the \"trash\" folder inside ${dir.name}. Delete them forever, or put them all back?")
            .setPositiveButton("Delete forever…") { _, _ -> confirmDelete(files, finishAfter = false) }
            .setNeutralButton("Restore all") { _, _ -> restoreAll(files) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(files: List<File>, finishAfter: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${files.size} images forever?")
            .setMessage("This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                deleteForever(files)
                if (finishAfter) finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteForever(files: List<File>) {
        var deleted = 0
        files.forEach { if (it.delete()) deleted++ }
        FileOps.scan(this, files)
        undoStack.removeAll { it.toTrash }
        toast("Deleted $deleted images")
        show()
    }

    private fun restoreAll(files: List<File>) {
        val keep = images.getOrNull(index)
        val changed = ArrayList<File>()
        files.forEach {
            val dest = FileOps.uniqueTarget(dir, it.name)
            if (FileOps.move(it, dest)) {
                images.add(dest)
                changed.add(it); changed.add(dest)
            }
        }
        images.sortWith(FileOps.nameOrder)
        index = keep?.let { images.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        undoStack.removeAll { it.toTrash }
        trashCount = 0
        FileOps.scan(this, changed)
        toast("Restored ${changed.size / 2} images")
        resetImageView()
        show()
    }

    /** Leaving the folder: remind about anything still sitting in the trash. */
    private fun leave() {
        val files = FileOps.trashFiles(dir)
        if (files.isEmpty()) { finish(); return }
        AlertDialog.Builder(this)
            .setTitle("${files.size} images in trash")
            .setMessage("Delete them forever now, or keep them in the trash folder for later?")
            .setPositiveButton("Delete forever…") { _, _ -> confirmDelete(files, finishAfter = true) }
            .setNeutralButton("Keep for later") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        leave()
    }
}
