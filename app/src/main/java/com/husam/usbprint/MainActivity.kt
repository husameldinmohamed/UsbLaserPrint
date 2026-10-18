package com.husam.usbprint

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
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

    private val papers = PaperSize.values()

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_RAW = 2
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

        paperSpin.adapter = spinnerAdapter(papers.map { it.label })
        qualitySpin.adapter = spinnerAdapter(listOf("عادية 600 DPI", "عالية 1200 DPI (أبطأ)"))
        modeSpin.adapter = spinnerAdapter(listOf("نصوص ومستندات", "صور ورسومات"))
        densitySpin.adapter = spinnerAdapter(listOf("فاتح", "متوسط", "غامق"))
        loadSettings()

        findViewById<android.widget.RadioGroup>(R.id.sides).setOnCheckedChangeListener { _, _ ->
            duplexOptions.visibility = if (duplexRadio.isChecked) View.VISIBLE else View.GONE
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshPrinter() }
        findViewById<Button>(R.id.btnPick).setOnClickListener { pickFile(REQ_PICK, arrayOf("application/pdf", "image/*")) }
        findViewById<Button>(R.id.btnRaw).setOnClickListener { pickFile(REQ_RAW, arrayOf("*/*")) }
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
                val d = DocumentSource.open(this, uri, mime)
                doc = d
                docName = name.substringBeforeLast('.')
                val bmp = d.thumbnail(0, 320)
                ui {
                    fileInfo.text = "📄 $name\nعدد الصفحات: ${d.pageCount}"
                    thumb.setImageBitmap(bmp)
                    thumb.visibility = View.VISIBLE
                    pagesEdit.setText("")
                }
            } catch (e: Exception) {
                ui { fileInfo.text = "مفيش ملف"; thumb.visibility = View.GONE }
                showError("مقدرتش أفتح الملف: ${e.message}")
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

    /** Render, encode and send a list of pages (null = blank sheet) as one printer job. */
    private fun runJob(
        p: PrinterInfo, d: DocumentSource, opt: JobOptions, mode: ColorMode,
        pages: List<Int?>, rotate180: Boolean, label: String, onDone: () -> Unit,
    ) {
        setBusy(true)
        ui { progress.max = pages.size; progress.progress = 0; jobStatus.text = "${label}بيجهّز…" }
        worker.execute {
            try {
                val enc = QpdlEncoder(opt)
                UsbPrinterConnection.open(usb, p).use { conn ->
                    conn.write(enc.jobHeader())
                    pages.forEachIndexed { i, pg ->
                        ui { jobStatus.text = "${label}بيطبع ${i + 1} من ${pages.size}" + (pg?.let { " (صفحة $it)" } ?: " (ضهر فاضي)") }
                        val bmp = if (pg == null) blankPage(opt)
                        else d.rasterize(pg - 1, opt.paper, opt.dpi, mode, rotate180)
                        conn.write(enc.encodePage(bmp))
                        ui { progress.progress = i + 1 }
                    }
                    conn.write(enc.jobFooter())
                }
                setBusy(false)
                onDone()
            } catch (e: Throwable) {
                setBusy(false)
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
