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
import com.adnote.model.PageTemplate
import com.adnote.model.PaperPresets
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
        onItemClick = { note ->
            EditorActivity.start(this, note.id)
        },
        onItemLongClick = { note ->
            showDeleteNoteDialog(note)
        }
    )

    private var selectedFolder: String? = null
    private var selectedTag: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshNotes()
        refreshFilters()
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
        val list = AdNoteApp.instance.repository.search(
            query = query,
            tag = selectedTag,
            folder = selectedFolder
        )
        noteAdapter.submitList(list)
        layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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
        val folders = AdNoteApp.instance.repository.allFolders()
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
        val tags = AdNoteApp.instance.repository.allTags()
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

        var selectedTemplate = PageTemplate.RULED
        var selectedColorHex = PaperPresets.WHITE.hex
        val categories = listOf("全部", "常规", "横线", "方格", "点阵", "练字", "专业")
        var selectedCategory = selectedTemplate.category.let { cat -> if (categories.contains(cat)) cat else "全部" }

        fun refreshTemplateChips() {
            layoutTemplateChips.removeAllViews()
            val filtered = if (selectedCategory == "全部") {
                PageTemplate.entries
            } else {
                PageTemplate.entries.filter { it.category == selectedCategory }
            }
            filtered.forEach { template ->
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

        fun refreshCategoryChips() {
            layoutTemplateCategoryChips.removeAllViews()
            categories.forEach { cat ->
                val isSelected = cat == selectedCategory
                val btn = Button(this).apply {
                    text = cat
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
                        selectedCategory = cat
                        refreshCategoryChips()
                        refreshTemplateChips()
                    }
                }
                layoutTemplateCategoryChips.addView(btn)
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

        refreshCategoryChips()
        refreshTemplateChips()
        refreshColorChips()

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
                    template = selectedTemplate,
                    backgroundColor = selectedColorHex
                ).copy(tags = tags)
                AdNoteApp.instance.repository.save(created)

                EditorActivity.start(this, created.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDeleteNoteDialog(note: Note) {
        AlertDialog.Builder(this)
            .setTitle("删除笔记")
            .setMessage("确定删除笔记「${note.title}」吗？\n若已同步，远端对应文件将在下次同步时一并清除。")
            .setPositiveButton("删除") { _, _ ->
                val inkDir = RemotePaths.inkDir(note)
                AdNoteApp.instance.repository.delete(note, inkDir)
                refreshNotes()
                refreshFilters()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
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

        Toast.makeText(this, "正在同步中...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.sync()
            }
            val msg = if (result.failed == 0) {
                "同步完成：成功 ${result.success} 篇笔记"
            } else {
                "同步完成：成功 ${result.success}，失败 ${result.failed}\n错误: ${result.firstError}"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            refreshNotes()
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
                refreshNotes()
                refreshFilters()
                Toast.makeText(this@MainActivity, "PDF 导入成功，共 ${note.pages.size} 页", Toast.LENGTH_SHORT).show()
                EditorActivity.start(this@MainActivity, note.id)
            } else {
                val err = result.exceptionOrNull()?.message ?: "导入失败"
                Toast.makeText(this@MainActivity, "导入失败: $err", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class NoteAdapter(
    private val onItemClick: (Note) -> Unit,
    private val onItemLongClick: (Note) -> Unit,
) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    private var items: List<Note> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun submitList(list: List<Note>) {
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

        fun bind(note: Note) {
            tvTitle.text = note.title
            tvPdfBadge.visibility = if (note.isPdf) View.VISIBLE else View.GONE
            tvFolder.text = note.folder
            tvPageCount.text = "${note.pages.size} 页"
            tvDate.text = dateFormat.format(Date(note.updatedAt))

            if (note.isDirty || note.sync.lastSyncedAt == 0L) {
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
