# 笔记组织与自动同步 实施计划（子系统 C）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让文件夹能重命名、解散；主界面能直接改标题/文件夹/标签；离开编辑器后自动同步。

**Architecture:** 文件夹操作是 `NoteRepository` 上的批量更新（文件夹只是笔记上的字符串），UI 在「文件夹」视图的头部长按菜单里调用。自动同步的触发条件抽成纯类 `SyncPolicy`，`AdNoteApp` 持有一个应用级协程作用域与「同步进行中」互斥标志，编辑器退出与主界面恢复时调用 `requestAutoSync()`。

**Tech Stack:** Kotlin 2.0、kotlinx-coroutines、AndroidX AppCompat、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-09-29-functional-fixes.md` 第 3.4 节。

## Global Constraints

- 不引入 WorkManager 等新依赖；自动同步只在应用进程存活时进行。
- `storage`/`sync` 不引用 `android.*`。
- 手动同步与自动同步共用一个互斥：同一时刻只有一个 `SyncEngine.sync()` 在跑。
- 每个任务结束时 `./gradlew testDebugUnitTest` 全绿再提交。
- 本计划在子系统 A 之后执行，`syncMessage` 依赖 A 的 Task 6 引入的 `SyncResult.pulled`。

## Review Focus

1. **重命名到已有文件夹名**（把「工作」改成「读书」）：两个文件夹合并，不能报错也不能丢笔记。Task 1 的 `renameIntoExistingFolderMerges` 覆盖。
2. **重命名时带首尾斜杠或空格**（用户输入 ` /公司/ `）：应规范化后再用。Task 1 的 `renameNormalizesInput` 覆盖。
3. **解散「收件箱」自身**：必须是空操作。Task 1 的 `dissolveInboxIsNoop` 覆盖。
4. **自动同步与手动同步同时触发**：只能跑一个。Task 4 的 `AdNoteApp.runSyncBlocking` 互斥保证，模拟器手工验证。
5. **没有网络时退出编辑器**：不能弹错误、不能卡住。Task 4 的 `SyncPolicy` 在 `online=false` 时返回 false，`SyncPolicyTest.offlineNeverRuns` 覆盖。

---

### Task 1: NoteRepository 文件夹重命名与解散

**Files:**
- Modify: `app/src/main/java/com/adnote/storage/NoteRepository.kt`（`allFolders` 之后）
- Test: `app/src/test/java/com/adnote/storage/NoteRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `fun NoteRepository.renameFolder(from: String, to: String, now: Long = System.currentTimeMillis()): Int`
  - `fun NoteRepository.dissolveFolder(folder: String, now: Long = System.currentTimeMillis()): Int`
  两者都返回受影响的笔记数，受影响笔记 `updatedAt = now`（变脏，下次同步 MOVE 远端文件）。

- [ ] **Step 1: 写失败测试**

`NoteRepositoryTest.kt` 追加：

```kotlin
    @Test
    fun testRenameFolderIncludesSubfolders() {
        val repo = NoteRepository(tempFolder.root)
        val a = repo.create("a", "工作", 10, 10)
        val b = repo.create("b", "工作/周会", 10, 10)
        val c = repo.create("c", "读书", 10, 10)

        val n = repo.renameFolder("工作", "公司", now = 5000L)
        assertEquals(2, n)
        assertEquals("公司", repo.load(a.id)!!.folder)
        assertEquals("公司/周会", repo.load(b.id)!!.folder)
        assertEquals("读书", repo.load(c.id)!!.folder)
        assertEquals(5000L, repo.load(a.id)!!.updatedAt)
        assertEquals(0, repo.renameFolder("公司", "公司"))
    }

    @Test
    fun testRenameNormalizesInput() {
        val repo = NoteRepository(tempFolder.root)
        val a = repo.create("a", "工作", 10, 10)
        assertEquals(1, repo.renameFolder(" /工作/ ", " 公司/ "))
        assertEquals("公司", repo.load(a.id)!!.folder)
        assertEquals(0, repo.renameFolder("公司", "  "))
    }

    @Test
    fun testRenameIntoExistingFolderMerges() {
        val repo = NoteRepository(tempFolder.root)
        val a = repo.create("a", "工作", 10, 10)
        val b = repo.create("b", "读书", 10, 10)
        assertEquals(1, repo.renameFolder("工作", "读书"))
        assertEquals("读书", repo.load(a.id)!!.folder)
        assertEquals("读书", repo.load(b.id)!!.folder)
        assertEquals(listOf("收件箱", "读书"), repo.allFolders())
    }

    @Test
    fun testDissolveFolderMovesToInbox() {
        val repo = NoteRepository(tempFolder.root)
        val a = repo.create("a", "工作", 10, 10)
        val b = repo.create("b", "工作/周会", 10, 10)
        assertEquals(2, repo.dissolveFolder("工作", now = 7000L))
        assertEquals("收件箱", repo.load(a.id)!!.folder)
        assertEquals("收件箱", repo.load(b.id)!!.folder)
        assertEquals(7000L, repo.load(b.id)!!.updatedAt)
    }

    @Test
    fun testDissolveInboxIsNoop() {
        val repo = NoteRepository(tempFolder.root)
        val a = repo.create("a", "收件箱", 10, 10)
        assertEquals(0, repo.dissolveFolder("收件箱"))
        assertEquals("收件箱", repo.load(a.id)!!.folder)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.storage.NoteRepositoryTest" -q`
Expected: 编译失败，`renameFolder`、`dissolveFolder` 未定义。

- [ ] **Step 3: 实现**

`NoteRepository.kt` 在 `fun allFolders()` 之后添加：

```kotlin
    /**
     * 重命名文件夹（含其子文件夹）。返回受影响的笔记数。
     * 受影响笔记 updatedAt 更新为 [now]，因此变为待同步，下次同步会 MOVE 远端文件。
     * 目标文件夹已存在时相当于合并。
     */
    fun renameFolder(from: String, to: String, now: Long = System.currentTimeMillis()): Int {
        val f = from.trim().trim('/')
        val t = to.trim().trim('/')
        if (f.isEmpty() || t.isEmpty() || f == t) return 0
        var n = 0
        for (note in list()) {
            val newFolder = when {
                note.folder == f -> t
                note.folder.startsWith("$f/") -> t + note.folder.removePrefix(f)
                else -> continue
            }
            save(note.copy(folder = newFolder, updatedAt = now))
            n++
        }
        return n
    }

    /** 解散文件夹：其中（含子文件夹）的笔记全部移到收件箱。收件箱本身不能解散。 */
    fun dissolveFolder(folder: String, now: Long = System.currentTimeMillis()): Int {
        val f = folder.trim().trim('/')
        if (f.isEmpty() || f == Note.DEFAULT_FOLDER) return 0
        var n = 0
        for (note in list()) {
            if (note.folder != f && !note.folder.startsWith("$f/")) continue
            save(note.copy(folder = Note.DEFAULT_FOLDER, updatedAt = now))
            n++
        }
        return n
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest -q`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/adnote/storage/NoteRepository.kt app/src/test/java/com/adnote/storage/NoteRepositoryTest.kt
git commit -m "feat(storage): rename and dissolve folders"
```

---

### Task 2: 主界面文件夹头部长按菜单

**Files:**
- Modify: `app/src/main/java/com/adnote/ui/MainActivity.kt`（`NoteAdapter` 构造参数与 `HeaderViewHolder.bind`；`noteAdapter` 初始化；新增两个对话框函数）

**Interfaces:**
- Consumes: `NoteRepository.renameFolder/dissolveFolder`（Task 1）
- Produces: `NoteAdapter` 新构造参数 `onHeaderLongClick: (NoteListItem.Header) -> Unit`

无法单元测试，模拟器验证。

- [ ] **Step 1: 适配器加长按回调**

`NoteAdapter` 构造函数改为：

```kotlin
class NoteAdapter(
    private val onItemClick: (NoteSummary) -> Unit,
    private val onItemLongClick: (NoteSummary) -> Unit,
    private val onHeaderClick: (NoteListItem.Header) -> Unit,
    private val onHeaderAddClick: (String) -> Unit,
    private val onHeaderLongClick: (NoteListItem.Header) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
```

`HeaderViewHolder.bind` 末尾 `itemView.setOnClickListener { onHeaderClick(header) }` 之后加：

```kotlin
            itemView.setOnLongClickListener {
                onHeaderLongClick(header)
                true
            }
```

`MainActivity` 中 `noteAdapter = NoteAdapter(...)` 的参数列表末尾加：

```kotlin
        onHeaderLongClick = { header -> if (header.isFolder) showFolderActions(header.key) },
```

- [ ] **Step 2: 对话框**

`MainActivity` 类中（放在 `showDeleteNoteDialog` 之后）添加：

```kotlin
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
```

`MainActivity` 已导入 `EditText`、`Dispatchers`、`withContext`、`lifecycleScope`、`Note`；若编译提示缺失，补上对应 import。

- [ ] **Step 3: 编译与模拟器验证**

Run: `./gradlew assembleDebug -q && ./gradlew testDebugUnitTest -q`

模拟器：切到「文件夹」视图，长按某个文件夹头部 → 重命名为新名 → 列表分组名更新、笔记卡片显示新文件夹、卡片出现「待同步」；再长按 → 解散 → 笔记进入收件箱。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/adnote/ui/MainActivity.kt
git commit -m "feat(main): rename and dissolve folders from the folder header"
```

---

### Task 3: 主界面长按笔记直接编辑标题、文件夹、标签

**Files:**
- Modify: `app/src/main/java/com/adnote/ui/MainActivity.kt:155-172`（`showNoteActions`）

无法单元测试，模拟器验证。

- [ ] **Step 1: 实现**

`showNoteActions` 整体替换为：

```kotlin
    private fun showNoteActions(note: Note) {
        val actions = listOf(
            "编辑标题、文件夹、标签",
            "设置封面颜色",
            if (note.isLocked) "修改或解除 PIN 锁" else "用 PIN 锁定",
            "移到回收站",
        )
        AlertDialog.Builder(this)
            .setTitle(note.title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showEditNoteDialog(note)
                    1 -> showCoverDialog(note)
                    2 -> if (note.isLocked) {
                        askPin("输入当前 PIN") { pin ->
                            if (PinLock.verify(pin, note.lockHash)) showSetPinDialog(note, allowRemove = true)
                            else Toast.makeText(this, "PIN 不正确", Toast.LENGTH_SHORT).show()
                        }
                    } else showSetPinDialog(note, allowRemove = false)
                    3 -> showDeleteNoteDialog(note)
                }
            }
            .show()
    }

    /** 与编辑器里的「编辑笔记属性」相同的表单，改完直接落盘并刷新列表。 */
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
```

- [ ] **Step 2: 编译与模拟器验证**

Run: `./gradlew assembleDebug -q`

模拟器：长按笔记 → 第一项 → 改标题与标签 → 列表立即更新，卡片显示「待同步」。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/adnote/ui/MainActivity.kt
git commit -m "feat(main): edit title, folder and tags from the note list"
```

---

### Task 4: 离开编辑器后自动同步

**Files:**
- Create: `app/src/main/java/com/adnote/sync/SyncPolicy.kt`
- Modify: `app/src/main/java/com/adnote/ui/AdNoteApp.kt`
- Modify: `app/src/main/java/com/adnote/ui/MainActivity.kt`（`triggerSync`、`onResume`、新增 `onPause`）
- Modify: `app/src/main/java/com/adnote/ui/EditorActivity.kt:2482-2497`（`onPause`）
- Modify: `app/src/main/java/com/adnote/ui/SettingsActivity.kt`
- Modify: `app/src/main/res/layout/activity_settings.xml`（WebDAV 卡片内）
- Test: `app/src/test/java/com/adnote/sync/SyncPolicyTest.kt`

**Interfaces:**
- Produces:
  - `class SyncPolicy(minIntervalMs: Long = 60_000L) { fun shouldRun(enabled: Boolean, configured: Boolean, online: Boolean, hasDirty: Boolean, lastRunAt: Long, now: Long): Boolean }`
  - `AdNoteApp.autoSync: Boolean`、`fun AdNoteApp.setAutoSync(v: Boolean)`
  - `fun AdNoteApp.runSyncBlocking(): SyncResult?`（互斥；已在跑或未配置返回 null；必须在 IO 线程调用）
  - `fun AdNoteApp.requestAutoSync()`
  - `AdNoteApp.onSyncFinished: ((SyncResult) -> Unit)?`（主线程回调）

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/adnote/sync/SyncPolicyTest.kt`：

```kotlin
package com.adnote.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {

    private val policy = SyncPolicy(minIntervalMs = 60_000L)

    @Test
    fun runsWhenEverythingIsReady() {
        assertTrue(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = true, lastRunAt = 0L, now = 100_000L))
    }

    @Test
    fun offlineNeverRuns() {
        assertFalse(policy.shouldRun(enabled = true, configured = true, online = false, hasDirty = true, lastRunAt = 0L, now = 100_000L))
    }

    @Test
    fun respectsMinInterval() {
        assertFalse(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = true, lastRunAt = 50_000L, now = 100_000L))
        assertTrue(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = true, lastRunAt = 40_000L, now = 100_000L))
    }

    @Test
    fun nothingToSyncOrDisabledOrUnconfigured() {
        assertFalse(policy.shouldRun(enabled = true, configured = true, online = true, hasDirty = false, lastRunAt = 0L, now = 100_000L))
        assertFalse(policy.shouldRun(enabled = false, configured = true, online = true, hasDirty = true, lastRunAt = 0L, now = 100_000L))
        assertFalse(policy.shouldRun(enabled = true, configured = false, online = true, hasDirty = true, lastRunAt = 0L, now = 100_000L))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.SyncPolicyTest" -q`
Expected: 编译失败。

- [ ] **Step 3: SyncPolicy**

创建 `app/src/main/java/com/adnote/sync/SyncPolicy.kt`：

```kotlin
package com.adnote.sync

/** 自动同步的触发条件。不依赖 Android，便于测试。 */
class SyncPolicy(private val minIntervalMs: Long = 60_000L) {
    fun shouldRun(enabled: Boolean, configured: Boolean, online: Boolean, hasDirty: Boolean, lastRunAt: Long, now: Long): Boolean =
        enabled && configured && online && hasDirty && now - lastRunAt >= minIntervalMs
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.adnote.sync.SyncPolicyTest" -q`
Expected: PASS。

- [ ] **Step 5: AdNoteApp 调度**

`AdNoteApp.kt` 新增 import：

```kotlin
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.adnote.sync.SyncPolicy
import com.adnote.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

在 `var fullRefreshEvery` 之后加字段：

```kotlin
    /** 离开编辑器、回到主界面时自动同步（需已配置 WebDAV、有网络、有待同步笔记）。 */
    var autoSync: Boolean = true
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncPolicy = SyncPolicy()
    private val syncLock = Any()
    @Volatile private var syncRunning = false
    @Volatile private var lastAutoSyncAt = 0L

    /** 主界面在前台时注册，自动同步结束后刷新列表；在主线程回调。 */
    @Volatile var onSyncFinished: ((SyncResult) -> Unit)? = null
```

`loadSettings` 中 `fullRefreshEvery = ...` 之后加：

```kotlin
        autoSync = prefs.getBoolean("auto_sync", true)
```

在 `setFullRefreshEvery` 之后加：

```kotlin
    fun setAutoSync(v: Boolean) { autoSync = v; prefs().edit().putBoolean("auto_sync", v).apply() }

    /** 跑一次完整同步。未配置或已有同步在跑时返回 null。必须在 IO 线程调用。 */
    fun runSyncBlocking(): SyncResult? {
        val engine = syncEngine ?: return null
        synchronized(syncLock) {
            if (syncRunning) return null
            syncRunning = true
        }
        return try {
            engine.sync()
        } finally {
            syncRunning = false
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 满足 [SyncPolicy] 才在后台同步一次；结果通过 [onSyncFinished] 通知主界面。 */
    fun requestAutoSync() {
        if (!autoSync || syncEngine == null) return
        appScope.launch {
            val hasDirty = runCatching { repository.summaries().any { it.isDirty } }.getOrDefault(false)
            val ok = syncPolicy.shouldRun(
                enabled = autoSync,
                configured = syncEngine != null,
                online = runCatching { isOnline() }.getOrDefault(false),
                hasDirty = hasDirty,
                lastRunAt = lastAutoSyncAt,
                now = System.currentTimeMillis(),
            )
            if (!ok) return@launch
            lastAutoSyncAt = System.currentTimeMillis()
            val result = runCatching { runSyncBlocking() }.getOrNull() ?: return@launch
            onSyncFinished?.let { cb -> withContext(Dispatchers.Main) { cb(result) } }
        }
    }
```

- [ ] **Step 6: 主界面改用互斥同步并接收自动同步结果**

`MainActivity.triggerSync` 中，把从 `if (syncing) {` 到函数结束整段替换为：

```kotlin
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
```

（`SyncResult.pulled` 来自子系统 A 的 Task 6；这里的文案已包含它，覆盖 A6 对 `triggerSync` 末尾的改动。）

删除字段 `private var syncing = false`。

`onResume` 改为：

```kotlin
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
```

- [ ] **Step 7: 编辑器退出时触发**

`EditorActivity.onPause` 中，在 `autoRecognize(currentPageIndex)` 之后、`if` 块结束之前加：

```kotlin
            // 退出编辑器（不是熄屏/切后台）：后台同步一次
            if (isFinishing) app.requestAutoSync()
```

- [ ] **Step 8: 设置开关**

`activity_settings.xml` WebDAV 卡片内、保存按钮所在 `LinearLayout` 之后（若已有 `btnRestoreFromCloud`，则放在它之后）加：

```xml
            <CheckBox
                android:id="@+id/cbAutoSync"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="离开编辑器后自动同步（有网络且有待同步笔记时）"
                android:textSize="13sp"
                android:textColor="@color/text_primary" />
```

`SettingsActivity.kt`：字段 `private lateinit var cbAutoSync: CheckBox`；`initViews()` 加 `cbAutoSync = findViewById(R.id.cbAutoSync)`；`loadCurrentSettings()` 加 `cbAutoSync.isChecked = app.autoSync`；`setupListeners()` 加：

```kotlin
        cbAutoSync.setOnCheckedChangeListener { _, v -> AdNoteApp.instance.setAutoSync(v) }
```

- [ ] **Step 9: 编译、全部测试、模拟器验证**

Run: `./gradlew assembleDebug -q && ./gradlew testDebugUnitTest -q`

模拟器（已配置 WebDAV）：
1. 新建笔记写几笔，按返回键回到主界面 → 几秒内卡片的「待同步」消失。
2. 立刻再进编辑器写一笔、退出 → 60 秒内不触发（卡片保持「待同步」），点手动同步正常。
3. 飞行模式下退出编辑器 → 无报错、无卡顿。
4. 设置里关掉开关 → 退出编辑器不再自动同步。

- [ ] **Step 10: 提交**

```bash
git add app/src/main/java/com/adnote/sync/SyncPolicy.kt app/src/main/java/com/adnote/ui/AdNoteApp.kt app/src/main/java/com/adnote/ui/MainActivity.kt app/src/main/java/com/adnote/ui/EditorActivity.kt app/src/main/java/com/adnote/ui/SettingsActivity.kt app/src/main/res/layout/activity_settings.xml app/src/test/java/com/adnote/sync/SyncPolicyTest.kt
git commit -m "feat(sync): auto sync after leaving the editor"
```
