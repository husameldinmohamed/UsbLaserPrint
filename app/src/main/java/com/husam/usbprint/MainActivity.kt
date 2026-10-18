package com.husam.usbprint

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.LinearLayout
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import com.husam.usbprint.render.ColorMode
import com.husam.usbprint.render.DocumentSource
import com.husam.usbprint.spl.JobOptions
import com.husam.usbprint.spl.PageBitmap
import com.husam.usbprint.spl.PaperSize
import com.husam.usbprint.spl.QpdlEncoder
import com.husam.usbprint.spl.TonerDensity
import com.husam.usbprint.usb.PrinterInfo
import com.husam.usbprint.usb.UsbPrinterConnection
import com.husam.usbprint.usb.UsbPrinterFinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var usb: UsbManager

    private var printer: PrinterInfo? = null
    private var doc: DocumentSource? = null
    private var docName: String = "document"
    private var busy = false

    private lateinit var printerStatus: TextView
    private lateinit var fileInfo: TextView
    private lateinit var thumb: ImageView
    private lateinit var pagesEdit: EditText
    private lateinit var copiesEdit: EditText
    private lateinit var duplexRadio: RadioButton
    private lateinit var duplexOptions: View
    private lateinit var reverseBacks: CheckBox
    private lateinit var rotateBacks: CheckBox
    private lateinit var paperSpin: Spinner
    private lateinit var qualitySpin: Spinner
    private lateinit var modeSpin: Spinner
    private lateinit var densitySpin: Spinner
    private lateinit var tonerSave: CheckBox
    private lateinit var btnPrint: Button
    private lateinit var progress: ProgressBar
    private lateinit var jobStatus: TextView
    private lateinit var previewLabel: TextView
    private lateinit var previewScroll: View
    private lateinit var previewStrip: LinearLayout

    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbCache = HashMap<Int, Bitmap>()   // accessed on worker thread only
    private var previewGen = 0
    private val previewRunnable = Runnable { updatePreview() }

    private val papers = PaperSize.values()

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_RAW = 2
        private const val REQ_SAVE = 3
        private const val PREFS = "settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager

        printerStatus = findViewById(R.id.printerStatus)
        fileInfo = findViewById(R.id.fileInfo)
        thumb = findViewById(R.id.thumb)
        pagesEdit = findViewById(R.id.pages)
        copiesEdit = findViewById(R.id.copies)
        duplexRadio = findViewById(R.id.duplex)
        duplexOptions = findViewById(R.id.duplexOptions)
        reverseBacks = findViewById(R.id.reverseBacks)
        rotateBacks = findViewById(R.id.rotateBacks)
        paperSpin = findViewById(R.id.paper)
        qualitySpin = findViewById(R.id.quality)
        modeSpin = findViewById(R.id.mode)
        densitySpin = findViewById(R.id.density)
        tonerSave = findViewById(R.id.tonerSave)
        btnPrint = findViewById(R.id.btnPrint)
        progress = findViewById(R.id.progress)
        jobStatus = findViewById(R.id.jobStatus)
        previewLabel = findViewById(R.id.previewLabel)
        previewScroll = findViewById(R.id.previewScroll)
        previewStrip = findViewById(R.id.previewStrip)

        pagesEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { schedulePreview() }
        })

        paperSpin.adapter = spinnerAdapter(papers.map { it.label })
        qualitySpin.adapter = spinnerAdapter(listOf("عادية 600 DPI", "عالية 1200 DPI (أبطأ)"))
        modeSpin.adapter = spinnerAdapter(listOf("نصوص ومستندات", "صور ورسومات"))
        densitySpin.adapter = spinnerAdapter(listOf("فاتح", "متوسط", "غامق"))
        loadSettings()

        findViewById<android.widget.RadioGroup>(R.id.sides).setOnCheckedChangeListener { _, _ ->
            duplexOptions.visibility = if (duplexRadio.isChecked) View.VISIBLE else View.GONE
            schedulePreview()
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshPrinter() }
        findViewById<Button>(R.id.btnPick).setOnClickListener { pickFile(REQ_PICK, arrayOf("application/pdf", "image/*")) }
        findViewById<Button>(R.id.btnRaw).setOnClickListener { pickFile(REQ_RAW, arrayOf("*/*")) }
        findViewById<Button>(R.id.btnTest).setOnClickListener { printTestPage() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { startSaveJob() }
        btnPrint.setOnClickListener { startPrint() }

        handleIntent(intent)
        refreshPrinter()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        saveSettings()
        worker.execute { doc?.close(); doc = null }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    // ------------------------------------------------------------------ UI helpers

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun setBusy(b: Boolean) = ui {
        busy = b
        btnPrint.isEnabled = !b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showError(msg: String) = ui {
        jobStatus.text = "❌ $msg"
        AlertDialog.Builder(this).setTitle("حصلت مشكلة").setMessage(msg).setPositiveButton("تمام", null).show()
    }

    private fun loadSettings() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        paperSpin.setSelection(p.getInt("paper", 0).coerceIn(0, papers.size - 1))
        qualitySpin.setSelection(p.getInt("quality", 0))
        modeSpin.setSelection(p.getInt("mode", 0))
        densitySpin.setSelection(p.getInt("density", 1))
        tonerSave.isChecked = p.getBoolean("tonerSave", false)
        reverseBacks.isChecked = p.getBoolean("reverseBacks", true)
        rotateBacks.isChecked = p.getBoolean("rotateBacks", false)
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("paper", paperSpin.selectedItemPosition)
            .putInt("quality", qualitySpin.selectedItemPosition)
            .putInt("mode", modeSpin.selectedItemPosition)
            .putInt("density", densitySpin.selectedItemPosition)
            .putBoolean("tonerSave", tonerSave.isChecked)
            .putBoolean("reverseBacks", reverseBacks.isChecked)
            .putBoolean("rotateBacks", rotateBacks.isChecked)
            .apply()
    }

    // ------------------------------------------------------------------ Printer

    private fun refreshPrinter() {
        val info = UsbPrinterFinder.find(usb)
        printer = info
        if (info == null) {
            printerStatus.text = "⚠️ مفيش طابعة متوصلة. وصّل الطابعة بالـ OTG وشغّلها، وبعدين دوس تحديث."
            return
        }
        printerStatus.text = "تم العثور على طابعة… في انتظار الإذن"
        UsbPrinterFinder.requestPermission(this, usb, info.device) { granted ->
            if (!granted) {
                ui { printerStatus.text = "⚠️ لازم توافق على إذن الوصول للطابعة. دوس تحديث وحاول تاني." }
                return@requestPermission
            }
            worker.execute {
                val id = try {
                    UsbPrinterConnection.open(usb, info).use { it.deviceId() }
                } catch (e: Exception) { null }
                val model = id?.let { parseField(it, "MDL", "MODEL") }
                    ?: info.device.productName ?: "طابعة USB"
                val cmd = id?.let { parseField(it, "CMD", "COMMAND SET") }
                ui {
                    printerStatus.text = buildString {
                        append("✅ متوصلة: ").append(model)
                        if (cmd != null) append("\nاللغات: ").append(cmd)
                        append("\nUSB: interface ${info.iface.id} / protocol ${info.iface.interfaceProtocol}")
                        if (cmd != null && !cmd.contains("SPL", true) && !cmd.contains("QPDL", true))
                            append("\n⚠️ الطابعة دي مش بتعلن دعم SPL، التطبيق معمول لـ HP Laser MFP 13x")
                    }
                }
            }
        }
    }

    private fun parseField(id: String, vararg keys: String): String? {
        for (part in id.split(';')) {
            val kv = part.split(':', limit = 2)
            if (kv.size == 2 && keys.any { it.equals(kv[0].trim(), true) }) return kv[1].trim()
        }
        return null
    }

    // ------------------------------------------------------------------ Files

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshPrinter()
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                uri?.let { loadDocument(it, intent.type) }
            }
            Intent.ACTION_VIEW -> intent.data?.let { loadDocument(it, intent.type) }
        }
    }

    private fun pickFile(req: Int, types: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (types.size == 1) types[0] else "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_PICK -> loadDocument(uri, contentResolver.getType(uri))
            REQ_RAW -> sendRaw(uri)
            REQ_SAVE -> finishSaveJob(uri)
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "document"
    }

    private fun loadDocument(uri: Uri, mime: String?) {
        if (busy) return
        fileInfo.text = "جاري فتح الملف…"
        val name = displayName(uri)
        worker.execute {
            try {
                doc?.close()
                doc = null
                thumbCache.values.forEach { it.recycle() }
                thumbCache.clear()
                val d = DocumentSource.open(this, uri, mime)
                doc = d
                docName = name.substringBeforeLast('.')
                val bmp = d.thumbnail(0, 320)
                ui {
                    fileInfo.text = "📄 $name\nعدد الصفحات: ${d.pageCount}"
                    thumb.setImageBitmap(bmp)
                    thumb.visibility = View.VISIBLE
                    pagesEdit.setText("")
                    schedulePreview()
                }
            } catch (e: Exception) {
                ui { fileInfo.text = "مفيش ملف"; thumb.visibility = View.GONE }
                showError("مقدرتش أفتح الملف: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ Preview

    private fun schedulePreview() {
        mainHandler.removeCallbacks(previewRunnable)
        mainHandler.postDelayed(previewRunnable, 400)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Show thumbnails of the selected pages (in duplex mode: labelled front/back per sheet). */
    private fun updatePreview() {
        val d = doc
        if (d == null) {
            previewLabel.visibility = View.GONE
            previewScroll.visibility = View.GONE
            return
        }
        val pages = try {
            PageRange.parse(pagesEdit.text.toString(), d.pageCount)
        } catch (e: IllegalArgumentException) {
            previewLabel.visibility = View.VISIBLE
            previewLabel.setTextColor(Color.rgb(198, 40, 40))
            previewLabel.text = "⚠️ ${e.message}"
            previewScroll.visibility = View.GONE
            return
        }
        val duplex = duplexRadio.isChecked
        val sheets = if (duplex) (pages.size + 1) / 2 else pages.size
        previewLabel.visibility = View.VISIBLE
        previewLabel.setTextColor(Color.rgb(90, 90, 90))
        previewLabel.text = if (duplex) "${pages.size} صفحة على $sheets ورقة (وش وضهر)"
                            else "${pages.size} صفحة على $sheets ورقة"

        val maxShown = 40
        val shown = pages.take(maxShown)
        val gen = ++previewGen
        val thumbW = dp(78)
        worker.execute {
            val bmps = shown.map { pg ->
                thumbCache.getOrPut(pg) { d.thumbnail(pg - 1, thumbW) }
            }
            ui {
                if (gen != previewGen || doc !== d) return@ui
                previewStrip.removeAllViews()
                shown.forEachIndexed { i, pg ->
                    val cap = if (duplex) "ورقة ${i / 2 + 1}\n${if (i % 2 == 0) "وش" else "ضهر"} • ص $pg" else "صفحة $pg"
                    previewStrip.addView(thumbView(bmps[i], cap, pg, d))
                }
                if (pages.size > maxShown) {
                    previewStrip.addView(TextView(this).apply {
                        text = "+${pages.size - maxShown}"
                        gravity = Gravity.CENTER
                        setPadding(dp(12), 0, dp(12), 0)
                    })
                }
                previewScroll.visibility = View.VISIBLE
            }
        }
    }

    private fun thumbView(bmp: Bitmap, caption: String, page: Int, d: DocumentSource): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val img = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setBackgroundColor(Color.rgb(200, 200, 200))
            setPadding(1, 1, 1, 1)
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tv = TextView(this).apply {
            text = caption
            textSize = 11f
            gravity = Gravity.CENTER
        }
        box.addView(img)
        box.addView(tv)
        box.setOnClickListener { showLargePreview(page, d) }
        return box
    }

    /** Tap a thumbnail to see the page bigger. */
    private fun showLargePreview(page: Int, d: DocumentSource) {
        val w = resources.displayMetrics.widthPixels
        worker.execute {
            val bmp = try { d.thumbnail(page - 1, w) } catch (e: Exception) { null } ?: return@execute
            ui {
                if (doc !== d) return@ui
                val img = ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    setBackgroundColor(Color.WHITE)
                }
                val scroll = android.widget.ScrollView(this).apply { addView(img) }
                AlertDialog.Builder(this)
                    .setTitle("صفحة $page من ${d.pageCount}")
                    .setView(scroll)
                    .setPositiveButton("قفل", null)
                    .setOnDismissListener { bmp.recycle() }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ Printing

    private fun currentOptions(): JobOptions = JobOptions(
        paper = papers[paperSpin.selectedItemPosition],
        dpi = if (qualitySpin.selectedItemPosition == 1) 1200 else 600,
        density = TonerDensity.values()[densitySpin.selectedItemPosition],
        tonerSave = if (tonerSave.isChecked) true else null,
        userName = "android",
        jobName = docName,
        serviceDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()),
    )

    private fun startPrint() {
        if (busy) return
        val d = doc ?: return showError("اختار ملف الأول")
        val p = printer ?: run { refreshPrinter(); return showError("الطابعة مش متوصلة") }
        if (!usb.hasPermission(p.device)) { refreshPrinter(); return showError("لسه مفيش إذن للطابعة. وافق على الإذن ودوس اطبع تاني.") }

        val pages = try { PageRange.parse(pagesEdit.text.toString(), d.pageCount) }
        catch (e: IllegalArgumentException) { return showError(e.message ?: "الصفحات غلط") }
        val copies = copiesEdit.text.toString().toIntOrNull()?.coerceIn(1, 99) ?: 1
        val opt = currentOptions()
        val mode = if (modeSpin.selectedItemPosition == 1) ColorMode.PHOTO else ColorMode.TEXT
        saveSettings()

        if (!duplexRadio.isChecked) {
            val list = ArrayList<Int?>()
            repeat(copies) { list.addAll(pages) }
            runJob(p, d, opt, mode, list, rotate180 = false, label = "") {
                ui { jobStatus.text = "✅ خلصت الطباعة (${list.size} صفحة)" }
            }
        } else {
            val plan = DuplexPlan.build(pages, copies, reverseBacks.isChecked)
            val rotate = rotateBacks.isChecked
            runJob(p, d, opt, mode, plan.fronts, rotate180 = false, label = "الوش: ") {
                ui { askForBacks(p, d, opt, mode, plan, rotate) }
            }
        }
    }

    private fun askForBacks(p: PrinterInfo, d: DocumentSource, opt: JobOptions, mode: ColorMode, plan: DuplexPlan, rotate: Boolean) {
        jobStatus.text = "⏸️ خلص الوش. اقلب الورق."
        AlertDialog.Builder(this)
            .setTitle("اقلب الورق")
            .setMessage(
                "1. استنى لحد ما آخر ورقة تخرج.\n" +
                "2. خد الورق المطبوع كله زي ما هو.\n" +
                "3. حطه في درج الورق والوش المطبوع لتحت، من غير ما تغيّر ترتيبه.\n" +
                "4. دوس \"اطبع الضهر\".\n\n" +
                "عدد الورق: ${plan.fronts.size}"
            )
            .setCancelable(false)
            .setPositiveButton("اطبع الضهر") { _, _ ->
                runJob(p, d, opt, mode, plan.backs, rotate180 = rotate, label = "الضهر: ") {
                    ui { jobStatus.text = "✅ خلصت الطباعة وش وضهر (${plan.fronts.size} ورقة)" }
                }
            }
            .setNegativeButton("إلغاء") { _, _ -> jobStatus.text = "اتلغى طباعة الضهر" }
            .show()
    }

    /**
     * Render and encode every page to a file first, then stream the whole job to the
     * printer in one go. Sending the PJL header and then pausing to render pages can
     * make the printer time out and print the rest as garbage text.
     */
    private fun runJob(
        p: PrinterInfo, d: DocumentSource, opt: JobOptions, mode: ColorMode,
        pages: List<Int?>, rotate180: Boolean, label: String, onDone: () -> Unit,
    ) {
        setBusy(true)
        ui { progress.max = pages.size; progress.progress = 0; jobStatus.text = "${label}بيجهّز…" }
        worker.execute {
            try {
                val job = buildJob(d, opt, mode, pages, rotate180, label)
                sendJobFile(p, job, label)
                setBusy(false)
                onDone()
            } catch (e: Throwable) {
                setBusy(false)
                showError(e.message ?: e.toString())
            }
        }
    }

    /** Must run on the worker thread. Writes the complete printer job to cache/job.prn. */
    private fun buildJob(
        d: DocumentSource?, opt: JobOptions, mode: ColorMode,
        pages: List<Int?>, rotate180: Boolean, label: String,
        pageProvider: ((Int?) -> PageBitmap)? = null,
    ): java.io.File {
        val file = java.io.File(cacheDir, "job.prn")
        val enc = QpdlEncoder(opt)
        java.io.BufferedOutputStream(file.outputStream(), 256 * 1024).use { out ->
            out.write(enc.jobHeader())
            pages.forEachIndexed { i, pg ->
                ui {
                    progress.max = pages.size; progress.progress = i
                    jobStatus.text = "${label}بيجهّز ${i + 1} من ${pages.size}" + (pg?.let { " (صفحة $it)" } ?: " (ضهر فاضي)")
                }
                val bmp = when {
                    pageProvider != null -> pageProvider(pg)
                    pg == null || d == null -> blankPage(opt)
                    else -> d.rasterize(pg - 1, opt.paper, opt.dpi, mode, rotate180)
                }
                out.write(enc.encodePage(bmp))
            }
            out.write(enc.jobFooter())
        }
        return file
    }

    /** Must run on the worker thread. Streams a finished job file to the printer. */
    private fun sendJobFile(p: PrinterInfo, file: java.io.File, label: String) {
        val data = file.readBytes()
        ui { progress.max = 100; progress.progress = 0; jobStatus.text = "${label}بيبعت للطابعة…" }
        UsbPrinterConnection.open(usb, p).use { conn ->
            val step = 64 * 1024
            var off = 0
            while (off < data.size) {
                val n = minOf(step, data.size - off)
                conn.write(data, off, n)
                off += n
                val pct = (off * 100L / data.size).toInt()
                ui { progress.progress = pct; jobStatus.text = "${label}بيبعت للطابعة $pct%" }
            }
        }
    }

    // ------------------------------------------------------------------ Diagnostics

    /** Print a built-in test page (no file needed): border, boxes, lines and a grey ramp. */
    private fun printTestPage() {
        if (busy) return
        val p = printer ?: return showError("الطابعة مش متوصلة")
        if (!usb.hasPermission(p.device)) { refreshPrinter(); return }
        val opt = currentOptions().copy(jobName = "test page")
        setBusy(true)
        worker.execute {
            try {
                val job = buildJob(null, opt, ColorMode.TEXT, listOf(1), false, "صفحة اختبار: ") { testPage(opt) }
                sendJobFile(p, job, "صفحة اختبار: ")
                ui { jobStatus.text = "✅ اتبعتت صفحة الاختبار" }
            } catch (e: Throwable) {
                showError(e.message ?: e.toString())
            } finally {
                setBusy(false)
            }
        }
    }

    private fun testPage(opt: JobOptions): PageBitmap {
        val w = opt.paper.pixelWidth(opt.dpi)
        val h = opt.paper.pixelHeight(opt.dpi)
        val rb = w / 8
        val d = ByteArray(rb * h)
        val u = opt.dpi / 600f
        fun set(x: Int, y: Int) { if (x in 0 until w && y in 0 until h) { val i = y * rb + (x ushr 3); d[i] = (d[i].toInt() or (0x80 ushr (x and 7))).toByte() } }
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0 until y1) for (x in x0 until x1) set(x, y) }
        val m = (200 * u).toInt(); val t = (12 * u).toInt()
        rect(m, m, w - m, m + t); rect(m, h - m - t, w - m, h - m)          // border
        rect(m, m, m + t, h - m); rect(w - m - t, m, w - m, h - m)
        for (k in 0 until 8) {                                             // boxes
            val s = ((60 + k * 40) * u).toInt(); val x = m + (150 * u).toInt() + k * (500 * u).toInt()
            rect(x, m + (300 * u).toInt(), minOf(x + s, w - m - t), m + (300 * u).toInt() + s)
        }
        for (k in 0 until 40) {                                            // horizontal lines
            val y = (h * 0.35).toInt() + k * (40 * u).toInt()
            rect(m + (150 * u).toInt(), y, w - m - (150 * u).toInt(), y + 1 + k / 10)
        }
        val gy = (h * 0.65).toInt(); val gh = (600 * u).toInt()              // grey ramp (dither)
        val bayer = intArrayOf(0,32,8,40,2,34,10,42,48,16,56,24,50,18,58,26,12,44,4,36,14,46,6,38,60,28,52,20,62,30,54,22,3,35,11,43,1,33,9,41,51,19,59,27,49,17,57,25,15,47,7,39,13,45,5,37,63,31,55,23,61,29,53,21)
        val x0 = m + (150 * u).toInt(); val x1 = w - m - (150 * u).toInt()
        for (y in gy until gy + gh) for (x in x0 until x1) {
            val g = (x - x0) * 64 / (x1 - x0)
            if (g > bayer[(y and 7) * 8 + (x and 7)]) set(x, y)
        }
        return PageBitmap(w, h, d)
    }

    /** Save the job file that would be sent to the printer, for troubleshooting. */
    private fun startSaveJob() {
        if (busy) return
        val d = doc ?: return showError("اختار ملف الأول")
        val pages = try { PageRange.parse(pagesEdit.text.toString(), d.pageCount) }
        catch (e: IllegalArgumentException) { return showError(e.message ?: "الصفحات غلط") }
        val opt = currentOptions()
        val mode = if (modeSpin.selectedItemPosition == 1) ColorMode.PHOTO else ColorMode.TEXT
        setBusy(true)
        worker.execute {
            try {
                buildJob(d, opt, mode, pages, false, "حفظ: ")
                ui {
                    setBusy(false)
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, "usbprint-job.prn")
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_SAVE)
                }
            } catch (e: Throwable) {
                setBusy(false)
                showError(e.message ?: e.toString())
            }
        }
    }

    private fun finishSaveJob(uri: Uri) {
        worker.execute {
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    java.io.File(cacheDir, "job.prn").inputStream().use { it.copyTo(out) }
                }
                ui { jobStatus.text = "✅ اتحفظ ملف الطباعة" }
            } catch (e: Throwable) {
                showError(e.message ?: e.toString())
            }
        }
    }

    private fun blankPage(opt: JobOptions): PageBitmap {
        val w = opt.paper.pixelWidth(opt.dpi)
        val h = opt.paper.pixelHeight(opt.dpi)
        return PageBitmap(w, h, ByteArray(w / 8 * h))
    }

    /** Diagnostic: send a ready-made .prn file (e.g. "Print to file" from Windows). */
    private fun sendRaw(uri: Uri) {
        val p = printer ?: return showError("الطابعة مش متوصلة")
        if (!usb.hasPermission(p.device)) { refreshPrinter(); return }
        setBusy(true)
        ui { jobStatus.text = "بيبعت الملف…"; progress.isIndeterminate = true }
        worker.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                UsbPrinterConnection.open(usb, p).use { it.write(bytes) }
                ui { jobStatus.text = "✅ اتبعت ${bytes.size / 1024} KB" }
            } catch (e: Throwable) {
                showError(e.message ?: e.toString())
            } finally {
                ui { progress.isIndeterminate = false }
                setBusy(false)
            }
        }
    }
}
