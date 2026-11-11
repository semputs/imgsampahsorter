package com.semput.imgsampahsorter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * appgentic5 remote: pairing, workflow list (duplicate / rename / delete), quick edit + generate,
 * the full appgentic5 editor in a WebView, recent runs, queue controls and image hand-off settings.
 */
class ComfyActivity : Activity() {

    private enum class Screen { SETUP, HOME, EDIT, RECENT, EDITOR }

    private var screen = Screen.HOME
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private var web: WebView? = null
    private lateinit var editorBack: TextView
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false

    // Home
    private var statusText: TextView? = null
    private var syncText: TextView? = null
    private var workflowsBox: LinearLayout? = null
    private var powerButton: TextView? = null
    private var progressBox: LinearLayout? = null
    private var progressBar: android.widget.ProgressBar? = null
    private var progressText: TextView? = null
    private var comfyOn = true

    // Quick edit
    private var editName: String? = null
    private var editWf: JSONObject? = null
    private var editDirty = false
    private var batch = 1
    private val nodeInfo = HashMap<String, JSONObject?>()

    // Editor JS hand-off
    private var pendingJs: String? = null
    private var pendingCb: ((String) -> Unit)? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.hideFromRecents(this)
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        editorBack = Ui.button(this, "◀ App", Ui.BLUE).apply {
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            alpha = 0.85f
            setOnClickListener { show(Screen.HOME) }
        }
        root.addView(editorBack, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(10); bottomMargin = dp(10)
        })
        setContentView(root)
        if (ComfyApi.isPaired(this)) {
            setupWeb()
            show(Screen.HOME)
            // Opened from the image viewer's "Make new workflow from this image".
            intent.getStringExtra("openWorkflow")?.let { openEdit(it) }
        } else {
            show(Screen.SETUP)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SyncManager.start(this)
        web?.onResume()
        if (screen == Screen.HOME) poll()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacksAndMessages(POLL)
        web?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        web?.destroy()
    }

    private fun show(s: Screen) {
        screen = s
        main.removeCallbacksAndMessages(POLL)
        val editor = s == Screen.EDITOR
        content.visibility = if (editor) View.GONE else View.VISIBLE
        editorBack.visibility = if (editor) View.VISIBLE else View.GONE
        web?.visibility = if (editor) View.VISIBLE else View.INVISIBLE
        if (editor) return
        content.removeAllViews()
        statusText = null; syncText = null; workflowsBox = null; powerButton = null
        progressBox = null; progressBar = null; progressText = null
        when (s) {
            Screen.SETUP -> renderSetup()
            Screen.HOME -> renderHome()
            Screen.EDIT -> renderEdit()
            Screen.RECENT -> renderRecent()
            Screen.EDITOR -> {}
        }
    }

    // ================================================================= setup

    private fun renderSetup() {
        content.addView(Ui.text(this, "Connect to your PC", 24f))
        content.addView(Ui.text(
            this,
            "1. Tailscale on, on this phone and the PC (same account).\n" +
                "2. On the PC, the bridge setup prints an Address and a Code.\n" +
                "   Show them again any time on the PC: http://127.0.0.1:8190/isb/local/pair",
            14f, Ui.TEXT_DIM
        ), lp(top = 8))
        val addr = edit("Address, e.g. 100.101.102.103", ComfyApi.address(this).substringBefore(":${ComfyApi.PORT}"))
        val code = edit("Code, e.g. ABCD-EFGH-JKLM-NPQR", ComfyApi.code(this)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        content.addView(addr, lp(top = 16))
        content.addView(code, lp(top = 8))
        val msg = Ui.text(this, "", 14f, Ui.TEXT_DIM)
        val btn = Ui.button(this, "Connect", Ui.GREEN)
        btn.setOnClickListener {
            val a = addr.text.toString().trim()
            val c = code.text.toString().trim()
            if (a.isEmpty() || c.isEmpty()) { msg.text = "Fill in both."; return@setOnClickListener }
            ComfyApi.savePairing(this, a, c)
            msg.text = "Connecting…"
            btn.isEnabled = false
            ComfyApi.bg({ ComfyApi.get(this, "/isb/status") }) { r ->
                btn.isEnabled = true
                r.onSuccess {
                    toast("Connected")
                    setupWeb()
                    SyncManager.start(this)
                    show(Screen.HOME)
                }.onFailure { msg.text = ComfyApi.explain(it) }
            }
        }
        content.addView(btn, lp(top = 16))
        content.addView(msg, lp(top = 12))
    }

    private fun edit(hint: String, value: String = "", multi: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT_DIM)
        textSize = 15f
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
    }

    // ================================================================= home

    private fun renderHome() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        box.addView(Ui.text(this, "appgentic5", 24f))
        statusText = Ui.text(this, "Connecting…", 14f, Ui.TEXT_DIM)
        box.addView(statusText, lp(top = 4))
        val pb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = Ui.rounded(this@ComfyActivity, Ui.SURFACE, 10)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
        }
        val ptxt = Ui.text(this, "", 14f)
        pb.addView(ptxt)
        pb.addView(bar, lp(top = 4))
        progressBox = pb; progressBar = bar; progressText = ptxt
        box.addView(pb, lp(top = 6))
        syncText = Ui.text(this, "", 13f, Ui.TEXT_DIM)
        box.addView(syncText, lp(top = 2))

        fun row(vararg b: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEach {
                it.textSize = 14f
                addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
            }
        }
        box.addView(row(
            Ui.button(this, "🖌 Full editor", Ui.BLUE).apply { setOnClickListener { openEditor() } },
            Ui.button(this, "📂 Sort images", Ui.GREEN).apply { setOnClickListener { openSorter() } },
        ), lp(top = 12))
        box.addView(row(
            Ui.button(this, "⇅ Image folders", Ui.GREY).apply { setOnClickListener { foldersDialog() } },
            Ui.button(this, "🕘 Recent runs", Ui.GREY).apply { setOnClickListener { show(Screen.RECENT) } },
        ), lp(top = 6))
        box.addView(row(
            Ui.button(this, "⏹ Stop current", Ui.GREY).apply {
                setOnClickListener { simplePost("/api/interrupt", "{}", "Stopped current job") }
            },
            Ui.button(this, "🧹 Clear waiting", Ui.GREY).apply {
                setOnClickListener { simplePost("/api/queue", "{\"clear\":true}", "Cleared waiting jobs") }
            },
        ), lp(top = 6))
        val power = Ui.button(this, "⏻ Stop appgentic5", Ui.RED).apply { setOnClickListener { powerDialog() } }
        powerButton = power
        box.addView(row(power), lp(top = 6))

        box.addView(Ui.text(this, "Workflows", 20f), lp(top = 20))
        box.addView(Ui.text(this, "Tap to edit & generate.", 13f, Ui.TEXT_DIM))
        workflowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(workflowsBox, lp(top = 6))

        box.addView(row(
            Ui.button(this, "↻ Restart appgentic5", Ui.GREY).apply { setOnClickListener { restartDialog() } },
            Ui.button(this, "⬆ Update PC helper", Ui.GREY).apply { setOnClickListener { updateHelper() } },
        ), lp(top = 24))
        box.addView(row(
            Ui.button(this, "Unpair", Ui.GREY).apply { setOnClickListener { unpairDialog() } },
        ), lp(top = 6))
        updatePower()

        loadWorkflows()
        poll()
    }

    private val POLL = Any()

    private fun poll() {
        main.removeCallbacksAndMessages(POLL)
        if (!resumed || screen != Screen.HOME) return
        ComfyApi.bg({
            val st = JSONObject(ComfyApi.get(this, "/isb/status"))
            val q = if (st.optBoolean("comfy_running")) runCatching { JSONObject(ComfyApi.get(this, "/api/queue")) }.getOrNull() else null
            val running = (q?.optJSONArray("queue_running")?.length() ?: 0) > 0
            progress = if (running) {
                try { JSONObject(ComfyApi.get(this, "/api/isb_progress")) }
                catch (e: ComfyApi.HttpError) { if (e.code == 404) JSONObject().put("missing", true) else null }
                catch (_: Exception) { null }
            } else null
            st to q
        }) { r ->
            val t = statusText ?: return@bg
            r.onSuccess { (st, q) ->
                comfyOn = st.optBoolean("comfy_on", true)
                updatePower()
                t.text = when {
                    !comfyOn && !st.optBoolean("comfy_running") -> "⚪ appgentic5 is switched off (PC memory is free)"
                    !comfyOn -> "⚪ Switched off, but another appgentic5 (desktop app?) is running"
                    st.optBoolean("comfy_running") -> {
                        val run = q?.optJSONArray("queue_running")?.length() ?: 0
                        val wait = q?.optJSONArray("queue_pending")?.length() ?: 0
                        "🟢 appgentic5 ready · " + if (run + wait == 0) "idle" else "running $run, waiting $wait"
                    }
                    st.optBoolean("comfy_starting") -> "🟡 appgentic5 is starting on the PC…"
                    else -> "🔴 appgentic5 not running. " + st.optString("last_error")
                }
                t.setTextColor(Ui.TEXT)
            }.onFailure {
                t.text = "🔴 " + ComfyApi.explain(it)
                t.setTextColor(Ui.RED)
            }
            syncText?.text = buildString {
                append("Moved to phone: ${SyncManager.downloaded}")
                if (SyncManager.waitingOnPc > 0) append(" · waiting on PC: ${SyncManager.waitingOnPc}")
                SyncManager.lastError?.let { append("\nSync: $it") }
            }
            val busy = showProgress(progress)
            main.postAtTime({ poll() }, POLL, android.os.SystemClock.uptimeMillis() + if (busy) 1500 else 3000)
        }
    }

    @Volatile private var progress: JSONObject? = null

    private fun fmtSecs(s: Double): String {
        val t = s.toLong()
        return if (t >= 60) "${t / 60}m ${t % 60}s" else "${t}s"
    }

    /** Fill the progress card; returns true while something is running. */
    private fun showProgress(p: JSONObject?): Boolean {
        val box = progressBox ?: return false
        val bar = progressBar ?: return false
        val txt = progressText ?: return false
        if (p == null) { box.visibility = View.GONE; return false }
        box.visibility = View.VISIBLE
        if (p.optBoolean("missing")) {
            txt.text = "Live progress needs one appgentic5 restart (tap ↻ Restart appgentic5 below)."
            txt.setTextColor(Ui.TEXT_DIM)
            bar.visibility = View.GONE
            return true
        }
        if (!p.optBoolean("running")) {
            txt.text = "Starting…"
            txt.setTextColor(Ui.TEXT_DIM)
            bar.isIndeterminate = true
            bar.visibility = View.VISIBLE
            return true
        }
        val title = p.optString("node_title").ifEmpty { "Working" }
        val value = p.optDouble("value", 0.0)
        val max = p.optDouble("max", 0.0)
        val total = p.optInt("nodes_total")
        val done = p.optInt("nodes_done")
        val line1 = StringBuilder(title)
        if (max > 0) {
            val pct = (value * 100 / max).toInt()
            line1.append(" · step ${value.toInt()}/${max.toInt()} ($pct%)")
            if (p.has("node_eta")) line1.append(" · ~${fmtSecs(p.optDouble("node_eta"))} left")
            bar.isIndeterminate = false
            bar.progress = (value * 1000 / max).toInt()
        } else {
            bar.isIndeterminate = true
        }
        val line2 = buildString {
            if (total > 0) append("Node ${(done + 1).coerceAtMost(total)} of $total · ")
            append("${fmtSecs(p.optDouble("elapsed", 0.0))} so far")
        }
        txt.text = "$line1\n$line2"
        txt.setTextColor(Ui.TEXT)
        bar.visibility = View.VISIBLE
        return true
    }

    private fun simplePost(path: String, body: String, okMsg: String) {
        ComfyApi.bg({ ComfyApi.post(this, path, body) }) { r ->
            r.onSuccess { toast(okMsg); poll() }.onFailure { toast(ComfyApi.explain(it)) }
        }
    }

    private data class Wf(val path: String, val modified: Double)

    private fun loadWorkflows() {
        val box = workflowsBox ?: return
        box.removeAllViews()
        box.addView(Ui.text(this, "Loading…", 14f, Ui.TEXT_DIM))
        ComfyApi.bg({
            val arr = JSONArray(ComfyApi.get(this, "/api/userdata?dir=workflows&recurse=true&full_info=true&split=false"))
            (0 until arr.length()).map { i ->
                val o = arr.opt(i)
                if (o is JSONObject) Wf(o.optString("path"), o.optDouble("modified", 0.0)) else Wf(o.toString(), 0.0)
            }.filter { it.path.endsWith(".json") }.sortedByDescending { it.modified }
        }) { r ->
            if (workflowsBox !== box) return@bg
            box.removeAllViews()
            r.onSuccess { list ->
                if (list.isEmpty()) box.addView(Ui.text(this, "No saved workflows yet. Save one in appgentic5 first.", 14f, Ui.TEXT_DIM))
                list.forEach { box.addView(workflowRow(it), lp(top = 6)) }
            }.onFailure {
                box.addView(Ui.text(this, ComfyApi.explain(it), 14f, Ui.RED))
                box.addView(Ui.button(this, "Try again", Ui.GREY).apply { setOnClickListener { loadWorkflows() } }, lp(top = 6))
            }
        }
    }

    private fun workflowRow(w: Wf): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@ComfyActivity, Ui.SURFACE, 10)
        }
        line.addView(TextView(this).apply {
            text = w.path.removeSuffix(".json")
            textSize = 16f
            setTextColor(Ui.TEXT)
            setPadding(dp(14), dp(14), dp(8), dp(14))
            setOnClickListener { openEdit(w.path) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun small(label: String, onClick: () -> Unit) = Ui.button(this, label, Ui.GREY).apply {
            textSize = 13f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }
        line.addView(small("Copy") { duplicateDialog(w.path) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) })
        line.addView(small("⋮") { moreDialog(w.path) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        return line
    }

    private fun wfUrl(path: String) = "/api/userdata/" + ComfyApi.enc("workflows/$path")

    private fun askName(title: String, start: String, onOk: (String) -> Unit) {
        val input = edit("Name", start).apply { selectAll() }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ ->
                var n = input.text.toString().trim().replace("\\", "/").trim('/')
                if (n.isEmpty() || n.split('/').any { it == ".." || it == "." }) return@setPositiveButton
                if (!n.endsWith(".json")) n += ".json"
                onOk(n)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun duplicateDialog(path: String) {
        askName("Copy \"${path.removeSuffix(".json")}\" as", path.removeSuffix(".json") + " copy") { newPath ->
            ComfyApi.bg({
                val wf = JSONObject(ComfyApi.get(this, wfUrl(path)))
                wf.put("id", UUID.randomUUID().toString())
                ComfyApi.post(this, wfUrl(newPath) + "?overwrite=false", wf.toString())
            }) { r ->
                r.onSuccess { toast("Copied"); loadWorkflows() }
                    .onFailure { toast(if ((it as? ComfyApi.HttpError)?.code == 409) "That name already exists" else ComfyApi.explain(it)) }
            }
        }
    }

    private fun moreDialog(path: String) {
        AlertDialog.Builder(this).setTitle(path.removeSuffix(".json"))
            .setItems(arrayOf("Open in full editor", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> ComfyApi.bg({ JSONObject(ComfyApi.get(this, wfUrl(path))) }) { r ->
                        r.onSuccess { wf -> openEditor(wf, path) }.onFailure { toast(ComfyApi.explain(it)) }
                    }
                    1 -> askName("Rename to", path.removeSuffix(".json")) { newPath ->
                        ComfyApi.bg({
                            ComfyApi.post(this, wfUrl(path) + "/move/" + ComfyApi.enc("workflows/$newPath") + "?overwrite=false", "")
                        }) { r ->
                            r.onSuccess { loadWorkflows() }
                                .onFailure { toast(if ((it as? ComfyApi.HttpError)?.code == 409) "That name already exists" else ComfyApi.explain(it)) }
                        }
                    }
                    2 -> AlertDialog.Builder(this).setTitle("Delete ${path.removeSuffix(".json")}?")
                        .setMessage("The workflow file is deleted from the PC. Images are not affected.")
                        .setPositiveButton("Delete") { _, _ ->
                            ComfyApi.bg({ ComfyApi.request(this, "DELETE", wfUrl(path)) }) { r ->
                                r.onSuccess { loadWorkflows() }.onFailure { toast(ComfyApi.explain(it)) }
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun shortPath(f: File) = f.absolutePath.replace(android.os.Environment.getExternalStorageDirectory().absolutePath, "Phone")

    private fun foldersDialog() {
        ComfyApi.bg({ JSONArray(ComfyApi.get(this, "/isb/folders")) }) { r ->
            r.onFailure { toast(ComfyApi.explain(it)) }
            r.onSuccess { arr ->
                if (arr.length() == 0) { toast("No subfolders in the PC's output folder yet"); return@onSuccess }
                val names = Array(arr.length()) { arr.getJSONObject(it).getString("name") }
                val checked = BooleanArray(arr.length()) { arr.getJSONObject(it).optBoolean("sync") }

                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                list.addView(Ui.text(
                    this,
                    "Tick PC folders to move to the phone (they're deleted from the PC once copied). " +
                        "Tap the → line to choose where on the phone they go.",
                    13f, Ui.TEXT_DIM
                ))
                for (i in names.indices) {
                    val name = names[i]
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = Ui.rounded(this@ComfyActivity, Ui.SURFACE, 8)
                        setPadding(dp(8), dp(4), dp(8), dp(8))
                    }
                    val cb = CheckBox(this).apply {
                        text = "$name  (${arr.getJSONObject(i).optInt("count")})"
                        textSize = 16f
                        setTextColor(Ui.TEXT)
                        isChecked = checked[i]
                    }
                    val dest = TextView(this).apply {
                        textSize = 13f
                        setPadding(dp(36), dp(2), dp(4), dp(6))
                    }
                    fun showDest() {
                        dest.text = "→ " + shortPath(SyncManager.destFor(this, name)) + "   (change)"
                        dest.setTextColor(if (cb.isChecked) Color.parseColor("#8AB4F8") else Ui.TEXT_DIM)
                    }
                    cb.setOnCheckedChangeListener { _, on -> checked[i] = on; showDest() }
                    dest.setOnClickListener {
                        FolderPicker(this, "Phone folder for \"$name\"", SyncManager.destFor(this, name)) { f ->
                            SyncManager.setDest(this, name, f)
                            showDest()
                        }.show()
                    }
                    showDest()
                    card.addView(cb)
                    card.addView(dest)
                    list.addView(card, lp(top = 6))
                }
                val box = ScrollView(this).apply {
                    setPadding(dp(16), dp(8), dp(16), 0)
                    addView(list)
                }
                AlertDialog.Builder(this)
                    .setTitle("Move images to phone")
                    .setView(box)
                    .setPositiveButton("Save") { _, _ ->
                        val sel = JSONArray()
                        names.indices.filter { checked[it] }.forEach { sel.put(names[it]) }
                        ComfyApi.bg({ ComfyApi.post(this, "/isb/config", JSONObject().put("sync_folders", sel).toString()) }) { rr ->
                            rr.onSuccess {
                                val lines = names.indices.filter { checked[it] }
                                    .joinToString("\n") { "• ${names[it]} → ${shortPath(SyncManager.destFor(this, names[it]))}" }
                                AlertDialog.Builder(this).setTitle("Saved")
                                    .setMessage(
                                        if (sel.length() == 0) "Nothing moves to the phone."
                                        else "While the app is open, new images move like this:\n\n$lines\n\n" +
                                            "They're deleted from the PC once safely copied. Unticked folders stay on the PC."
                                    )
                                    .setPositiveButton("OK", null).show()
                            }.onFailure { toast(ComfyApi.explain(it)) }
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun openSorter() {
        val dir = (SyncManager.lastDir?.takeIf { it.isDirectory } ?: SyncManager.phoneRoot()).apply { mkdirs() }
        startActivity(
            Intent(this, MainActivity::class.java).putExtra("browse", dir.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun updatePower() {
        val b = powerButton ?: return
        b.text = if (comfyOn) "⏻ Stop appgentic5 (free PC memory)" else "▶ Start appgentic5"
        b.background = Ui.rounded(this, if (comfyOn) Ui.RED else Ui.GREEN)
    }

    private fun powerDialog() {
        if (!comfyOn) {
            simplePost("/isb/start", "{}", "Starting appgentic5… takes about a minute")
            comfyOn = true
            updatePower()
            return
        }
        AlertDialog.Builder(this).setTitle("Stop appgentic5 on the PC?")
            .setMessage("Frees the PC's memory and graphics card. Anything running is stopped.\n\n" +
                "It stays off (even after a PC restart) until you tap Start here, or use the " +
                "\"on-off\" shortcut on the PC's desktop. Image moving to the phone keeps working.")
            .setPositiveButton("Stop") { _, _ ->
                simplePost("/isb/stop", "{}", "appgentic5 stopped")
                comfyOn = false
                updatePower()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateHelper() {
        AlertDialog.Builder(this).setTitle("Update the PC helper?")
            .setMessage("Downloads the latest helper from GitHub onto the PC and restarts it. " +
                "appgentic5 keeps running; the connection drops for a few seconds.")
            .setPositiveButton("Update") { _, _ ->
                ComfyApi.bg({ JSONObject(ComfyApi.post(this, "/isb/update", "{}")) }) { r ->
                    r.onSuccess { toast("PC helper updated: ${it.optString("old")} → ${it.optString("new")}") }
                        .onFailure {
                            toast(if ((it as? ComfyApi.HttpError)?.code == 404)
                                "This PC helper is too old to update itself. Run the setup line on the PC once."
                            else ComfyApi.explain(it))
                        }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun restartDialog() {
        AlertDialog.Builder(this).setTitle("Restart appgentic5 on the PC?")
            .setMessage("Anything running now is stopped. It takes about a minute to come back.")
            .setPositiveButton("Restart") { _, _ -> simplePost("/isb/restart", "{}", "Restarting appgentic5…") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun unpairDialog() {
        AlertDialog.Builder(this).setTitle("Unpair from the PC?")
            .setMessage("You'll need the address and code again to reconnect.")
            .setPositiveButton("Unpair") { _, _ ->
                ComfyApi.forget(this)
                CookieManager.getInstance().removeAllCookies(null)
                web?.let { root.removeView(it); it.destroy() }
                web = null
                show(Screen.SETUP)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ================================================================= quick edit

    private fun openEdit(path: String) {
        editName = path
        editWf = null
        editDirty = false
        batch = 1
        show(Screen.EDIT)
        ComfyApi.bg({
            val wf = JSONObject(ComfyApi.get(this, wfUrl(path)))
            val nodes = wf.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nodes.length()) {
                val type = nodes.optJSONObject(i)?.optString("type") ?: continue
                if (type.isEmpty() || nodeInfo.containsKey(type)) continue
                nodeInfo[type] = runCatching {
                    JSONObject(ComfyApi.get(this, "/api/object_info/" + ComfyApi.enc(type))).optJSONObject(type)
                }.getOrNull()
            }
            wf
        }) { r ->
            if (screen != Screen.EDIT || editName != path) return@bg
            r.onSuccess { editWf = it; show(Screen.EDIT) }.onFailure { toast(ComfyApi.explain(it)); show(Screen.HOME) }
        }
    }

    private class Slot(val label: String, val options: List<String>?, val control: Boolean, val hidden: Boolean)

    private val controlValues = listOf("fixed", "increment", "decrement", "randomize")
    private val plainTypes = setOf("INT", "FLOAT", "STRING", "BOOLEAN")

    /** Widget names for a node type, in widgets_values order; null if unknown. */
    private fun slotsFor(info: JSONObject?): List<Slot>? {
        val input = info?.optJSONObject("input") ?: return null
        val order = info.optJSONObject("input_order")
        val names = ArrayList<Pair<String, JSONArray>>()
        for (group in listOf("required", "optional")) {
            val g = input.optJSONObject(group) ?: continue
            val keys = order?.optJSONArray(group)?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: g.keys().asSequence().toList()
            keys.forEach { k -> g.optJSONArray(k)?.let { names.add(k to it) } }
        }
        val slots = ArrayList<Slot>()
        for ((name, spec) in names) {
            val t = spec.opt(0)
            val opts = spec.optJSONObject(1)
            if (opts?.optBoolean("forceInput") == true) continue
            val label = opts?.optString("display_name")?.takeIf { it.isNotEmpty() } ?: name.replace('_', ' ')
            when {
                t is JSONArray -> slots.add(Slot(label, (0 until t.length()).map { t.optString(it) }, false, false))
                t == "COMBO" -> {
                    val o = opts?.optJSONArray("options")
                    slots.add(Slot(label, o?.let { a -> (0 until a.length()).map { a.optString(it) } }, false, false))
                }
                t is String && t in plainTypes -> slots.add(Slot(label, null, false, false))
                else -> continue
            }
            if (opts != null && (opts.optBoolean("image_upload") || opts.optBoolean("video_upload") || opts.optBoolean("audio_upload"))) {
                slots.add(Slot("upload", null, false, true))
            }
            val wantsControl = opts?.optBoolean("control_after_generate") == true ||
                (t == "INT" && (name == "seed" || name == "noise_seed"))
            if (wantsControl) slots.add(Slot("$label (after generate)", controlValues, true, false))
        }
        return slots
    }

    private fun renderEdit() {
        val name = editName ?: return show(Screen.HOME)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.button(this, "◀", Ui.GREY).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { leaveEdit() }
        })
        header.addView(Ui.text(this, name.removeSuffix(".json"), 20f).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)

        val wf = editWf
        if (wf == null) {
            content.addView(Ui.text(this, "Loading…", 15f, Ui.TEXT_DIM), lp(top = 16))
            return
        }

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        val nodes = wf.optJSONArray("nodes") ?: JSONArray()
        val list = (0 until nodes.length()).mapNotNull { nodes.optJSONObject(it) }.sortedWith(
            compareBy<JSONObject>({ it.optJSONArray("pos")?.optDouble(1) ?: 0.0 }, { it.optJSONArray("pos")?.optDouble(0) ?: 0.0 })
        )
        var shown = 0
        for (node in list) {
            val card = nodeCard(node) ?: continue
            box.addView(card, lp(top = 8))
            shown++
        }
        if (shown == 0) box.addView(Ui.text(this, "No editable values. Use the full editor.", 14f, Ui.TEXT_DIM))
        if ((wf.optJSONObject("definitions")?.optJSONArray("subgraphs")?.length() ?: 0) > 0) {
            box.addView(Ui.text(this, "Values inside subgraphs can only be changed in the full editor.", 13f, Ui.TEXT_DIM), lp(top = 8))
        }
        box.addView(Ui.button(this, "Open in full editor", Ui.GREY).apply {
            setOnClickListener { saveEdit { openEditor(wf, name) } }
        }, lp(top = 16))

        // Bottom bar: batch + save + generate
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val count = Ui.text(this, "×$batch", 18f).apply { gravity = Gravity.CENTER; minWidth = dp(44) }
        fun step(label: String, d: Int) = Ui.button(this, label, Ui.GREY).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { batch = (batch + d).coerceIn(1, 64); count.text = "×$batch" }
        }
        bar.addView(step("−", -1))
        bar.addView(count)
        bar.addView(step("+", 1))
        bar.addView(Ui.button(this, "Save", Ui.GREY).apply {
            textSize = 14f
            setOnClickListener { saveEdit { toast("Saved") } }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        val gen = Ui.button(this, "▶ Generate", Ui.GREEN).apply { textSize = 14f }
        gen.setOnClickListener { generate(gen) }
        bar.addView(gen, LinearLayout.LayoutParams(0, -2, 1.4f).apply { leftMargin = dp(6) })
        content.addView(bar, lp(top = 8))
    }

    private fun nodeCard(node: JSONObject): View? {
        val type = node.optString("type")
        val info = nodeInfo[type]
        val values = node.opt("widgets_values")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@ComfyActivity, Ui.SURFACE, 10)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val title = node.optString("title").ifEmpty { info?.optString("display_name")?.ifEmpty { null } ?: type }
        val mode = when (node.optInt("mode")) { 2 -> "  (muted)"; 4 -> "  (bypassed)"; else -> "" }
        card.addView(Ui.text(this, title + mode, 15f).apply { typeface = Typeface.DEFAULT_BOLD })
        var fields = 0
        when (values) {
            is JSONArray -> {
                val slots = slotsFor(info)?.takeIf { it.size == values.length() }
                for (i in 0 until values.length()) {
                    val s = slots?.get(i)
                    if (s?.hidden == true) continue
                    val v = values.opt(i)
                    val label = s?.label ?: "value ${i + 1}"
                    field(label, v, s?.options, s?.control == true) { values.put(i, it); editDirty = true }
                        ?.let { card.addView(it, lp(top = 6)); fields++ }
                }
            }
            is JSONObject -> {
                for (k in values.keys().asSequence().toList()) {
                    field(k.replace('_', ' '), values.opt(k), null, false) { values.put(k, it); editDirty = true }
                        ?.let { card.addView(it, lp(top = 6)); fields++ }
                }
            }
        }
        return if (fields > 0) card else null
    }

    /** One labelled editor for a value, or null if the value type isn't editable here. */
    private fun field(label: String, v: Any?, options: List<String>?, control: Boolean, set: (Any) -> Unit): View? {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(Ui.text(this, label, 12f, Ui.TEXT_DIM))
        when (v) {
            is Boolean -> {
                val cb = CheckBox(this).apply {
                    text = if (v) "on" else "off"
                    isChecked = v
                    setTextColor(Ui.TEXT)
                    setOnCheckedChangeListener { b, on -> b.text = if (on) "on" else "off"; set(on) }
                }
                wrap.addView(cb)
            }
            is Number -> {
                val isInt = v is Int || v is Long
                val e = edit("", if (isInt) v.toLong().toString() else v.toDouble().toString()).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or
                        (if (isInt) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL)
                }
                e.addTextChangedListener(watcher { s ->
                    if (isInt) s.toLongOrNull()?.let(set) else s.toDoubleOrNull()?.let(set)
                })
                wrap.addView(e)
            }
            is String -> {
                val choices = when {
                    control || (options == null && v in controlValues && label.contains("after")) -> controlValues
                    options != null -> if (v in options) options else listOf(v) + options
                    else -> null
                }
                if (choices != null) {
                    val sp = Spinner(this)
                    sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices)
                    sp.setSelection(choices.indexOf(v).coerceAtLeast(0))
                    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            if (choices[pos] != v) set(choices[pos])
                        }
                        override fun onNothingSelected(p: AdapterView<*>?) {}
                    }
                    wrap.addView(sp)
                } else {
                    val multi = v.length > 40 || v.contains('\n') || label.contains("text", true) || label.contains("prompt", true)
                    val e = edit("", v, multi)
                    e.addTextChangedListener(watcher { set(it) })
                    wrap.addView(e)
                }
            }
            else -> return null
        }
        return wrap
    }

    private fun watcher(on: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = on(s?.toString() ?: "")
    }

    private fun saveEdit(then: () -> Unit) {
        val name = editName ?: return
        val wf = editWf ?: return
        if (!editDirty) { then(); return }
        ComfyApi.bg({ ComfyApi.post(this, wfUrl(name) + "?overwrite=true", wf.toString()) }) { r ->
            r.onSuccess { editDirty = false; then() }.onFailure { toast("Couldn't save: " + ComfyApi.explain(it)) }
        }
    }

    private val rng = java.util.Random()

    /** Returns true if any seed value changed. */
    private fun bumpSeeds(wf: JSONObject): Boolean {
        var changed = false
        val nodes = wf.optJSONArray("nodes") ?: return false
        for (n in 0 until nodes.length()) {
            val node = nodes.optJSONObject(n) ?: continue
            if (node.optInt("mode") == 2 || node.optInt("mode") == 4) continue
            val values = node.optJSONArray("widgets_values") ?: continue
            val slots = slotsFor(nodeInfo[node.optString("type")])?.takeIf { it.size == values.length() }
            for (i in 1 until values.length()) {
                val ctrl = values.opt(i) as? String ?: continue
                val isControl = slots?.get(i)?.control ?: (ctrl in controlValues)
                if (!isControl) continue
                val cur = values.opt(i - 1) as? Number ?: continue
                val next = when (ctrl) {
                    "randomize" -> (rng.nextLong() and 0x3FFFFFFFFFFFFL)  // up to 2^50, like appgentic5
                    "increment" -> cur.toLong() + 1
                    "decrement" -> (cur.toLong() - 1).coerceAtLeast(0)
                    else -> continue
                }
                values.put(i - 1, next)
                changed = true
            }
        }
        return changed
    }

    private fun generate(btn: TextView) {
        val name = editName ?: return
        val wf = editWf ?: return
        btn.isEnabled = false
        btn.text = "Sending…"
        // Apply each seed's "control after generate" (randomize / increment / decrement) now, so every
        // Generate is a new job. Otherwise appgentic5 sees the same seed and just returns the cached images.
        if (bumpSeeds(wf)) editDirty = true
        saveEdit {
            runInEditor(wf, name, batch) { msg ->
                btn.isEnabled = true
                btn.text = "▶ Generate"
                if (msg == "queued") toast("Queued ×$batch")
                else AlertDialog.Builder(this).setTitle("Couldn't start")
                    .setMessage(msg.removePrefix("error: "))
                    .setPositiveButton("Open full editor") { _, _ -> show(Screen.EDITOR) }
                    .setNegativeButton("OK", null).show()
            }
        }
    }

    private fun leaveEdit() {
        if (!editDirty) { show(Screen.HOME); return }
        AlertDialog.Builder(this).setTitle("Save changes?")
            .setPositiveButton("Save") { _, _ -> saveEdit { show(Screen.HOME) } }
            .setNegativeButton("Discard") { _, _ -> editDirty = false; show(Screen.HOME) }
            .setNeutralButton("Cancel", null).show()
    }

    // ================================================================= recent runs

    private fun renderRecent() {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.button(this, "◀", Ui.GREY).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { show(Screen.HOME) }
        })
        header.addView(Ui.text(this, "Recent runs", 20f).apply { setPadding(dp(12), 0, 0, 0) })
        content.addView(header)
        content.addView(Ui.text(this, "Every run remembers its workflow. Save one to get it back as an editable workflow.", 13f, Ui.TEXT_DIM), lp(top = 6))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        box.addView(Ui.text(this, "Loading…", 14f, Ui.TEXT_DIM))
        ComfyApi.bg({ JSONObject(ComfyApi.get(this, "/api/history?max_items=40")) }) { r ->
            if (screen != Screen.RECENT) return@bg
            box.removeAllViews()
            r.onFailure { box.addView(Ui.text(this, ComfyApi.explain(it), 14f, Ui.RED)) }
            r.onSuccess { hist ->
                val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                val items = hist.keys().asSequence().toList().reversed()
                var n = 0
                for (id in items) {
                    val item = hist.optJSONObject(id) ?: continue
                    val wf = item.optJSONArray("prompt")?.optJSONObject(3)
                        ?.optJSONObject("extra_pnginfo")?.optJSONObject("workflow") ?: continue
                    var ts = 0L
                    val msgs = item.optJSONObject("status")?.optJSONArray("messages")
                    if (msgs != null) for (i in 0 until msgs.length()) {
                        val m = msgs.optJSONArray(i) ?: continue
                        if (m.optString(0) == "execution_start") ts = m.optJSONObject(1)?.optLong("timestamp") ?: 0L
                    }
                    var firstImage = ""
                    item.optJSONObject("outputs")?.let { outs ->
                        for (k in outs.keys()) {
                            val img = outs.optJSONObject(k)?.optJSONArray("images")?.optJSONObject(0) ?: continue
                            firstImage = listOf(img.optString("subfolder"), img.optString("filename")).filter { it.isNotEmpty() }.joinToString("/")
                            break
                        }
                    }
                    val ok = item.optJSONObject("status")?.optString("status_str") == "success"
                    val label = (if (ts > 0) fmt.format(Date(ts)) else id.take(8)) +
                        (if (ok) "" else "  ⚠") + "\n" + firstImage.ifEmpty { "(no image)" }
                    box.addView(TextView(this).apply {
                        text = label
                        textSize = 15f
                        setTextColor(Ui.TEXT)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                        background = Ui.rounded(this@ComfyActivity, Ui.SURFACE, 10)
                        setOnClickListener { recentActions(wf, firstImage) }
                    }, lp(top = 6))
                    n++
                }
                if (n == 0) box.addView(Ui.text(this, "No recent runs with a saved workflow.", 14f, Ui.TEXT_DIM))
            }
        }
    }

    private fun recentActions(wf: JSONObject, image: String) {
        val suggested = image.substringAfterLast('/').substringBefore('_').ifEmpty { "recent" }
        AlertDialog.Builder(this).setTitle("This run's workflow")
            .setItems(arrayOf("Save as a workflow", "Open in full editor")) { _, which ->
                when (which) {
                    0 -> askName("Save as", suggested) { path ->
                        val copy = JSONObject(wf.toString()).put("id", UUID.randomUUID().toString())
                        ComfyApi.bg({ ComfyApi.post(this, wfUrl(path) + "?overwrite=false", copy.toString()) }) { r ->
                            r.onSuccess { toast("Saved"); openEdit(path) }
                                .onFailure { toast(if ((it as? ComfyApi.HttpError)?.code == 409) "That name already exists" else ComfyApi.explain(it)) }
                        }
                    }
                    1 -> openEditor(wf, null)
                }
            }.show()
    }

    // ================================================================= full editor (WebView)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWeb() {
        if (web != null) return
        val base = ComfyApi.base(this)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setCookie(base, "isb_token=${ComfyApi.code(this)}; Path=/")
        cm.flush()
        val w = WebView(this)
        w.setBackgroundColor(Color.parseColor("#202020"))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }
        cm.setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(JsBridge(), "IsbBridge")
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith(base)) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pendingJs?.let { view.evaluateJavascript(it, null) }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(params.createIntent(), FILE_REQ); true
                } catch (_: Exception) {
                    fileCb = null; false
                }
            }
        }
        w.visibility = View.INVISIBLE
        root.addView(w, 0, FrameLayout.LayoutParams(-1, -1))
        web = w
        w.loadUrl("$base/", mapOf("X-ISB-Token" to ComfyApi.code(this)))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQ) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun done(msg: String) {
            runOnUiThread {
                pendingJs = null
                val cb = pendingCb
                pendingCb = null
                cb?.invoke(msg)
            }
        }
    }

    private fun openEditor(wf: JSONObject? = null, name: String? = null) {
        setupWeb()
        show(Screen.EDITOR)
        if (wf != null) runInEditor(wf, name, 0) { msg -> if (msg.startsWith("error")) toast(msg) }
    }

    /** Load [wf] into the appgentic5 page and (if [queue] > 0) queue it [queue] times. */
    private fun runInEditor(wf: JSONObject, name: String?, queue: Int, cb: (String) -> Unit) {
        setupWeb()
        val w = web ?: return cb("error: not connected")
        pendingCb?.invoke("error: replaced by a newer request")
        pendingCb = cb
        val nameJs = if (name == null) "null" else JSONObject.quote(name.removeSuffix(".json"))
        val js = """
            (async () => {
              const done = (m) => { try { IsbBridge.done(String(m)); } catch (e) {} };
              if (window.__isbBusy) return; window.__isbBusy = true;
              try {
                let app = null;
                for (let i = 0; i < 180; i++) {
                  app = (window.comfyAPI && window.comfyAPI.app && window.comfyAPI.app.app) || window.app;
                  if (app && app.graph && app.canvas) break;
                  await new Promise(r => setTimeout(r, 500));
                }
                if (!app || !app.graph) { done('error: The appgentic5 page did not finish loading. Is appgentic5 running on the PC?'); return; }
                const wf = $wf;
                try { await app.loadGraphData(wf, true, true, $nameJs); }
                catch (e) { await app.loadGraphData(wf, true, true, null); }
                if ($queue > 0) {
                  await new Promise(r => setTimeout(r, 400));
                  const ok = await app.queuePrompt(0, $queue);
                  done(ok === false ? 'error: appgentic5 refused the job. Open the full editor to see the red error.' : 'queued');
                } else done('loaded');
              } catch (e) { done('error: ' + (e && e.message ? e.message : e)); }
              finally { window.__isbBusy = false; }
            })();
        """.trimIndent()
        pendingJs = js
        if (w.url == null) w.loadUrl(ComfyApi.base(this) + "/", mapOf("X-ISB-Token" to ComfyApi.code(this)))
        else w.evaluateJavascript(js, null)
        main.postDelayed({
            if (pendingCb === cb) {
                pendingJs = null
                pendingCb = null
                cb("error: Timed out waiting for appgentic5. Is it still starting on the PC?")
            }
        }, 120_000)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.EDITOR -> show(Screen.HOME)
            Screen.EDIT -> leaveEdit()
            Screen.RECENT -> show(Screen.HOME)
            else -> super.onBackPressed()
        }
    }

    companion object {
        private const val FILE_REQ = 77
    }
}
