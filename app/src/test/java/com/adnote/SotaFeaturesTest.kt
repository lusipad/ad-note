package com.adnote

import com.adnote.ink.Ruler
import com.adnote.ink.Selection
import com.adnote.ink.SelectionOps
import com.adnote.ink.ShapeRecognizer
import com.adnote.model.InkPoint
import com.adnote.model.Note
import com.adnote.model.Page
import com.adnote.model.TapeMask
import com.adnote.model.Tool
import com.adnote.model.ToolState
import com.adnote.storage.NoteRepository
import com.adnote.storage.NoteSortOrder
import com.adnote.storage.NoteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI

class SotaFeaturesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun starredNotesAndMultiSortOrderWorkCorrectly() {
        val n1 = NoteSummary(
            id = "1", title = "B 周会纪要", folder = "工作",
            updatedAt = 1000L, createdAt = 500L, pageCount = 2, isStarred = false,
            snippet = "讨论 Q4 路线图",
        )
        val n2 = NoteSummary(
            id = "2", title = "A 架构设计", folder = "工作",
            updatedAt = 800L, createdAt = 900L, pageCount = 10, isStarred = true,
            snippet = "核心模块拆分",
        )
        val n3 = NoteSummary(
            id = "3", title = "C 读书笔记", folder = "读书",
            updatedAt = 2000L, createdAt = 100L, pageCount = 5, isStarred = false,
            snippet = "第三章摘录",
        )
        val all = listOf(n1, n2, n3)

        // 星标筛选
        val starredOnly = NoteSummary.filter(all, "", onlyStarred = true)
        assertEquals(listOf("2"), starredOnly.map { it.id })

        // 按最近修改排序：星标笔记 (n2) 始终置顶，随后按 updatedAt 倒序 (n3 -> n1)
        val byUpdated = NoteSummary.filter(all, "", sortOrder = NoteSortOrder.UPDATED_DESC)
        assertEquals(listOf("2", "3", "1"), byUpdated.map { it.id })

        // 按页数排序：星标置顶 (n2: 10页)，随后按页数倒序 (n3: 5页 -> n1: 2页)
        val byPages = NoteSummary.filter(all, "", sortOrder = NoteSortOrder.PAGES_DESC)
        assertEquals(listOf("2", "3", "1"), byPages.map { it.id })
    }

    @Test
    fun repositoryBatchOperationsAndStarToggle() {
        val repo = NoteRepository(tempFolder.root)
        val a = repo.create("笔记 A", "收件箱", 1404, 1872, now = 100L)
        val b = repo.create("笔记 B", "收件箱", 1404, 1872, now = 200L)

        // 切换星标
        val toggled = repo.toggleStar(a.id)!!
        assertTrue(toggled.isStarred)
        assertFalse(repo.toggleStar(a.id)!!.isStarred)

        // 批量移动文件夹与批量加标签
        assertEquals(2, repo.batchMoveToFolder(listOf(a.id, b.id), "项目/核心", now = 500L))
        assertEquals("项目/核心", repo.load(a.id)!!.folder)
        assertEquals("项目/核心", repo.load(b.id)!!.folder)

        assertEquals(2, repo.batchAddTags(listOf(a.id, b.id), listOf("#重点", "里程碑"), now = 600L))
        assertEquals(listOf("重点", "里程碑"), repo.load(a.id)!!.tags)
    }

    @Test
    fun recognizesSingleStrokeArrow() {
        // 从 (0, 100) 画直线到 (200, 100)，随后折返画箭头翼到 (170, 80)
        val shaft = (0..20).map { i -> InkPoint(i * 10f, 100f, 0.6f) }
        val wing = (1..6).map { i -> InkPoint(200f - i * 5f, 100f - i * 3.5f, 0.6f) }
        val result = ShapeRecognizer.recognize(shaft + wing)
        assertNotNull(result)
        assertEquals(ShapeRecognizer.Kind.ARROW, result!!.kind)
    }

    @Test
    fun rulerMagneticAngleSnappingAndReadout() {
        val r0 = Ruler(cx = 500f, cy = 500f, angle = Math.toRadians(44.0).toFloat())
        val snapped = r0.withMagneticSnap(snapToleranceDeg = 2.5f)
        assertEquals(45.0f, snapped.angleDegrees, 0.05f)

        val unsnapped = Ruler(cx = 500f, cy = 500f, angle = Math.toRadians(37.0).toFloat()).withMagneticSnap(2.0f)
        assertEquals(37.0f, unsnapped.angleDegrees, 0.1f)
    }

    @Test
    fun studyTapeMaskSelectionAndRevealToggle() {
        val tape = TapeMask(x = 100f, y = 200f, width = 240f, height = 36f, color = "#FACC15", revealed = false)
        assertTrue(tape.contains(150f, 218f))
        assertFalse(tape.contains(50f, 50f))

        val page = Page(width = 1404, height = 1872, tapes = listOf(tape))
        val sel = Selection(tapes = setOf(tape.id))
        val moved = SelectionOps.transform(page, sel, com.adnote.ink.Affine.translate(20f, 30f))
        assertEquals(120f, moved.tapes.single().x, 0.01f)
        assertEquals(230f, moved.tapes.single().y, 0.01f)

        val tools = ToolState(tool = Tool.TAPE, tapeColor = "#4ADE80")
        assertEquals("#4ADE80", tools.activeColor)
    }

    @Test
    fun noteSnippetPreviewRespectsPinLock() {
        val openNote = Note(
            title = "公开笔记",
            folder = "收件箱",
            pages = listOf(Page(width = 100, height = 100, recognizedText = "第一行手写识别内容\n第二行内容")),
            createdAt = 1L,
            updatedAt = 2L,
        )
        assertTrue(openNote.snippetPreview().contains("第一行手写识别内容"))

        val lockedNote = openNote.copy(lockHash = "locked_hash")
        assertEquals("", lockedNote.snippetPreview())
    }

    @Test
    fun drawAndHoldSmartShapeRecognitionAndDynamicStretch() {
        // 轻微倾斜的准水平线：首尾 (0, 100) -> (300, 103)
        val wobblyH = (0..30).map { i ->
            InkPoint(i * 10f, 100f + (if (i % 2 == 0) 1.5f else -1.5f) + i * 0.1f, 0.6f)
        }
        val recognized = ShapeRecognizer.recognize(wobblyH)
        assertNotNull(recognized)
        assertEquals(ShapeRecognizer.Kind.LINE, recognized!!.kind)
        // 智能水平吸附：两端 y 坐标完全一致
        assertEquals(recognized.points.first().y, recognized.points.last().y, 0.001f)

        // 动态拉伸：笔尖拖拽到 (450, 103) 时自动动态延长
        val stretched = ShapeRecognizer.stretch(recognized.kind, recognized.points, InkPoint(450f, 103f, 0.6f))
        assertEquals(0f, stretched.first().x, 0.01f)
        assertEquals(450f, stretched.last().x, 0.01f)
        assertEquals(stretched.first().y, stretched.last().y, 0.001f)
    }
}
