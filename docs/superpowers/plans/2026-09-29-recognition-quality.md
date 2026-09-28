# 手写识别质量 实施计划（子系统 B）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把整页笔迹按行拆开分别识别，让多行笔记的识别结果不再乱序；允许用户选择识别语言。

**Architecture:** 新增纯算法 `ink/LineGrouper`（按笔画包围盒垂直重叠聚类，保持书写顺序），`MlKitRecognizer` 对每一行分别构造 ML Kit `Ink` 并识别，结果按行连接。识别语言作为一个设置项保存在 `AdNoteApp`，变更时重建识别器实例。

**Tech Stack:** Kotlin 2.0、Google ML Kit Digital Ink Recognition 18.1.0、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-09-29-functional-fixes.md` 第 3.3 节。

## Global Constraints

- `ink/` 包不引用 `android.*`，`LineGrouper` 必须能在 JVM 单元测试跑通。
- `Recognizer` 接口签名不变（`recognize(page)`、`isModelDownloaded()`、`downloadModel()`），编辑器不需要改。
- 不新增依赖。
- 每个任务结束时 `./gradlew testDebugUnitTest` 全绿再提交。

## Review Focus

1. **一个点或一条很扁的横线**（高度接近 0）：不能因为重叠比例算不出来而单独成行。Task 1 的 `dotJoinsNearestLine` 覆盖。
2. **先写下面一行再写上面一行**：输出仍要从上到下。Task 1 的 `linesSortedTopToBottom` 覆盖。
3. **只有荧光笔的页面**：分组结果为空，识别返回空串而不是异常。Task 1 的 `highlighterIgnored` 与 Task 2 的实现共同覆盖。
4. **用户切换语言但新模型未下载**：识别应返回失败信息「模型未下载」而不是用旧语言模型，由 `MlKitRecognizer` 按新语言实例化保证；模拟器手工验证。
5. **某一行识别为空串**（比如只是一个涂抹）：不能在结果中留下空行。Task 2 实现里过滤空行。

---

### Task 1: LineGrouper 按行分组

**Files:**
- Create: `app/src/main/java/com/adnote/ink/LineGrouper.kt`
- Test: `app/src/test/java/com/adnote/ink/LineGrouperTest.kt`

**Interfaces:**
- Produces:
  - `object LineGrouper { fun group(strokes: List<Stroke>, minOverlap: Float = 0.4f, minHeightRatio: Float = 0.3f): List<List<Stroke>> }`
  - `data class LineGrouper.Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)`、`fun LineGrouper.boundsOf(stroke: Stroke): Bounds`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/adnote/ink/LineGrouperTest.kt`：

```kotlin
package com.adnote.ink

import com.adnote.model.InkPoint
import com.adnote.model.PenType
import com.adnote.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineGrouperTest {

    /** 一个矩形范围内的两点笔画。 */
    private fun stroke(id: String, left: Float, top: Float, right: Float, bottom: Float, pen: PenType = PenType.FOUNTAIN) =
        Stroke(id = id, points = listOf(InkPoint(left, top), InkPoint(right, bottom)), pen = pen)

    @Test
    fun twoRowsSplitIntoTwoLines() {
        // 两行各三个字，交错书写
        val strokes = listOf(
            stroke("a1", 10f, 100f, 40f, 140f),
            stroke("b1", 10f, 200f, 40f, 240f),
            stroke("a2", 50f, 105f, 80f, 138f),
            stroke("b2", 50f, 202f, 80f, 241f),
            stroke("a3", 90f, 100f, 120f, 140f),
            stroke("b3", 90f, 200f, 120f, 240f),
        )
        val lines = LineGrouper.group(strokes)
        assertEquals(2, lines.size)
        assertEquals(listOf("a1", "a2", "a3"), lines[0].map { it.id })
        assertEquals(listOf("b1", "b2", "b3"), lines[1].map { it.id })
    }

    @Test
    fun linesSortedTopToBottom() {
        val strokes = listOf(
            stroke("bottom", 0f, 300f, 30f, 340f),
            stroke("top", 0f, 100f, 30f, 140f),
        )
        assertEquals(listOf("top", "bottom"), LineGrouper.group(strokes).map { it[0].id })
    }

    @Test
    fun dotJoinsNearestLine() {
        val dot = Stroke(id = "dot", points = listOf(InkPoint(45f, 130f)))
        val strokes = listOf(
            stroke("a", 10f, 100f, 40f, 140f),
            stroke("b", 10f, 200f, 40f, 240f),
            dot,
        )
        val lines = LineGrouper.group(strokes)
        assertEquals(2, lines.size)
        assertEquals(listOf("a", "dot"), lines[0].map { it.id })
    }

    @Test
    fun highlighterIgnored() {
        val strokes = listOf(
            stroke("h", 0f, 100f, 200f, 140f, pen = PenType.HIGHLIGHTER),
            stroke("a", 10f, 100f, 40f, 140f),
        )
        val lines = LineGrouper.group(strokes)
        assertEquals(1, lines.size)
        assertEquals(listOf("a"), lines[0].map { it.id })
        assertTrue(LineGrouper.group(listOf(stroke("h2", 0f, 0f, 1f, 1f, pen = PenType.HIGHLIGHTER))).isEmpty())
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertTrue(LineGrouper.group(emptyList()).isEmpty())
        assertTrue(LineGrouper.group(listOf(Stroke(points = emptyList()))).isEmpty())
    }

    @Test
    fun descenderStillSameLine() {
        // 第二笔比第一笔低一截（像 g 的下伸部分），重叠仍然超过一半
        val strokes = listOf(
            stroke("a", 10f, 100f, 40f, 140f),
            stroke("g", 50f, 110f, 80f, 165f),
        )
        assertEquals(1, LineGrouper.group(strokes).size)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.ink.LineGrouperTest" -q`
Expected: 编译失败，`LineGrouper` 未定义。

- [ ] **Step 3: 实现**

创建 `app/src/main/java/com/adnote/ink/LineGrouper.kt`：

```kotlin
package com.adnote.ink

import com.adnote.model.PenType
import com.adnote.model.Stroke
import kotlin.math.max
import kotlin.math.min

/**
 * 把一页笔迹按行分组，供手写识别逐行处理。ML Kit 对单行文本效果最好，整页一起送会乱序。
 */
object LineGrouper {

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height: Float get() = bottom - top
    }

    fun boundsOf(stroke: Stroke): Bounds {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (p in stroke.points) {
            if (p.x < l) l = p.x
            if (p.x > r) r = p.x
            if (p.y < t) t = p.y
            if (p.y > b) b = p.y
        }
        return Bounds(l, t, r, b)
    }

    /**
     * 每一笔按书写顺序找「垂直重叠比例」最大的已有行，比例 ≥ [minOverlap] 就归入，否则另起一行。
     * 重叠比例 = 重叠高度 / 两者中较矮者的高度。
     * 笔画高度至少按所有笔画高度中位数的 [minHeightRatio] 计，避免一个点、一条横线这种很扁的笔画归不进任何行。
     * 荧光笔不参与。返回的行按顶部从上到下排序，行内保持书写顺序。
     */
    fun group(strokes: List<Stroke>, minOverlap: Float = 0.4f, minHeightRatio: Float = 0.3f): List<List<Stroke>> {
        val items = strokes
            .filter { it.points.isNotEmpty() && it.pen != PenType.HIGHLIGHTER }
            .map { it to boundsOf(it) }
        if (items.isEmpty()) return emptyList()

        val sortedHeights = items.map { it.second.height }.sorted()
        val medianHeight = sortedHeights[sortedHeights.size / 2]
        val minHeight = max(medianHeight * minHeightRatio, 1f)

        class Line(var top: Float, var bottom: Float, val strokes: MutableList<Stroke>)

        val lines = ArrayList<Line>()
        for ((stroke, b) in items) {
            val pad = max(0f, (minHeight - b.height) / 2f)
            val top = b.top - pad
            val bottom = b.bottom + pad
            var best: Line? = null
            var bestRatio = 0f
            for (line in lines) {
                val overlap = min(bottom, line.bottom) - max(top, line.top)
                if (overlap <= 0f) continue
                val ratio = overlap / max(min(bottom - top, line.bottom - line.top), 1f)
                if (ratio > bestRatio) {
                    bestRatio = ratio
                    best = line
                }
            }
            if (best != null && bestRatio >= minOverlap) {
                best.strokes += stroke
                best.top = min(best.top, top)
                best.bottom = max(best.bottom, bottom)
            } else {
                lines += Line(top, bottom, mutableListOf(stroke))
            }
        }
        return lines.sortedBy { it.top }.map { it.strokes }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.ink.LineGrouperTest" -q`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/ink/LineGrouper.kt app/src/test/java/com/adnote/ink/LineGrouperTest.kt
git commit -m "feat(ink): group strokes into text lines for recognition"
```

---

### Task 2: 逐行识别 + 识别语言设置

**Files:**
- Create: `app/src/main/java/com/adnote/recognition/RecognitionLanguages.kt`
- Modify: `app/src/main/java/com/adnote/recognition/MlKitRecognizer.kt:66-105`（`recognize`）
- Modify: `app/src/main/java/com/adnote/ui/AdNoteApp.kt`（设置项与识别器重建）
- Modify: `app/src/main/java/com/adnote/ui/SettingsActivity.kt`
- Modify: `app/src/main/res/layout/activity_settings.xml:350-368`（模型状态与下载按钮之间）
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/adnote/recognition/RecognitionLanguagesTest.kt`

**Interfaces:**
- Consumes: `LineGrouper.group`（Task 1）
- Produces:
  - `data class RecognitionLanguage(val tag: String, val displayName: String)`
  - `object RecognitionLanguages { val ALL: List<RecognitionLanguage>; const val DEFAULT = "zh-Hani-CN"; fun byTag(tag: String?): RecognitionLanguage }`
  - `AdNoteApp.recognitionLanguage: String`、`fun AdNoteApp.setRecognitionLanguage(tag: String)`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/adnote/recognition/RecognitionLanguagesTest.kt`：

```kotlin
package com.adnote.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionLanguagesTest {

    @Test
    fun defaultIsSimplifiedChinese() {
        assertEquals("zh-Hani-CN", RecognitionLanguages.DEFAULT)
        assertEquals("中文（简体）", RecognitionLanguages.byTag(null).displayName)
        assertEquals("中文（简体）", RecognitionLanguages.byTag("nope").displayName)
    }

    @Test
    fun knownTagsResolve() {
        assertEquals("英语", RecognitionLanguages.byTag("en-US").displayName)
        assertTrue(RecognitionLanguages.ALL.map { it.tag }.containsAll(listOf("zh-Hani-CN", "zh-Hani-TW", "en-US", "ja")))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.recognition.RecognitionLanguagesTest" -q`
Expected: 编译失败。

- [ ] **Step 3: 实现语言表**

创建 `app/src/main/java/com/adnote/recognition/RecognitionLanguages.kt`：

```kotlin
package com.adnote.recognition

data class RecognitionLanguage(val tag: String, val displayName: String)

/** 可选的 ML Kit 手写识别语言。标签已对照 ML Kit 18.1.0 的 DigitalInkRecognitionModelIdentifier 核对（日语是 ja，不是 ja-JP）。 */
object RecognitionLanguages {
    const val DEFAULT = "zh-Hani-CN"

    val ALL = listOf(
        RecognitionLanguage("zh-Hani-CN", "中文（简体）"),
        RecognitionLanguage("zh-Hani-TW", "中文（繁体）"),
        RecognitionLanguage("en-US", "英语"),
        RecognitionLanguage("ja", "日语"),
    )

    fun byTag(tag: String?): RecognitionLanguage = ALL.firstOrNull { it.tag == tag } ?: ALL[0]
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.recognition.RecognitionLanguagesTest" -q`
Expected: PASS。

- [ ] **Step 5: 逐行识别**

`MlKitRecognizer.kt` 中，`recognize` 函数里从 `runCatching {` 开始到函数结束整段替换为：

```kotlin
        runCatching {
            val lines = LineGrouper.group(page.strokes)
            val texts = ArrayList<String>(lines.size)
            for (line in lines) {
                val inkBuilder = Ink.builder()
                for (stroke in line) {
                    val strokeBuilder = Ink.Stroke.builder()
                    for (pt in stroke.points) {
                        strokeBuilder.addPoint(Ink.Point.create(pt.x, pt.y, pt.t))
                    }
                    inkBuilder.addStroke(strokeBuilder.build())
                }
                val text = client.recognize(inkBuilder.build()).await().candidates.firstOrNull()?.text.orEmpty().trim()
                if (text.isNotEmpty()) texts += text
            }
            texts.joinToString("\n")
        }
    }
```

文件顶部加 `import com.adnote.ink.LineGrouper`。原来 `if (page.strokes.isEmpty()) return success("")` 保留。

- [ ] **Step 6: AdNoteApp 设置项**

`AdNoteApp.kt`：

在 `var preferOnyx` 之后加：

```kotlin
    /** 手写识别语言（ML Kit BCP-47 标签）。 */
    var recognitionLanguage: String = RecognitionLanguages.DEFAULT
        private set
```

`onCreate` 中删除 `recognizer = MlKitRecognizer()` 这一行（`loadSettings` 负责创建）。

`loadSettings` 中 `fullRefreshEvery = ...` 之后加：

```kotlin
        recognitionLanguage = prefs.getString("recognition_language", null) ?: RecognitionLanguages.DEFAULT
        recognizer = MlKitRecognizer(recognitionLanguage)
```

在 `setFullRefreshEvery` 之后加：

```kotlin
    fun setRecognitionLanguage(tag: String) {
        recognitionLanguage = tag
        prefs().edit().putString("recognition_language", tag).apply()
        recognizer = MlKitRecognizer(tag)
    }
```

import 加 `import com.adnote.recognition.RecognitionLanguages`。

- [ ] **Step 7: 设置页 UI**

`strings.xml` 把 `settings_download_model` 的值改为 `下载当前语言的手写模型`，并新增：

```xml
    <string name="settings_recognition_language">识别语言</string>
```

`activity_settings.xml` 在 id 为 `tvModelStatus` 的 `TextView` 之后、`btnDownloadModel` 之前插入：

```xml
            <Button
                android:id="@+id/btnRecognitionLanguage"
                android:layout_width="wrap_content"
                android:layout_height="36dp"
                android:textSize="12sp"
                android:textColor="@color/text_primary"
                android:background="@drawable/bg_button_secondary"
                android:paddingHorizontal="14dp"
                android:stateListAnimator="@null"
                android:layout_marginBottom="8dp" />
```

`SettingsActivity.kt`：
- 字段：`private lateinit var btnRecognitionLanguage: Button`
- `initViews()`：`btnRecognitionLanguage = findViewById(R.id.btnRecognitionLanguage)`
- `updateOptionButtons()` 末尾加：

```kotlin
        btnRecognitionLanguage.text = "识别语言：${RecognitionLanguages.byTag(app.recognitionLanguage).displayName}"
```

- `setupListeners()` 末尾加：

```kotlin
        btnRecognitionLanguage.setOnClickListener {
            val langs = RecognitionLanguages.ALL
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("手写识别语言")
                .setItems(langs.map { it.displayName }.toTypedArray()) { _, which ->
                    AdNoteApp.instance.setRecognitionLanguage(langs[which].tag)
                    updateOptionButtons()
                    checkStatus()
                }
                .show()
        }
```

- `checkStatus()` 里模型状态文案改为：

```kotlin
            val lang = RecognitionLanguages.byTag(AdNoteApp.instance.recognitionLanguage)
            tvModelStatus.text = if (downloaded) {
                "模型状态：已下载并可用（${lang.displayName}）"
            } else {
                "模型状态：未下载（${lang.displayName}，首次识别前需联网下载）"
            }
```

import 加 `import com.adnote.recognition.RecognitionLanguages`。

- [ ] **Step 8: 编译、全部测试、模拟器验证**

Run: `./gradlew assembleDebug -q && ./gradlew testDebugUnitTest -q`
Expected: 成功、全绿。

模拟器手工验证：
1. 设置 → 识别语言 → 选「英语」→ 下载模型。
2. 新建笔记，分三行写 `hello` / `world` / `test`，点「识别」。
3. 结果应为三行、顺序从上到下。
4. 切回「中文（简体）」，状态显示对应语言。

- [ ] **Step 9: 提交**

```bash
git add app/src/main/java/com/adnote/recognition app/src/main/java/com/adnote/ui/AdNoteApp.kt app/src/main/java/com/adnote/ui/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml app/src/test/java/com/adnote/recognition/RecognitionLanguagesTest.kt
git commit -m "feat(recognition): recognize line by line, selectable language"
```
