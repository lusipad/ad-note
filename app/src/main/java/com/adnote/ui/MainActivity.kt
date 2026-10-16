package com.adnote.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adnote.R
import com.adnote.export.RemotePaths
import com.adnote.model.Note
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import com.adnote.model.PinLock
import com.adnote.pen.EinkRefresher
import com.adnote.storage.NoteSortOrder
import com.adnote.storage.NoteSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    enum class ViewMode { ALL, FOLDERS, TAGS }

    private lateinit var tvWorkspaceStats: TextView
    private lateinit var btnNewNote: Button
    private lateinit var btnImportPdf: Button
    private lateinit var btnSort: Button
    private lateinit var btnLayoutMode: Button
    private lateinit var btnBatchMode: Button
    private lateinit var btnSync: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSettings: Button
    private lateinit var layoutBatchBar: View
    private lateinit var tvBatchCount: TextView
    private lateinit var etSearch: EditText
    private lateinit var layoutFilters: LinearLayout
    private lateinit var scrollFilters: View
    private lateinit var btnViewAll: Button
    private lateinit var btnViewFolders: Button
    private lateinit var btnViewTags: Button
    private lateinit var rvNotes: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var btnClearSearch: View
    private lateinit var layoutBookshelfPager: View
    private lateinit var btnBookshelfPrev: Button
    private lateinit var tvBookshelfPage: TextView
    private lateinit var btnBookshelfNext: Button

    private val openPdfLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importPdf(uri)
        }
    }

    private var currentViewMode = ViewMode.ALL
    private var currentSortOrder = NoteSortOrder.UPDATED_DESC
    private var isGridLayout = false
    private var isBatchMode = false
    private var currentBookshelfPage = 0
    private var totalBookshelfPages = 1
    private val selectedBatchIds = mutableSetOf<String>()

    private val collapsedFolders = mutableSetOf<String>()
    private val collapsedTags = mutableSetOf<String>()

    private var selectedFolder: String? = null
    private var selectedTag: String? = null
    private var filterOnlyStarred: Boolean = false
    private var filterOnlyPdf: Boolean = false
    private var filterOnlyUnsynced: Boolean = false

    private val noteAdapter = NoteAdapter(
        onItemClick = { item ->
            if (isBatchMode) {
                toggleBatchSelection(item.id)
            } else {
                openNote(item)
            }
        },
        onItemLongClick = { item ->
            if (isBatchMode) {
                toggleBatchSelection(item.id)
            } else {
                AdNoteApp.instance.repository.load(item.id)?.let(::showNoteActions)
            }
        },
        onStarClick = { item ->
            lifecycleScope.launch {
                val updated = withContext(Dispatchers.IO) { AdNoteApp.instance.repository.toggleStar(item.id) }
                reload()
                if (updated != null) {
                    val tip = if (updated.isStarred) "★ 已置顶收藏「${updated.title}」" else "已取消星标"
                    Toast.makeText(this@MainActivity, tip, Toast.LENGTH_SHORT).show()
                }
            }
        },
        onHeaderClick = { header ->
            if (header.isFolder) {
                if (collapsedFolders.contains(header.key)) collapsedFolders.remove(header.key)
                else collapsedFolders.add(header.key)
            } else {
                if (collapsedTags.contains(header.key)) collapsedTags.remove(header.key)
                else collapsedTags.add(header.key)
            }
            refreshNotes()
        },
        onHeaderAddClick = { folder ->
            showNewNoteDialog(initialFolder = folder)
        },
        onHeaderLongClick = { header -> if (header.isFolder) showFolderActions(header.key) },
    )

    private var allNotes: List<NoteSummary> = emptyList()
    private var trashCount = 0
    private var loaded = false
    private var reloadJob: kotlinx.coroutines.Job? = null
    private var searchDebounceJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        AdNoteApp.instance.repository.purgeExpired()
        handleQuickNoteIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleQuickNoteIntent(intent)
    }

    private fun handleQuickNoteIntent(intent: Intent?) {
        if (intent?.action != ACTION_QUICK_NOTE) return
        intent.action = null
        val title = "速记 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val created = AdNoteApp.instance.repository.create(
            title = title,
            folder = Note.DEFAULT_FOLDER,
            pageWidth = 1404,
            pageHeight = 1872,
            template = PageTemplate.RULED,
        )
        EditorActivity.start(this, created.id)
    }

    private fun openNote(note: NoteSummary) {
        if (!note.isLocked) {
            EditorActivity.start(this, note.id, note.lastPageIndex)
            return
        }
        askPin("输入 PIN 打开「${note.title}」") { pin ->
            if (PinLock.verify(pin, note.lockHash)) EditorActivity.start(this, note.id, note.lastPageIndex)
            else Toast.makeText(this, "PIN 不正确", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askPin(title: String, onPin: (String) -> Unit) {
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4–12 位数字"
        }
        val wrap = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("确定") { _, _ -> onPin(et.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showNoteActions(note: Note) {
        val actions = listOf(
            if (note.isStarred) "☆ 取消星标置顶" else "★ 设为星标置顶",
            "编辑标题、文件夹、标签",
            "设置封面颜色",
            "进入批量多选模式",
            if (note.isLocked) "修改或解除 PIN 锁" else "用 PIN 锁定",
            "移到回收站",
        )
        AlertDialog.Builder(this)
            .setTitle(note.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> lifecycleScope.launch {
                        withContext(Dispatchers.IO) { AdNoteApp.instance.repository.toggleStar(note.id) }
                        reload()
                    }
                    1 -> showEditNoteDialog(note)
                    2 -> showCoverDialog(note)
                    3 -> {
                        enterBatchMode()
                        toggleBatchSelection(note.id)
                    }
                    4 -> if (note.isLocked) {
                        askPin("输入当前 PIN") { pin ->
                            if (PinLock.verify(pin, note.lockHash)) showSetPinDialog(note, allowRemove = true)
                            else Toast.makeText(this, "PIN 不正确", Toast.LENGTH_SHORT).show()
                        }
                    } else showSetPinDialog(note, allowRemove = false)
                    5 -> showDeleteNoteDialog(note)
                }
            }
            .show()
    }

    private fun showEditNoteDialog(note: Note) {
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
                val newFolder = etFolder.text.toString().trim().trim('/').ifEmpty { Note.DEFAULT_FOLDER }
                val newTags = etTags.text.toString().split(',', '，')
                    .map { it.trim().removePrefix("#") }
                    .filter { it.isNotEmpty() }
                if (newTitle == note.title && newFolder == note.folder && newTags == note.tags) return@setPositiveButton
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val repo = AdNoteApp.instance.repository
                        val latest = repo.load(note.id) ?: return@withContext
                        repo.save(latest.copy(title = newTitle, folder = newFolder, tags = newTags, updatedAt = System.currentTimeMillis()))
                    }
                    reload()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCoverDialog(note: Note) {
        val names = listOf("无") + COVER_COLORS.map { it.second }
        AlertDialog.Builder(this)
            .setTitle("封面颜色")
            .setItems(names.toTypedArray()) { _, which ->
                val color = if (which == 0) null else COVER_COLORS[which - 1].first
                AdNoteApp.instance.repository.save(note.copy(coverColor = color))
                reload()
            }
            .show()
    }

    private fun showSetPinDialog(note: Note, allowRemove: Boolean) {
        askPin(if (allowRemove) "设置新 PIN（留空则解除锁定）" else "设置 PIN（4–12 位数字）") { pin ->
            val repo = AdNoteApp.instance.repository
            when {
                pin.isEmpty() && allowRemove -> {
                    repo.save(note.copy(lockHash = null))
                    Toast.makeText(this, "已解除锁定", Toast.LENGTH_SHORT).show()
                }
                PinLock.isValidPin(pin) -> {
                    repo.save(note.copy(lockHash = PinLock.hash(pin)))
                    Toast.makeText(this, "已锁定。注意：这是打开时的访问锁，文件和同步内容并未加密", Toast.LENGTH_LONG).show()
                }
                else -> Toast.makeText(this, "PIN 需为 4–12 位数字", Toast.LENGTH_SHORT).show()
            }
            reload()
        }
    }

    private fun showTrashDialog() {
        val repo = AdNoteApp.instance.repository
        val trashed = repo.listTrash()
        if (trashed.isEmpty()) {
            Toast.makeText(this, "回收站是空的", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val labels = trashed.map { "${it.note.title}（${fmt.format(Date(it.deletedAt))} 删除）" }
        AlertDialog.Builder(this)
            .setTitle("回收站（30 天后自动清除）")
            .setItems(labels.toTypedArray()) { _, which ->
                val item = trashed[which]
                AlertDialog.Builder(this)
                    .setTitle(item.note.title)
                    .setPositiveButton("恢复") { _, _ ->
                        repo.restore(item.note.id)
                        reload()
                        Toast.makeText(this, "已恢复，下次同步会重新上传", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("彻底删除") { _, _ ->
                        repo.purge(item.note.id)
                        reload()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNeutralButton("清空回收站") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("清空回收站？")
                    .setMessage("${trashed.size} 篇笔记将被永久删除，无法恢复。")
                    .setPositiveButton("清空") { _, _ ->
                        trashed.forEach { repo.purge(it.note.id) }
                        reload()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        AdNoteApp.instance.onSyncFinished = { result ->
            reload()
            if (result.failed > 0) Toast.makeText(this, syncMessage(result), Toast.LENGTH_LONG).show()
        }
        reload()
        AdNoteApp.instance.requestAutoSync()
    }

    override fun onPause() {
        super.onPause()
        AdNoteApp.instance.onSyncFinished = null
    }

    private fun reload() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            val repo = AdNoteApp.instance.repository
            val (notes, trash) = withContext(Dispatchers.IO) { repo.summaries() to repo.trashCount() }
            allNotes = notes
            trashCount = trash
            loaded = true
            updateWorkspaceStats()
            refreshNotes()
            refreshFilters()
        }
    }

    private fun updateWorkspaceStats() {
        val totalNotes = allNotes.size
        val totalPages = allNotes.sumOf { it.pageCount }
        val dirtyCount = allNotes.count { it.isDirty }
        val syncText = if (dirtyCount > 0) "$dirtyCount 篇待同步" else "全部已同步"
        tvWorkspaceStats.text = "墨水屏手写 · Obsidian 互联 · 共 $totalNotes 篇 / $totalPages 页 · $syncText"
    }

    private fun initViews() {
        tvWorkspaceStats = findViewById(R.id.tvWorkspaceStats)
        btnNewNote = findViewById(R.id.btnNewNote)
        btnImportPdf = findViewById(R.id.btnImportPdf)
        btnSort = findViewById(R.id.btnSort)
        btnLayoutMode = findViewById(R.id.btnLayoutMode)
        btnBatchMode = findViewById(R.id.btnBatchMode)
        btnSync = findViewById(R.id.btnSync)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSettings = findViewById(R.id.btnSettings)
        layoutBatchBar = findViewById(R.id.layoutBatchBar)
        tvBatchCount = findViewById(R.id.tvBatchCount)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnViewAll = findViewById(R.id.btnViewAll)
        btnViewFolders = findViewById(R.id.btnViewFolders)
        btnViewTags = findViewById(R.id.btnViewTags)
        scrollFilters = findViewById(R.id.scrollFilters)
        layoutFilters = findViewById(R.id.layoutFilters)
        rvNotes = findViewById(R.id.rvNotes)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        layoutBookshelfPager = findViewById(R.id.layoutBookshelfPager)
        btnBookshelfPrev = findViewById(R.id.btnBookshelfPrev)
        tvBookshelfPage = findViewById(R.id.tvBookshelfPage)
        btnBookshelfNext = findViewById(R.id.btnBookshelfNext)

        rvNotes.itemAnimator = null
        applyLayoutManager()
        rvNotes.adapter = noteAdapter
    }

    private fun applyLayoutManager() {
        btnLayoutMode.text = if (isGridLayout) "列表" else "网格"
        if (isGridLayout && currentViewMode == ViewMode.ALL) {
            val cols = if (resources.configuration.screenWidthDp >= 720) 3 else 2
            rvNotes.layoutManager = GridLayoutManager(this, cols)
        } else {
            rvNotes.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun setupListeners() {
        btnNewNote.setOnClickListener { showNewNoteDialog() }
        btnImportPdf.setOnClickListener { openPdfLauncher.launch(arrayOf("application/pdf")) }
        btnSort.setOnClickListener { showSortDialog() }
        btnLayoutMode.setOnClickListener {
            isGridLayout = !isGridLayout
            currentBookshelfPage = 0
            applyLayoutManager()
            refreshNotes()
        }
        btnBatchMode.setOnClickListener {
            if (isBatchMode) exitBatchMode() else enterBatchMode()
        }
        btnSync.setOnClickListener { triggerSync() }
        btnRefresh.setOnClickListener { EinkRefresher.fullRefresh(window.decorView) }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        btnBookshelfPrev.setOnClickListener { prevBookshelfPage() }
        btnBookshelfNext.setOnClickListener { nextBookshelfPage() }

        findViewById<View>(R.id.btnBatchSelectAll).setOnClickListener {
            if (selectedBatchIds.size >= allNotes.size) selectedBatchIds.clear()
            else selectedBatchIds.addAll(allNotes.map { it.id })
            updateBatchBar()
            refreshNotes()
        }
        findViewById<View>(R.id.btnBatchMoveFolder).setOnClickListener { showBatchMoveFolderDialog() }
        findViewById<View>(R.id.btnBatchAddTags).setOnClickListener { showBatchAddTagsDialog() }
        findViewById<View>(R.id.btnBatchStar).setOnClickListener {
            if (selectedBatchIds.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { AdNoteApp.instance.repository.batchSetStarred(selectedBatchIds, true) }
                exitBatchMode()
                reload()
                Toast.makeText(this@MainActivity, "已将 $n 篇笔记设为星标置顶", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.btnBatchTrash).setOnClickListener { showBatchTrashDialog() }
        findViewById<View>(R.id.btnBatchExit).setOnClickListener { exitBatchMode() }

        btnViewAll.setOnClickListener { switchViewMode(ViewMode.ALL) }
        btnViewFolders.setOnClickListener { switchViewMode(ViewMode.FOLDERS) }
        btnViewTags.setOnClickListener { switchViewMode(ViewMode.TAGS) }

        btnClearSearch.setOnClickListener { etSearch.setText("") }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                currentBookshelfPage = 0
                // 墨水屏防频闪：输入搜索词时延迟 280ms 防抖合并刷新，避免每敲一个字整屏闪动一次
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(280L)
                    refreshNotes()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun showSortDialog() {
        val orders = NoteSortOrder.entries
        val currentIdx = orders.indexOf(currentSortOrder)
        AlertDialog.Builder(this)
            .setTitle("笔记排序方式（星标笔记始终优先置顶）")
            .setSingleChoiceItems(orders.map { it.displayName }.toTypedArray(), currentIdx) { dialog, which ->
                currentSortOrder = orders[which]
                dialog.dismiss()
                refreshNotes()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun enterBatchMode() {
        isBatchMode = true
        selectedBatchIds.clear()
        layoutBatchBar.visibility = View.VISIBLE
        btnBatchMode.setBackgroundResource(R.drawable.bg_chip_selected)
        btnBatchMode.setTextColor(getColor(R.color.white))
        updateBatchBar()
        refreshNotes()
    }

    private fun exitBatchMode() {
        isBatchMode = false
        selectedBatchIds.clear()
        layoutBatchBar.visibility = View.GONE
        btnBatchMode.setBackgroundResource(R.drawable.bg_button_secondary)
        btnBatchMode.setTextColor(getColor(R.color.text_primary))
        refreshNotes()
    }

    private fun toggleBatchSelection(noteId: String) {
        if (!selectedBatchIds.add(noteId)) selectedBatchIds.remove(noteId)
        updateBatchBar()
        refreshNotes()
    }

    private fun updateBatchBar() {
        tvBatchCount.text = "已选 ${selectedBatchIds.size} 篇"
    }

    private fun showBatchMoveFolderDialog() {
        if (selectedBatchIds.isEmpty()) {
            Toast.makeText(this, "请先勾选要移动的笔记", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(selectedFolder ?: Note.DEFAULT_FOLDER)
            hint = getString(R.string.note_folder_hint)
        }
        AlertDialog.Builder(this)
            .setTitle("批量移动 ${selectedBatchIds.size} 篇笔记到文件夹")
            .setView(input)
            .setPositiveButton("移动") { _, _ ->
                val target = input.text.toString().trim().trim('/').ifEmpty { Note.DEFAULT_FOLDER }
                val ids = selectedBatchIds.toList()
                lifecycleScope.launch {
                    val moved = withContext(Dispatchers.IO) { AdNoteApp.instance.repository.batchMoveToFolder(ids, target) }
                    exitBatchMode()
                    reload()
                    Toast.makeText(this@MainActivity, "已移动 $moved 篇笔记到「$target」", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchAddTagsDialog() {
        if (selectedBatchIds.isEmpty()) {
            Toast.makeText(this, "请先勾选笔记", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.note_tags_hint)
        }
        AlertDialog.Builder(this)
            .setTitle("为 ${selectedBatchIds.size} 篇笔记批量添加标签")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val tags = input.text.toString().split(',', '，').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
                if (tags.isEmpty()) return@setPositiveButton
                val ids = selectedBatchIds.toList()
                lifecycleScope.launch {
                    val count = withContext(Dispatchers.IO) { AdNoteApp.instance.repository.batchAddTags(ids, tags) }
                    exitBatchMode()
                    reload()
                    Toast.makeText(this@MainActivity, "已为 $count 篇笔记添加标签", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBatchTrashDialog() {
        if (selectedBatchIds.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的笔记", Toast.LENGTH_SHORT).show()
            return
        }
        val ids = selectedBatchIds.toList()
        AlertDialog.Builder(this)
            .setTitle("批量移入回收站？")
            .setMessage("选中的 ${ids.size} 篇笔记将移入回收站，30 天内可随时恢复。")
            .setPositiveButton("移入回收站") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val repo = AdNoteApp.instance.repository
                        for (id in ids) {
                            val n = repo.load(id) ?: continue
                            repo.moveToTrash(n, RemotePaths.inkDir(n))
                        }
                    }
                    exitBatchMode()
                    reload()
                    Toast.makeText(this@MainActivity, "已将 ${ids.size} 篇笔记移入回收站", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun switchViewMode(mode: ViewMode) {
        if (currentViewMode == mode) return
        currentViewMode = mode
        updateViewModeButtons()
        applyLayoutManager()
        scrollFilters.visibility = if (mode == ViewMode.ALL) View.VISIBLE else View.GONE
        refreshNotes()
    }

    private fun updateViewModeButtons() {
        val buttons = listOf(
            btnViewAll to ViewMode.ALL,
            btnViewFolders to ViewMode.FOLDERS,
            btnViewTags to ViewMode.TAGS,
        )
        for ((btn, mode) in buttons) {
            if (currentViewMode == mode) {
                btn.setBackgroundResource(R.drawable.bg_chip_selected)
                btn.setTextColor(getColor(R.color.white))
            } else {
                btn.setBackgroundResource(android.R.color.transparent)
                btn.setTextColor(getColor(R.color.text_primary))
            }
        }
    }

    private fun refreshNotes() {
        val query = etSearch.text.toString().trim()
        val filtered = NoteSummary.filter(
            allNotes,
            query = query,
            tag = if (currentViewMode == ViewMode.ALL) selectedTag else null,
            folder = if (currentViewMode == ViewMode.ALL) selectedFolder else null,
            onlyStarred = if (currentViewMode == ViewMode.ALL) filterOnlyStarred else false,
            onlyPdf = if (currentViewMode == ViewMode.ALL) filterOnlyPdf else false,
            onlyUnsynced = if (currentViewMode == ViewMode.ALL) filterOnlyUnsynced else false,
            sortOrder = currentSortOrder,
        )

        val items = when (currentViewMode) {
            ViewMode.ALL -> {
                filtered.map { NoteListItem.Note(it) }
            }
            ViewMode.FOLDERS -> {
                val folders = NoteSummary.foldersOf(filtered)
                val list = mutableListOf<NoteListItem>()
                for (folder in folders) {
                    val inFolder = filtered.filter { it.folder == folder }
                    if (inFolder.isEmpty() && query.isNotEmpty()) continue
                    val isCollapsed = collapsedFolders.contains(folder)
                    list.add(NoteListItem.Header(
                        key = folder,
                        title = "📁 $folder",
                        count = inFolder.size,
                        isFolder = true,
                        isCollapsed = isCollapsed,
                    ))
                    if (!isCollapsed) {
                        list.addAll(inFolder.map { NoteListItem.Note(it) })
                    }
                }
                list
            }
            ViewMode.TAGS -> {
                val tags = NoteSummary.tagsOf(filtered)
                val list = mutableListOf<NoteListItem>()
                for (tag in tags) {
                    val withTag = filtered.filter { tag in it.tags }
                    if (withTag.isEmpty() && query.isNotEmpty()) continue
                    val isCollapsed = collapsedTags.contains(tag)
                    list.add(NoteListItem.Header(
                        key = tag,
                        title = "🏷️ #$tag",
                        count = withTag.size,
                        isFolder = false,
                        isCollapsed = isCollapsed,
                    ))
                    if (!isCollapsed) {
                        list.addAll(withTag.map { NoteListItem.Note(it, contextTag = tag) })
                    }
                }
                val untagged = filtered.filter { it.tags.isEmpty() }
                if (untagged.isNotEmpty()) {
                    val noTagKey = "__no_tag__"
                    val isCollapsed = collapsedTags.contains(noTagKey)
                    list.add(NoteListItem.Header(
                        key = noTagKey,
                        title = "🏷️ 未分类",
                        count = untagged.size,
                        isFolder = false,
                        isCollapsed = isCollapsed,
                    ))
                    if (!isCollapsed) {
                        list.addAll(untagged.map { NoteListItem.Note(it) })
                    }
                }
                list
            }
        }

        val pageSize = if (isGridLayout && currentViewMode == ViewMode.ALL) 8 else 6
        totalBookshelfPages = ((items.size - 1).coerceAtLeast(0) / pageSize) + 1
        currentBookshelfPage = currentBookshelfPage.coerceIn(0, totalBookshelfPages - 1)
        val fromIdx = currentBookshelfPage * pageSize
        val toIdx = (fromIdx + pageSize).coerceAtMost(items.size)
        val pagedItems = if (items.isEmpty()) emptyList() else items.subList(fromIdx, toIdx)

        noteAdapter.submitList(pagedItems, isBatchMode, selectedBatchIds)
        layoutEmpty.visibility = if (loaded && items.isEmpty()) View.VISIBLE else View.GONE
        layoutBookshelfPager.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        tvBookshelfPage.text = "第 ${currentBookshelfPage + 1} / $totalBookshelfPages 页 · 共 ${filtered.size} 篇"
        val canPrev = currentBookshelfPage > 0
        val canNext = currentBookshelfPage < totalBookshelfPages - 1
        btnBookshelfPrev.isEnabled = canPrev
        btnBookshelfPrev.alpha = if (canPrev) 1f else 0.35f
        btnBookshelfNext.isEnabled = canNext
        btnBookshelfNext.alpha = if (canNext) 1f else 0.35f
        rvNotes.post {
            EinkRefresher.refreshCanvasPartial(rvNotes)
            EinkRefresher.refreshUiControl(layoutBookshelfPager)
        }
    }

    private fun prevBookshelfPage() {
        if (currentBookshelfPage > 0) {
            currentBookshelfPage--
            refreshNotes()
        }
    }

    private fun nextBookshelfPage() {
        if (currentBookshelfPage < totalBookshelfPages - 1) {
            currentBookshelfPage++
            refreshNotes()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val volumePaging = AdNoteApp.instance.volumeKeyPaging
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                nextBookshelfPage()
                return true
            }
            android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                prevBookshelfPage()
                return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumePaging) {
                nextBookshelfPage()
                return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> if (volumePaging) {
                prevBookshelfPage()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (AdNoteApp.instance.volumeKeyPaging &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun clearQuickFilters() {
        selectedFolder = null
        selectedTag = null
        filterOnlyStarred = false
        filterOnlyPdf = false
        filterOnlyUnsynced = false
        currentBookshelfPage = 0
    }

    private fun refreshFilters() {
        layoutFilters.removeAllViews()

        val isAllSelected = selectedFolder == null && selectedTag == null &&
            !filterOnlyStarred && !filterOnlyPdf && !filterOnlyUnsynced
        layoutFilters.addView(createFilterButton("全部 (${allNotes.size})", isAllSelected) {
            clearQuickFilters()
            refreshFilters()
            refreshNotes()
        })

        val starredCount = allNotes.count { it.isStarred }
        if (starredCount > 0 || filterOnlyStarred) {
            layoutFilters.addView(createFilterButton("★ 星标 ($starredCount)", filterOnlyStarred) {
                val next = !filterOnlyStarred
                clearQuickFilters()
                filterOnlyStarred = next
                refreshFilters()
                refreshNotes()
            })
        }

        val pdfCount = allNotes.count { it.isPdf }
        if (pdfCount > 0 || filterOnlyPdf) {
            layoutFilters.addView(createFilterButton("📄 PDF ($pdfCount)", filterOnlyPdf) {
                val next = !filterOnlyPdf
                clearQuickFilters()
                filterOnlyPdf = next
                refreshFilters()
                refreshNotes()
            })
        }

        val dirtyCount = allNotes.count { it.isDirty }
        if (dirtyCount > 0 || filterOnlyUnsynced) {
            layoutFilters.addView(createFilterButton("● 待同步 ($dirtyCount)", filterOnlyUnsynced) {
                val next = !filterOnlyUnsynced
                clearQuickFilters()
                filterOnlyUnsynced = next
                refreshFilters()
                refreshNotes()
            })
        }

        // 文件夹按钮
        val folders = NoteSummary.foldersOf(allNotes)
        for (folder in folders) {
            val isSelected = selectedFolder == folder
            val btn = createFilterButton("📁 $folder", isSelected) {
                val next = if (isSelected) null else folder
                clearQuickFilters()
                selectedFolder = next
                refreshFilters()
                refreshNotes()
            }
            layoutFilters.addView(btn)
        }

        // 标签按钮
        val tags = NoteSummary.tagsOf(allNotes)
        for (tag in tags) {
            val isSelected = selectedTag == tag
            val btn = createFilterButton("#$tag", isSelected) {
                val next = if (isSelected) null else tag
                clearQuickFilters()
                selectedTag = next
                refreshFilters()
                refreshNotes()
            }
            layoutFilters.addView(btn)
        }

        if (trashCount > 0) {
            layoutFilters.addView(createFilterButton("🗑 回收站 ($trashCount)", false) { showTrashDialog() })
        }
    }

    private fun createFilterButton(text: String, isSelected: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
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
            setOnClickListener { onClick() }
        }
    }

    private fun showNewNoteDialog(initialFolder: String? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_note, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.etDialogTitle)
        val etFolder = dialogView.findViewById<EditText>(R.id.etDialogFolder)
        val etTags = dialogView.findViewById<EditText>(R.id.etDialogTags)
        val layoutTemplateCategoryChips = dialogView.findViewById<LinearLayout>(R.id.layoutTemplateCategoryChips)
        val layoutTemplateChips = dialogView.findViewById<LinearLayout>(R.id.layoutTemplateChips)
        val layoutColorChips = dialogView.findViewById<LinearLayout>(R.id.layoutColorChips)

        val defaultTitle = "笔记 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        etTitle.setText(defaultTitle)
        etFolder.setText(initialFolder ?: selectedFolder ?: Note.DEFAULT_FOLDER)
        selectedTag?.let { etTags.setText(it) }

        val picker = TemplatePicker(
            context = this,
            categoryContainer = layoutTemplateCategoryChips,
            templateContainer = layoutTemplateChips,
            colorContainer = layoutColorChips,
            initialTemplate = PageTemplate.RULED,
            initialColor = PaperPresets.WHITE.hex,
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.new_note_dialog_title)
            .setView(dialogView)
            .setPositiveButton("创建") { _, _ ->
                val title = etTitle.text.toString().trim().ifEmpty { defaultTitle }
                val folder = etFolder.text.toString().trim().ifEmpty { Note.DEFAULT_FOLDER }
                val tags = etTags.text.toString().split(',', '，')
                    .map { it.trim().removePrefix("#") }
                    .filter { it.isNotEmpty() }

                val created = AdNoteApp.instance.repository.create(
                    title = title,
                    folder = folder,
                    pageWidth = 1404,
                    pageHeight = 1872,
                    template = picker.selectedTemplate,
                    backgroundColor = picker.selectedColor
                ).copy(tags = tags)
                AdNoteApp.instance.repository.save(created)

                EditorActivity.start(this, created.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteNoteDialog(note: Note) {
        AlertDialog.Builder(this)
            .setTitle("移到回收站")
            .setMessage("「${note.title}」将移到回收站，30 天内可恢复。\n若已同步，远端对应文件将在下次同步时移除（恢复后会重新上传）。")
            .setPositiveButton("移到回收站") { _, _ ->
                val inkDir = RemotePaths.inkDir(note)
                AdNoteApp.instance.repository.moveToTrash(note, inkDir)
                reload()
                Toast.makeText(this, "已移到回收站", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFolderActions(folder: String) {
        val actions = arrayOf("重命名文件夹", "解散文件夹（笔记移到收件箱）")
        AlertDialog.Builder(this)
            .setTitle("📁 $folder")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> showDissolveFolderDialog(folder)
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(folder: String) {
        val input = EditText(this).apply {
            setText(folder)
            setSelection(folder.length)
            hint = getString(R.string.note_folder_hint)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名文件夹")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val to = input.text.toString().trim().trim('/')
                if (to.isEmpty() || to == folder) return@setPositiveButton
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { AdNoteApp.instance.repository.renameFolder(folder, to) }
                    if (selectedFolder == folder) selectedFolder = to
                    collapsedFolders.remove(folder)
                    reload()
                    Toast.makeText(this@MainActivity, "已重命名，影响 $n 篇笔记，下次同步生效", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDissolveFolderDialog(folder: String) {
        if (folder == Note.DEFAULT_FOLDER) {
            Toast.makeText(this, "收件箱不能解散", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("解散文件夹")
            .setMessage("「$folder」及其子文件夹中的笔记将全部移到「${Note.DEFAULT_FOLDER}」。")
            .setPositiveButton("解散") { _, _ ->
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { AdNoteApp.instance.repository.dissolveFolder(folder) }
                    if (selectedFolder == folder) selectedFolder = null
                    collapsedFolders.remove(folder)
                    reload()
                    Toast.makeText(this@MainActivity, "已移动 $n 篇笔记", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun triggerSync() {
        val engine = AdNoteApp.instance.syncEngine
        if (engine == null) {
            AlertDialog.Builder(this)
                .setTitle("未配置 WebDAV")
                .setMessage("请先在设置中配置 WebDAV 服务器地址与账号密码。")
                .setPositiveButton("前往设置") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (AdNoteApp.instance.isSyncing) {
            Toast.makeText(this, "正在同步，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在同步中...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AdNoteApp.instance.runSyncBlocking() }
            if (result == null) {
                Toast.makeText(this@MainActivity, "正在同步，请稍候", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(this@MainActivity, syncMessage(result), Toast.LENGTH_LONG).show()
            reload()
        }
    }

    private fun syncMessage(result: com.adnote.sync.SyncResult): String {
        val pulledText = if (result.pulled > 0) "，拉取 Obsidian 修改 ${result.pulled} 篇" else ""
        return if (result.failed == 0) {
            "同步完成：成功 ${result.success} 篇笔记$pulledText"
        } else {
            "同步完成：成功 ${result.success}，失败 ${result.failed}$pulledText\n错误: ${result.firstError}"
        }
    }

    private fun importPdf(uri: android.net.Uri) {
        Toast.makeText(this, "正在导入并解析 PDF 页面...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                com.adnote.pdf.PdfImporter.importPdfFromUri(
                    context = this@MainActivity,
                    uri = uri,
                    repository = AdNoteApp.instance.repository,
                    folder = selectedFolder ?: Note.DEFAULT_FOLDER
                )
            }
            if (result.isSuccess) {
                val note = result.getOrThrow()
                reload()
                Toast.makeText(this@MainActivity, "PDF 导入成功，共 ${note.pages.size} 页", Toast.LENGTH_SHORT).show()
                EditorActivity.start(this@MainActivity, note.id)
            } else {
                val err = result.exceptionOrNull()?.message ?: "导入失败"
                Toast.makeText(this@MainActivity, "导入失败: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_QUICK_NOTE = "com.adnote.action.QUICK_NOTE"
    }
}

private val COVER_COLORS = listOf(
    "#111827" to "墨黑", "#1E3A8A" to "藏青", "#047857" to "墨绿", "#B91C1C" to "朱红",
    "#B45309" to "琥珀", "#6D28D9" to "葡萄紫", "#9CA3AF" to "浅灰",
)

sealed class NoteListItem {
    data class Header(
        val key: String,
        val title: String,
        val count: Int,
        val isFolder: Boolean,
        val isCollapsed: Boolean,
    ) : NoteListItem()

    data class Note(
        val summary: NoteSummary,
        val contextTag: String? = null,
    ) : NoteListItem()
}

class NoteAdapter(
    private val onItemClick: (NoteSummary) -> Unit,
    private val onItemLongClick: (NoteSummary) -> Unit,
    private val onStarClick: (NoteSummary) -> Unit,
    private val onHeaderClick: (NoteListItem.Header) -> Unit,
    private val onHeaderAddClick: (String) -> Unit,
    private val onHeaderLongClick: (NoteListItem.Header) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_NOTE = 1
    }

    private var items: List<NoteListItem> = emptyList()
    private var batchMode: Boolean = false
    private var selectedIds: Set<String> = emptySet()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(list: List<NoteListItem>, batchMode: Boolean = false, selectedIds: Set<String> = emptySet()) {
        items = list
        this.batchMode = batchMode
        this.selectedIds = selectedIds.toSet()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is NoteListItem.Header -> TYPE_HEADER
        is NoteListItem.Note -> TYPE_NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_section_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_note, parent, false)
            NoteViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NoteListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is NoteListItem.Note -> (holder as NoteViewHolder).bind(item.summary)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        private val tvCount: TextView = view.findViewById(R.id.tvSectionCount)
        private val btnAdd: Button = view.findViewById(R.id.btnSectionAdd)
        private val tvToggle: TextView = view.findViewById(R.id.tvSectionToggle)

        fun bind(header: NoteListItem.Header) {
            tvTitle.text = header.title
            tvCount.text = "${header.count} 篇"
            tvToggle.text = if (header.isCollapsed) "▶" else "▼"
            btnAdd.visibility = if (header.isFolder) View.VISIBLE else View.GONE
            btnAdd.setOnClickListener { onHeaderAddClick(header.key) }
            itemView.setOnClickListener { onHeaderClick(header) }
            itemView.setOnLongClickListener {
                onHeaderLongClick(header)
                true
            }
        }
    }

    inner class NoteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvBatchCheck: TextView = view.findViewById(R.id.tvBatchCheck)
        private val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        private val tvPdfBadge: TextView = view.findViewById(R.id.tvPdfBadge)
        private val tvSyncBadge: TextView = view.findViewById(R.id.tvSyncBadge)
        private val btnStar: TextView = view.findViewById(R.id.btnStar)
        private val tvSnippet: TextView = view.findViewById(R.id.tvSnippet)
        private val tvFolder: TextView = view.findViewById(R.id.tvFolder)
        private val tvPageCount: TextView = view.findViewById(R.id.tvPageCount)
        private val tvDate: TextView = view.findViewById(R.id.tvDate)
        private val tvTags: TextView = view.findViewById(R.id.tvTags)
        private val viewCover: View = view.findViewById(R.id.viewCover)

        fun bind(note: NoteSummary) {
            tvBatchCheck.visibility = if (batchMode) View.VISIBLE else View.GONE
            if (batchMode) {
                tvBatchCheck.text = if (note.id in selectedIds) "☑" else "☐"
            }

            tvTitle.text = buildString {
                if (note.isStarred) append("★ ")
                if (note.isLocked) append("🔒 ")
                append(note.title)
            }
            btnStar.text = if (note.isStarred) "★" else "☆"
            btnStar.setOnClickListener { onStarClick(note) }

            val cover = note.coverColor
            viewCover.visibility = if (cover != null) View.VISIBLE else View.GONE
            if (cover != null) viewCover.setBackgroundColor(StrokePainter.parseColor(cover))
            tvPdfBadge.visibility = if (note.isPdf) View.VISIBLE else View.GONE
            tvFolder.text = note.folder

            if (!note.isLocked && note.snippet.isNotBlank()) {
                tvSnippet.visibility = View.VISIBLE
                tvSnippet.text = note.snippet
            } else {
                tvSnippet.visibility = View.GONE
            }

            val pageProgress = if (note.pageCount > 1 && note.lastPageIndex > 0) {
                "共 ${note.pageCount} 页 · 读至第 ${note.lastPageIndex + 1} 页"
            } else {
                "${note.pageCount} 页"
            }
            tvPageCount.text = pageProgress
            tvDate.text = dateFormat.format(Date(note.updatedAt))

            if (note.isDirty) {
                tvSyncBadge.visibility = View.VISIBLE
                tvSyncBadge.text = "● 待同步"
            } else {
                tvSyncBadge.visibility = View.GONE
            }

            if (note.tags.isNotEmpty()) {
                tvTags.visibility = View.VISIBLE
                tvTags.text = note.tags.joinToString("  ") { "#$it" }
            } else {
                tvTags.visibility = View.GONE
            }

            itemView.setOnClickListener { onItemClick(note) }
            itemView.setOnLongClickListener {
                onItemLongClick(note)
                true
            }
        }
    }
}
