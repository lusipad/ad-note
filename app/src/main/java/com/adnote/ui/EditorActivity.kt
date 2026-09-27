package com.adnote.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adnote.R
import com.adnote.ink.Eraser
import com.adnote.model.InkPoint
import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import com.adnote.model.PenPresets
import com.adnote.model.Stroke
import com.adnote.pen.EinkRefresher
import com.adnote.pen.PenInput
import com.adnote.pen.PenInputFactory
import com.adnote.pen.PenInputListener
import kotlinx.coroutines.launch

class EditorActivity : AppCompatActivity() {

    private lateinit var note: Note
    private var currentPageIndex: Int = 0
    private var penInput: PenInput? = null
    private var pdfRenderer: com.adnote.pdf.PdfPageRenderer? = null

    private lateinit var btnBack: Button
    private lateinit var tvNoteTitle: TextView
    private lateinit var btnPrevPage: Button
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNextPage: Button
    private lateinit var btnAddPage: Button
    private lateinit var btnPageTemplate: Button
    private lateinit var btnPenSettings: Button
    private lateinit var btnUndo: Button
    private lateinit var btnRecognize: Button
    private lateinit var btnRefresh: Button
    private lateinit var inkCanvas: InkCanvasView
    private lateinit var layoutRecognized: LinearLayout
    private lateinit var tvRecognizedResult: TextView

    private var activePenColor: String = com.adnote.model.PenPresets.BLACK.hex
    private var activePenWidth: Float = com.adnote.model.PenPresets.WIDTH_MEDIUM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        val loaded = noteId?.let { AdNoteApp.instance.repository.load(it) }
        if (loaded == null) {
            Toast.makeText(this, "未找到笔记", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        note = loaded

        if (note.isPdf) {
            val pdfFile = AdNoteApp.instance.repository.getPdfFile(note)
            if (pdfFile != null && pdfFile.exists()) {
                pdfRenderer = com.adnote.pdf.PdfPageRenderer(pdfFile)
            }
        }

        initViews()
        setupListeners()
        loadPage(0)
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvNoteTitle = findViewById(R.id.tvNoteTitle)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnPageTemplate = findViewById(R.id.btnPageTemplate)
        btnPenSettings = findViewById(R.id.btnPenSettings)
        btnUndo = findViewById(R.id.btnUndo)
        btnRecognize = findViewById(R.id.btnRecognize)
        btnRefresh = findViewById(R.id.btnRefresh)
        inkCanvas = findViewById(R.id.inkCanvas)
        layoutRecognized = findViewById(R.id.layoutRecognized)
        tvRecognizedResult = findViewById(R.id.tvRecognizedResult)
        findViewById<View?>(R.id.btnCloseRecognized)?.setOnClickListener {
            layoutRecognized.visibility = View.GONE
        }

        btnAddPage.visibility = if (note.isPdf) View.GONE else View.VISIBLE
        btnPageTemplate.visibility = if (note.isPdf) View.GONE else View.VISIBLE
        updateTitleView()
    }

    private fun updateTitleView() {
        tvNoteTitle.text = buildString {
            if (note.isPdf) append("📄 [PDF] ")
            append(note.title)
            if (note.folder != Note.DEFAULT_FOLDER) {
                append(" (${note.folder})")
            }
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            saveNote()
            finish()
        }

        tvNoteTitle.setOnClickListener {
            showEditMetadataDialog()
        }

        btnPrevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                loadPage(currentPageIndex - 1)
            }
        }

        btnNextPage.setOnClickListener {
            if (currentPageIndex < note.pages.size - 1) {
                loadPage(currentPageIndex + 1)
            }
        }

        btnAddPage.setOnClickListener {
            val current = note.pages.getOrNull(currentPageIndex)
            val template = current?.template ?: com.adnote.model.PageTemplate.BLANK
            val bgColor = current?.backgroundColor ?: "#FFFFFF"
            val w = inkCanvas.width.coerceAtLeast(1404)
            val h = inkCanvas.height.coerceAtLeast(1872)
            val newPage = Page(width = w, height = h, template = template, backgroundColor = bgColor)
            note = note.copy(
                pages = note.pages + newPage,
                updatedAt = System.currentTimeMillis()
            )
            saveNote()
            loadPage(note.pages.lastIndex)
        }

        btnPageTemplate.setOnClickListener {
            showPageTemplateDialog()
        }

        btnPenSettings.setOnClickListener {
            showPenSettingsDialog()
        }

        btnUndo.setOnClickListener {
            penInput?.setEnabled(false)
            val undone = inkCanvas.undo()
            if (undone != null) {
                syncCurrentPageFromCanvas()
                saveNote()
            }
            penInput?.setEnabled(true)
        }

        btnRefresh.setOnClickListener {
            penInput?.setEnabled(false)
            EinkRefresher.fullRefresh(inkCanvas)
            penInput?.setEnabled(true)
        }

        btnRecognize.setOnClickListener {
            recognizeCurrentPage()
        }

        // 延迟初始化画笔通道
        inkCanvas.post {
            setupPenInput()
        }
    }

    private fun setupPenInput() {
        val rect = Rect()
        inkCanvas.getGlobalVisibleRect(rect)
        if (rect.isEmpty) {
            rect.set(0, 0, inkCanvas.width, inkCanvas.height)
        }

        val app = AdNoteApp.instance
        val input = PenInputFactory.create(
            preferOnyx = app.preferOnyx,
            stylusOnly = app.stylusOnly
        )
        penInput = input

        val listener = object : PenInputListener {
            override fun onDrawing(points: List<InkPoint>) {
                if (points.isNotEmpty()) {
                    inkCanvas.setTransientStroke(Stroke(points = points, width = activePenWidth, color = activePenColor))
                } else {
                    inkCanvas.setTransientStroke(null)
                }
            }

            override fun onStroke(points: List<InkPoint>) {
                inkCanvas.setTransientStroke(null)
                if (points.isEmpty()) return
                val stroke = Stroke(points = points, width = activePenWidth, color = activePenColor)
                inkCanvas.addStroke(stroke)
                syncCurrentPageFromCanvas()
                saveNote()
            }

            override fun onErase(points: List<InkPoint>) {
                inkCanvas.setTransientStroke(null)
                if (points.isEmpty()) return
                val currentPage = note.pages.getOrNull(currentPageIndex) ?: return
                val hitIds = Eraser.hitStrokes(currentPage.strokes, points, radius = 10f)
                if (hitIds.isNotEmpty()) {
                    penInput?.setEnabled(false)
                    inkCanvas.eraseStrokes(hitIds)
                    syncCurrentPageFromCanvas()
                    saveNote()
                    penInput?.setEnabled(true)
                }
            }
        }

        input.attach(inkCanvas, rect, listener)
        runCatching {
            input.setStrokeColor(Color.parseColor(activePenColor))
            input.setStrokeWidth(activePenWidth)
        }
    }

    private fun loadPage(index: Int) {
        if (index !in note.pages.indices) return
        currentPageIndex = index
        val page = note.pages[index]
        inkCanvas.setPage(page)

        val paperTone = PaperPresets.find(page.backgroundColor)
        if (paperTone.isDark && activePenColor.equals(PenPresets.BLACK.hex, ignoreCase = true)) {
            activePenColor = PenPresets.WHITE.hex
        } else if (!paperTone.isDark && activePenColor.equals(PenPresets.WHITE.hex, ignoreCase = true)) {
            activePenColor = PenPresets.BLACK.hex
        }
        runCatching {
            penInput?.setStrokeColor(Color.parseColor(activePenColor))
            penInput?.setStrokeWidth(activePenWidth)
        }

        tvPageIndicator.text = "${index + 1}/${note.pages.size}"
        btnPrevPage.isEnabled = index > 0
        btnNextPage.isEnabled = index < note.pages.size - 1

        val recognized = page.recognizedText
        if (!recognized.isNullOrBlank()) {
            layoutRecognized.visibility = View.VISIBLE
            tvRecognizedResult.text = "识别结果：\n$recognized"
        } else {
            layoutRecognized.visibility = View.GONE
        }

        val renderer = pdfRenderer
        if (renderer != null) {
            inkCanvas.post {
                val targetW = if (inkCanvas.width > 0) inkCanvas.width else page.width
                val targetH = if (inkCanvas.height > 0) inkCanvas.height else page.height
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val bitmap = renderer.renderPage(index, targetW, targetH)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        inkCanvas.setBackgroundBitmap(bitmap)
                    }
                }
            }
        } else {
            inkCanvas.setBackgroundBitmap(null)
        }
    }

    private fun showPageTemplateDialog() {
        penInput?.setEnabled(false)
        val dialogView = layoutInflater.inflate(R.layout.dialog_paper_template, null)
        val layoutTemplateChips = dialogView.findViewById<LinearLayout>(R.id.layoutTemplateChips)
        val layoutColorChips = dialogView.findViewById<LinearLayout>(R.id.layoutColorChips)
        val cbApplyToAllPages = dialogView.findViewById<CheckBox>(R.id.cbApplyToAllPages)

        val currentPage = note.pages.getOrNull(currentPageIndex)
        var selectedTemplate = currentPage?.template ?: PageTemplate.BLANK
        var selectedColorHex = currentPage?.backgroundColor ?: PaperPresets.WHITE.hex

        fun refreshTemplateChips() {
            layoutTemplateChips.removeAllViews()
            PageTemplate.entries.forEach { template ->
                val isSelected = template == selectedTemplate
                val btn = Button(this).apply {
                    text = template.displayName
                    textSize = 12f
                    stateListAnimator = null
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        marginEnd = (8 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                    setPadding((12 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt(), 0)
                    if (isSelected) {
                        setBackgroundResource(R.drawable.bg_chip_selected)
                        setTextColor(getColor(R.color.white))
                    } else {
                        setBackgroundResource(R.drawable.bg_chip_unselected)
                        setTextColor(getColor(R.color.text_primary))
                    }
                    setOnClickListener {
                        selectedTemplate = template
                        refreshTemplateChips()
                    }
                }
                layoutTemplateChips.addView(btn)
            }
        }

        fun refreshColorChips() {
            layoutColorChips.removeAllViews()
            PaperPresets.ALL.forEach { tone ->
                val isSelected = tone.hex.equals(selectedColorHex, ignoreCase = true)
                val btn = Button(this).apply {
                    text = tone.displayName
                    textSize = 12f
                    stateListAnimator = null
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        marginEnd = (8 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                    setPadding((12 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt(), 0)
                    if (isSelected) {
                        setBackgroundResource(R.drawable.bg_chip_selected)
                        setTextColor(getColor(R.color.white))
                    } else {
                        setBackgroundResource(R.drawable.bg_chip_unselected)
                        setTextColor(getColor(R.color.text_primary))
                    }
                    setOnClickListener {
                        selectedColorHex = tone.hex
                        refreshColorChips()
                    }
                }
                layoutColorChips.addView(btn)
            }
        }

        refreshTemplateChips()
        refreshColorChips()

        AlertDialog.Builder(this)
            .setTitle("笔记本底质与纸张")
            .setView(dialogView)
            .setPositiveButton("应用") { _, _ ->
                val applyToAll = cbApplyToAllPages.isChecked
                val updatedPages = if (applyToAll) {
                    note.pages.map { it.copy(template = selectedTemplate, backgroundColor = selectedColorHex) }
                } else {
                    note.pages.mapIndexed { idx, p ->
                        if (idx == currentPageIndex) p.copy(template = selectedTemplate, backgroundColor = selectedColorHex) else p
                    }
                }
                note = note.copy(pages = updatedPages, updatedAt = System.currentTimeMillis())
                saveNote()
                loadPage(currentPageIndex)
                penInput?.setEnabled(true)
            }
            .setNegativeButton("取消") { _, _ ->
                penInput?.setEnabled(true)
            }
            .setOnDismissListener {
                penInput?.setEnabled(true)
            }
            .show()
    }

    private fun showPenSettingsDialog() {
        penInput?.setEnabled(false)
        val dialogView = layoutInflater.inflate(R.layout.dialog_pen_settings, null)
        val layoutPenColorChips = dialogView.findViewById<LinearLayout>(R.id.layoutPenColorChips)
        val layoutPenWidthChips = dialogView.findViewById<LinearLayout>(R.id.layoutPenWidthChips)

        var tempColorHex = activePenColor
        var tempWidth = activePenWidth

        fun refreshColorChips() {
            layoutPenColorChips.removeAllViews()
            PenPresets.ALL.forEach { pen ->
                val isSelected = pen.hex.equals(tempColorHex, ignoreCase = true)
                val btn = Button(this).apply {
                    val span = SpannableString("●  ${pen.displayName}")
                    span.setSpan(
                        ForegroundColorSpan(Color.parseColor(pen.hex)),
                        0,
                        1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    text = span
                    textSize = 12f
                    stateListAnimator = null
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        marginEnd = (8 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                    setPadding((12 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt(), 0)
                    if (isSelected) {
                        setBackgroundResource(R.drawable.bg_chip_selected)
                        setTextColor(getColor(R.color.white))
                    } else {
                        setBackgroundResource(R.drawable.bg_chip_unselected)
                        setTextColor(getColor(R.color.text_primary))
                    }
                    setOnClickListener {
                        tempColorHex = pen.hex
                        refreshColorChips()
                    }
                }
                layoutPenColorChips.addView(btn)
            }
        }

        fun refreshWidthChips() {
            layoutPenWidthChips.removeAllViews()
            PenPresets.WIDTHS.forEach { opt ->
                val isSelected = (opt.width == tempWidth)
                val btn = Button(this).apply {
                    text = opt.displayName
                    textSize = 12f
                    stateListAnimator = null
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (32 * resources.displayMetrics.density).toInt()
                    ).apply {
                        marginEnd = (8 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                    setPadding((12 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt(), 0)
                    if (isSelected) {
                        setBackgroundResource(R.drawable.bg_chip_selected)
                        setTextColor(getColor(R.color.white))
                    } else {
                        setBackgroundResource(R.drawable.bg_chip_unselected)
                        setTextColor(getColor(R.color.text_primary))
                    }
                    setOnClickListener {
                        tempWidth = opt.width
                        refreshWidthChips()
                    }
                }
                layoutPenWidthChips.addView(btn)
            }
        }

        refreshColorChips()
        refreshWidthChips()

        AlertDialog.Builder(this)
            .setTitle("画笔与墨水设置")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                activePenColor = tempColorHex
                activePenWidth = tempWidth
                runCatching {
                    penInput?.setStrokeColor(Color.parseColor(activePenColor))
                    penInput?.setStrokeWidth(activePenWidth)
                }
                Toast.makeText(this, "画笔已更新", Toast.LENGTH_SHORT).show()
                penInput?.setEnabled(true)
            }
            .setNegativeButton("取消") { _, _ ->
                penInput?.setEnabled(true)
            }
            .setOnDismissListener {
                penInput?.setEnabled(true)
            }
            .show()
    }

    private fun syncCurrentPageFromCanvas() {
        val canvasPage = inkCanvas.getPage() ?: return
        val currentList = note.pages.toMutableList()
        if (currentPageIndex in currentList.indices) {
            val existing = currentList[currentPageIndex]
            currentList[currentPageIndex] = canvasPage.copy(
                recognizedText = existing.recognizedText
            )
            note = note.copy(
                pages = currentList,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun saveNote() {
        AdNoteApp.instance.repository.save(note)
    }

    private fun recognizeCurrentPage() {
        val page = note.pages.getOrNull(currentPageIndex) ?: return
        if (page.strokes.isEmpty()) {
            Toast.makeText(this, "当前页无手写笔画", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在识别手写内容...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = AdNoteApp.instance.recognizer.recognize(page)
            if (result.isSuccess) {
                val text = result.getOrNull().orEmpty()
                val updatedPages = note.pages.toMutableList()
                updatedPages[currentPageIndex] = page.copy(recognizedText = text)
                note = note.copy(
                    pages = updatedPages,
                    updatedAt = System.currentTimeMillis()
                )
                saveNote()
                layoutRecognized.visibility = View.VISIBLE
                tvRecognizedResult.text = "识别结果：\n$text"
                Toast.makeText(this@EditorActivity, "识别完成", Toast.LENGTH_SHORT).show()
            } else {
                val msg = result.exceptionOrNull()?.message ?: "识别失败"
                Toast.makeText(this@EditorActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showEditMetadataDialog() {
        penInput?.setEnabled(false)
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_note, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.etDialogTitle)
        val etFolder = dialogView.findViewById<EditText>(R.id.etDialogFolder)
        val etTags = dialogView.findViewById<EditText>(R.id.etDialogTags)

        etTitle.setText(note.title)
        etFolder.setText(note.folder)
        etTags.setText(note.tags.joinToString(", "))

        AlertDialog.Builder(this)
            .setTitle("编辑笔记属性")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newTitle = etTitle.text.toString().trim().ifEmpty { note.title }
                val newFolder = etFolder.text.toString().trim().ifEmpty { Note.DEFAULT_FOLDER }
                val newTags = etTags.text.toString().split(',', '，')
                    .map { it.trim().removePrefix("#") }
                    .filter { it.isNotEmpty() }

                note = note.copy(
                    title = newTitle,
                    folder = newFolder,
                    tags = newTags,
                    updatedAt = System.currentTimeMillis()
                )
                saveNote()
                updateTitleView()
                penInput?.setEnabled(true)
            }
            .setNegativeButton("取消") { _, _ ->
                penInput?.setEnabled(true)
            }
            .setOnDismissListener {
                penInput?.setEnabled(true)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        penInput?.setEnabled(true)
    }

    override fun onPause() {
        super.onPause()
        penInput?.setEnabled(false)
        saveNote()
    }

    override fun onDestroy() {
        super.onDestroy()
        penInput?.detach()
        penInput = null
        pdfRenderer?.close()
        pdfRenderer = null
    }

    companion object {
        private const val EXTRA_NOTE_ID = "extra_note_id"

        fun start(context: Context, noteId: String) {
            val intent = Intent(context, EditorActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
            }
            context.startActivity(intent)
        }
    }
}
