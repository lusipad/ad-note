package com.adnote.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adnote.R
import com.adnote.ink.Affine
import com.adnote.ink.EditHistory
import com.adnote.ink.Eraser
import com.adnote.ink.Ruler
import com.adnote.ink.ScratchOut
import com.adnote.ink.Selection
import com.adnote.ink.SelectionOps
import com.adnote.ink.ShapeRecognizer
import com.adnote.ink.StrokeGeometry
import com.adnote.ink.TextLayout
import com.adnote.model.DockPosition
import com.adnote.model.EraserMode
import com.adnote.model.EraserSizes
import com.adnote.model.ImageItem
import com.adnote.model.InkPoint
import com.adnote.model.Layer
import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.PenPreset
import com.adnote.model.PageOps
import com.adnote.model.PaperPresets
import com.adnote.model.PenPresets
import com.adnote.model.PenType
import com.adnote.model.Recording
import com.adnote.model.Stroke
import com.adnote.model.StylusButtonAction
import com.adnote.model.TapeMask
import com.adnote.model.PageTemplate
import com.adnote.model.TextBox
import com.adnote.model.Tool
import com.adnote.model.ToolState
import com.adnote.model.Viewport
import com.adnote.pdf.PdfPageRenderer
import com.adnote.pen.EinkRefresher
import com.adnote.pen.PenInput
import com.adnote.pen.PenInputFactory
import com.adnote.pen.PenInputListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import kotlin.math.hypot
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity() {

    // Draw & Hold (智能几何停顿自动吸附) 状态
    private var shapeHoldJob: Runnable? = null
    private var holdSnappedResult: ShapeRecognizer.Result? = null
    private var holdBasePoints: List<InkPoint>? = null
    private var holdSnappedStroke: Stroke? = null
    private var lastPenMoveUptime: Long = 0L
    private var lastPenMovePoint: InkPoint? = null

    private lateinit var note: Note
    private var currentPageIndex: Int = 0
    private var penInput: PenInput? = null
    private var pdfRenderer: PdfPageRenderer? = null
    private lateinit var assets: BitmapAssets

    private lateinit var tvNoteTitle: TextView
    private lateinit var btnPrevPage: ImageButton
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNextPage: ImageButton
    private lateinit var btnAddPage: ImageButton
    private lateinit var btnPageTemplate: Button
    private lateinit var toolButtons: Map<Tool, ImageButton>
    private lateinit var btnRuler: ImageButton
    private lateinit var btnInsertImage: ImageButton
    private lateinit var btnRecord: ImageButton
    private lateinit var layoutQuickColors: LinearLayout
    private lateinit var tvToolHint: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var inkCanvas: InkCanvasView
    private lateinit var layoutSelectionBar: View
    private lateinit var tvSelectionInfo: TextView
    private lateinit var layoutRecordingBar: View
    private lateinit var tvRecordingTime: TextView
    private lateinit var tvZoom: TextView
    private lateinit var layoutRecognized: LinearLayout
    private lateinit var tvRecognizedResult: TextView

    private lateinit var toolbar: View
    private lateinit var dividerTop: View
    private lateinit var dockTop: ViewGroup
    private lateinit var dockLeft: ViewGroup
    private lateinit var dockRight: ViewGroup
    private lateinit var dividerDockLeft: View
    private lateinit var dividerDockRight: View
    private lateinit var dividerToolTop: View
    private lateinit var layoutPenPropertyBar: View
    private lateinit var dividerPenProperty: View
    private lateinit var layoutToolBarContent: LinearLayout
    private lateinit var layoutPenPresets: LinearLayout

    private lateinit var slotPreset1: View
    private lateinit var tvPreset1Text: TextView
    private lateinit var vPreset1Color: View
    private lateinit var slotPreset2: View
    private lateinit var tvPreset2Text: TextView
    private lateinit var vPreset2Color: View
    private lateinit var slotPreset3: View
    private lateinit var tvPreset3Text: TextView
    private lateinit var vPreset3Color: View

    private lateinit var btnZen: ImageButton
    private lateinit var btnDockPosition: ImageButton
    private lateinit var btnExitZen: View
    private lateinit var btnSplitObsidian: ImageButton
    private lateinit var layoutSplitObsidian: View
    private lateinit var etSplitObsidianMd: EditText
    private lateinit var tvSplitOcrPreview: TextView
    private lateinit var layoutFilmstripBar: View
    private lateinit var layoutFilmstripItems: LinearLayout

    private var isZenMode: Boolean = false

    private val app get() = AdNoteApp.instance
    private val repo get() = AdNoteApp.instance.repository
    private val isEink by lazy {
        com.adnote.pen.DeviceDetector.detect().screenCategory == com.adnote.pen.ScreenCategory.EINK
    }

    private var tools: ToolState = ToolState()

    /** 每页一份撤销历史（整页快照），按页面 id 索引，增删、移动页面后依然对得上。 */
    private val histories = HashMap<String, EditHistory<Page>>()

    /** 每页当前编辑的图层。 */
    private val activeLayers = HashMap<String, Int>()

    /** 一次擦除手势开始前的页面；整个手势只记一条撤销记录。 */
    private var eraseSnapshot: Page? = null

    /** 实时擦除时已处理到的轨迹点数。 */
    private var erasedUpTo = 0

    private var selection: Selection = Selection.EMPTY

    /** 插入图片、粘贴、笔身按键套索时自动切到套索前的工具；选区结束后切回去。 */
    private var toolBeforeAutoLasso: Tool? = null

    /** 上次交给直绘层的书写区域与排除区域，没变化时不重复设置。 */
    private var lastPenRegion: Pair<Rect, List<Rect>>? = null

    private var resumed = false
    private var windowFocused = false

    /** 当前打开的对话框/弹出菜单层数（可能嵌套，如页面概览里再弹菜单）。 */
    private var overlayDepth = 0

    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveNow() }

    private val player = AudioPlayer()
    private var activePlaybackRecording: Recording? = null
    private val playbackSyncTicker = object : Runnable {
        override fun run() {
            val rec = activePlaybackRecording
            if (rec == null || player.playingPath != rec.id) {
                activePlaybackRecording = null
                inkCanvas.setPlaybackCutoff(null)
                EinkRefresher.refreshCanvasPartial(inkCanvas)
                return
            }
            val cutoff = rec.createdAt - rec.durationMs + player.currentPositionMs
            inkCanvas.setPlaybackCutoff(cutoff)
            EinkRefresher.refreshCanvasPartial(inkCanvas)
            handler.postDelayed(this, 500L)
        }
    }
    private val recordTicker = object : Runnable {
        override fun run() {
            val active = RecordingSession.active ?: return
            val other = if (active.noteId != note.id) "（「${active.noteTitle}」）" else ""
            // 墨水屏防频闪：墨水屏上每 15 秒低频更新一次录音时长，避免书写时右上角每秒重绘闪动
            tvRecordingTime.text = "● 录音中$other ${formatDuration(RecordingSession.elapsedMs)}"
            handler.postDelayed(this, if (isEink) 15_000L else 1_000L)
        }
    }

    /** 录音结束且属于这篇笔记时，记进内存里的笔记（否则之后保存会把它覆盖掉）。 */
    private val recordingListener: (Recording) -> Unit = { rec ->
        note = note.copy(recordings = note.recordings + rec, updatedAt = System.currentTimeMillis())
        saveNow()
        updateRecordingBar()
    }

    /** 选择自定义背景后是否应用到全部页面。 */
    private var pendingBackgroundAll = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::insertImage)
    }
    private val pickBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::applyCustomBackground)
    }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("未获得麦克风权限，无法录音")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val noteId = intent.getStringExtra(EXTRA_NOTE_ID)
        val loaded = noteId?.let { repo.load(it) }
        if (loaded == null) {
            toast("未找到笔记")
            finish()
            return
        }
        note = loaded
        // 每次进入编辑器都拿起笔：颜色、粗细等设置沿用上次，但工具不沿用
        // （上次停在橡皮、套索或文字时，进来直接写字会没反应）
        tools = app.toolState.copy(tool = Tool.PEN)
        assets = BitmapAssets(repo.getNoteDir(note.id))
        RecordingSession.addListener(note.id, recordingListener)

        if (note.isPdf) {
            val pdfFile = repo.getPdfFile(note)
            if (pdfFile != null && pdfFile.exists()) pdfRenderer = PdfPageRenderer(pdfFile)
        }

        initViews()
        setupListeners()
        applyTools()
        applyDockPosition(tools.dockPosition)
    }

    // region 初始化

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        dividerTop = findViewById(R.id.dividerTop)
        dockTop = findViewById(R.id.dockTop)
        dockLeft = findViewById(R.id.dockLeft)
        dockRight = findViewById(R.id.dockRight)
        dividerDockLeft = findViewById(R.id.dividerDockLeft)
        dividerDockRight = findViewById(R.id.dividerDockRight)
        dividerToolTop = findViewById(R.id.dividerToolTop)
        layoutPenPropertyBar = findViewById(R.id.layoutPenPropertyBar)
        dividerPenProperty = findViewById(R.id.dividerPenProperty)
        layoutToolBarContent = findViewById(R.id.layoutToolBarContent)
        layoutPenPresets = findViewById(R.id.layoutPenPresets)

        slotPreset1 = findViewById(R.id.slotPreset1)
        tvPreset1Text = findViewById(R.id.tvPreset1Text)
        vPreset1Color = findViewById(R.id.vPreset1Color)

        slotPreset2 = findViewById(R.id.slotPreset2)
        tvPreset2Text = findViewById(R.id.tvPreset2Text)
        vPreset2Color = findViewById(R.id.vPreset2Color)

        slotPreset3 = findViewById(R.id.slotPreset3)
        tvPreset3Text = findViewById(R.id.tvPreset3Text)
        vPreset3Color = findViewById(R.id.vPreset3Color)

        btnZen = findViewById(R.id.btnZen)
        btnDockPosition = findViewById(R.id.btnDockPosition)
        btnExitZen = findViewById(R.id.btnExitZen)

        tvNoteTitle = findViewById(R.id.tvNoteTitle)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnPageTemplate = findViewById(R.id.btnPageTemplate)
        toolButtons = mapOf(
            Tool.PEN to findViewById(R.id.btnToolPen),
            Tool.HIGHLIGHTER to findViewById(R.id.btnToolHighlighter),
            Tool.ERASER to findViewById(R.id.btnToolEraser),
            Tool.LASSO to findViewById(R.id.btnToolLasso),
            Tool.SHAPE to findViewById(R.id.btnToolShape),
            Tool.TEXT to findViewById(R.id.btnToolText),
            Tool.TAPE to findViewById(R.id.btnToolTape),
            Tool.LASER to findViewById(R.id.btnToolLaser),
        )
        btnSplitObsidian = findViewById(R.id.btnSplitObsidian)
        layoutSplitObsidian = findViewById(R.id.layoutSplitObsidian)
        etSplitObsidianMd = findViewById(R.id.etSplitObsidianMd)
        tvSplitOcrPreview = findViewById(R.id.tvSplitOcrPreview)
        layoutFilmstripBar = findViewById(R.id.layoutFilmstripBar)
        layoutFilmstripItems = findViewById(R.id.layoutFilmstripItems)
        btnRuler = findViewById(R.id.btnRuler)
        btnInsertImage = findViewById(R.id.btnInsertImage)
        btnRecord = findViewById(R.id.btnRecord)
        layoutQuickColors = findViewById(R.id.layoutQuickColors)
        tvToolHint = findViewById(R.id.tvToolHint)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        inkCanvas = findViewById(R.id.inkCanvas)
        layoutSelectionBar = findViewById(R.id.layoutSelectionBar)
        tvSelectionInfo = findViewById(R.id.tvSelectionInfo)
        layoutRecordingBar = findViewById(R.id.layoutRecordingBar)
        tvRecordingTime = findViewById(R.id.tvRecordingTime)
        tvZoom = findViewById(R.id.tvZoom)
        layoutRecognized = findViewById(R.id.layoutRecognized)
        tvRecognizedResult = findViewById(R.id.tvRecognizedResult)
        findViewById<View>(R.id.btnCloseRecognized).setOnClickListener {
            layoutRecognized.visibility = View.GONE
            updateExcludeRects()
        }

        inkCanvas.setAssets(assets)
        btnAddPage.visibility = if (PageOps.canEditStructure(note)) View.VISIBLE else View.GONE
        btnPageTemplate.visibility = if (note.isPdf) View.GONE else View.VISIBLE
        updateTitleView()
    }

    private fun updateTitleView() {
        tvNoteTitle.text = buildString {
            if (note.isPdf) append("📄 [PDF] ")
            append(note.title)
            if (note.folder != Note.DEFAULT_FOLDER) append(" (${note.folder})")
        }
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBack).setOnClickListener {
            saveNow()
            finish()
        }
        tvNoteTitle.setOnClickListener { showEditMetadataDialog() }

        btnZen.setOnClickListener { enterZenMode() }
        btnExitZen.setOnClickListener { exitZenMode() }
        btnDockPosition.setOnClickListener { showDockPositionDialog() }

        slotPreset1.setOnClickListener { selectPreset(0) }
        slotPreset1.setOnLongClickListener { saveCurrentToPreset(0); true }
        slotPreset2.setOnClickListener { selectPreset(1) }
        slotPreset2.setOnLongClickListener { saveCurrentToPreset(1); true }
        slotPreset3.setOnClickListener { selectPreset(2) }
        slotPreset3.setOnLongClickListener { saveCurrentToPreset(2); true }

        btnPrevPage.setOnClickListener { prevPage() }
        btnNextPage.setOnClickListener { nextPage(appendAtEnd = true) }
        tvPageIndicator.setOnClickListener { toggleFilmstripBar() }
        tvPageIndicator.setOnLongClickListener { showPageOverview(); true }
        btnSplitObsidian.setOnClickListener { toggleSplitObsidian() }
        findViewById<View>(R.id.btnSaveSplitObsidian).setOnClickListener { saveSplitObsidian() }
        findViewById<View>(R.id.btnCloseSplitObsidian).setOnClickListener { toggleSplitObsidian(forceClose = true) }
        findViewById<View>(R.id.btnCloseFilmstrip).setOnClickListener { toggleFilmstripBar(forceClose = true) }
        btnAddPage.setOnClickListener { insertPageAfter(currentPageIndex) }
        findViewById<View>(R.id.btnPages).setOnClickListener { showPageOverview() }
        findViewById<View>(R.id.btnRecognize).setOnClickListener { recognizeCurrentPage() }
        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            withPenPaused { inkCanvas.doAfterNextDraw { EinkRefresher.fullRefresh(inkCanvas) } }
        }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }

        toolButtons.forEach { (tool, btn) ->
            btn.setOnClickListener {
                // 再点一次打开设置。橡皮例外：擦除方式和大小已经直接摆在工具栏上，
                // 墨水屏上点了没马上变色时用户常会再点一次，不能因此弹出对话框；
                // 套索的「设置」是粘贴，同样只响应长按，避免误粘贴
                if (tools.tool == tool) {
                    if (tool != Tool.LASSO && tool != Tool.ERASER) openToolSettings(tool)
                } else {
                    selectTool(tool)
                }
            }
            btn.setOnLongClickListener { openToolSettings(tool); true }
        }
        tvToolHint.setOnClickListener { openToolSettings(tools.tool) }

        btnRuler.setOnClickListener { toggleRuler() }
        btnInsertImage.setOnClickListener { pickImage.launch(arrayOf("image/*")) }
        findViewById<View>(R.id.btnLayers).setOnClickListener { showLayersDialog() }
        btnRecord.setOnClickListener { toggleRecording() }
        findViewById<View>(R.id.btnStopRecording).setOnClickListener { stopRecording() }
        tvZoom.setOnClickListener {
            val z = inkCanvas.viewport.zoom
            val nextZoom = when {
                z < 1.15f -> 1.25f
                z < 1.40f -> 1.50f
                z < 1.85f -> 2.00f
                else -> 1.00f
            }
            inkCanvas.setDiscreteZoom(nextZoom)
        }
        tvZoom.setOnLongClickListener {
            inkCanvas.resetZoom()
            true
        }

        btnUndo.setOnClickListener { undo() }
        btnRedo.setOnClickListener { redo() }
        btnPageTemplate.setOnClickListener { showPageTemplateDialog() }

        findViewById<View>(R.id.btnSelCut).setOnClickListener { cutSelection() }
        findViewById<View>(R.id.btnSelCopy).setOnClickListener { copySelection() }
        findViewById<View>(R.id.btnSelDuplicate).setOnClickListener { duplicateSelection() }
        findViewById<View>(R.id.btnSelDelete).setOnClickListener { deleteSelection() }
        findViewById<View>(R.id.btnSelColor).setOnClickListener { recolorSelection() }
        findViewById<View>(R.id.btnSelToText).setOnClickListener { convertSelectionToText() }
        findViewById<View>(R.id.btnSelLayer).setOnClickListener { moveSelectionToLayer() }
        findViewById<View>(R.id.btnSelDone).setOnClickListener { exitSelection() }

        inkCanvas.listener = object : InkCanvasView.Listener {
            override fun onSelectionTransformed(transform: Affine) {
                val before = page()
                commitPage(before, SelectionOps.transform(before, selection, transform))
                inkCanvas.setSelection(selection)
            }

            override fun onSelectionDismissed() = exitSelection()

            override fun onSwipe(direction: Int) {
                if (app.fingerSwipePaging) {
                    if (direction > 0) nextPage(appendAtEnd = false) else prevPage()
                }
            }

            override fun onTap(pageX: Float, pageY: Float) = handleTap(pageX, pageY, byPen = false)

            override fun onMultiFingerTap(fingers: Int) {
                if (!app.multiFingerUndo) return
                if (fingers == 2) undo() else if (fingers >= 3) redo()
            }

            override fun onViewportChanged(viewport: Viewport) {
                applyPenInputStyle()
                updateExcludeRects()
                updateZoomLabel()
            }

            override fun onRulerMoved(ruler: Ruler) = Unit
        }

        // 画布完成布局后再加载页面、初始化画笔通道（需要知道画布尺寸与屏幕区域）
        inkCanvas.post {
            migrateLegacyCoords()
            val startPage = intent.getIntExtra(EXTRA_PAGE_INDEX, -1)
                .takeIf { it >= 0 } ?: note.lastPageIndex.coerceIn(0, (note.pages.size - 1).coerceAtLeast(0))
            loadPage(startPage)
            setupPenInput()
        }
    }

    /**
     * 旧版笔记的笔迹是按画布视图坐标保存的，换算成页面坐标（只做一次）。
     * 旧版编辑器只有一行工具栏，画布比现在高一行（约 51dp）。
     */
    private fun migrateLegacyCoords() {
        if (!com.adnote.model.LegacyCoords.needsMigration(note)) return
        val oldH = inkCanvas.height + (51 * resources.displayMetrics.density).roundToInt()
        note = com.adnote.model.LegacyCoords.migrate(note, inkCanvas.width, oldH)
        saveNow()
    }

    private fun setupPenInput() {
        // 相对画布视图的坐标，只包含页面本身（不含四周灰边）
        val rect = inkCanvas.visiblePageRect()
        if (rect.isEmpty) rect.set(0, 0, inkCanvas.width, inkCanvas.height)

        val input = PenInputFactory.create(preferOnyx = app.preferOnyx, stylusOnly = app.stylusOnly)
        penInput = input
        lastPenRegion = null

        val listener = object : PenInputListener {
            override fun onPenDown() {
                inkCanvas.setPenDown(true)
                val now = SystemClock.uptimeMillis()
                lastPenMoveUptime = now
                lastPenMovePoint = null
                holdSnappedResult = null
                holdBasePoints = null
                holdSnappedStroke = null
                shapeHoldJob?.let { handler.removeCallbacks(it) }
                shapeHoldJob = null

                if (eraserPreviewShown) {
                    eraserPreviewShown = false
                    handler.removeCallbacks(hideEraserPreview)
                    inkCanvas.hideEraserCursor()
                }
            }

            override fun onDrawing(points: List<InkPoint>) {
                if (points.isEmpty()) {
                    inkCanvas.setPenDown(false)
                    shapeHoldJob?.let { handler.removeCallbacks(it) }
                    shapeHoldJob = null
                    holdSnappedResult = null
                    holdBasePoints = null
                    holdSnappedStroke = null
                }
                val pts = inkCanvas.toPage(points)
                val isOnyxRaw = penInput is com.adnote.pen.OnyxPenInput
                when (tools.tool) {
                    Tool.PEN, Tool.HIGHLIGHTER -> {
                        if (pts.isEmpty() || !startsOnPage(pts)) {
                            inkCanvas.setTransientStroke(null)
                            return
                        }
                        if (app.shapeHold) {
                            val snapped = holdSnappedResult
                            val base = holdBasePoints
                            if (snapped != null && base != null) {
                                // 已经吸附规整：笔尖拖动时动态以起点为锚点拉伸旋转
                                val stretched = ShapeRecognizer.stretch(snapped.kind, base, pts.last())
                                val dynamicStroke = newStroke(stretched)
                                holdSnappedStroke = dynamicStroke
                                inkCanvas.setTransientStroke(dynamicStroke)
                                return
                            }

                            val now = SystemClock.uptimeMillis()
                            val cur = pts.last()
                            val prev = lastPenMovePoint
                            val density = inkCanvas.resources.displayMetrics.density
                            val holdRadius = 18f * density
                            if (prev == null || hypot(cur.x - prev.x, cur.y - prev.y) > holdRadius) {
                                lastPenMovePoint = cur
                                lastPenMoveUptime = now
                                shapeHoldJob?.let { handler.removeCallbacks(it) }
                                val job = Runnable {
                                    if (inkCanvas.isPenDown() && pts.size >= 4) {
                                        val res = ShapeRecognizer.recognize(pts)
                                        if (res != null) {
                                            holdSnappedResult = res
                                            holdBasePoints = pts.toList()
                                            val s = newStroke(res.points)
                                            holdSnappedStroke = s
                                            inkCanvas.setTransientStroke(s)
                                            inkCanvas.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        }
                                    }
                                }
                                shapeHoldJob = job
                                handler.postDelayed(job, 420L)
                            }
                        }
                        // 文石硬件直绘通道已经在屏幕底层渲染了实时线条，不重复触发 Android View 层重绘
                        if (!isOnyxRaw) {
                            inkCanvas.setTransientStroke(newStroke(pts))
                        }
                    }
                    Tool.SHAPE, Tool.TAPE -> if (!isOnyxRaw) {
                        inkCanvas.setTransientStroke(if (pts.isEmpty() || !startsOnPage(pts)) null else newStroke(pts))
                    }
                    Tool.LASER -> inkCanvas.addLaserTrail(pts, tools.laserColor)
                    Tool.ERASER -> if (!isOnyxRaw) liveErase(pts)
                    Tool.LASSO -> if (!isOnyxRaw) inkCanvas.setLassoPath(pts)
                    Tool.TEXT -> Unit
                }
            }

            override fun onStroke(points: List<InkPoint>) {
                inkCanvas.setPenDown(false)
                inkCanvas.setTransientStroke(null)
                shapeHoldJob?.let { handler.removeCallbacks(it) }
                shapeHoldJob = null

                if (points.isEmpty()) return
                val pts = inkCanvas.toPage(points)
                val inkTool = tools.tool == Tool.PEN || tools.tool == Tool.HIGHLIGHTER ||
                    tools.tool == Tool.SHAPE || tools.tool == Tool.TEXT || tools.tool == Tool.TAPE
                if (inkTool && !startsOnPage(pts)) {
                    holdSnappedResult = null
                    holdBasePoints = null
                    holdSnappedStroke = null
                    withPenPaused { inkCanvas.invalidate() }
                    return
                }

                // 优先检查停顿期间是否已实时吸附规整
                val ready = holdSnappedStroke
                holdSnappedResult = null
                holdBasePoints = null
                holdSnappedStroke = null
                if (ready != null && (tools.tool == Tool.PEN || tools.tool == Tool.HIGHLIGHTER)) {
                    commitStroke(ready, pause = true)
                    return
                }

                when (tools.tool) {
                    Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE -> handleInk(pts)
                    Tool.TAPE -> handleTapeStroke(pts)
                    Tool.LASER -> {
                        inkCanvas.addLaserTrail(pts, tools.laserColor)
                        withPenPaused { inkCanvas.invalidate() }
                    }
                    Tool.ERASER -> finishErase(pts)
                    Tool.LASSO -> if (isTap(pts)) {
                        withPenPaused { inkCanvas.setLassoPath(emptyList()) }
                        handleTap(pts[0].x, pts[0].y, byPen = true)
                    } else {
                        finishLasso(pts)
                    }
                    Tool.TEXT -> handleTap(pts[0].x, pts[0].y, byPen = true)
                }
            }

            // 笔尾橡皮 / 笔身按键：无论当前是什么工具，都按橡皮设置擦除
            override fun onErasing(points: List<InkPoint>) {
                if (points.isEmpty()) inkCanvas.setPenDown(false)
                liveErase(inkCanvas.toPage(points))
            }

            override fun onErase(points: List<InkPoint>) {
                inkCanvas.setPenDown(false)
                finishErase(inkCanvas.toPage(points))
            }

            override fun onAltDrawing(points: List<InkPoint>) {
                if (points.isEmpty()) inkCanvas.setPenDown(false)
                val pts = inkCanvas.toPage(points)
                when (app.stylusButtonAction) {
                    StylusButtonAction.LASSO -> inkCanvas.setLassoPath(pts)
                    StylusButtonAction.HIGHLIGHTER ->
                        inkCanvas.setTransientStroke(if (pts.isEmpty()) null else highlighterStroke(pts))
                    else -> Unit
                }
            }

            override fun onAltStroke(points: List<InkPoint>) {
                inkCanvas.setPenDown(false)
                inkCanvas.setTransientStroke(null)
                val pts = inkCanvas.toPage(points)
                when (app.stylusButtonAction) {
                    StylusButtonAction.LASSO -> {
                        switchToLassoTemporarily()
                        finishLasso(pts)
                        // 没圈中任何东西时不会进入选区，直接回到原来的工具
                        if (selection.isEmpty) {
                            toolBeforeAutoLasso?.let { tools = tools.copy(tool = it); applyTools() }
                            toolBeforeAutoLasso = null
                        }
                    }
                    StylusButtonAction.HIGHLIGHTER -> commitStroke(highlighterStroke(pts), pause = true)
                    else -> Unit
                }
            }

            override fun onHover(x: Float, y: Float, eraser: Boolean) {
                inkCanvas.notePenNear()
                // 墨水屏上悬停光标每移动一下就要刷新一次屏幕，只在普通彩屏上显示
                if (isEink) return
                val px = inkCanvas.toPageX(x); val py = inkCanvas.toPageY(y)
                val r = if (eraser || tools.tool == Tool.ERASER) tools.eraserRadius else tools.activeWidth / 2f
                inkCanvas.setHover(px, py, r)
            }

            override fun onHoverExit() = inkCanvas.clearHover()
        }

        inkCanvas.stylusOnly = app.stylusOnly
        input.attach(inkCanvas, rect, listener)
        input.setGestureDelegate({ inkCanvas.handleGesture(it) }, { inkCanvas.wantsFinger(it) })
        input.setStylusButtonAction(app.stylusButtonAction)
        applyPenInputStyle()
        refreshPenEnabled()
        updateExcludeRects()
    }

    // endregion

    // region 工具

    private fun selectTool(tool: Tool) {
        // 用户自己换了工具，就不再自动切回
        toolBeforeAutoLasso = null
        if (tools.tool == tool) return
        tools = tools.copy(tool = tool)
        applyTools()
    }

    private fun openToolSettings(tool: Tool) {
        when (tool) {
            Tool.PEN, Tool.SHAPE -> showPenSettingsDialog(highlighter = false, target = tool)
            Tool.HIGHLIGHTER -> showPenSettingsDialog(highlighter = true, target = tool)
            Tool.ERASER -> showEraserSettingsDialog()
            Tool.TEXT -> showTextDefaultsDialog()
            Tool.LASSO -> clipboardPaste()
            Tool.TAPE -> toggleAllTapesReveal()
            Tool.LASER -> toast("激光教鞭：书写轨迹将在 1.6 秒后自动消隐")
        }
    }

    /** 同步工具栏外观、画笔通道参数，并持久化工具状态。 */
    private fun applyTools() {
        // 工具栏在直绘开启时不会刷新到墨水屏上：暂停直绘，等这一帧画完再恢复，点了马上能看到切换
        withPenPaused {
            toolButtons.forEach { (tool, btn) -> setToolActive(btn, tool == tools.tool) }
            refreshPresets()
            refreshQuickColors()
            if (tools.tool != Tool.ERASER) inkCanvas.hideEraserCursor()
            if (tools.tool != Tool.LASSO) {
                inkCanvas.setLassoPath(emptyList())
                exitSelection()
            }
            applyPenInputStyle()
            EinkRefresher.refreshUiControl(dockTop)
            EinkRefresher.refreshUiControl(layoutPenPropertyBar)
        }
        app.saveToolState(tools)
    }

    /** 当前工具反色显示（黑底白图标），其余为普通按钮。 */
    private fun setToolActive(btn: ImageButton, active: Boolean) {
        btn.setBackgroundResource(if (active) R.drawable.bg_tool_active else R.drawable.bg_button_secondary)
        btn.imageTintList = if (active) android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE) else null
    }

    private fun applyPenInputStyle() {
        val input = penInput ?: return
        val tool = tools.tool
        val writing = tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.SHAPE
        val scale = inkCanvas.viewport.scale
        val density = resources.displayMetrics.density
        val page = note.pages.getOrNull(currentPageIndex)
        // PDF 原文、自定义背景图或带底纹（横线/方格/康奈尔等）的页面上，纸色轨迹会盖住底图与底纹，不能用来预览擦除
        val plainPaper = page != null && !note.isPdf && page.backgroundImage == null && page.template == com.adnote.model.PageTemplate.BLANK
        runCatching {
            when {
                tool == Tool.ERASER && plainPaper -> {
                    // 纯白空白页：硬件直绘层画纸色粗线，宽度等于橡皮直径，擦过的地方立即变白，抬笔后按真实擦除结果刷新
                    input.setEraserTrailStyle()
                    input.setStrokeColor(StrokePainter.parseColor(PaperPresets.find(page!!.backgroundColor).hex))
                    input.setStrokeWidth(tools.eraserRadius * 2f * scale)
                }
                tool == Tool.ERASER || tool == Tool.LASSO -> {
                    // 套索、底图或底纹（横线/方格）上的橡皮：细虚线显示轨迹，不遮挡底纹内容
                    input.setDashStyle()
                    input.setStrokeColor(StrokePainter.parseColor(GUIDE_COLOR))
                    input.setStrokeWidth(1.5f * density)
                }
                else -> {
                    // 荧光笔用马克笔笔型：文石按「变暗」叠加，不会盖住下面的字；抬笔后换成半透明的最终效果
                    input.setPenStyle(tools.activePen)
                    input.setStrokeColor(StrokePainter.parseColor(tools.activeColor))
                    // 直绘层按屏幕像素画，需要乘上当前缩放
                    input.setStrokeWidth(tools.activeWidth * scale)
                }
            }
            // 文字工具只有点按，不需要画出轨迹；其余工具都由硬件直绘层实时显示
            input.setRenderEnabled(tool != Tool.TEXT)
            input.setPredictionEnabled(writing)
        }
    }

    private fun refreshQuickColors() {
        layoutQuickColors.removeAllViews()
        tvToolHint.visibility = View.GONE
        when (tools.tool) {
            Tool.PEN, Tool.SHAPE -> tools.recentPenColors.forEach { hex ->
                layoutQuickColors.addView(Chips.swatch(this, hex, hex.equals(tools.penColor, true)) {
                    tools = tools.copy(penColor = hex)
                    applyTools()
                })
            }
            Tool.HIGHLIGHTER -> PenPresets.HIGHLIGHTERS.forEach { style ->
                layoutQuickColors.addView(Chips.swatch(this, style.hex, style.hex.equals(tools.highlighterColor, true)) {
                    tools = tools.copy(highlighterColor = style.hex)
                    applyTools()
                })
            }
            Tool.TEXT -> PenPresets.ALL.take(5).forEach { style ->
                layoutQuickColors.addView(Chips.swatch(this, style.hex, style.hex.equals(tools.textColor, true)) {
                    tools = tools.copy(textColor = style.hex)
                    applyTools()
                })
            }
            Tool.ERASER -> {
                // 擦除方式与大小直接放在工具栏上，圆点越大橡皮越大；「▾」打开连续调节
                EraserMode.entries.forEach { m ->
                    layoutQuickColors.addView(Chips.text(this, m.displayName, m == tools.eraserMode) {
                        tools = tools.copy(eraserMode = m)
                        applyTools()
                    })
                }
                EraserSizes.PRESETS.forEachIndexed { i, r ->
                    layoutQuickColors.addView(eraserSizeDot(i, r))
                }
                showHint("大小 ▾")
            }
            Tool.LASSO -> showHint(
                if (app.clipboard != null) "圈选后可拖动/缩放/旋转 · 长按套索粘贴" else "圈选后可拖动、缩放、旋转、删除、复制"
            )
            Tool.TAPE -> {
                PenPresets.HIGHLIGHTERS.forEach { style ->
                    layoutQuickColors.addView(Chips.swatch(this, style.hex, style.hex.equals(tools.tapeColor, true)) {
                        tools = tools.copy(tapeColor = style.hex)
                        applyTools()
                    })
                }
                showHint("涂抹生成遮挡条 · 点按胶带揭开/隐藏 · 再点工具按钮一键全显/全隐")
            }
            Tool.LASER -> {
                listOf("#EF4444", "#3B82F6", "#10B981", "#F59E0B").forEach { hex ->
                    layoutQuickColors.addView(Chips.swatch(this, hex, hex.equals(tools.laserColor, true)) {
                        tools = tools.copy(laserColor = hex)
                        applyTools()
                    })
                }
                showHint("激光教鞭 · 演示轨迹 1.6 秒自动消隐")
            }
        }
        val width = String.format(Locale.US, "%.1f", tools.activeWidth)
        if (tools.tool == Tool.PEN) showHint("${tools.penType.displayName} · 粗细 $width  ▾")
        if (tools.tool == Tool.HIGHLIGHTER) showHint("荧光笔 · 粗细 $width  ▾")
        if (tools.tool == Tool.SHAPE) showHint("${tools.penType.displayName} · 粗细 $width · 自动几何规整  ▾")
        if (tools.tool == Tool.TEXT) showHint("点按页面任意位置添加文字框")
    }

    private fun showHint(text: String) {
        tvToolHint.visibility = View.VISIBLE
        tvToolHint.text = text
    }

    private fun newStroke(points: List<InkPoint>): Stroke = tools.newStroke(points).copy(layer = activeLayer())

    private fun startsOnPage(points: List<InkPoint>): Boolean =
        inkCanvas.isOnPage(points[0].x, points[0].y, slop = 4f / inkCanvas.viewport.scale)

    private fun highlighterStroke(points: List<InkPoint>): Stroke =
        Stroke(points = points, width = tools.highlighterWidth, color = tools.highlighterColor,
            pen = PenType.HIGHLIGHTER, layer = activeLayer())

    private fun refreshPresets() {
        val activeIdx = tools.activePresetIndex()
        val presets = tools.presets
        if (presets.size >= 3) {
            updatePresetSlot(slotPreset1, tvPreset1Text, vPreset1Color, presets[0], activeIdx == 0)
            updatePresetSlot(slotPreset2, tvPreset2Text, vPreset2Color, presets[1], activeIdx == 1)
            updatePresetSlot(slotPreset3, tvPreset3Text, vPreset3Color, presets[2], activeIdx == 2)
        }
    }

    private fun updatePresetSlot(slot: View, tv: TextView, dot: View, preset: PenPreset, isActive: Boolean) {
        slot.setBackgroundResource(if (isActive) R.drawable.bg_tool_active else R.drawable.bg_button_secondary)
        tv.setTextColor(if (isActive) android.graphics.Color.WHITE else getColor(R.color.text_primary))
        tv.text = when {
            preset.tool == Tool.HIGHLIGHTER -> "荧"
            preset.width <= 2.2f -> "0.5"
            preset.width <= 4.0f -> "0.8"
            else -> String.format(Locale.US, "%.1f", preset.width)
        }
        val colorInt = StrokePainter.parseColor(preset.color)
        val density = resources.displayMetrics.density
        val dotDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(colorInt)
            // 选中时槽位是黑底，白圈保证黑色圆点仍然看得见
            if (isActive) setStroke((1.5f * density).toInt(), android.graphics.Color.WHITE)
            else setStroke((1 * density).toInt(), android.graphics.Color.parseColor("#80888888"))
        }
        dot.background = dotDrawable
        val tip = if (preset.tool == Tool.HIGHLIGHTER) {
            "快捷荧光笔 · 宽 ${preset.width} (长按将当前笔存入)"
        } else {
            "快捷画笔 · ${preset.penType.displayName} · 宽 ${preset.width} (长按将当前笔存入)"
        }
        slot.contentDescription = tip
    }

    private fun selectPreset(index: Int) {
        if (index !in tools.presets.indices) return
        tools = tools.applyPreset(tools.presets[index])
        applyTools()
        val p = tools.presets[index]
        val name = if (p.tool == Tool.HIGHLIGHTER) "荧光笔" else "${p.penType.displayName} (${p.width})"
        toast("已切换到预设 ${index + 1}：$name")
    }

    private fun saveCurrentToPreset(index: Int) {
        if (index !in tools.presets.indices) return
        tools = tools.savePreset(index)
        applyTools()
        val p = tools.presets[index]
        val name = if (p.tool == Tool.HIGHLIGHTER) "荧光笔" else "${p.penType.displayName} (${p.width})"
        toast("已将当前笔保存至快捷槽位 ${index + 1}：$name")
    }

    private fun applyDockPosition(dock: DockPosition) {
        if (isZenMode) return
        val isVertical = dock == DockPosition.LEFT || dock == DockPosition.RIGHT
        (layoutToolBarContent.parent as? ViewGroup)?.removeView(layoutToolBarContent)

        updateToolBarOrientation(isVertical)
        layoutPenPropertyBar.visibility = View.VISIBLE
        dividerPenProperty.visibility = View.VISIBLE

        when (dock) {
            DockPosition.TOP -> {
                dockTop.addView(layoutToolBarContent)
                dockTop.visibility = View.VISIBLE
                dividerToolTop.visibility = View.VISIBLE
                dockLeft.visibility = View.GONE
                dividerDockLeft.visibility = View.GONE
                dockRight.visibility = View.GONE
                dividerDockRight.visibility = View.GONE
            }
            DockPosition.LEFT -> {
                dockLeft.addView(layoutToolBarContent)
                dockLeft.visibility = View.VISIBLE
                dividerDockLeft.visibility = View.VISIBLE
                dockTop.visibility = View.GONE
                dividerToolTop.visibility = View.GONE
                dockRight.visibility = View.GONE
                dividerDockRight.visibility = View.GONE
            }
            DockPosition.RIGHT -> {
                dockRight.addView(layoutToolBarContent)
                dockRight.visibility = View.VISIBLE
                dividerDockRight.visibility = View.VISIBLE
                dockTop.visibility = View.GONE
                dividerToolTop.visibility = View.GONE
                dockLeft.visibility = View.GONE
                dividerDockLeft.visibility = View.GONE
            }
        }
        inkCanvas.post { updateExcludeRects() }
    }

    private fun updateToolBarOrientation(isVertical: Boolean) {
        val density = resources.displayMetrics.density
        layoutToolBarContent.orientation = if (isVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        layoutPenPresets.orientation = LinearLayout.HORIZONTAL
        layoutQuickColors.orientation = LinearLayout.HORIZONTAL

        val dp22 = (22 * density).toInt()
        val dp1 = (1.5f * density).toInt().coerceAtLeast(1)
        val dp4 = (4 * density).toInt()
        val dp6 = (6 * density).toInt()

        val dividers = listOf<View?>(
            findViewById(R.id.dividerTool1),
            findViewById(R.id.dividerTool2),
        )
        dividers.forEach { v ->
            v?.layoutParams = if (isVertical) {
                LinearLayout.LayoutParams(dp22, dp1).apply { setMargins(0, dp6, 0, dp6) }
            } else {
                LinearLayout.LayoutParams(dp1, dp22).apply { setMargins(dp6, 0, dp6, 0) }
            }
        }

        val btnW = (35 * density).toInt()
        val btnH = (34 * density).toInt()
        val toolBtnParams = if (isVertical) {
            LinearLayout.LayoutParams(btnW, btnH).apply {
                setMargins(0, 0, 0, dp4)
            }
        } else {
            LinearLayout.LayoutParams(btnW, btnH).apply {
                setMargins(0, 0, dp4, 0)
            }
        }
        toolButtons.values.forEach { it.layoutParams = toolBtnParams }

        btnUndo.layoutParams = toolBtnParams
        btnRedo.layoutParams = LinearLayout.LayoutParams(btnW, btnH)

        val extraActionBtns = listOf(btnRuler, btnInsertImage, findViewById<View>(R.id.btnLayers), btnRecord)
        extraActionBtns.forEach { it?.layoutParams = toolBtnParams }

        val slotW = (36 * density).toInt()
        val slotH = (30 * density).toInt()
        val slotParamsWithMargin = LinearLayout.LayoutParams(slotW, slotH).apply {
            setMargins(0, 0, dp4, 0)
        }
        val slotParamsLast = LinearLayout.LayoutParams(slotW, slotH)
        slotPreset1.layoutParams = slotParamsWithMargin
        slotPreset2.layoutParams = slotParamsWithMargin
        slotPreset3.layoutParams = slotParamsLast
    }

    private fun showDockPositionDialog() {
        val options = DockPosition.entries.map { it.displayName }.toTypedArray()
        val currentIdx = DockPosition.entries.indexOf(tools.dockPosition)
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("工具栏停靠位置（避手模式）")
                .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                    val chosen = DockPosition.entries[which]
                    dialog.dismiss()
                    if (chosen != tools.dockPosition) {
                        tools = tools.copy(dockPosition = chosen)
                        applyDockPosition(chosen)
                        app.saveToolState(tools)
                        toast("已切换为：${chosen.displayName}")
                    }
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun enterZenMode() {
        if (isZenMode) return
        isZenMode = true
        toolbar.visibility = View.GONE
        dividerTop.visibility = View.GONE
        dockTop.visibility = View.GONE
        dividerToolTop.visibility = View.GONE
        layoutPenPropertyBar.visibility = View.GONE
        dividerPenProperty.visibility = View.GONE
        dockLeft.visibility = View.GONE
        dividerDockLeft.visibility = View.GONE
        dockRight.visibility = View.GONE
        dividerDockRight.visibility = View.GONE
        btnExitZen.visibility = View.VISIBLE
        inkCanvas.post { updateExcludeRects() }
        toast("已进入全屏禅模式，点按左上角按钮退出")
    }

    private fun exitZenMode() {
        if (!isZenMode) return
        isZenMode = false
        toolbar.visibility = View.VISIBLE
        dividerTop.visibility = View.VISIBLE
        layoutPenPropertyBar.visibility = View.VISIBLE
        dividerPenProperty.visibility = View.VISIBLE
        btnExitZen.visibility = View.GONE
        applyDockPosition(tools.dockPosition)
        inkCanvas.post { updateExcludeRects() }
    }

    // endregion

    // region 页面内容与撤销

    private fun page(): Page = inkCanvas.getPage() ?: note.pages[currentPageIndex]

    private fun history(): EditHistory<Page> {
        val pageId = note.pages.getOrNull(currentPageIndex)?.id ?: ""
        return histories.getOrPut(pageId) { EditHistory() }
    }

    private fun activeLayer(): Int {
        val p = page()
        val id = activeLayers[p.id]
        return if (id != null && p.layers.any { it.id == id }) id else p.layers.lastOrNull()?.id ?: 0
    }

    private fun activeLayerVisible(): Boolean = page().layers.firstOrNull { it.id == activeLayer() }?.visible ?: true

    /** 以一条撤销记录替换整页内容。 */
    private fun commitPage(before: Page, after: Page, pause: Boolean = true) {
        if (after === before) return
        if (pause) withPenPaused { inkCanvas.updatePage(after) } else inkCanvas.updatePage(after)
        history().record(before)
        onPageChanged()
    }

    private fun commitStroke(stroke: Stroke, pause: Boolean) {
        if (!activeLayerVisible()) {
            toast("当前图层已隐藏，请先在「图层」里显示它")
            withPenPaused { inkCanvas.invalidate() }
            return
        }
        val before = page()
        if (pause) withPenPaused { inkCanvas.addStroke(stroke) } else inkCanvas.addStroke(stroke)
        history().record(before)
        onPageChanged()
    }

    private fun onPageChanged() {
        syncCurrentPageFromCanvas()
        scheduleSave()
        updateUndoRedo()
    }

    /** 书写工具抬笔：直尺吸附、形状规整、划掉删除，最后落笔。 */
    private fun handleInk(points: List<InkPoint>) {
        var pts = points
        var replaced = false
        inkCanvas.getRuler()?.snap(pts)?.let { pts = it; replaced = true }
        if (!replaced && tools.tool == Tool.SHAPE) {
            ShapeRecognizer.recognize(pts)?.let { pts = it.points; replaced = true }
        }
        if (!replaced && (tools.tool == Tool.PEN || tools.tool == Tool.HIGHLIGHTER) && app.shapeHold) {
            val d = com.adnote.template.TemplateLayout.density(inkCanvas.width)
            val k = ShapeRecognizer.holdStartIndex(pts, holdMs = 450L, radius = 20f * d)
            val wallHoldMs = SystemClock.uptimeMillis() - lastPenMoveUptime
            if (k > 0) {
                ShapeRecognizer.recognize(pts.subList(0, k + 1))?.let { pts = it.points; replaced = true }
            } else if (wallHoldMs >= 450L) {
                ShapeRecognizer.recognize(pts)?.let { pts = it.points; replaced = true }
            }
        }
        if (!replaced && tools.tool == Tool.PEN && app.scratchOut) {
            val p = page()
            val layer = activeLayer()
            val targets = ScratchOut.targets(p.strokes.filter { it.layer == layer }, pts)
            if (targets.isNotEmpty()) {
                commitPage(p, p.copy(strokes = p.strokes.filterNot { it.id in targets }))
                return
            }
        }
        // 被替换的笔画、荧光笔都需要刷掉硬件直绘层上的原始笔迹
        commitStroke(newStroke(pts), pause = replaced || tools.tool == Tool.HIGHLIGHTER)
    }

    private fun isTap(points: List<InkPoint>): Boolean {
        val slop = 12f / inkCanvas.viewport.scale
        return points.maxOf { it.x } - points.minOf { it.x } < slop && points.maxOf { it.y } - points.minOf { it.y } < slop
    }


    /** 记忆胶带工具：横向划过重点文字即生成一条平整的复习遮挡胶带；若只是点按已有胶带则切换揭开/遮挡。 */
    private fun handleTapeStroke(points: List<InkPoint>) {
        if (points.isEmpty()) return
        if (isTap(points)) {
            handleTap(points[0].x, points[0].y, byPen = true)
            return
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val avgY = points.map { it.y }.average().toFloat()
        val w = (maxX - minX).coerceAtLeast(48f)
        val h = tools.tapeHeight.coerceIn(24f, 80f)
        val tape = TapeMask(
            x = minX,
            y = (avgY - h / 2f).coerceAtLeast(0f),
            width = w,
            height = h,
            color = tools.tapeColor,
            revealed = false,
            layer = activeLayer(),
        )
        val before = page()
        commitPage(before, before.copy(tapes = before.tapes + tape))
    }

    /** 一键将当前页所有记忆胶带全部揭开或全部遮挡（背诵自测完一键核对答案）。 */
    private fun toggleAllTapesReveal() {
        val before = page()
        if (before.tapes.isEmpty()) {
            toast("本页还没有记忆遮挡胶带，在重点文字上横向涂抹即可添加")
            return
        }
        val anyHidden = before.tapes.any { !it.revealed }
        val after = before.copy(tapes = before.tapes.map { it.copy(revealed = anyHidden) })
        commitPage(before, after)
        toast(if (anyHidden) "已揭开本页全部记忆胶带" else "已遮挡本页全部记忆胶带")
    }

    /** 切换底部页面胶片快速导航栏。 */
    private fun toggleFilmstripBar(forceClose: Boolean = false) {
        val show = if (forceClose) false else layoutFilmstripBar.visibility != View.VISIBLE
        layoutFilmstripBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            refreshFilmstripBar()
            EinkRefresher.refreshUiControl(layoutFilmstripBar)
        }
        inkCanvas.post { updateExcludeRects() }
    }

    private fun refreshFilmstripBar() {
        if (layoutFilmstripBar.visibility != View.VISIBLE) return
        layoutFilmstripItems.removeAllViews()
        note.pages.forEachIndexed { idx, p ->
            val label = buildString {
                append("P${idx + 1}")
                if (!p.bookmark.isNullOrBlank()) append("·${p.bookmark.take(6)}")
            }
            layoutFilmstripItems.addView(Chips.text(this, label, idx == currentPageIndex) {
                goToPage(idx)
            })
        }
        if (PageOps.canEditStructure(note)) {
            layoutFilmstripItems.addView(Chips.text(this, "＋ 新页", false) {
                insertPageAfter(currentPageIndex)
            })
        }
    }

    /** 切换右侧 Obsidian 实时双栏对照视图。 */
    private fun toggleSplitObsidian(forceClose: Boolean = false) {
        val show = if (forceClose) false else layoutSplitObsidian.visibility != View.VISIBLE
        if (!show && layoutSplitObsidian.visibility == View.VISIBLE) {
            saveSplitObsidian(silent = true)
        }
        layoutSplitObsidian.visibility = if (show) View.VISIBLE else View.GONE
        btnSplitObsidian.setBackgroundResource(if (show) R.drawable.bg_tool_active else R.drawable.bg_button_secondary)
        if (show) {
            etSplitObsidianMd.setText(note.userMarkdown.orEmpty())
            refreshSplitOcrPreview()
        }
        inkCanvas.post { updateExcludeRects() }
    }

    private fun refreshSplitOcrPreview() {
        if (layoutSplitObsidian.visibility != View.VISIBLE) return
        val cur = note.pages.getOrNull(currentPageIndex)
        val ocr = cur?.recognizedText?.trim().orEmpty()
        tvSplitOcrPreview.text = if (ocr.isNotEmpty()) ocr else "（本页暂无手写识别结果，点顶栏「识别」或翻页自动提取）"
    }

    private fun saveSplitObsidian(silent: Boolean = false) {
        val newText = etSplitObsidianMd.text.toString().trim().ifEmpty { null }
        if (newText != note.userMarkdown) {
            note = note.copy(userMarkdown = newText, updatedAt = System.currentTimeMillis())
            saveNow()
            if (!silent) toast("已保存 Obsidian 备注，下次同步自动推送")
        } else if (!silent) {
            toast("内容已是最新")
        }
    }

    /** 点按：记忆胶带揭开/隐藏、待办清单复选框打勾、页面链接跳转、文字框新建或编辑。 */
    private fun handleTap(x: Float, y: Float, byPen: Boolean) {
        val curPage = page()
        // 1. 优先响应记忆遮挡胶带点按切换（主动回忆揭开/遮挡）
        val hitTape = curPage.tapes.lastOrNull { it.contains(x, y) }
        if (hitTape != null) {
            val updatedTapes = curPage.tapes.map { if (it.id == hitTape.id) it.copy(revealed = !it.revealed) else it }
            commitPage(curPage, curPage.copy(tapes = updatedTapes))
            return
        }
        // 2. 待办清单模板左侧复选框交互式打勾/取消打勾
        if (!byPen && curPage.template == PageTemplate.TODO && toggleTodoCheckboxAt(curPage, x, y)) {
            return
        }
        val hit = TextLayout.hit(curPage, x, y)
        if (tools.tool == Tool.TEXT && byPen) {
            showTextDialog(hit, x, y)
            return
        }
        val link = hit?.linkPageId
        if (link != null) {
            val target = note.pages.indexOfFirst { it.id == link }
            if (target >= 0) goToPage(target) else toast("链接的页面已被删除")
            return
        }
        if (tools.tool == Tool.TEXT) showTextDialog(hit, x, y)
    }


    /** 在待办清单（TODO）模板左侧复选框区域点按时，自动切换打勾笔画。 */
    private fun toggleTodoCheckboxAt(p: Page, x: Float, y: Float): Boolean {
        val d = com.adnote.template.TemplateLayout.density(p.width)
        val boxLeft = 60f * d
        val boxSize = 26f * d
        if (x !in (boxLeft - 14f * d)..(boxLeft + boxSize + 14f * d)) return false
        val firstRowY = 174f * d
        val spacing = 64f * d
        if (y < firstRowY - boxSize - 20f * d) return false
        val row = Math.round((y - (firstRowY - boxSize / 2f - 12f * d)) / spacing)
        val rowLineY = firstRowY + row * spacing
        if (rowLineY > p.height - 60f * d) return false
        val boxTop = rowLineY - boxSize - 12f * d
        val cx = boxLeft + boxSize / 2f
        val cy = boxTop + boxSize / 2f
        if (kotlin.math.abs(y - cy) > boxSize) return false

        // 查找该复选框内是否已有打勾笔画
        val existing = p.strokes.firstOrNull { s ->
            val b = StrokeGeometry.bounds(s)
            val scx = (b[0] + b[2]) / 2f
            val scy = (b[1] + b[3]) / 2f
            scx in boxLeft..(boxLeft + boxSize) && scy in boxTop..(boxTop + boxSize) &&
                (b[2] - b[0]) <= boxSize * 1.8f && (b[3] - b[1]) <= boxSize * 1.8f
        }
        if (existing != null) {
            commitPage(p, p.copy(strokes = p.strokes.filterNot { it.id == existing.id }))
        } else {
            val checkPts = listOf(
                InkPoint(boxLeft + boxSize * 0.18f, boxTop + boxSize * 0.55f, 0.75f),
                InkPoint(boxLeft + boxSize * 0.44f, boxTop + boxSize * 0.82f, 0.85f),
                InkPoint(boxLeft + boxSize * 0.88f, boxTop + boxSize * 0.20f, 0.70f),
            )
            val checkStroke = Stroke(points = checkPts, width = 3.5f * d, color = "#047857", pen = PenType.MARKER, layer = activeLayer())
            commitPage(p, p.copy(strokes = p.strokes + checkStroke))
        }
        return true
    }

    private fun applyErase(path: List<InkPoint>) {
        if (path.isEmpty() || !activeLayerVisible()) return
        val p = page()
        val layer = activeLayer()
        val editable = p.strokes.filter { it.layer == layer }
        val radius = tools.eraserRadius
        val pathBounds = eraserPathBounds(path, radius)
        val next: List<Stroke>
        val changed: List<Stroke>
        when (tools.eraserMode) {
            EraserMode.PARTIAL -> {
                val erased = Eraser.erasePartial(editable, path, radius)
                if (erased === editable) {
                    // 即使没有笔画被切除，也重绘橡皮划过的区域，消除硬件直绘覆盖的白迹，恢复背景格线
                    if (pathBounds != null) inkCanvas.updatePage(p, dirty = pathBounds)
                    return
                }
                next = p.strokes.filter { it.layer != layer } + erased
                val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Stroke, Boolean>())
                kept.addAll(erased)
                changed = editable.filter { it !in kept }
            }
            EraserMode.STROKE -> {
                val hit = Eraser.hitStrokes(editable, path, radius)
                if (hit.isEmpty()) {
                    // 同样刷新橡皮划过的区域，恢复被硬件擦除色覆盖的底纹
                    if (pathBounds != null) inkCanvas.updatePage(p, dirty = pathBounds)
                    return
                }
                next = p.strokes.filterNot { it.id in hit }
                changed = editable.filter { it.id in hit }
            }
        }
        // 重绘范围同时包含被删笔画与橡皮划过的路径，避免留下任何白条痕迹
        val strokeBounds = StrokeGeometry.bounds(changed)
        val dirty = unionBounds(strokeBounds, pathBounds)
        inkCanvas.updatePage(p.copy(strokes = next), dirty = dirty)
    }

    private fun eraserPathBounds(path: List<InkPoint>, radius: Float): FloatArray? {
        if (path.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (pt in path) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }
        return floatArrayOf(minX - radius, minY - radius, maxX + radius, maxY + radius)
    }

    private fun unionBounds(a: FloatArray?, b: FloatArray?): FloatArray? {
        if (a == null) return b
        if (b == null) return a
        return floatArrayOf(
            minOf(a[0], b[0]),
            minOf(a[1], b[1]),
            maxOf(a[2], b[2]),
            maxOf(a[3], b[3]),
        )
    }

    /** 实时擦除：只处理新增的轨迹段，同时显示橡皮光标。 */
    private fun liveErase(points: List<InkPoint>) {
        if (points.isEmpty()) {
            // 手势被取消：把已经擦掉的部分作为一次操作收尾
            if (eraseSnapshot != null) finishErase(emptyList()) else inkCanvas.hideEraserCursor()
            return
        }
        val last = points.last()
        inkCanvas.setEraserCursor(last.x, last.y, tools.eraserRadius)
        if (eraseSnapshot == null) {
            eraseSnapshot = page()
            erasedUpTo = 0
        }
        applyErase(points.subList((erasedUpTo - 1).coerceIn(0, points.size), points.size))
        erasedUpTo = points.size
    }

    private fun finishErase(points: List<InkPoint>) {
        inkCanvas.hideEraserCursor()
        val before = eraseSnapshot ?: page()
        // 文石通道没有实时回调，抬笔时一次性处理整条轨迹
        val remaining = if (eraseSnapshot == null) points else points.subList(
            (erasedUpTo - 1).coerceIn(0, points.size), points.size
        )
        eraseSnapshot = null
        erasedUpTo = 0
        withPenPaused { applyErase(remaining) }
        if (page() !== before) {
            history().record(before)
            onPageChanged()
        }
    }

    private fun undo() {
        exitSelection()
        val prev = history().undo(page()) ?: return
        withPenPaused { inkCanvas.updatePage(withCurrentSettings(prev)) }
        onPageChanged()
    }

    private fun redo() {
        exitSelection()
        val next = history().redo(page()) ?: return
        withPenPaused { inkCanvas.updatePage(withCurrentSettings(next)) }
        onPageChanged()
    }

    /** 撤销只恢复内容（笔画、文字、图片、图层），底纹、书签等页面设置保持当前值。 */
    private fun withCurrentSettings(snapshot: Page): Page {
        val cur = note.pages.getOrNull(currentPageIndex) ?: return snapshot
        return cur.copy(strokes = snapshot.strokes, texts = snapshot.texts, images = snapshot.images, tapes = snapshot.tapes, layers = snapshot.layers)
    }

    private fun updateUndoRedo() {
        val h = history()
        val undoChanged = btnUndo.isEnabled != h.canUndo
        val redoChanged = btnRedo.isEnabled != h.canRedo
        btnUndo.isEnabled = h.canUndo
        btnUndo.alpha = if (h.canUndo) 1f else 0.35f
        btnRedo.isEnabled = h.canRedo
        btnRedo.alpha = if (h.canRedo) 1f else 0.35f
        if (undoChanged || redoChanged) {
            EinkRefresher.refreshFast(btnUndo)
            EinkRefresher.refreshFast(btnRedo)
        }
    }

    private fun confirmClearPage() {
        val p = page()
        if (p.strokes.isEmpty() && p.texts.isEmpty() && p.images.isEmpty()) {
            toast("本页没有内容")
            return
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("清空本页？")
                .setMessage("笔迹、文字和图片都会被清除，可以通过「撤销」恢复。")
                .setPositiveButton("清空") { _, _ ->
                    exitSelection()
                    val before = page()
                    commitPage(before, before.copy(strokes = emptyList(), texts = emptyList(), images = emptyList()))
                }
                .setNegativeButton("取消", null)
        )
    }

    // endregion

    // region 套索选区

    private fun finishLasso(points: List<InkPoint>) {
        inkCanvas.setLassoPath(emptyList())
        val layer = activeLayer()
        val sel = SelectionOps.lasso(page(), points, if (activeLayerVisible()) setOf(layer) else emptySet())
        if (sel.isEmpty) {
            withPenPaused { inkCanvas.invalidate() }
            toast("没有圈中当前图层的内容")
            return
        }
        enterSelection(sel)
    }

    private fun enterSelection(sel: Selection) {
        selection = sel
        inkCanvas.setSelection(sel)
        tvSelectionInfo.text = "已选 ${sel.size} 项 · 拖动移动，拖角缩放，拖顶部圆点旋转"
        layoutSelectionBar.visibility = View.VISIBLE
        refreshPenEnabled()
        updateExcludeRects()
    }

    /** 结束选区。[restoreTool] 为 false 时（紧接着要选中新内容）保持套索，不切回原来的工具。 */
    private fun exitSelection(restoreTool: Boolean = true) {
        if (selection.isEmpty) return
        selection = Selection.EMPTY
        inkCanvas.clearSelection()
        layoutSelectionBar.visibility = View.GONE
        refreshPenEnabled()
        updateExcludeRects()
        // 自动切到套索的，处理完选区后回到原来的工具，否则接着写字会没反应
        if (!restoreTool) return
        val previous = toolBeforeAutoLasso
        toolBeforeAutoLasso = null
        if (previous != null && tools.tool == Tool.LASSO) {
            tools = tools.copy(tool = previous)
            applyTools()
        }
    }

    /** 自动切到套索（插图、粘贴等），记住原来的工具。 */
    private fun switchToLassoTemporarily() {
        if (tools.tool == Tool.LASSO) return
        toolBeforeAutoLasso = tools.tool
        tools = tools.copy(tool = Tool.LASSO)
        applyTools()
    }

    private fun deleteSelection() {
        val before = page()
        val sel = selection
        exitSelection()
        commitPage(before, SelectionOps.delete(before, sel))
    }

    private fun copySelection() {
        app.clipboard = SelectionOps.copy(page(), selection, note.id)
        toast("已拷贝 ${selection.size} 项，可在任意页用「更多 → 粘贴」")
    }

    private fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    private fun duplicateSelection() {
        val before = page()
        val clip = SelectionOps.copy(before, selection, note.id)
        val (after, newSel) = SelectionOps.paste(before, clip, activeLayer(), 40f, 40f)
        exitSelection(restoreTool = false)
        commitPage(before, after)
        enterSelection(newSel)
    }

    private fun clipboardPaste() {
        val clip = app.clipboard ?: run {
            toast("剪贴板为空：先用套索圈选，再点「拷贝」或「剪切」")
            return
        }
        val vp = inkCanvas.viewport
        val before = page()
        val (dx, dy) = SelectionOps.offsetToCenter(clip, before.width, vp.toPageX(vp.viewW / 2f), vp.toPageY(vp.viewH / 2f))
        val (after, newSel) = SelectionOps.paste(before, clip, activeLayer(), dx, dy) { path ->
            repo.copyAsset(clip.sourceNoteId, path, note.id)
        }
        switchToLassoTemporarily()
        commitPage(before, after)
        enterSelection(newSel)
    }

    private fun recolorSelection() {
        val palette = PenPresets.ALL + PenPresets.HIGHLIGHTERS
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("修改选中内容颜色")
                .setItems(palette.map { it.displayName }.toTypedArray()) { _, which ->
                    val before = page()
                    commitPage(before, SelectionOps.recolor(before, selection, palette[which].hex))
                    inkCanvas.setSelection(selection)
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun moveSelectionToLayer() {
        val layers = page().layers
        if (layers.size < 2) {
            toast("只有一个图层，可在「图层」里新建")
            return
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("移到图层")
                .setItems(layers.map { it.name }.toTypedArray()) { _, which ->
                    val target = layers[which].id
                    val before = page()
                    val sel = selection
                    val after = before.copy(
                        strokes = before.strokes.map { if (it.id in sel.strokes) it.copy(layer = target) else it },
                        texts = before.texts.map { if (it.id in sel.texts) it.copy(layer = target) else it },
                        images = before.images.map { if (it.id in sel.images) it.copy(layer = target) else it },
                    )
                    exitSelection()
                    commitPage(before, after)
                }
                .setNegativeButton("取消", null)
        )
    }

    /** 选中的手写识别成文字框，替换原笔迹（可撤销）。 */
    private fun convertSelectionToText() {
        val before = page()
        val sel = selection
        val strokes = before.strokes.filter { it.id in sel.strokes && it.pen != PenType.HIGHLIGHTER }
        if (strokes.isEmpty()) {
            toast("选区里没有手写笔迹")
            return
        }
        toast("正在识别…")
        lifecycleScope.launch {
            val result = app.recognizer.recognize(Page(width = before.width, height = before.height, strokes = strokes))
            val text = result.getOrNull()?.trim()
            if (text.isNullOrEmpty()) {
                toast(result.exceptionOrNull()?.message ?: "没有识别出文字")
                return@launch
            }
            val b = SelectionOps.bounds(before.copy(strokes = strokes, texts = emptyList(), images = emptyList()),
                Selection(strokes = strokes.map { it.id }.toSet())) ?: return@launch
            val lines = text.lines().size.coerceAtLeast(1)
            val size = ((b[3] - b[1]) / lines / TextLayout.LINE_HEIGHT).coerceIn(20f, 96f)
            val box = TextBox(
                x = b[0], y = b[1], text = text, size = size, color = strokes.first().color,
                layer = strokes.first().layer, maxWidth = (b[2] - b[0]).coerceAtLeast(size * 4),
            )
            val cur = page()
            exitSelection()
            commitPage(cur, cur.copy(strokes = cur.strokes.filterNot { it.id in sel.strokes && it.pen != PenType.HIGHLIGHTER }, texts = cur.texts + box))
        }
    }

    // endregion

    // region 文字

    private fun showTextDialog(existing: TextBox?, x: Float, y: Float) {
        val view = layoutInflater.inflate(R.layout.dialog_text_edit, null)
        val et = view.findViewById<EditText>(R.id.etText)
        val sizeChips = view.findViewById<LinearLayout>(R.id.layoutTextSizeChips)
        val colorChips = view.findViewById<LinearLayout>(R.id.layoutTextColorChips)
        val tvLink = view.findViewById<TextView>(R.id.tvTextLink)
        var size = existing?.size ?: tools.textSize
        var color = existing?.color ?: tools.textColor
        var link = existing?.linkPageId
        et.setText(existing?.text.orEmpty())
        et.setSelection(et.text.length)

        lateinit var refresh: () -> Unit
        refresh = {
            sizeChips.removeAllViews()
            TEXT_SIZES.forEach { s ->
                sizeChips.addView(Chips.text(this, s.toInt().toString(), s == size) { size = s; refresh() })
            }
            colorChips.removeAllViews()
            PenPresets.ALL.forEach { st ->
                colorChips.addView(Chips.color(this, st.hex, st.displayName, st.hex.equals(color, true)) { color = st.hex; refresh() })
            }
            val idx = note.pages.indexOfFirst { it.id == link }
            tvLink.text = if (idx >= 0) "点按跳到第 ${idx + 1} 页" else "无链接"
        }
        refresh()
        view.findViewById<View>(R.id.btnTextLink).setOnClickListener {
            val labels = listOf("不链接") + note.pages.mapIndexed { i, p -> "第 ${i + 1} 页" + (p.bookmark?.let { " · $it" } ?: "") }
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle("链接到页面")
                    .setItems(labels.toTypedArray()) { _, which ->
                        link = if (which == 0) null else note.pages[which - 1].id
                        refresh()
                    }
            )
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加文字" else "编辑文字")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val text = et.text.toString().trimEnd()
                val before = page()
                tools = tools.copy(textSize = size, textColor = color)
                app.saveToolState(tools)
                val after = when {
                    existing == null && text.isBlank() -> before
                    existing == null -> before.copy(
                        texts = before.texts + TextBox(x = x, y = y, text = text, size = size, color = color,
                            layer = activeLayer(), linkPageId = link),
                    )
                    text.isBlank() -> before.copy(texts = before.texts.filterNot { it.id == existing.id })
                    else -> before.copy(texts = before.texts.map {
                        if (it.id == existing.id) it.copy(text = text, size = size, color = color, linkPageId = link) else it
                    })
                }
                commitPage(before, after)
            }
            .setNegativeButton("取消", null)
        if (existing != null) {
            builder.setNeutralButton("删除") { _, _ ->
                val before = page()
                commitPage(before, before.copy(texts = before.texts.filterNot { it.id == existing.id }))
            }
        }
        val dialog = showDialog(builder)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        et.requestFocus()
    }

    private fun showTextDefaultsDialog() {
        val labels = TEXT_SIZES.map { "${it.toInt()} 号" + if (it == tools.textSize) "（当前）" else "" }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("默认字号（点按页面添加文字）")
                .setItems(labels.toTypedArray()) { _, which ->
                    tools = tools.copy(textSize = TEXT_SIZES[which])
                    applyTools()
                }
                .setNegativeButton("取消", null)
        )
    }

    // endregion

    // region 图片、背景、图层、直尺

    private fun insertImage(uri: Uri) {
        lifecycleScope.launch {
            val imported = runCatching {
                withContext(Dispatchers.IO) { ImageImporter.importImage(this@EditorActivity, uri, repo, note.id) }
            }.getOrElse {
                toast("插入图片失败：${it.message}")
                return@launch
            }
            val before = page()
            val vp = inkCanvas.viewport
            val w = minOf(imported.width.toFloat(), before.width * 0.6f)
            val h = w * imported.height / imported.width
            val cx = vp.toPageX(vp.viewW / 2f); val cy = vp.toPageY(vp.viewH / 2f)
            val item = ImageItem(
                path = imported.path, x = (cx - w / 2f).coerceAtLeast(0f), y = (cy - h / 2f).coerceAtLeast(0f),
                width = w, height = h, layer = activeLayer(),
            )
            switchToLassoTemporarily()
            commitPage(before, before.copy(images = before.images + item))
            enterSelection(Selection(images = setOf(item.id)))
        }
    }

    private fun applyCustomBackground(uri: Uri) {
        lifecycleScope.launch {
            val imported = runCatching {
                withContext(Dispatchers.IO) { ImageImporter.importBackground(this@EditorActivity, uri, repo, note.id) }
            }.getOrElse {
                toast("设置背景失败：${it.message}")
                return@launch
            }
            updatePageSettings(pendingBackgroundAll) { it.copy(backgroundImage = imported.path) }
            toast("已设置自定义背景")
        }
    }

    /** 修改页面设置（底纹、背景、书签、尺寸）：不进撤销历史，画布同步刷新并保持缩放。 */
    private fun updatePageSettings(allPages: Boolean, change: (Page) -> Page) {
        note = note.copy(
            pages = note.pages.mapIndexed { i, p -> if (allPages || i == currentPageIndex) change(p) else p },
            updatedAt = System.currentTimeMillis(),
        )
        saveNow()
        val cur = note.pages[currentPageIndex]
        withPenPaused {
            inkCanvas.updatePage(page().let { c -> cur.copy(strokes = c.strokes, texts = c.texts, images = c.images, layers = c.layers) })
            inkCanvas.doAfterNextDraw { EinkRefresher.fullRefresh(inkCanvas) }
        }
        updatePageIndicator()
        // 纸色、页面尺寸可能变了：橡皮直绘色、笔宽缩放与书写区域随之更新
        applyPenInputStyle()
        updateExcludeRects()
    }

    private fun toggleRuler() {
        if (inkCanvas.getRuler() != null) {
            withPenPaused { inkCanvas.setRuler(null) }
            btnRuler.setBackgroundResource(R.drawable.bg_button_secondary)
            return
        }
        val vp = inkCanvas.viewport
        val p = page()
        val ruler = Ruler(
            cx = vp.toPageX(vp.viewW / 2f), cy = vp.toPageY(vp.viewH / 2f),
            length = p.width * 0.8f, width = p.width * 0.07f,
        )
        withPenPaused { inkCanvas.setRuler(ruler) }
        btnRuler.setBackgroundResource(R.drawable.bg_tool_selected)
        toast("贴着尺边书写会画出直线；手指拖动移动，双指旋转")
    }

    private fun showLayersDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        lateinit var rebuild: () -> Unit
        fun changeLayers(newLayers: List<Layer>, contentFilter: ((Int) -> Boolean)? = null) {
            val before = page()
            var after = before.copy(layers = newLayers)
            if (contentFilter != null) {
                after = after.copy(
                    strokes = after.strokes.filter { contentFilter(it.layer) },
                    texts = after.texts.filter { contentFilter(it.layer) },
                    images = after.images.filter { contentFilter(it.layer) },
                )
            }
            commitPage(before, after)
            rebuild()
        }

        rebuild = {
            container.removeAllViews()
            val layers = page().layers
            val active = activeLayer()
            // 最上层显示在最前
            layers.asReversed().forEach { layer ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pad = (6 * resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                }
                row.addView(Chips.text(this, (if (layer.id == active) "● " else "○ ") + layer.name, layer.id == active) {
                    activeLayers[page().id] = layer.id
                    rebuild()
                })
                row.addView(Chips.text(this, if (layer.visible) "显示" else "隐藏", false) {
                    changeLayers(page().layers.map { if (it.id == layer.id) it.copy(visible = !it.visible) else it })
                })
                val idx = layers.indexOf(layer)
                if (idx < layers.lastIndex) row.addView(Chips.text(this, "上移", false) {
                    changeLayers(layers.toMutableList().apply { add(idx + 1, removeAt(idx)) })
                })
                if (idx > 0) row.addView(Chips.text(this, "下移", false) {
                    changeLayers(layers.toMutableList().apply { add(idx - 1, removeAt(idx)) })
                })
                container.addView(row)
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Chips.text(this, "＋ 新建图层", false) {
                val ls = page().layers
                val id = (ls.maxOfOrNull { it.id } ?: 0) + 1
                changeLayers(ls + Layer(id, "图层 ${ls.size + 1}"))
                activeLayers[page().id] = id
                rebuild()
            })
            actions.addView(Chips.text(this, "重命名", false) {
                val target = page().layers.first { it.id == activeLayer() }
                promptText("重命名图层", target.name) { name ->
                    changeLayers(page().layers.map { if (it.id == target.id) it.copy(name = name) else it })
                }
            })
            if (layers.size > 1) actions.addView(Chips.text(this, "删除当前图层", false) {
                val target = activeLayer()
                showDialog(
                    AlertDialog.Builder(this)
                        .setTitle("删除图层？")
                        .setMessage("图层上的笔迹、文字和图片会一起删除，可以撤销。")
                        .setPositiveButton("删除") { _, _ ->
                            changeLayers(page().layers.filterNot { it.id == target }) { it != target }
                            activeLayers.remove(page().id)
                            rebuild()
                        }
                        .setNegativeButton("取消", null)
                )
            })
            container.addView(actions)
        }
        rebuild()

        showDialog(
            AlertDialog.Builder(this)
                .setTitle("图层（上方的图层显示在上面）")
                .setView(scroll)
                .setPositiveButton("完成", null)
        )
    }

    private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
        }
        val wrap = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("确定") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
                .setNegativeButton("取消", null)
        )
    }

    // endregion

    // region 录音

    private fun toggleRecording() {
        if (RecordingSession.active != null) {
            stopRecording()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        try {
            RecordingSession.start(this, note.id, note.title, note.pages.getOrNull(currentPageIndex)?.id)
        } catch (e: Exception) {
            toast("无法开始录音：${e.message}")
            return
        }
        toast("开始录音，熄屏或切到别的应用也会继续，可在通知栏停止")
        updateRecordingBar()
    }

    private fun stopRecording() {
        val (active, rec) = RecordingSession.stop(this) ?: return
        updateRecordingBar()
        when {
            rec == null -> toast("录音太短，已丢弃")
            active.noteId == note.id -> toast("已保存录音 ${formatDuration(rec.durationMs)}，在「更多 → 录音」里回放")
            else -> toast("录音已保存到「${active.noteTitle}」")
        }
    }

    /** 录音条跟随进程里的录音状态（可能是在别的笔记里开始的，或已从通知栏停止）。 */
    private fun updateRecordingBar() {
        val recording = RecordingSession.active != null
        handler.removeCallbacks(recordTicker)
        layoutRecordingBar.visibility = if (recording) View.VISIBLE else View.GONE
        btnRecord.setBackgroundResource(if (recording) R.drawable.bg_tool_selected else R.drawable.bg_button_secondary)
        if (recording) handler.post(recordTicker)
        updateExcludeRects()
    }

    private fun showRecordingsDialog() {
        if (note.recordings.isEmpty()) {
            toast("还没有录音，点工具栏的麦克风开始")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        var dialog: AlertDialog? = null
        lateinit var rebuild: () -> Unit
        rebuild = {
            container.removeAllViews()
            note.recordings.forEachIndexed { i, rec ->
                val pageIdx = note.pages.indexOfFirst { it.id == rec.pageId }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = "录音 ${i + 1} · ${formatDuration(rec.durationMs)}" + if (pageIdx >= 0) " · 第 ${pageIdx + 1} 页" else ""
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val playing = player.playingPath == rec.id
                row.addView(Chips.text(this, if (playing) "停止" else "播放", playing) {
                    if (playing) {
                        player.stop()
                        activePlaybackRecording = null
                        inkCanvas.setPlaybackCutoff(null)
                    } else runCatching {
                        if (pageIdx >= 0 && pageIdx != currentPageIndex) goToPage(pageIdx)
                        activePlaybackRecording = rec
                        player.play(repo.assetFile(note.id, rec.path), rec.id) {
                            runOnUiThread {
                                activePlaybackRecording = null
                                inkCanvas.setPlaybackCutoff(null)
                                rebuild()
                            }
                        }
                        handler.post(playbackSyncTicker)
                    }.onFailure { toast("无法播放：${it.message}") }
                    rebuild()
                })
                if (pageIdx >= 0) row.addView(Chips.text(this, "跳到页", false) {
                    dialog?.dismiss()
                    goToPage(pageIdx)
                })
                row.addView(Chips.text(this, "删除", false) {
                    player.stop()
                    activePlaybackRecording = null
                    inkCanvas.setPlaybackCutoff(null)
                    repo.assetFile(note.id, rec.path).delete()
                    note = note.copy(recordings = note.recordings.filterNot { it.id == rec.id }, updatedAt = System.currentTimeMillis())
                    saveNow()
                    rebuild()
                })
                container.addView(row)
            }
        }
        rebuild()
        dialog = showDialog(
            AlertDialog.Builder(this)
                .setTitle("录音（声墨同步回放）")
                .setView(ScrollView(this).apply {
                    overScrollMode = View.OVER_SCROLL_NEVER
                    addView(container)
                })
                .setPositiveButton("关闭") { _, _ ->
                    player.stop()
                    activePlaybackRecording = null
                    inkCanvas.setPlaybackCutoff(null)
                }
        )
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // endregion

    // region 翻页与页面管理

    private fun loadPage(index: Int) {
        if (index !in note.pages.indices) return
        if (index != currentPageIndex) autoRecognize(currentPageIndex)
        exitSelection()
        currentPageIndex = index
        if (note.lastPageIndex != index) {
            note = note.copy(lastPageIndex = index)
            saveNow()
        }
        // 空白页按画布比例调整高度，整页铺满、四周没有写不进去的灰边。
        // 本次打开后擦空的页面还能撤销回原来的内容，尺寸保持不变
        val canUndo = histories[note.pages[index].id]?.canUndo == true
        val fitted = if (canUndo) note else PageOps.fitEmptyPageToCanvas(note, index, inkCanvas.width, inkCanvas.height)
        if (fitted !== note) {
            note = fitted
            saveNow()
        }
        val page = note.pages[index]
        inkCanvas.setPage(page)

        val adapted = tools.adaptToPaper(PaperPresets.find(page.backgroundColor).isDark)
        if (adapted != tools) {
            tools = adapted
            applyTools()
        }
        applyPenInputStyle()
        updatePageIndicator()
        updateUndoRedo()
        updateZoomLabel()
        layoutRecognized.visibility = View.GONE

        val renderer = pdfRenderer
        if (renderer != null) {
            inkCanvas.post {
                val targetW = if (inkCanvas.width > 0) inkCanvas.width else page.width
                val targetH = (targetW.toLong() * page.height / page.width).toInt()
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

    /** 翻页前后都会整页重绘，墨水屏上先暂停硬件直绘再切换，翻页后按设置做快刷/定期全刷。 */
    private fun goToPage(index: Int) {
        if (index == currentPageIndex || index !in note.pages.indices) return
        withPenPaused { loadPage(index) }
        note = note.copy(lastPageIndex = index)
        saveNow()
        inkCanvas.doAfterNextDraw { EinkRefresher.onPageTurned(inkCanvas, app.fullRefreshEvery) }
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
        refreshFilmstripBar()
        refreshSplitOcrPreview()
    }

    private fun updateZoomLabel() {
        val vp = inkCanvas.viewport
        tvZoom.visibility = if (vp.isZoomed) View.VISIBLE else View.GONE
        tvZoom.text = "🔍 ${(vp.zoom * 100).roundToInt()}% · 点按切档 / 长按复位"
        updateExcludeRects()
    }

    private fun insertPageAfter(index: Int) {
        val updated = PageOps.insertBlankAfter(note, index)
        if (updated === note) return
        note = updated
        saveNow()
        withPenPaused { loadPage(index + 1) }
        toast("已新建第 ${index + 2} 页")
    }

    private fun duplicatePage(index: Int) {
        val updated = PageOps.duplicate(note, index)
        if (updated === note) return
        note = updated
        saveNow()
        withPenPaused { loadPage(index + 1) }
        toast("已复制为第 ${index + 2} 页")
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
            toast("笔记至少保留一页")
            return
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("删除第 ${index + 1} 页？")
                .setMessage("页面及其内容将被删除，此操作无法撤销。")
                .setPositiveButton("删除") { _, _ ->
                    val removedId = note.pages[index].id
                    note = PageOps.delete(note, index)
                    histories.remove(removedId)
                    val target = if (index < currentPageIndex) currentPageIndex - 1 else currentPageIndex.coerceAtMost(note.pages.lastIndex)
                    saveNow()
                    currentPageIndex = target
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
        var order = 0
        fun add(id: Int, title: String) = m.add(0, id, order++, title)
        if (app.clipboard != null) add(MENU_PASTE, "粘贴")
        add(MENU_BOOKMARKS, "目录与书签")
        add(MENU_RECORDINGS, "录音" + if (note.recordings.isNotEmpty()) "（${note.recordings.size}）" else "")
        add(MENU_SHARE_PDF, "分享整本 PDF")
        add(MENU_SHARE_PNG, "分享本页图片")
        add(MENU_ZEN, "全屏沉浸模式")
        add(MENU_DOCK, "工具栏停靠位置（当前：${tools.dockPosition.displayName}）")
        if (canEdit) {
            add(MENU_INSERT, "在后面插入新页")
            add(MENU_DUPLICATE, "复制当前页")
            add(MENU_ORIENTATION, "本页横竖切换")
            add(MENU_EXTEND, "向下延长本页")
            add(MENU_DELETE, "删除当前页")
        }
        add(MENU_CLEAR, "清空本页")
        add(MENU_OVERVIEW, "页面概览")
        val hasUserMd = !note.userMarkdown.isNullOrBlank()
        add(MENU_OBSIDIAN_TEXT, if (hasUserMd) "📄 Obsidian 笔记正文" else "📄 添加 Obsidian 备注")
        if (!note.isPdf) add(MENU_TEMPLATE, "底纹与纸张")
        add(MENU_PROPERTIES, "笔记属性")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PASTE -> clipboardPaste()
                MENU_ZEN -> enterZenMode()
                MENU_DOCK -> showDockPositionDialog()
                MENU_OBSIDIAN_TEXT -> showObsidianTextDialog()
                MENU_BOOKMARKS -> showPageOverview(startWithOutline = true)
                MENU_RECORDINGS -> showRecordingsDialog()
                MENU_SHARE_PDF -> shareExport(pdf = true)
                MENU_SHARE_PNG -> shareExport(pdf = false)
                MENU_INSERT -> insertPageAfter(currentPageIndex)
                MENU_DUPLICATE -> duplicatePage(currentPageIndex)
                MENU_ORIENTATION -> {
                    note = PageOps.toggleOrientation(note, currentPageIndex)
                    saveNow()
                    withPenPaused { loadPage(currentPageIndex) }
                }
                MENU_EXTEND -> {
                    val updated = PageOps.extendDown(note, currentPageIndex)
                    if (updated === note) toast("页面已经足够长了") else updatePageSettings(false) { updated.pages[currentPageIndex] }
                }
                MENU_DELETE -> confirmDeletePage(currentPageIndex)
                MENU_CLEAR -> confirmClearPage()
                MENU_OVERVIEW -> showPageOverview(startWithOutline = false)
                MENU_TEMPLATE -> showPageTemplateDialog()
                MENU_PROPERTIES -> showEditMetadataDialog()
            }
            true
        }
        showPopup(popup)
    }

    private fun showObsidianTextDialog() {
        val currentText = note.userMarkdown.orEmpty()
        val et = EditText(this).apply {
            setText(currentText)
            hint = "在 Obsidian 中编写的补充正文、大纲或思考将显示在此处。\n你也可以在此输入文字，下次同步会自动推送到 Obsidian。"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setLineSpacing(6f, 1f)
            background = null
            gravity = android.view.Gravity.TOP
            minLines = 8
        }
        val scroller = ScrollView(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Obsidian 笔记正文")
            .setView(scroller)
            .setPositiveButton("保存") { _, _ ->
                val newText = et.text.toString().trim()
                val updatedText = if (newText.isEmpty()) null else newText
                if (updatedText != note.userMarkdown) {
                    note = note.copy(userMarkdown = updatedText, updatedAt = System.currentTimeMillis())
                    saveNow()
                    toast("已保存，下次同步将推送到 Obsidian")
                }
            }
            .setNegativeButton("关闭", null)
        showDialog(builder)
    }

    private fun showBookmarksDialog() {
        val marked = note.pages.withIndex().filter { it.value.bookmark != null }
        val labels = marked.map { "第 ${it.index + 1} 页 · ${it.value.bookmark}" }
        val current = note.pages[currentPageIndex].bookmark
        val builder = AlertDialog.Builder(this)
            .setTitle(if (marked.isEmpty()) "目录（还没有书签）" else "目录")
            .setPositiveButton(if (current == null) "为本页加书签" else "编辑本页书签") { _, _ ->
                promptText("本页书签", current ?: "第 ${currentPageIndex + 1} 页") { title ->
                    updatePageSettings(false) { it.copy(bookmark = title) }
                }
            }
            .setNegativeButton("关闭", null)
        if (current != null) {
            builder.setNeutralButton("移除本页书签") { _, _ -> updatePageSettings(false) { it.copy(bookmark = null) } }
        }
        if (marked.isNotEmpty()) {
            builder.setItems(labels.toTypedArray()) { _, which -> goToPage(marked[which].index) }
        }
        showDialog(builder)
    }

    private fun shareExport(pdf: Boolean) {
        saveNow()
        toast(if (pdf) "正在导出 PDF…" else "正在导出图片…")
        val snapshot = note
        val index = currentPageIndex
        lifecycleScope.launch {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = NoteExporter.exportDir(this@EditorActivity)
                    if (pdf) {
                        File(dir, NoteExporter.fileName(snapshot, ".pdf")).also {
                            NoteExporter.exportPdf(snapshot, assets, pdfRenderer, it)
                        }
                    } else {
                        File(dir, NoteExporter.fileName(snapshot, "-p${index + 1}.png")).also {
                            NoteExporter.exportPng(snapshot, index, assets, pdfRenderer, it)
                        }
                    }
                }
            }.getOrElse {
                toast("导出失败：${it.message}")
                return@launch
            }
            runCatching { NoteExporter.share(this@EditorActivity, file, if (pdf) "application/pdf" else "image/png") }
                .onFailure { toast("无法分享：${it.message}") }
        }
    }

    private fun showPageOverview(startWithOutline: Boolean = false) {
        val view = layoutInflater.inflate(R.layout.dialog_page_overview, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvPages)
        val tvHint = view.findViewById<TextView>(R.id.tvOverviewHint)
        val btnThumbs = view.findViewById<Button>(R.id.btnOverviewThumbs)
        val btnOutline = view.findViewById<Button>(R.id.btnOverviewOutline)

        val canEdit = PageOps.canEditStructure(note)
        val span = if (resources.configuration.screenWidthDp >= 600) 4 else 3
        val thumbW = (resources.displayMetrics.widthPixels * 0.72f / span).toInt()
        var dialog: AlertDialog? = null

        lateinit var thumbAdapter: PageOverviewAdapter
        lateinit var outlineAdapter: PageOutlineAdapter

        thumbAdapter = PageOverviewAdapter(
            scope = lifecycleScope,
            pdfRenderer = pdfRenderer,
            assets = assets,
            thumbWidthPx = thumbW,
            onClick = { index ->
                dialog?.dismiss()
                goToPage(index)
            },
            onLongClick = { index, anchor ->
                if (canEdit) showPageActionMenu(index, anchor) {
                    thumbAdapter.submit(note.pages, currentPageIndex)
                }
            },
        )

        outlineAdapter = PageOutlineAdapter(
            onPageClick = { index ->
                dialog?.dismiss()
                goToPage(index)
            },
            onEditBookmarkClick = { index ->
                val curBookmark = note.pages[index].bookmark
                promptText("第 ${index + 1} 页书签", curBookmark ?: "第 ${index + 1} 页") { title ->
                    val clean = title.trim().ifEmpty { null }
                    updatePageSettings(false) { p -> if (p.id == note.pages[index].id) p.copy(bookmark = clean) else p }
                    outlineAdapter.submit(note.pages, currentPageIndex)
                    thumbAdapter.submit(note.pages, currentPageIndex)
                }
            }
        )

        fun setOverviewMode(isOutline: Boolean) {
            if (isOutline) {
                btnOutline.setBackgroundResource(R.drawable.bg_chip_selected)
                btnOutline.setTextColor(getColor(R.color.white))
                btnThumbs.setBackgroundResource(android.R.color.transparent)
                btnThumbs.setTextColor(getColor(R.color.text_primary))
                tvHint.text = "点按条目即可跳转；可直接编辑各页书签"
                rv.layoutManager = LinearLayoutManager(this@EditorActivity)
                rv.adapter = outlineAdapter
                outlineAdapter.submit(note.pages, currentPageIndex)
            } else {
                btnThumbs.setBackgroundResource(R.drawable.bg_chip_selected)
                btnThumbs.setTextColor(getColor(R.color.white))
                btnOutline.setBackgroundResource(android.R.color.transparent)
                btnOutline.setTextColor(getColor(R.color.text_primary))
                tvHint.text = if (canEdit) "点按跳转；长按可移动、复制、插入或删除页面" else "点按页面即可跳转"
                rv.layoutManager = GridLayoutManager(this@EditorActivity, span)
                rv.adapter = thumbAdapter
                thumbAdapter.submit(note.pages, currentPageIndex)
            }
            rv.scrollToPosition(currentPageIndex)
        }

        btnThumbs.setOnClickListener { setOverviewMode(false) }
        btnOutline.setOnClickListener { setOverviewMode(true) }

        setOverviewMode(startWithOutline)

        val builder = AlertDialog.Builder(this)
            .setTitle("页面概览与大纲（共 ${note.pages.size} 页）")
            .setView(view)
            .setNegativeButton("关闭", null)
        if (canEdit) builder.setNeutralButton("末尾加页") { _, _ -> insertPageAfter(note.pages.lastIndex) }
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

    // region 设置类对话框

    private fun showPageTemplateDialog() {
        if (note.isPdf) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_paper_template, null)
        val cbApplyToAllPages = dialogView.findViewById<CheckBox>(R.id.cbApplyToAllPages)
        val currentPage = note.pages[currentPageIndex]
        val picker = TemplatePicker(
            context = this,
            categoryContainer = dialogView.findViewById(R.id.layoutTemplateCategoryChips),
            templateContainer = dialogView.findViewById(R.id.layoutTemplateChips),
            colorContainer = dialogView.findViewById(R.id.layoutColorChips),
            initialTemplate = currentPage.template,
            initialColor = currentPage.backgroundColor,
        )
        dialogView.findViewById<TextView>(R.id.tvBackgroundState).text =
            if (currentPage.backgroundImage != null) "本页已有自定义背景" else ""

        var dialog: AlertDialog? = null
        dialogView.findViewById<View>(R.id.btnCustomBackground).setOnClickListener {
            pendingBackgroundAll = cbApplyToAllPages.isChecked
            dialog?.dismiss()
            pickBackground.launch(arrayOf("image/*", "application/pdf"))
        }
        dialogView.findViewById<View>(R.id.btnClearBackground).setOnClickListener {
            dialog?.dismiss()
            updatePageSettings(cbApplyToAllPages.isChecked) { it.copy(backgroundImage = null) }
        }

        dialog = showDialog(
            AlertDialog.Builder(this)
                .setTitle("底纹与纸张")
                .setView(dialogView)
                .setPositiveButton("应用") { _, _ ->
                    updatePageSettings(cbApplyToAllPages.isChecked) {
                        it.copy(template = picker.selectedTemplate, backgroundColor = picker.selectedColor)
                    }
                    val dark = PaperPresets.find(picker.selectedColor).isDark
                    val adapted = tools.adaptToPaper(dark)
                    if (adapted != tools) { tools = adapted; applyTools() }
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun showPenSettingsDialog(highlighter: Boolean, target: Tool) {
        val view = layoutInflater.inflate(R.layout.dialog_pen_settings, null)
        val preview = view.findViewById<StrokePreviewView>(R.id.strokePreview)
        val typeChips = view.findViewById<LinearLayout>(R.id.layoutPenTypeChips)
        val colorChips = view.findViewById<LinearLayout>(R.id.layoutPenColorChips)
        val widthChips = view.findViewById<LinearLayout>(R.id.layoutPenWidthChips)
        val seek = view.findViewById<SeekBar>(R.id.seekPenWidth)
        val tvWidth = view.findViewById<TextView>(R.id.tvPenWidthValue)
        val paper = note.pages[currentPageIndex].backgroundColor

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
            palette.chunked(5).forEachIndexed { rowIdx, rowPalette ->
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (rowIdx > 0) topMargin = (6 * resources.displayMetrics.density).toInt()
                    }
                }
                rowPalette.forEach { s ->
                    rowLayout.addView(Chips.color(this, s.hex, s.displayName, s.hex.equals(color, true)) {
                        color = s.hex; refresh()
                    })
                }
                colorChips.addView(rowLayout)
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
                .setTitle(if (highlighter) "荧光笔设置" else if (target == Tool.SHAPE) "形状笔设置" else "画笔设置")
                .setView(view)
                .setPositiveButton("确定") { _, _ ->
                    tools = if (highlighter) {
                        tools.copy(tool = Tool.HIGHLIGHTER, highlighterColor = color, highlighterWidth = width)
                    } else {
                        tools.withPenColor(color).copy(tool = target, penType = pen, penWidth = width)
                    }
                    applyTools()
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun widthToProgress(w: Float): Int = ((w - PenPresets.WIDTH_MIN) * 2).roundToInt().coerceAtLeast(0)

    /** 工具栏上的橡皮档位：圆点按档位由小到大，当前档位实心。 */
    private fun eraserSizeDot(index: Int, radius: Float): View {
        val density = resources.displayMetrics.density
        val n = EraserSizes.PRESETS.size
        val size = (36 * density).roundToInt()
        return EraserPreviewView(this).apply {
            compact = true
            diameterPx = (8f + 20f * index / (n - 1).coerceAtLeast(1)) * density
            filled = kotlin.math.abs(tools.eraserRadius - radius) < 0.5f
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = "橡皮大小 ${index + 1}"
            setOnClickListener { setEraserRadius(radius) }
        }
    }

    private fun setEraserRadius(radius: Float) {
        tools = tools.copy(tool = Tool.ERASER, eraserRadius = EraserSizes.clamp(radius))
        applyTools()
        flashEraserPreview()
    }

    /** 在页面中央按实际大小短暂显示橡皮范围，换了大小马上能看到擦起来有多大。 */
    private fun flashEraserPreview() {
        val vp = inkCanvas.viewport
        val p = page()
        val cx = vp.toPageX(vp.viewW / 2f).coerceIn(0f, p.width.toFloat())
        val cy = vp.toPageY(vp.viewH / 2f).coerceIn(0f, p.height.toFloat())
        handler.removeCallbacks(hideEraserPreview)
        withPenPaused { inkCanvas.setEraserCursor(cx, cy, tools.eraserRadius) }
        eraserPreviewShown = true
        handler.postDelayed(hideEraserPreview, ERASER_PREVIEW_MS)
    }

    private var eraserPreviewShown = false

    private val hideEraserPreview = Runnable {
        eraserPreviewShown = false
        withPenPaused { inkCanvas.hideEraserCursor() }
    }

    private fun showEraserSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_eraser_settings, null)
        val modeChips = view.findViewById<LinearLayout>(R.id.layoutEraserModeChips)
        val sizeChips = view.findViewById<LinearLayout>(R.id.layoutEraserSizeChips)
        val hint = view.findViewById<TextView>(R.id.tvEraserModeHint)
        val seek = view.findViewById<SeekBar>(R.id.seekEraserSize)
        val tvValue = view.findViewById<TextView>(R.id.tvEraserSizeValue)
        val preview = view.findViewById<EraserPreviewView>(R.id.eraserPreview)
        var mode = tools.eraserMode
        var radius = tools.eraserRadius
        val scale = inkCanvas.viewport.scale

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
            EraserSizes.PRESETS.forEachIndexed { i, r ->
                sizeChips.addView(Chips.text(this, "${i + 1} 档", kotlin.math.abs(r - radius) < 0.5f) {
                    radius = r
                    seek.progress = radiusToProgress(r)
                    refresh()
                })
            }
            tvValue.text = "直径 ${(radius * 2).roundToInt()}（下方圆圈是按当前缩放在屏幕上的实际大小）"
            preview.diameterPx = radius * 2f * scale
        }
        seek.max = radiusToProgress(EraserSizes.MAX)
        seek.progress = radiusToProgress(radius)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                radius = EraserSizes.clamp(EraserSizes.MIN + progress)
                refresh()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
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
                    val changed = radius != tools.eraserRadius
                    tools = tools.copy(tool = Tool.ERASER, eraserMode = mode, eraserRadius = EraserSizes.clamp(radius))
                    applyTools()
                    if (changed) flashEraserPreview()
                }
                .setNegativeButton("取消", null)
        )
    }

    private fun radiusToProgress(r: Float): Int = (r - EraserSizes.MIN).roundToInt().coerceAtLeast(0)

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
                    note = note.copy(title = newTitle, folder = newFolder, tags = newTags, updatedAt = System.currentTimeMillis())
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
            inkCanvas.doAfterNextDraw {
                EinkRefresher.refreshCanvasPartial(inkCanvas)
                refreshPenEnabled()
            }
            inkCanvas.invalidate()
        }
        return builder.show()
    }

    private fun showPopup(popup: PopupMenu) {
        overlayDepth++
        refreshPenEnabled()
        popup.setOnDismissListener {
            overlayDepth = (overlayDepth - 1).coerceAtLeast(0)
            inkCanvas.doAfterNextDraw {
                EinkRefresher.refreshCanvasPartial(inkCanvas)
                refreshPenEnabled()
            }
            inkCanvas.invalidate()
        }
        popup.show()
    }

    // endregion

    // region 画笔通道开关、保存、识别

    private fun refreshPenEnabled() {
        penInput?.setEnabled(resumed && windowFocused && overlayDepth == 0 && selection.isEmpty && !repaintPending)
    }

    /**
     * 窗口失去焦点（下拉通知栏、系统弹窗、音量条等）时暂停直绘：
     * 文石直绘开启时屏幕不刷新，通知栏会显示不出来，笔也会画到通知栏上。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (windowFocused == hasFocus) return
        windowFocused = hasFocus
        refreshPenEnabled()
    }

    /** 暂停直绘、等待应用重绘的内容真正刷到屏幕上；期间直绘保持关闭。 */
    private var repaintPending = false

    private fun doResumeAfterRepaint() {
        if (!repaintPending) return
        repaintPending = false
        handler.removeCallbacks(resumeAfterRepaintFallback)
        refreshPenEnabled()
    }

    private val resumeAfterRepaint: Runnable = Runnable { doResumeAfterRepaint() }

    /** 兜底：画布这一帧没画（不可见、尺寸为 0 等）时，也不能让笔一直写不了。 */
    private val resumeAfterRepaintFallback: Runnable = Runnable { doResumeAfterRepaint() }

    /**
     * 暂停直绘执行 [block]，等画布下一帧画完、墨水屏刷新后再恢复：墨水屏需要这样才能刷出应用自己重绘的内容。
     * 以前是 block 之后立刻恢复，而 invalidate() 要到下一帧才真正绘制，那时直绘已经开了，
     * 文石不刷新普通视图，于是「擦完底纹过一会才出来」「点了工具过一会才变」。
     * 嵌套或连续调用会合并成一次暂停。
     */
    private inline fun withPenPaused(block: () -> Unit) {
        penInput?.setEnabled(false)
        // 先标记再执行：block 里嵌套的 refreshPenEnabled()（如退出选区）不会中途把直绘打开
        val schedule = penInput != null && !repaintPending
        if (schedule) repaintPending = true
        block()
        if (schedule) {
            inkCanvas.doAfterNextDraw {
                EinkRefresher.refreshFast(inkCanvas)
                handler.postDelayed(resumeAfterRepaint, EPD_SETTLE_MS)
            }
            handler.postDelayed(resumeAfterRepaintFallback, REPAINT_TIMEOUT_MS)
        }
        refreshPenEnabled()
    }

    /**
     * 更新直绘层的书写区域：只包含页面本身（缩放、翻页、页面尺寸变化后都要更新），
     * 并排除浮在画布上的面板，笔点上去就是点按钮。坐标都相对画布视图。
     */
    private fun updateExcludeRects() {
        inkCanvas.post {
            val input = penInput ?: return@post
            val origin = IntArray(2).also { inkCanvas.getLocationOnScreen(it) }
            val rects = listOf(layoutRecognized, layoutSelectionBar, layoutRecordingBar, tvZoom, btnExitZen, layoutFilmstripBar)
                .filter { it.visibility == View.VISIBLE && it.width > 0 }
                .map { v ->
                    val loc = IntArray(2).also { v.getLocationOnScreen(it) }
                    val l = loc[0] - origin[0]; val t = loc[1] - origin[1]
                    Rect(l, t, l + v.width, t + v.height)
                }
                .toMutableList()
            inkCanvas.getMinimapScreenRect()?.let { mm ->
                rects.add(Rect(mm.left.toInt(), mm.top.toInt(), mm.right.toInt(), mm.bottom.toInt()))
            }
            val limit = inkCanvas.visiblePageRect()
            val region = limit to rects
            // 没变化就不动直绘层：每次暂停、恢复直绘在墨水屏上都可能多刷一次
            if (region == lastPenRegion) return@post
            lastPenRegion = region
            withPenPaused {
                if (!limit.isEmpty) input.setLimitRect(limit)
                input.setExcludeRects(rects)
            }
        }
    }

    /** 把画布上的页面内容写回笔记；页面设置（底纹、书签、识别结果等）以笔记为准。 */
    private fun syncCurrentPageFromCanvas() {
        val canvasPage = inkCanvas.getPage() ?: return
        if (currentPageIndex !in note.pages.indices) return
        val list = note.pages.toMutableList()
        list[currentPageIndex] = list[currentPageIndex].copy(
            strokes = canvasPage.strokes, texts = canvasPage.texts, images = canvasPage.images, tapes = canvasPage.tapes, layers = canvasPage.layers,
        )
        note = note.copy(pages = list, lastPageIndex = currentPageIndex, updatedAt = System.currentTimeMillis())
    }

    /** 连续书写时合并保存，避免每一笔都序列化整本笔记。 */
    private fun scheduleSave() {
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
    }

    /** 在后台保存：整本笔记序列化可能要几百毫秒，不能卡住书写。之后立即读取也能拿到这一版。 */
    private fun saveNow() {
        handler.removeCallbacks(saveRunnable)
        repo.saveAsync(note)
    }

    /** 后台识别离开的页面，结果用于全文搜索与 Obsidian 同步；笔迹没变就跳过。 */
    private fun autoRecognize(pageIndex: Int) {
        if (!app.autoRecognize) return
        val p = note.pages.getOrNull(pageIndex) ?: return
        if (p.strokes.none { it.pen != PenType.HIGHLIGHTER }) return
        val hash = p.inkHash()
        if (p.recognizedHash == hash) return
        lifecycleScope.launch {
            val rec = app.recognizer
            if (!rec.isModelDownloaded()) return@launch
            val text = rec.recognize(p).getOrNull() ?: return@launch
            val idx = note.pages.indexOfFirst { it.id == p.id }
            if (idx < 0 || note.pages[idx].inkHash() != hash) return@launch
            val list = note.pages.toMutableList()
            list[idx] = list[idx].copy(recognizedText = text, recognizedHash = hash)
            note = note.copy(pages = list, updatedAt = System.currentTimeMillis())
            // 可能发生在离开编辑器之后，直接落盘而不是等延迟保存
            saveNow()
        }
    }

    private fun recognizeCurrentPage() {
        syncCurrentPageFromCanvas()
        val page = note.pages[currentPageIndex]
        if (page.strokes.isEmpty()) {
            toast("当前页无手写笔画")
            return
        }
        toast("正在识别手写内容...")
        val pageId = page.id
        lifecycleScope.launch {
            val result = app.recognizer.recognize(page)
            if (result.isSuccess) {
                val text = result.getOrNull().orEmpty()
                val idx = note.pages.indexOfFirst { it.id == pageId }
                if (idx >= 0) {
                    val list = note.pages.toMutableList()
                    list[idx] = list[idx].copy(recognizedText = text, recognizedHash = page.inkHash())
                    note = note.copy(pages = list, updatedAt = System.currentTimeMillis())
                    saveNow()
                }
                if (note.pages.getOrNull(currentPageIndex)?.id == pageId) {
                    layoutRecognized.visibility = View.VISIBLE
                    tvRecognizedResult.text = "识别结果：\n$text"
                    updateExcludeRects()
                }
                toast("识别完成")
            } else {
                toast(result.exceptionOrNull()?.message ?: "识别失败")
            }
        }
    }

    private val restoreToolHintRunnable = Runnable {
        if (!isFinishing && ::tvToolHint.isInitialized) {
            refreshQuickColors()
            EinkRefresher.refreshUiControl(tvToolHint)
        }
    }

    /**
     * 墨水屏防残影反馈：优先使用工具栏第二行内嵌静态状态胶囊 [tvToolHint] 显示提示，
     * 彻底杜绝系统浮动 Toast 的淡入淡出动画在画布底部留下灰框残影或打断硬件直绘。
     */
    private fun toast(msg: String) {
        if (::tvToolHint.isInitialized && !isZenMode && tools.dockPosition == DockPosition.TOP) {
            handler.removeCallbacks(restoreToolHintRunnable)
            tvToolHint.visibility = View.VISIBLE
            tvToolHint.text = "✓ $msg"
            EinkRefresher.refreshUiControl(tvToolHint)
            handler.postDelayed(restoreToolHintRunnable, 2200L)
            return
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // endregion

    /** 实体翻页键、音量键翻页；外接键盘 Ctrl+Z / Ctrl+Y 撤销重做，Ctrl+V 粘贴。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val volumePaging = app.volumeKeyPaging
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
            KeyEvent.KEYCODE_V -> if (event.isCtrlPressed) { clipboardPaste(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // 吞掉音量键抬起事件，避免翻页时弹出系统音量条
        if (app.volumeKeyPaging && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPenEnabled()
        if (::note.isInitialized) updateRecordingBar()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        refreshPenEnabled()
        if (::note.isInitialized) {
            // 录音不停：熄屏、切到别的应用都继续录，由前台服务保活
            handler.removeCallbacks(recordTicker)
            player.stop()
            syncCurrentPageFromCanvas()
            note = note.copy(lastPageIndex = currentPageIndex)
            saveNow()
            // 离开编辑器（切到别的应用、熄屏）时确保写到磁盘，进程随后被系统回收也不丢
            repo.flush()
            autoRecognize(currentPageIndex)
            // 退出编辑器（不是熄屏/切后台）：后台同步一次
            if (isFinishing) app.requestAutoSync()
        }
        app.saveToolState(tools)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        RecordingSession.removeListener(recordingListener)
        penInput?.detach()
        penInput = null
        pdfRenderer?.close()
        pdfRenderer = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isZenMode) {
            exitZenMode()
            return
        }
        super.onBackPressed()
    }

    companion object {
        private const val EXTRA_NOTE_ID = "extra_note_id"
        private const val EXTRA_PAGE_INDEX = "extra_page_index"
        private const val SAVE_DELAY_MS = 1500L
        private val TEXT_SIZES = listOf(24f, 32f, 40f, 56f, 72f, 96f)
        /** 套索、橡皮路径提示的颜色。 */
        private const val GUIDE_COLOR = "#4B5563"
        private const val ERASER_PREVIEW_MS = 1500L
        /** 画布画完后再等这么久恢复直绘，让渲染线程交付这一帧、墨水屏控制器完成采样。 */
        private const val EPD_SETTLE_MS = 110L
        /** 等不到画布重绘时最多暂停直绘这么久。 */
        private const val REPAINT_TIMEOUT_MS = 380L

        private const val MENU_INSERT = 1
        private const val MENU_DUPLICATE = 2
        private const val MENU_DELETE = 3
        private const val MENU_CLEAR = 4
        private const val MENU_OVERVIEW = 5
        private const val MENU_TEMPLATE = 6
        private const val MENU_PROPERTIES = 7
        private const val MENU_PASTE = 8
        private const val MENU_BOOKMARKS = 9
        private const val MENU_RECORDINGS = 10
        private const val MENU_SHARE_PDF = 11
        private const val MENU_SHARE_PNG = 12
        private const val MENU_ORIENTATION = 13
        private const val MENU_EXTEND = 14
        private const val MENU_OBSIDIAN_TEXT = 15
        private const val MENU_ZEN = 16
        private const val MENU_DOCK = 17

        fun start(context: Context, noteId: String, pageIndex: Int = -1) {
            context.startActivity(intent(context, noteId, pageIndex))
        }

        fun intent(context: Context, noteId: String, pageIndex: Int = -1): Intent =
            Intent(context, EditorActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
                if (pageIndex >= 0) putExtra(EXTRA_PAGE_INDEX, pageIndex)
            }
    }
}
