# AdNote 设计文档（v0.1）

日期：2026-09-26
状态：用户要求"直接开发"，以下未确认项均为默认决定，可随时推翻。

## 1. 目标与背景

- **使用者**：开发者本人（自用），设备为 **得到阅读器 Max**（文石代工，电磁笔，Android 10 左右的定制固件）。
- **要替代的**：自带笔记。痛点是：
  - B：笔记无法在电脑/手机上查看
  - C：组织方式不够（标签、链接）
  - E：识别结果无法检索、导出
- **成功标准**：
  1. 在 Max 上能流畅手写（能用 Onyx 低延迟通道就用，不能用就退回普通触控）。
  2. 笔记同步到自己的 WebDAV（如坚果云、NAS）后，能在电脑上用 Obsidian 查看手写页面，并能搜到识别文字。
  3. 在 Obsidian 里写的补充文字、改的标签不会被下一次同步覆盖。

## 2. 关键决策（默认值）

| 决策 | 选择 | 理由 |
|---|---|---|
| 跨设备编辑范围 | 手写只在阅读器上改；Markdown 的"用户区"和 `tags` 在 Obsidian 可改 | 不需要做桌面端，工作量可控 |
| 云端格式 | Obsidian 兼容目录：`.md` + SVG 渲染图 + `ink.json` 原始笔迹 | 开放格式，直接解决 B/C/E |
| 画笔实现 | `PenInput` 接口：Onyx `TouchHelper` 优先，失败回退 `MotionEvent` | 未在真机验证 SDK，必须有兜底 |
| 手写识别 | Google ML Kit Digital Ink（`zh-Hani-CN`），封装在接口后 | 离线可用；模型首次下载需访问 Google，可能失败，失败时提示 |
| UI 技术 | Kotlin + 传统 View，无动画、高对比 | 适配墨水屏，TouchHelper 需要 View |
| SDK 版本 | compileSdk 34 / targetSdk 30 / minSdk 26 | 自用侧载；低 targetSdk 对隐藏 API 更宽松 |
| 同步方向 | 阅读器为笔迹唯一来源；推送为主，另外支持"从云端恢复" | 只有一台书写设备，不需要双向笔迹合并 |
| 擦除 | 整笔擦除（笔画与橡皮轨迹相交即删除） | 实现简单，墨水屏上响应快 |

## 3. 架构

单一 app 模块，按包划分，每个包职责单一：

```
com.adnote
├── model/        Note、Page、Stroke、InkPoint；纯数据，可序列化
├── ink/          StrokeGeometry（笔迹轮廓计算，Canvas 和 SVG 共用）、Eraser 相交判断
├── storage/      NoteRepository：本地文件读写、列表、搜索
├── export/       SvgExporter、MarkdownComposer（生成 + 合并用户区）
├── sync/         WebDavClient（OkHttp）、SyncEngine、SyncSettings
├── recognition/  Recognizer 接口 + MlKitRecognizer
├── pen/          PenInput 接口、OnyxPenInput、MotionPenInput、EinkRefresher
└── ui/           MainActivity（列表）、EditorActivity（书写）、SettingsActivity、InkCanvasView
```

依赖方向：`ui → (pen, storage, sync, recognition, export) → (ink, model)`。
`model`、`ink`、`export`、`sync` 不依赖 Android framework（除 Bitmap/Canvas 外），可做 JVM 单元测试。

## 4. 数据模型

```kotlin
InkPoint(x: Float, y: Float, pressure: Float /*0..1*/, t: Long)
Stroke(id: String, points: List<InkPoint>, width: Float /*基准宽度 px*/, tool: PEN)
Page(id: String, strokes: List<Stroke>, recognizedText: String?, width: Int, height: Int)
Note(id, title, folder /*"收件箱" 或 "a/b"*/, tags: List<String>,
     pages: List<Page>, createdAt, updatedAt,
     sync: SyncState(lastSyncedAt, remoteMdPath, remoteMdEtag))
```

本地存储：`filesDir/notes/<id>/note.json`（一个笔记一个文件，写入时先写临时文件再 rename，防止写坏）。
删除：本地删除后留下 tombstone（`filesDir/tombstones/<id>.json`，记录远端路径），下次同步时删除远端文件。

## 5. 云端（WebDAV）布局

```
<远端根目录>/                       例如 /dav/AdNote/，可直接作为 Obsidian 仓库
  <folder>/<title>.md
  <folder>/_ink/<id>/ink.json       原始笔迹（恢复用）
  <folder>/_ink/<id>/page-001.svg   每页渲染图
```

Markdown 文件结构：

```markdown
---
adnote-id: 8f3c...
title: 会议记录
tags: [工作, 周会]
created: 2026-09-26T10:00:00+08:00
updated: 2026-09-26T11:30:00+08:00
---

（这里是用户区：在 Obsidian 里随意写，同步不会覆盖）

<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->
## 第 1 页
![[_ink/8f3c.../page-001.svg]]

> 识别文字……
<!-- adnote:end -->
```

合并规则：
- 生成区（begin/end 标记之间）每次整体替换。
- 用户区保持远端原样。
- `tags`：如果远端 md 的 ETag 和上次同步时记录的不同（说明在别处改过），就采用远端的 tags 写回本地；否则以本地为准。
- 标题改了：先用 WebDAV `MOVE` 把远端 md 移到新路径，再做合并。

## 6. 画笔与墨水屏

- `PenInput` 接口：`attach(view, limitRect, listener)`、`setEnabled(Boolean)`、`setStrokeWidth`、`detach()`。
  listener 回调：`onStroke(points)`、`onErase(points)`。
- `OnyxPenInput`：用 `TouchHelper` 的原始绘制模式，由固件直接画到屏幕上；笔身橡皮键走 `onRawErasing*` 回调。
  弹出菜单、翻页、擦除后需要重绘时，先 `setRawDrawingEnabled(false)`，重绘 View，再重新打开。
- `MotionPenInput`：普通 `onTouchEvent`，只接受 `TOOL_TYPE_STYLUS`/`TOOL_TYPE_ERASER`（防止手掌误触），笔尖按钮也当作橡皮。
- 选择策略：启动时尝试创建 `OnyxPenInput`，只要抛出异常（包括 `NoClassDefFoundError`、`UnsatisfiedLinkError`）就回退。当前使用哪一种会显示在设置页，也写进日志，方便真机验证。
- `EinkRefresher`：包装 `EpdController`，提供"全刷"（清残影）；非文石设备上什么也不做。

## 7. 手写识别

- `Recognizer.recognize(page): Result<String>`。
- `MlKitRecognizer`：按笔画构造 `Ink`，模型为 `zh-Hani-CN`；首次使用时下载模型，失败时返回明确错误（如"模型下载失败：可能需要能访问 Google 的网络"）。
- 识别是手动触发（编辑页"识别"按钮），结果存在 `Page.recognizedText`，参与本地搜索，也写进 md。

## 8. 错误处理

- 本地写文件：原子写入；读取时遇到损坏的 JSON 就跳过该笔记并记日志，不让整个列表崩掉。
- 同步：每条笔记单独 try/catch，一条失败不影响其他笔记；结束时汇总"成功 N / 失败 M + 第一条错误信息"。
  出现 401 时直接中止并提示检查账号或应用密码。
- Onyx SDK：所有调用都包在 `runCatching` 里，失败就降级。

## 9. 测试

- JVM 单元测试：
  - 模型序列化往返
  - `StrokeGeometry` 轮廓
  - 橡皮相交判断
  - `SvgExporter` 输出
  - `MarkdownComposer` 的生成与合并（用户区保留、tags 规则）
  - `WebDavClient`（MockWebServer）
  - `SyncEngine`（MockWebServer：新建、更新、改名、删除、401）
- 真机手动验证清单（等设备在身边时做）：
  - 设置页显示的画笔通道
  - 书写延迟
  - 压感
  - 笔身橡皮键
  - 全刷
  - 坚果云同步
  - Obsidian 打开效果

## 10. 不在 v0.1 范围内

- 套索选择/移动
- 多种笔刷
- 图层
- 无限画布
- PDF 批注
- 桌面端编辑手写
- 双向笔迹合并
- 页面模板（格线等）
- 音频录制
