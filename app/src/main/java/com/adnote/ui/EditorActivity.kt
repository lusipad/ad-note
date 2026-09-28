package com.adnote.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adnote.R
import com.adnote.ink.Eraser
import com.adnote.ink.Lasso
import com.adnote.ink.StrokeHistory
import com.adnote.model.EraserMode
import com.adnote.model.EraserSize
import com.adnote.model.InkPoint
import com.adnote.model.Note
import com.adnote.model.PageOps
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import com.adnote.model.PenPresets
import com.adnote.model.PenType
import com.adnote.model.Stroke
import com.adnote.model.Tool
import com.adnote.model.ToolState
import com.adnote.model.newId
import com.adnote.pdf.PdfPageRenderer
import com.adnote.pen.EinkRefresher
import com.adnote.pen.PenInput
import com.adnote.pen.PenInputFactory
import com.adnote.pen.PenInputListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity() {

    private lateinit var note: Note
    private var currentPageIndex: Int = 0
    private var penInput: PenInput? = null
    private var pdfRenderer: PdfPageRenderer? = null

    private lateinit var tvNoteTitle: TextView
    private lateinit var btnPrevPage: ImageButton
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNextPage: ImageButton
    private lateinit var btnAddPage: ImageButton
    private lateinit var btnPageTemplate: Button
    private lateinit var btnToolPen: ImageButton
    private lateinit var btnToolHighlighter: ImageButton
    private lateinit var btnToolEraser: ImageButton
    private lateinit var btnToolLasso: ImageButton
    private lateinit var layoutQuickColors: LinearLayout
    private lateinit var tvToolHint: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var inkCanvas: InkCanvasView
    private lateinit var layoutSelectionBar: LinearLayout
    private lateinit var tvSelectionInfo: TextView
    private lateinit var layoutRecognized: LinearLayout
    private lateinit var tvRecognizedResult: TextView

    private var tools: ToolState = ToolState()

    /** 每页一份撤销历史，按页面 id 索引（增删、移动页面后依然对得上）。 */
    private val histories = HashMap<String, StrokeHistory>()

    /** 一次擦除手势开始前的笔画列表；整个手势只记一条撤销记录。 */
    private var eraseSnapshot: List<Stroke>? = null

    /** 实时擦除时已处理到的轨迹点数。 */
    private var erasedUpTo = 0

    private var selectedIds: Set<String> = emptySet()

    private var resumed = false

    /** 当前打开的对话框/弹出菜单层数（可能嵌套，如页面概览里再弹菜单）。 */
    private var overlayDepth = 0

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

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
        tools = AdNoteApp.instance.toolState

        if (note.isPdf) {
            val pdfFile = AdNoteApp.instance.repository.getPdfFile(note)
            if (pdfFile != null && pdfFile.exists()) {
                pdfRenderer = PdfPageRenderer(pdfFile)
            }
        }

        initViews()
        setupListeners()
        applyTools()
        loadPage(0)
    }

    // region 初始化

    private fun initViews() {
        tvNoteTitle = findViewById(R.id.tvNoteTitle)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnPageTemplate = findViewById(R.id.btnPageTemplate)
        btnToolPen = findViewById(R.id.btnToolPen)
        btnToolHighlighter = findViewById(R.id.btnToolHighlighter)
        btnToolEraser = findViewById(R.id.btnToolEraser)
        btnToolLasso = findViewById(R.id.btnToolLasso)
        layoutQuickColors = findViewById(R.id.layoutQuickColors)
        tvToolHint = findViewById(R.id.tvToolHint)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        inkCanvas = findViewById(R.id.inkCanvas)
        layoutSelectionBar = findViewById(R.id.layoutSelectionBar)
        tvSelectionInfo = findViewById(R.id.tvSelectionInfo)
        layoutRecognized = findViewById(R.id.layoutRecognized)
        tvRecognizedResult = findViewById(R.id.tvRecognizedResult)
        findViewById<View>(R.id.btnCloseRecognized).setOnClickListener {
            layoutRecognized.visibility = View.GONE
        }

        val canEditPages = PageOps.canEditStructure(note)
        btnAddPage.visibility = if (canEditPages) View.VISIBLE else View.GONE
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
        findViewById<View>(R.id.btnBack).setOnClickListener {
            saveNow()
            finish()
        }
        tvNoteTitle.setOnClickListener { showEditMetadataDialog() }

        btnPrevPage.setOnClickListener { prevPage() }
        btnNextPage.setOnClickListener { nextPage(appendAtEnd = true) }
        tvPageIndicator.setOnClickListener { showPageOverview() }
        btnAddPage.setOnClickListener { insertPageAfter(currentPageIndex) }
        findViewById<View>(R.id.btnPages).setOnClickListener { showPageOverview() }
        findViewById<View>(R.id.btnRecognize).setOnClickListener { recognizeCurrentPage() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            withPenPaused { EinkRefresher.fullRefresh(inkCanvas) }
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }

        btnToolPen.setOnClickListener {
            if (tools.tool == Tool.PEN) showPenSettingsDialog(highlighter = false) else selectTool(Tool.PEN)
        }
        btnToolPen.setOnLongClickListener { showPenSettingsDialog(highlighter = false); true }
        btnToolHighlighter.setOnClickListener {
            if (tools.tool == Tool.HIGHLIGHTER) showPenSettingsDialog(highlighter = true) else selectTool(Tool.HIGHLIGHTER)
        }
        btnToolHighlighter.setOnLongClickListener { showPenSettingsDialog(highlighter = true); true }
        btnToolEraser.setOnClickListener {
            if (tools.tool == Tool.ERASER) showEraserSettingsDialog() else selectTool(Tool.ERASER)
        }
        btnToolEraser.setOnLongClickListener { showEraserSettingsDialog(); true }
        btnToolLasso.setOnClickListener { selectTool(Tool.LASSO) }
        tvToolHint.setOnClickListener { if (tools.tool == Tool.ERASER) showEraserSettingsDialog() }

        btnUndo.setOnClickListener { undo() }
        btnRedo.setOnClickListener { redo() }
        btnPageTemplate.setOnClickListener { showPageTemplateDialog() }

        findViewById<View>(R.id.btnSelDelete).setOnClickListener { deleteSelection() }
        findViewById<View>(R.id.btnSelDuplicate).setOnClickListener { duplicateSelection() }
        findViewById<View>(R.id.btnSelColor).setOnClickListener { recolorSelection() }
        findViewById<View>(R.id.btnSelDone).setOnClickListener { exitSelection() }

        inkCanvas.onSwipe = { direction ->
            if (AdNoteApp.instance.fingerSwipePaging) {
                if (direction > 0) nextPage(appendAtEnd = false) else prevPage()
            }
        }
        inkCanvas.selectionListener = object : InkCanvasView.SelectionListener {
            override fun onSelectionMoved(dx: Float, dy: Float) {
                val before = currentStrokes()
                commitChange(before, Lasso.translate(before, selectedIds, dx, dy))
            }

            override fun onSelectionDismissed() = exitSelection()
        }

        // 画布完成布局后再初始化画笔通道（需要知道画布在屏幕上的区域）
        inkCanvas.post { setupPenInput() }
    }

    private fun setupPenInput() {
        val rect = Rect()
        inkCanvas.getGlobalVisibleRect(rect)
        if (rect.isEmpty) {
            rect.set(0, 0, inkCanvas.width, inkCanvas.height)
        }

        val app = AdNoteApp.instance
        val input = PenInputFactory.create(preferOnyx = app.preferOnyx, stylusOnly = app.stylusOnly)
        penInput = input

        val listener = object : PenInputListener {
            override fun onDrawing(points: List<InkPoint>) {
                when (tools.tool) {
                    Tool.PEN, Tool.HIGHLIGHTER ->
                        inkCanvas.setTransientStroke(if (points.isEmpty()) null else tools.newStroke(points))
                    Tool.ERASER -> liveErase(points)
                    Tool.LASSO -> inkCanvas.setLassoPath(points)
                }
            }

            override fun onStroke(points: List<InkPoint>) {
                inkCanvas.setTransientStroke(null)
                when (tools.tool) {
                    Tool.PEN, Tool.HIGHLIGHTER -> if (points.isNotEmpty()) commitStroke(tools.newStroke(points))
                    Tool.ERASER -> finishErase(points)
                    Tool.LASSO -> finishLasso(points)
                }
            }

            // 笔尾橡皮 / 笔身按键：无论当前是什么工具，都按橡皮设置擦除
            override fun onErasing(points: List<InkPoint>) = liveErase(points)

            override fun onErase(points: List<InkPoint>) = finishErase(points)
        }

        input.attach(inkCanvas, rect, listener)
        applyPenInputStyle()
        refreshPenEnabled()
    }

    // endregion

    // region 工具

    private fun selectTool(tool: Tool) {
        if (tools.tool == tool) return
        tools = tools.copy(tool = tool)
        applyTools()
    }

    /** 同步工具栏外观、画笔通道参数，并持久化工具状态。 */
    private fun applyTools() {
        val buttons = mapOf(
            Tool.PEN to btnToolPen,
            Tool.HIGHLIGHTER to btnToolHighlighter,
            Tool.ERASER to btnToolEraser,
            Tool.LASSO to btnToolLasso,
        )
        buttons.forEach { (tool, btn) ->
            btn.setBackgroundResource(if (tool == tools.tool) R.drawable.bg_tool_selected else R.drawable.bg_button_secondary)
        }
        refreshQuickColors()

        if (tools.tool != Tool.ERASER) inkCanvas.hideEraserCursor()
        if (tools.tool != Tool.LASSO) {
            inkCanvas.setLassoPath(emptyList())
            exitSelection()
        }
        applyPenInputStyle()
        AdNoteApp.instance.saveToolState(tools)
    }

    private fun applyPenInputStyle() {
        val input = penInput ?: return
        runCatching {
            input.setPenStyle(tools.activePen)
            input.setStrokeColor(StrokePainter.parseColor(tools.activeColor))
            input.setStrokeWidth(tools.activeWidth)
            // 只有普通书写由硬件直绘；荧光笔（半透明）、橡皮与套索由应用自己绘制
            input.setRenderEnabled(tools.tool == Tool.PEN)
        }
    }

    private fun refreshQuickColors() {
        layoutQuickColors.removeAllViews()
        when (tools.tool) {
            Tool.PEN -> {
                tvToolHint.visibility = View.GONE
                tools.recentPenColors.forEach { hex ->
                    layoutQuickColors.addView(Chips.swatch(this, hex, hex.equals(tools.penColor, true)) {
                        tools = tools.copy(penColor = hex)
                        applyTools()
                    })
                }
            }
            Tool.HIGHLIGHTER -> {
                tvToolHint.visibility = View.GONE
                PenPresets.HIGHLIGHTERS.forEach { style ->
                    layoutQuickColors.addView(
                        Chips.swatch(this, style.hex, style.hex.equals(tools.highlighterColor, true)) {
                            tools = tools.copy(highlighterColor = style.hex)
                            applyTools()
                        }
                    )
                }
            }
            Tool.ERASER -> {
                tvToolHint.visibility = View.VISIBLE
                tvToolHint.text = "${tools.eraserMode.displayName} · ${tools.eraserSize.displayName}号  ▾"
            }
            Tool.LASSO -> {
                tvToolHint.visibility = View.VISIBLE
                tvToolHint.text = "圈选笔迹后可拖动、删除、复制、改色"
            }
        }
    }

    // endregion

    // region 书写、擦除、套索

    private fun currentStrokes(): List<Stroke> = inkCanvas.getPage()?.strokes.orEmpty()

    private fun history(): StrokeHistory {
        val pageId = note.pages.getOrNull(currentPageIndex)?.id ?: ""
        return histories.getOrPut(pageId) { StrokeHistory() }
    }

    private fun commitStroke(stroke: Stroke) {
        val before = currentStrokes()
        // 荧光笔没有硬件直绘预览，需要暂停直绘让墨水屏刷出应用绘制的结果
        if (tools.tool == Tool.HIGHLIGHTER) withPenPaused { inkCanvas.addStroke(stroke) } else inkCanvas.addStroke(stroke)
        history().record(before)
        onStrokesChanged()
    }

    /** 以撤销记录的形式替换整页笔画。 */
    private fun commitChange(before: List<Stroke>, after: List<Stroke>) {
        if (after === before) return
        withPenPaused { inkCanvas.setStrokes(after) }
        history().record(before)
        onStrokesChanged()
    }

    private fun onStrokesChanged() {
        syncCurrentPageFromCanvas()
        scheduleSave()
        updateUndoRedo()
    }

    private fun applyErase(path: List<InkPoint>) {
        if (path.isEmpty()) return
        val cur = currentStrokes()
        val radius = tools.eraserSize.radius
        val next = when (tools.eraserMode) {
            EraserMode.PARTIAL -> Eraser.erasePartial(cur, path, radius)
            EraserMode.STROKE -> {
                val hit = Eraser.hitStrokes(cur, path, radius)
                if (hit.isEmpty()) cur else cur.filterNot { it.id in hit }
            }
        }
        if (next !== cur) inkCanvas.setStrokes(next)
    }

    /** 实时擦除：只处理新增的轨迹段，同时显示橡皮光标。 */
    private fun liveErase(points: List<InkPoint>) {
        if (points.isEmpty()) {
            // 手势被取消：把已经擦掉的部分作为一次操作收尾
            if (eraseSnapshot != null) finishErase(emptyList()) else inkCanvas.hideEraserCursor()
            return
        }
        val last = points.last()
        inkCanvas.setEraserCursor(last.x, last.y, tools.eraserSize.radius)
        if (eraseSnapshot == null) {
            eraseSnapshot = currentStrokes()
            erasedUpTo = 0
        }
        applyErase(points.subList((erasedUpTo - 1).coerceIn(0, points.size), points.size))
        erasedUpTo = points.size
    }

    private fun finishErase(points: List<InkPoint>) {
        inkCanvas.hideEraserCursor()
        val before = eraseSnapshot ?: currentStrokes()
        // 文石通道没有实时回调，抬笔时一次性处理整条轨迹
        val remaining = if (eraseSnapshot == null) points else points.subList(
            (erasedUpTo - 1).coerceIn(0, points.size), points.size
        )
        eraseSnapshot = null
        erasedUpTo = 0
        withPenPaused { applyErase(remaining) }
        if (currentStrokes() !== before) {
            history().record(before)
            onStrokesChanged()
        }
    }

    private fun finishLasso(points: List<InkPoint>) {
        inkCanvas.setLassoPath(emptyList())
        val ids = Lasso.select(currentStrokes(), points)
        if (ids.isEmpty()) {
            withPenPaused { inkCanvas.invalidate() }
            Toast.makeText(this, "没有圈中笔迹", Toast.LENGTH_SHORT).show()
            return
        }
        enterSelection(ids)
    }

    private fun enterSelection(ids: Set<String>) {
        selectedIds = ids
        inkCanvas.setSelection(ids)
        tvSelectionInfo.text = "已选 ${ids.size} 笔 · 拖动可移动"
        layoutSelectionBar.visibility = View.VISIBLE
        refreshPenEnabled()
    }

    private fun exitSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds = emptySet()
        inkCanvas.clearSelection()
        layoutSelectionBar.visibility = View.GONE
        refreshPenEnabled()
    }

    private fun deleteSelection() {
        val before = currentStrokes()
        val ids = selectedIds
        exitSelection()
        commitChange(before, before.filterNot { it.id in ids })
    }

    private fun duplicateSelection() {
        val before = currentStrokes()
        val offset = 40f
        val copies = before.filter { it.id in selectedIds }.map { s ->
            s.copy(id = newId(), points = s.points.map { it.copy(x = it.x + offset, y = it.y + offset) })
        }
        if (copies.isEmpty()) return
        exitSelection()
        commitChange(before, before + copies)
        enterSelection(copies.map { it.id }.toSet())
    }

    private fun recolorSelection() {
        val palette = PenPresets.ALL + PenPresets.HIGHLIGHTERS
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("修改选中笔迹颜色")
                .setItems(palette.map { it.displayName }.toTypedArray()) { _, which ->
                    val before = currentStrokes()
                    commitChange(before, Lasso.recolor(before, selectedIds, palette[which].hex))
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun undo() {
        exitSelection()
        val prev = history().undo(currentStrokes()) ?: return
        withPenPaused { inkCanvas.setStrokes(prev) }
        onStrokesChanged()
    }

    private fun redo() {
        exitSelection()
        val next = history().redo(currentStrokes()) ?: return
        withPenPaused { inkCanvas.setStrokes(next) }
        onStrokesChanged()
    }

    private fun updateUndoRedo() {
        val h = history()
        btnUndo.isEnabled = h.canUndo
        btnUndo.alpha = if (h.canUndo) 1f else 0.35f
        btnRedo.isEnabled = h.canRedo
        btnRedo.alpha = if (h.canRedo) 1f else 0.35f
    }

    private fun confirmClearPage() {
        if (currentStrokes().isEmpty()) {
            Toast.makeText(this, "本页没有笔迹", Toast.LENGTH_SHORT).show()
            return
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("清空本页笔迹？")
                .setMessage("可以通过「撤销」恢复。")
                .setPositiveButton("清空") { _, _ ->
                    exitSelection()
                    commitChange(currentStrokes(), emptyList())
                }
                .setNegativeButton("取消", null)
        )
    }

    // endregion

    // region 翻页与页面管理

    private fun loadPage(index: Int) {
        if (index !in note.pages.indices) return
        exitSelection()
        currentPageIndex = index
        val page = note.pages[index]
        inkCanvas.setPage(page)

        val adapted = tools.adaptToPaper(PaperPresets.find(page.backgroundColor).isDark)
        if (adapted != tools) {
            tools = adapted
            applyTools()
        }

        updatePageIndicator()
        updateUndoRedo()

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
                lifecycleScope.launch(Dispatchers.IO) {
                    val bitmap = renderer.renderPage(index, targetW, targetH)
                    withContext(Dispatchers.Main) {
                        if (currentPageIndex == index) withPenPaused { inkCanvas.setBackgroundBitmap(bitmap) }
                    }
                }
            }
        } else {
            inkCanvas.setBackgroundBitmap(null)
        }
    }

    /** 翻页前后都会整页重绘，墨水屏上先暂停硬件直绘再切换。 */
    private fun goToPage(index: Int) {
        if (index == currentPageIndex || index !in note.pages.indices) return
        withPenPaused { loadPage(index) }
    }

    private fun prevPage() {
        if (currentPageIndex > 0) goToPage(currentPageIndex - 1)
    }

    /** 下一页；在最后一页且 [appendAtEnd] 时自动新建一页（PDF 笔记除外）。 */
    private fun nextPage(appendAtEnd: Boolean) {
        if (currentPageIndex < note.pages.lastIndex) {
            goToPage(currentPageIndex + 1)
        } else if (appendAtEnd && PageOps.canEditStructure(note)) {
            insertPageAfter(currentPageIndex)
        }
    }

    private fun updatePageIndicator() {
        val last = note.pages.lastIndex
        tvPageIndicator.text = "${currentPageIndex + 1} / ${note.pages.size}"
        val canPrev = currentPageIndex > 0
        val canNext = currentPageIndex < last || PageOps.canEditStructure(note)
        btnPrevPage.isEnabled = canPrev
        btnPrevPage.alpha = if (canPrev) 1f else 0.35f
        btnNextPage.isEnabled = canNext
        btnNextPage.alpha = if (canNext) 1f else 0.35f
        btnNextPage.contentDescription = if (currentPageIndex < last) "下一页" else "新建一页"
    }

    private fun insertPageAfter(index: Int) {
        val updated = PageOps.insertBlankAfter(note, index)
        if (updated === note) return
        note = updated
        saveNow()
        withPenPaused { loadPage(index + 1) }
        Toast.makeText(this, "已新建第 ${index + 2} 页", Toast.LENGTH_SHORT).show()
    }

    private fun duplicatePage(index: Int) {
        val updated = PageOps.duplicate(note, index)
        if (updated === note) return
        note = updated
        saveNow()
        withPenPaused { loadPage(index + 1) }
        Toast.makeText(this, "已复制为第 ${index + 2} 页", Toast.LENGTH_SHORT).show()
    }

    private fun movePage(from: Int, to: Int) {
        val updated = PageOps.move(note, from, to)
        if (updated === note) return
        note = updated
        val cur = currentPageIndex
        currentPageIndex = when {
            cur == from -> to
            from < cur && to >= cur -> cur - 1
            from > cur && to <= cur -> cur + 1
            else -> cur
        }
        saveNow()
        updatePageIndicator()
    }

    private fun confirmDeletePage(index: Int, onDone: () -> Unit = {}) {
        if (note.pages.size <= 1) {
            Toast.makeText(this, "笔记至少保留一页", Toast.LENGTH_SHORT).show()
            return
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("删除第 ${index + 1} 页？")
                .setMessage("页面及其笔迹将被删除，此操作无法撤销。")
                .setPositiveButton("删除") { _, _ ->
                    val removedId = note.pages[index].id
                    note = PageOps.delete(note, index)
                    histories.remove(removedId)
                    val target = when {
                        index < currentPageIndex -> currentPageIndex - 1
                        else -> currentPageIndex.coerceAtMost(note.pages.lastIndex)
                    }
                    saveNow()
                    withPenPaused { loadPage(target) }
                    onDone()
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun showMoreMenu(anchor: View) {
        val canEdit = PageOps.canEditStructure(note)
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        if (canEdit) {
            m.add(0, MENU_INSERT, 0, "在后面插入新页")
            m.add(0, MENU_DUPLICATE, 1, "复制当前页")
            m.add(0, MENU_DELETE, 2, "删除当前页")
        }
        m.add(0, MENU_CLEAR, 3, "清空本页笔迹")
        m.add(0, MENU_OVERVIEW, 4, "页面概览")
        if (!note.isPdf) m.add(0, MENU_TEMPLATE, 5, "底纹与纸张")
        m.add(0, MENU_PROPERTIES, 6, "笔记属性")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INSERT -> insertPageAfter(currentPageIndex)
                MENU_DUPLICATE -> duplicatePage(currentPageIndex)
                MENU_DELETE -> confirmDeletePage(currentPageIndex)
                MENU_CLEAR -> confirmClearPage()
                MENU_OVERVIEW -> showPageOverview()
                MENU_TEMPLATE -> showPageTemplateDialog()
                MENU_PROPERTIES -> showEditMetadataDialog()
            }
            true
        }
        showPopup(popup)
    }

    private fun showPageOverview() {
        val view = layoutInflater.inflate(R.layout.dialog_page_overview, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvPages)
        val canEdit = PageOps.canEditStructure(note)
        if (!canEdit) view.findViewById<TextView>(R.id.tvOverviewHint).text = "点按页面即可跳转"

        val span = if (resources.configuration.screenWidthDp >= 600) 4 else 3
        val thumbW = (resources.displayMetrics.widthPixels * 0.72f / span).toInt()
        var dialog: AlertDialog? = null
        lateinit var adapter: PageOverviewAdapter
        adapter = PageOverviewAdapter(
            scope = lifecycleScope,
            pdfRenderer = pdfRenderer,
            thumbWidthPx = thumbW,
            onClick = { index ->
                dialog?.dismiss()
                goToPage(index)
            },
            onLongClick = { index, anchor ->
                if (canEdit) showPageActionMenu(index, anchor) { adapter.submit(note.pages, currentPageIndex) }
            },
        )
        rv.layoutManager = GridLayoutManager(this, span)
        rv.adapter = adapter
        adapter.submit(note.pages, currentPageIndex)
        rv.scrollToPosition(currentPageIndex)

        val builder = AlertDialog.Builder(this)
            .setTitle("页面概览（共 ${note.pages.size} 页）")
            .setView(view)
            .setNegativeButton("关闭", null)
        if (canEdit) {
            builder.setNeutralButton("末尾加页") { _, _ -> insertPageAfter(note.pages.lastIndex) }
        }
        dialog = showDialog(builder)
    }

    private fun showPageActionMenu(index: Int, anchor: View, onChanged: () -> Unit) {
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        m.add(0, 1, 0, "在后面插入新页")
        m.add(0, 2, 1, "复制此页")
        if (index > 0) m.add(0, 3, 2, "前移")
        if (index < note.pages.lastIndex) m.add(0, 4, 3, "后移")
        if (note.pages.size > 1) m.add(0, 5, 4, "删除此页")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> insertPageAfter(index)
                2 -> duplicatePage(index)
                3 -> movePage(index, index - 1)
                4 -> movePage(index, index + 1)
                5 -> confirmDeletePage(index, onChanged)
            }
            onChanged()
            true
        }
        showPopup(popup)
    }

    // endregion

    // region 对话框

    private fun showPageTemplateDialog() {
        if (note.isPdf) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_paper_template, null)
        val cbApplyToAllPages = dialogView.findViewById<CheckBox>(R.id.cbApplyToAllPages)
        val currentPage = note.pages.getOrNull(currentPageIndex)
        val picker = TemplatePicker(
            context = this,
            categoryContainer = dialogView.findViewById(R.id.layoutTemplateCategoryChips),
            templateContainer = dialogView.findViewById(R.id.layoutTemplateChips),
            colorContainer = dialogView.findViewById(R.id.layoutColorChips),
            initialTemplate = currentPage?.template ?: PageTemplate.BLANK,
            initialColor = currentPage?.backgroundColor ?: PaperPresets.WHITE.hex,
        )

        showDialog(
            AlertDialog.Builder(this)
                .setTitle("底纹与纸张")
                .setView(dialogView)
                .setPositiveButton("应用") { _, _ ->
                    val template = picker.selectedTemplate
                    val color = picker.selectedColor
                    val applyToAll = cbApplyToAllPages.isChecked
                    note = note.copy(
                        pages = note.pages.mapIndexed { idx, p ->
                            if (applyToAll || idx == currentPageIndex) p.copy(template = template, backgroundColor = color) else p
                        },
                        updatedAt = System.currentTimeMillis(),
                    )
                    saveNow()
                    withPenPaused { loadPage(currentPageIndex) }
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun showPenSettingsDialog(highlighter: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_pen_settings, null)
        val preview = view.findViewById<StrokePreviewView>(R.id.strokePreview)
        val typeChips = view.findViewById<LinearLayout>(R.id.layoutPenTypeChips)
        val colorChips = view.findViewById<LinearLayout>(R.id.layoutPenColorChips)
        val widthChips = view.findViewById<LinearLayout>(R.id.layoutPenWidthChips)
        val seek = view.findViewById<SeekBar>(R.id.seekPenWidth)
        val tvWidth = view.findViewById<TextView>(R.id.tvPenWidthValue)
        val paper = note.pages.getOrNull(currentPageIndex)?.backgroundColor ?: PaperPresets.WHITE.hex

        var pen = if (highlighter) PenType.HIGHLIGHTER else tools.penType
        var color = if (highlighter) tools.highlighterColor else tools.penColor
        var width = if (highlighter) tools.highlighterWidth else tools.penWidth
        val palette = if (highlighter) PenPresets.HIGHLIGHTERS else PenPresets.ALL
        val presets = if (highlighter) listOf(12f, 18f, 24f) else listOf(2f, 3.5f, 6f, 10f)

        if (highlighter) {
            view.findViewById<View>(R.id.tvPenTypeLabel).visibility = View.GONE
            view.findViewById<View>(R.id.scrollPenTypeChips).visibility = View.GONE
        }

        lateinit var refresh: () -> Unit
        refresh = {
            preview.update(pen, color, width, paper)
            tvWidth.text = String.format("%.1f", width)
            typeChips.removeAllViews()
            PenType.WRITING.forEach { t ->
                typeChips.addView(Chips.text(this, t.displayName, t == pen) { pen = t; refresh() })
            }
            colorChips.removeAllViews()
            palette.forEach { s ->
                colorChips.addView(Chips.color(this, s.hex, s.displayName, s.hex.equals(color, true)) {
                    color = s.hex; refresh()
                })
            }
            widthChips.removeAllViews()
            presets.forEach { w ->
                widthChips.addView(Chips.text(this, String.format("%.1f", w), w == width) {
                    width = w
                    seek.progress = widthToProgress(w)
                    refresh()
                })
            }
        }

        seek.max = widthToProgress(PenPresets.WIDTH_MAX)
        seek.progress = widthToProgress(width)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                width = PenPresets.WIDTH_MIN + progress / 2f
                refresh()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        refresh()

        showDialog(
            AlertDialog.Builder(this)
                .setTitle(if (highlighter) "荧光笔设置" else "画笔设置")
                .setView(view)
                .setPositiveButton("确定") { _, _ ->
                    tools = if (highlighter) {
                        tools.copy(tool = Tool.HIGHLIGHTER, highlighterColor = color, highlighterWidth = width)
                    } else {
                        tools.withPenColor(color).copy(tool = Tool.PEN, penType = pen, penWidth = width)
                    }
                    applyTools()
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun widthToProgress(w: Float): Int = ((w - PenPresets.WIDTH_MIN) * 2).roundToInt().coerceAtLeast(0)

    private fun showEraserSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_eraser_settings, null)
        val modeChips = view.findViewById<LinearLayout>(R.id.layoutEraserModeChips)
        val sizeChips = view.findViewById<LinearLayout>(R.id.layoutEraserSizeChips)
        val hint = view.findViewById<TextView>(R.id.tvEraserModeHint)
        var mode = tools.eraserMode
        var size = tools.eraserSize

        lateinit var refresh: () -> Unit
        refresh = {
            hint.text = when (mode) {
                EraserMode.PARTIAL -> "像真实橡皮一样，只擦掉经过的部分"
                EraserMode.STROKE -> "碰到哪一笔就删除整条笔画"
            }
            modeChips.removeAllViews()
            EraserMode.entries.forEach { m ->
                modeChips.addView(Chips.text(this, m.displayName, m == mode) { mode = m; refresh() })
            }
            sizeChips.removeAllViews()
            EraserSize.entries.forEach { s ->
                sizeChips.addView(Chips.text(this, s.displayName, s == size) { size = s; refresh() })
            }
        }
        refresh()

        var dialog: AlertDialog? = null
        view.findViewById<View>(R.id.btnClearPage).setOnClickListener {
            dialog?.dismiss()
            confirmClearPage()
        }
        dialog = showDialog(
            AlertDialog.Builder(this)
                .setTitle("橡皮擦设置")
                .setView(view)
                .setPositiveButton("确定") { _, _ ->
                    tools = tools.copy(tool = Tool.ERASER, eraserMode = mode, eraserSize = size)
                    applyTools()
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun showEditMetadataDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_note, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.etDialogTitle)
        val etFolder = dialogView.findViewById<EditText>(R.id.etDialogFolder)
        val etTags = dialogView.findViewById<EditText>(R.id.etDialogTags)

        etTitle.setText(note.title)
        etFolder.setText(note.folder)
        etTags.setText(note.tags.joinToString(", "))

        showDialog(
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
                    saveNow()
                    updateTitleView()
                }
                .setNegativeButton("取消", null)
        )
    }

    /**
     * 显示对话框期间暂停画笔通道：文石直绘层会拦截落在画布区域内的笔触，
     * 不暂停的话在对话框上点选会变成在画布上写字。
     */
    private fun showDialog(builder: AlertDialog.Builder): AlertDialog {
        overlayDepth++
        refreshPenEnabled()
        builder.setOnDismissListener {
            overlayDepth = (overlayDepth - 1).coerceAtLeast(0)
            refreshPenEnabled()
        }
        return builder.show()
    }

    private fun showPopup(popup: PopupMenu) {
        overlayDepth++
        refreshPenEnabled()
        popup.setOnDismissListener {
            overlayDepth = (overlayDepth - 1).coerceAtLeast(0)
            refreshPenEnabled()
        }
        popup.show()
    }

    // endregion

    // region 画笔通道开关、保存

    private fun refreshPenEnabled() {
        penInput?.setEnabled(resumed && overlayDepth == 0 && selectedIds.isEmpty())
    }

    /** 暂停直绘执行 [block] 后恢复：墨水屏需要这样才能刷出应用自己重绘的内容。 */
    private inline fun withPenPaused(block: () -> Unit) {
        penInput?.setEnabled(false)
        block()
        refreshPenEnabled()
    }

    private fun syncCurrentPageFromCanvas() {
        val canvasPage = inkCanvas.getPage() ?: return
        val currentList = note.pages.toMutableList()
        if (currentPageIndex in currentList.indices) {
            val existing = currentList[currentPageIndex]
            currentList[currentPageIndex] = canvasPage.copy(recognizedText = existing.recognizedText)
            note = note.copy(pages = currentList, updatedAt = System.currentTimeMillis())
        }
    }

    /** 连续书写时合并保存，避免每一笔都序列化整本笔记。 */
    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    private fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        AdNoteApp.instance.repository.save(note)
    }

    // endregion

    private fun recognizeCurrentPage() {
        val page = note.pages.getOrNull(currentPageIndex) ?: return
        if (page.strokes.isEmpty()) {
            Toast.makeText(this, "当前页无手写笔画", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在识别手写内容...", Toast.LENGTH_SHORT).show()
        val pageIndex = currentPageIndex
        lifecycleScope.launch {
            val result = AdNoteApp.instance.recognizer.recognize(page)
            if (result.isSuccess) {
                val text = result.getOrNull().orEmpty()
                val updatedPages = note.pages.toMutableList()
                if (pageIndex in updatedPages.indices && updatedPages[pageIndex].id == page.id) {
                    updatedPages[pageIndex] = updatedPages[pageIndex].copy(recognizedText = text)
                    note = note.copy(pages = updatedPages, updatedAt = System.currentTimeMillis())
                    saveNow()
                }
                if (currentPageIndex == pageIndex) {
                    layoutRecognized.visibility = View.VISIBLE
                    tvRecognizedResult.text = "识别结果：\n$text"
                }
                Toast.makeText(this@EditorActivity, "识别完成", Toast.LENGTH_SHORT).show()
            } else {
                val msg = result.exceptionOrNull()?.message ?: "识别失败"
                Toast.makeText(this@EditorActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 实体翻页键、音量键翻页；外接键盘 Ctrl+Z / Ctrl+Y 撤销重做。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val volumePaging = AdNoteApp.instance.volumeKeyPaging
        when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> { nextPage(appendAtEnd = false); return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> { prevPage(); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumePaging) { nextPage(appendAtEnd = false); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumePaging) { prevPage(); return true }
            KeyEvent.KEYCODE_Z -> if (event.isCtrlPressed) {
                if (event.isShiftPressed) redo() else undo()
                return true
            }
            KeyEvent.KEYCODE_Y -> if (event.isCtrlPressed) { redo(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // 吞掉音量键抬起事件，避免翻页时弹出系统音量条
        if (AdNoteApp.instance.volumeKeyPaging &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPenEnabled()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        refreshPenEnabled()
        if (::note.isInitialized) saveNow()
        AdNoteApp.instance.saveToolState(tools)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(saveRunnable)
        penInput?.detach()
        penInput = null
        pdfRenderer?.close()
        pdfRenderer = null
    }

    companion object {
        private const val EXTRA_NOTE_ID = "extra_note_id"
        private const val SAVE_DELAY_MS = 1500L

        private const val MENU_INSERT = 1
        private const val MENU_DUPLICATE = 2
        private const val MENU_DELETE = 3
        private const val MENU_CLEAR = 4
        private const val MENU_OVERVIEW = 5
        private const val MENU_TEMPLATE = 6
        private const val MENU_PROPERTIES = 7

        fun start(context: Context, noteId: String) {
            val intent = Intent(context, EditorActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
            }
            context.startActivity(intent)
        }
    }
}
