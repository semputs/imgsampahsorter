package com.semput.imgsampahsorter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen sorter.
 *  swipe down  -> move to "trash" folder (hidden from gallery, can be undone)
 *  swipe up    -> move to "good" folder
 *  swipe left  -> next image, swipe right -> previous image
 *  pinch / double-tap -> zoom (drag to look around while zoomed)
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
    private lateinit var imageView: ImageView   // current image
    private lateinit var peekView: ImageView    // neighbour shown while swiping
    private var peekFile: File? = null
    private lateinit var badge: TextView
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
        Ui.hideFromRecents(this)
        dir = File(intent.getStringExtra("dir") ?: run { finish(); return })
        val dm = resources.displayMetrics
        loader = ImageLoader(min(dm.widthPixels, dm.heightPixels))
        buildUi()
        reload(prefs.getString(posKey, null))
        if (index > 0) askWhereToStart()
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

    override fun onStop() {
        super.onStop()
        // Left the app (home, app switch, screen off): close the image viewer so no picture is
        // left on screen. The position is saved in onPause, so "Continue" picks up from here.
        if (!isChangingConfigurations) finish()
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

        peekView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
        }
        root.addView(peekView, FrameLayout.LayoutParams(match, match))

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(imageView, FrameLayout.LayoutParams(match, match))

        message = Ui.text(this, "", 16f, Ui.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(match, wrap, Gravity.CENTER))

        // Invisible layer that catches gestures. It never moves, so the maths stay simple.
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
        info = Ui.text(this, "", 14f).apply {
            // Tap the counter to jump to any image number.
            setOnClickListener { jumpDialog() }
        }
        topBar.addView(info, LinearLayout.LayoutParams(0, wrap, 1f))
        trashButton = Ui.button(this, "Trash (0)", Ui.GREY).apply {
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { trashDialog() }
        }
        topBar.addView(trashButton)
        root.addView(topBar, FrameLayout.LayoutParams(match, wrap, Gravity.TOP))

        // Quick 👍 / 👎 feedback, top right
        badge = TextView(this).apply {
            textSize = 34f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            alpha = 0f
        }
        root.addView(badge, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(76); rightMargin = dp(16)
        })

        // Bottom bar: hint + Trash / Undo / Good buttons
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(dp(12), dp(8), dp(12), dp(14))
        }
        bottomBar.addView(
            Ui.text(this, "↓ trash   ↑ good   ← → browse   pinch / double-tap: zoom   tap: hide   tap counter: jump", 12f, Ui.TEXT_DIM)
                .apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(match, wrap)
        )
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowParams() = LinearLayout.LayoutParams(0, wrap, 1f).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        }
        row.addView(Ui.button(this, "🗑  Trash", Ui.RED).apply {
            setOnClickListener { decide(toTrash = true, flyOut = false) }
        }, rowParams())
        row.addView(Ui.button(this, "↶  Undo", Ui.GREY).apply {
            setOnClickListener { undo() }
        }, rowParams())
        row.addView(Ui.button(this, "✓  Good", Ui.GREEN).apply {
            setOnClickListener { decide(toTrash = false, flyOut = false) }
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
        resetView()
        show()
    }

    private fun trashTotal() = FileOps.trashFiles(dir).size

    private fun show() {
        val total = trashTotal()
        trashButton.text = "Trash ($total)"
        trashButton.background = Ui.rounded(this, if (total > 0) Ui.RED else Ui.GREY)
        hidePeek()

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

    /** Put [f]'s picture into the peek view (the neighbour that slides in alongside). */
    private fun setPeek(f: File?) {
        if (f == null) { hidePeek(); return }
        peekView.visibility = View.VISIBLE
        if (peekFile == f) return
        peekFile = f
        val cached = loader.cached(f)
        if (cached != null) peekView.setImageBitmap(cached)
        else {
            peekView.setImageDrawable(null)
            loader.load(f) { bmp -> if (peekFile == f && bmp != null) peekView.setImageBitmap(bmp) }
        }
    }

    private fun hidePeek() {
        peekView.animate().cancel()
        peekView.visibility = View.INVISIBLE
        peekView.translationX = 0f
        peekView.translationY = 0f
        peekView.setImageDrawable(null)
        peekFile = null
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

    private fun flashBadge(good: Boolean) {
        badge.animate().cancel()
        badge.text = if (good) "👍" else "👎"
        badge.background = Ui.rounded(this, if (good) Ui.GREEN else Ui.RED, 40)
        badge.alpha = 1f
        badge.scaleX = 0.6f; badge.scaleY = 0.6f
        badge.animate().scaleX(1f).scaleY(1f).setDuration(120).withEndAction {
            badge.animate().alpha(0f).setStartDelay(350).setDuration(200)
                .withEndAction { badge.animate().setStartDelay(0) }.start()
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun buzz() {
        root.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    // ---------------------------------------------------------------- zoom

    private var zoom = 1f
    private val maxZoom = 6f

    private fun zoomed() = zoom > 1.01f

    /** How far the zoomed picture may be dragged before its edge reaches the screen edge. */
    private fun clampPan() {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val d = imageView.drawable
        var cw = w
        var ch = h
        if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            val aspect = d.intrinsicWidth.toFloat() / d.intrinsicHeight
            if (w / h > aspect) { ch = h; cw = h * aspect } else { cw = w; ch = w / aspect }
        }
        val maxX = max(0f, (cw * zoom - w) / 2f)
        val maxY = max(0f, (ch * zoom - h) / 2f)
        imageView.translationX = imageView.translationX.coerceIn(-maxX, maxX)
        imageView.translationY = imageView.translationY.coerceIn(-maxY, maxY)
    }

    /** Scale by [factor] keeping the screen point (fx, fy) still. */
    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val newZoom = (zoom * factor).coerceIn(0.8f, maxZoom)
        val f = newZoom / zoom
        val cx = root.width / 2f
        val cy = root.height / 2f
        imageView.translationX = (fx - cx) * (1 - f) + f * imageView.translationX
        imageView.translationY = (fy - cy) * (1 - f) + f * imageView.translationY
        zoom = newZoom
        imageView.scaleX = zoom; imageView.scaleY = zoom
    }

    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        val cx = root.width / 2f
        val cy = root.height / 2f
        val f = target / zoom
        var tx = (fx - cx) * (1 - f) + f * imageView.translationX
        var ty = (fy - cy) * (1 - f) + f * imageView.translationY
        zoom = target
        if (target <= 1.01f) { tx = 0f; ty = 0f; zoom = 1f }
        // Work out clamped end position without moving yet.
        val oldX = imageView.translationX
        val oldY = imageView.translationY
        imageView.translationX = tx; imageView.translationY = ty
        clampPan()
        tx = imageView.translationX; ty = imageView.translationY
        imageView.translationX = oldX; imageView.translationY = oldY
        imageView.animate().scaleX(zoom).scaleY(zoom).translationX(tx).translationY(ty)
            .setDuration(180).start()
    }

    private fun resetView() {
        imageView.animate().cancel()
        zoom = 1f
        imageView.scaleX = 1f; imageView.scaleY = 1f
        imageView.translationX = 0f; imageView.translationY = 0f
        imageView.alpha = 1f
    }

    // ---------------------------------------------------------------- gestures

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var axis = 0 // 0 = undecided, 1 = horizontal, 2 = vertical, 3 = zoom / pan
    private var tracker: VelocityTracker? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val flingVelocity by lazy { 1000 * resources.displayMetrics.density }

    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                if (busy || images.isEmpty()) return false
                if (axis == 1 || axis == 2) {
                    // A swipe had started — cancel it and zoom instead.
                    imageView.translationX = 0f; imageView.translationY = 0f
                    hidePeek()
                }
                axis = 3
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                if (zoom < 1.05f) animateZoomTo(1f, d.focusX, d.focusY) else clampPan()
            }
        })
    }

    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setBars(!barsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (busy || images.isEmpty()) return true
                if (zoomed()) animateZoomTo(1f, e.x, e.y) else animateZoomTo(2.5f, e.x, e.y)
                return true
            }
        })
    }

    private fun onTouch(e: MotionEvent) {
        if (images.isEmpty()) {
            tapDetector.onTouchEvent(e)
            return
        }
        if (busy) return
        handleTouch(e)
        // Detectors run after, so a double-tap zoom isn't cancelled by the touch-down handling.
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
    }

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                imageView.animate().cancel()
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                axis = if (zoomed()) 3 else 0
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Keep panning smoothly with whichever finger is left.
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                if (axis == 3) {
                    if (!scaleDetector.isInProgress && e.pointerCount == 1 && zoomed()) {
                        imageView.translationX += e.x - lastX
                        imageView.translationY += e.y - lastY
                        clampPan()
                    }
                    lastX = e.x; lastY = e.y
                    return
                }
                if (axis == 0 && zoomed()) { axis = 3; lastX = e.x; lastY = e.y; return }
                val dx = e.x - downX
                val dy = e.y - downY
                if (axis == 0 && hypot(dx, dy) > slop) axis = if (abs(dx) > abs(dy)) 1 else 2
                val w = root.width.toFloat()
                if (axis == 1) {
                    val neighbour = images.getOrNull(if (dx < 0) index + 1 else index - 1)
                    val x = if (neighbour == null) dx * 0.25f else dx
                    imageView.translationX = x
                    if (neighbour != null) {
                        setPeek(neighbour)
                        peekView.translationY = 0f
                        peekView.translationX = if (dx < 0) x + w else x - w
                    } else hidePeek()
                } else if (axis == 2) {
                    imageView.translationY = dy
                    // Show what will come next underneath the picture being dragged away.
                    setPeek(images.getOrNull(index + 1) ?: images.getOrNull(index - 1))
                    peekView.translationX = 0f
                    peekView.translationY = 0f
                }
            }
            MotionEvent.ACTION_UP -> {
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                val vy = tracker?.yVelocity ?: 0f
                tracker?.recycle(); tracker = null
                val dx = e.x - downX
                val dy = e.y - downY
                when (axis) {
                    1 -> {
                        val hThr = root.width * 0.2f
                        when {
                            (dx < -hThr || (vx < -flingVelocity && dx < 0)) && index < images.size - 1 -> slide(+1)
                            (dx > hThr || (vx > flingVelocity && dx > 0)) && index > 0 -> slide(-1)
                            else -> springBack()
                        }
                    }
                    2 -> {
                        val vThr = root.height * 0.18f
                        val minDrag = slop * 3
                        when {
                            dy > vThr || (vy > flingVelocity && dy > minDrag) -> decide(toTrash = true, flyOut = true)
                            dy < -vThr || (vy < -flingVelocity && dy < -minDrag) -> decide(toTrash = false, flyOut = true)
                            else -> springBack()
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle(); tracker = null
                if (axis == 1 || axis == 2) springBack()
            }
        }
    }

    private fun springBack() {
        val w = root.width.toFloat()
        val peekTarget = when {
            peekView.translationX > 0 -> w
            peekView.translationX < 0 -> -w
            else -> 0f
        }
        imageView.animate().translationX(0f).translationY(0f).setDuration(160).start()
        if (peekView.visibility == View.VISIBLE && peekTarget != 0f) {
            peekView.animate().translationX(peekTarget).setDuration(160).withEndAction { hidePeek() }.start()
        } else {
            hidePeek()
        }
    }

    /** Pager-style: current slides out while the neighbour slides in beside it. */
    private fun slide(step: Int) {
        busy = true
        val w = root.width.toFloat()
        setPeek(images.getOrNull(index + step))
        if (peekView.translationX == 0f) peekView.translationX = step * w
        imageView.animate().translationX(-step * w).setDuration(160).start()
        peekView.animate().translationX(0f).setDuration(160).withEndAction {
            val d = peekView.drawable
            index += step
            resetView()
            show()
            if (imageView.drawable == null && d != null) imageView.setImageDrawable(d)
            busy = false
        }.start()
    }

    // ---------------------------------------------------------------- actions

    /**
     * Move the current image to trash or good. [flyOut] = it was swiped, so let it keep
     * travelling off-screen (revealing the next image underneath); buttons swap instantly.
     */
    private fun decide(toTrash: Boolean, flyOut: Boolean) {
        if (busy || images.isEmpty()) return
        val f = images[index]
        val complete = {
            if (moveCurrent(f, toTrash)) {
                buzz()
                flashBadge(good = !toTrash)
            } else {
                toast("Couldn't move ${f.name}")
            }
            resetView()
            show()
            busy = false
        }
        if (!flyOut) { complete(); return }
        busy = true
        val h = root.height.toFloat()
        imageView.animate().translationY(if (toTrash) h else -h).setDuration(120)
            .withEndAction { complete() }.start()
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
        resetView()
        show()
        toast(if (m.toTrash) "Back from trash" else "Back from good")
    }

    private fun jumpTo(i: Int) {
        if (images.isEmpty()) return
        index = i.coerceIn(0, images.size - 1)
        resetView()
        show()
    }

    /** Opened a folder with a saved spot: continue there, or start over from #1? */
    private fun askWhereToStart() {
        val name = images.getOrNull(index)?.name ?: return
        AlertDialog.Builder(this)
            .setTitle("Where to start?")
            .setMessage("Last time you were at image ${index + 1} of ${images.size}\n($name).")
            .setPositiveButton("Continue from ${index + 1}") { _, _ -> }
            .setNegativeButton("Start from 1") { _, _ -> jumpTo(0) }
            .setNeutralButton("Pick number…") { _, _ -> jumpDialog() }
            .show()
    }

    /** Type an image number to go straight to it. */
    private fun jumpDialog() {
        if (busy || images.isEmpty()) return
        val pad = dp(20)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – ${images.size}"
            setText((index + 1).toString())
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Go to image (1 – ${images.size})")
            .setView(box)
            .setPositiveButton("Go") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null) jumpTo(n - 1)
            }
            .setNeutralButton("First") { _, _ -> jumpTo(0) }
            .setNegativeButton("Last") { _, _ -> jumpTo(images.size - 1) }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
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
        resetView()
        show()
    }

    /** Leaving the folder: remind about anything still sitting in the trash. */
    /** Leaving the folder. Trash is kept; empty it with the Trash button when you're done. */
    private fun leave() {
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (zoomed()) { animateZoomTo(1f, root.width / 2f, root.height / 2f); return }
        leave()
    }
}
