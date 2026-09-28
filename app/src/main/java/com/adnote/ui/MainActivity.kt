package com.adnote.ui

import android.content.Context
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adnote.R
import com.adnote.export.RemotePaths
import com.adnote.model.Note
import com.adnote.storage.NoteSummary
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
import com.adnote.model.PinLock
import com.adnote.pen.EinkRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnNewNote: Button
    private lateinit var btnImportPdf: Button
    private lateinit var btnSync: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSettings: Button
    private lateinit var etSearch: EditText
    private lateinit var layoutFilters: LinearLayout
    private lateinit var rvNotes: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var btnClearSearch: View

    private val openPdfLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importPdf(uri)
        }
    }

    private val noteAdapter = NoteAdapter(
        onItemClick = { item -> openNote(item) },
        // 长按操作要改笔记本身（封面、锁、删除），这时才读取整篇笔记
        onItemLongClick = { item -> AdNoteApp.instance.repository.load(item.id)?.let(::showNoteActions) }
    )

    private var selectedFolder: String? = null
    private var selectedTag: String? = null

    /** 在后台读好的全部笔记；筛选、搜索都在这份列表上做，不再每按一个键就把所有笔记读一遍。 */
    private var allNotes: List<NoteSummary> = emptyList()
    private var trashCount = 0
    private var loaded = false
    private var reloadJob: kotlinx.coroutines.Job? = null
    private var syncing = false

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

    /** 桌面快捷方式 / 小部件「速记」：直接新建一页并打开编辑器。 */
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
            EditorActivity.start(this, note.id)
            return
        }
        askPin("输入 PIN 打开「${note.title}」") { pin ->
            if (PinLock.verify(pin, note.lockHash)) EditorActivity.start(this, note.id)
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
        val actions = listOf("设置封面颜色", if (note.isLocked) "修改或解除 PIN 锁" else "用 PIN 锁定", "移到回收站")
        AlertDialog.Builder(this)
            .setTitle(note.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showCoverDialog(note)
                    1 -> if (note.isLocked) {
                        askPin("输入当前 PIN") { pin ->
                            if (PinLock.verify(pin, note.lockHash)) showSetPinDialog(note, allowRemove = true)
                            else Toast.makeText(this, "PIN 不正确", Toast.LENGTH_SHORT).show()
                        }
                    } else showSetPinDialog(note, allowRemove = false)
                    2 -> showDeleteNoteDialog(note)
                }
            }
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
        reload()
    }

    /** 在后台重新读取全部笔记（打开的笔记还没写完的保存也会读到最新版本），然后刷新列表和筛选条。 */
    private fun reload() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            val repo = AdNoteApp.instance.repository
            val (notes, trash) = withContext(Dispatchers.IO) { repo.summaries() to repo.trashCount() }
            allNotes = notes
            trashCount = trash
            loaded = true
            refreshNotes()
            refreshFilters()
        }
    }

    private fun initViews() {
        btnNewNote = findViewById(R.id.btnNewNote)
        btnImportPdf = findViewById(R.id.btnImportPdf)
        btnSync = findViewById(R.id.btnSync)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSettings = findViewById(R.id.btnSettings)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        layoutFilters = findViewById(R.id.layoutFilters)
        rvNotes = findViewById(R.id.rvNotes)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        rvNotes.layoutManager = LinearLayoutManager(this)
        rvNotes.adapter = noteAdapter
    }

    private fun setupListeners() {
        btnNewNote.setOnClickListener {
            showNewNoteDialog()
        }

        btnImportPdf.setOnClickListener {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }

        btnSync.setOnClickListener {
            triggerSync()
        }

        btnRefresh.setOnClickListener {
            EinkRefresher.fullRefresh(window.decorView)
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnClearSearch.setOnClickListener {
            etSearch.setText("")
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refreshNotes()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun refreshNotes() {
        val query = etSearch.text.toString().trim()
        val list = NoteSummary.filter(allNotes, query = query, tag = selectedTag, folder = selectedFolder)
        noteAdapter.submitList(list)
        // 第一次读完之前不显示「还没有笔记」
        layoutEmpty.visibility = if (loaded && list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshFilters() {
        layoutFilters.removeAllViews()

        // "全部" 按钮
        val isAllSelected = selectedFolder == null && selectedTag == null
        val btnAll = createFilterButton("全部", isAllSelected) {
            selectedFolder = null
            selectedTag = null
            refreshFilters()
            refreshNotes()
        }
        layoutFilters.addView(btnAll)

        // 文件夹按钮
        val folders = NoteSummary.foldersOf(allNotes)
        for (folder in folders) {
            val isSelected = selectedFolder == folder
            val btn = createFilterButton("📁 $folder", isSelected) {
                selectedFolder = if (isSelected) null else folder
                selectedTag = null
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
                selectedTag = if (isSelected) null else tag
                selectedFolder = null
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

    private fun showNewNoteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_note, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.etDialogTitle)
        val etFolder = dialogView.findViewById<EditText>(R.id.etDialogFolder)
        val etTags = dialogView.findViewById<EditText>(R.id.etDialogTags)
        val layoutTemplateCategoryChips = dialogView.findViewById<LinearLayout>(R.id.layoutTemplateCategoryChips)
        val layoutTemplateChips = dialogView.findViewById<LinearLayout>(R.id.layoutTemplateChips)
        val layoutColorChips = dialogView.findViewById<LinearLayout>(R.id.layoutColorChips)

        val defaultTitle = "笔记 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        etTitle.setText(defaultTitle)
        etFolder.setText(selectedFolder ?: Note.DEFAULT_FOLDER)
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

        if (syncing) {
            Toast.makeText(this, "正在同步，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        syncing = true
        Toast.makeText(this, "正在同步中...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { engine.sync() }
            } finally {
                syncing = false
            }
            val msg = if (result.failed == 0) {
                "同步完成：成功 ${result.success} 篇笔记"
            } else {
                "同步完成：成功 ${result.success}，失败 ${result.failed}\n错误: ${result.firstError}"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            reload()
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

class NoteAdapter(
    private val onItemClick: (NoteSummary) -> Unit,
    private val onItemLongClick: (NoteSummary) -> Unit,
) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    private var items: List<NoteSummary> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(list: List<NoteSummary>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = items[position]
        holder.bind(note)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        private val tvPdfBadge: TextView = view.findViewById(R.id.tvPdfBadge)
        private val tvSyncBadge: TextView = view.findViewById(R.id.tvSyncBadge)
        private val tvFolder: TextView = view.findViewById(R.id.tvFolder)
        private val tvPageCount: TextView = view.findViewById(R.id.tvPageCount)
        private val tvDate: TextView = view.findViewById(R.id.tvDate)
        private val tvTags: TextView = view.findViewById(R.id.tvTags)
        private val viewCover: View = view.findViewById(R.id.viewCover)

        fun bind(note: NoteSummary) {
            tvTitle.text = if (note.isLocked) "🔒 ${note.title}" else note.title
            val cover = note.coverColor
            viewCover.visibility = if (cover != null) View.VISIBLE else View.GONE
            if (cover != null) viewCover.setBackgroundColor(StrokePainter.parseColor(cover))
            tvPdfBadge.visibility = if (note.isPdf) View.VISIBLE else View.GONE
            tvFolder.text = note.folder
            tvPageCount.text = "${note.pageCount} 页"
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
