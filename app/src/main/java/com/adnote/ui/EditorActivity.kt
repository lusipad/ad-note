package com.adnote.ui

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.Button
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

    private lateinit var btnBack: Button
    private lateinit var tvNoteTitle: TextView
    private lateinit var btnPrevPage: Button
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNextPage: Button
    private lateinit var btnAddPage: Button
    private lateinit var btnUndo: Button
    private lateinit var btnRecognize: Button
    private lateinit var btnRefresh: Button
    private lateinit var inkCanvas: InkCanvasView
    private lateinit var layoutRecognized: LinearLayout
    private lateinit var tvRecognizedResult: TextView

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
        btnUndo = findViewById(R.id.btnUndo)
        btnRecognize = findViewById(R.id.btnRecognize)
        btnRefresh = findViewById(R.id.btnRefresh)
        inkCanvas = findViewById(R.id.inkCanvas)
        layoutRecognized = findViewById(R.id.layoutRecognized)
        tvRecognizedResult = findViewById(R.id.tvRecognizedResult)

        updateTitleView()
    }

    private fun updateTitleView() {
        tvNoteTitle.text = buildString {
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
            val w = inkCanvas.width.coerceAtLeast(1404)
            val h = inkCanvas.height.coerceAtLeast(1872)
            val newPage = Page(width = w, height = h)
            note = note.copy(
                pages = note.pages + newPage,
                updatedAt = System.currentTimeMillis()
            )
            saveNote()
            loadPage(note.pages.lastIndex)
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
            override fun onStroke(points: List<InkPoint>) {
                if (points.isEmpty()) return
                val stroke = Stroke(points = points, width = 3.5f)
                inkCanvas.addStroke(stroke)
                syncCurrentPageFromCanvas()
                saveNote()
            }

            override fun onErase(points: List<InkPoint>) {
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
    }

    private fun loadPage(index: Int) {
        if (index !in note.pages.indices) return
        currentPageIndex = index
        val page = note.pages[index]
        inkCanvas.setPage(page)

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
