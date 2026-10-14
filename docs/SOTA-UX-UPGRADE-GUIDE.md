# AdNote Pro v1.0.0 — SOTA 商业级改造与用户体验升级架构指南

本文档详细记录了将 **AdNote** 从开源工具型应用（v0.5.0）全面升级为对标 **GoodNotes 6 / Notability / 文石原生笔记 / Supernote Atelier** 的 **SOTA 商业级软件（v1.0.0）** 的完整用户体验（UX）重构与底层能力增强。

---

## 一、 核心设计哲学：以「零摩擦心流」为核心的商业级体验

在墨水屏（E-Ink）与高刷平板（120Hz/144Hz）双栖场景下，商业级手写软件的核心竞争力在于**降低每一次高频操作的心智负担与交互步数**：

1. **信息密度与视觉呼吸感平衡（Editorial Monochrome 2.0）**：在墨水屏上保持零残影高对比度的同时，提供书架网格与紧凑列表双视图、卡片正文摘要预览、全局工作区实时统计。
2. **工具高频可达性（Zero-Modal Flow）**：通过底部实时页面胶片栏（Filmstrip Drawer）、右下角缩放全局小地图（Minimap）、直尺中央实时角度读数与 15° 磁吸、待办清单（TODO）复选框点按直接打勾，彻底告别频繁弹窗中断书写心流。
3. **主动学习与复习闭环（Active Recall & Audio-Ink Sync）**：新增**记忆遮挡胶带（Study Tape）**、**激光教鞭（Laser Pointer）**以及**音字同步时间戳回放（Audio-Ink Sync）**，覆盖课堂、会议、考研复习与演示全场景。
4. **双链知识库同屏工作流（Obsidian Split-Screen Studio）**：支持在手写画布右侧一键展开 **Obsidian 实时双栏对照面板**，边看手写与 OCR 识别结果，边编写结构化 Markdown 与 `[[双链]]` 笔记。

---

## 二、 升级模块全景矩阵

| 维度 | 升级前 (v0.5.0) | SOTA 商业级升级后 (v1.0.0) | 核心代码落点 |
| :--- | :--- | :--- | :--- |
| **书架与知识库管理** | 仅单列纯文字列表，按修改时间固定排序，无置顶与摘要预览 | **双视图切换（书架网格 / 紧凑列表）**、**4 维排序**（修改时间/创建时间/标题/页数）、**★ 一键星标置顶**、**卡片 OCR/Markdown 摘要预览**（锁定笔记自动隐藏）、**批量多选操作栏**（批量移动、加标签、加星标、移至回收站） | `MainActivity.kt`、`NoteSummary.kt`、`NoteRepository.kt`、`activity_main.xml`、`item_note.xml` |
| **主动学习与演示工具** | 仅基础画笔、橡皮、套索、文字框 | 新增 **记忆遮挡胶带（`Tool.TAPE`）**（涂抹生成平整遮挡条，点按切换揭开/隐藏，支持一键全显/全隐自测）与 **激光教鞭（`Tool.LASER`）**（双层柔光高亮轨迹，1.6 秒自动消隐，不污染画布与撤销栈） | `ToolState.kt`、`PageItems.kt`、`PageRenderer.kt`、`InkCanvasView.kt`、`EditorActivity.kt` |
| **智能几何与量角直尺** | 仅识别直线、三角形、矩形、圆；直尺无角度显示与吸附 | **一笔连画箭头识别（`Kind.ARROW`）**、**五边形/六边形识别**；**量角器直尺**内置实时角度胶囊（`0.0°`）与 **15° 常用角自动磁吸**（0°/15°/30°/45°/60°/90°） | `ShapeRecognizer.kt`、`Ruler.kt`、`InkCanvasView.kt` |
| **画布导航与交互细节** | 缩放后容易迷失位置；翻页需盲点或弹窗；待办模板仅静态方框 | **右下角全局缩放小地图（Zoom Minimap）**（放大 > 115% 自动浮现，支持点按/拖拽小地图快速平移视口）；**底部实时页面胶片抽屉（Filmstrip Bar）**；**TODO 待办模板复选框点按直接打勾** | `InkCanvasView.kt`、`EditorActivity.kt`、`activity_editor.xml` |
| **同屏对照与音字同步** | Obsidian 正文需弹窗编辑；录音与笔迹无视觉联动 | **Obsidian 双栏同屏对照模式（Split View）**（左画布手写 + 右栏 Markdown 编辑与本页 OCR 实时对照）；**音字同步回放高亮（`playbackCutoffMs`）**（按笔画时间戳动态点亮历史笔迹） | `EditorActivity.kt`、`PageRenderer.kt`、`InkCanvasView.kt` |

---

## 三、 兼容性与工程质量保障

- **100% 向后兼容存储格式**：所有新增字段（`Note.isStarred`、`Page.tapes`、`NoteSummary.snippet` 等）均使用 `@EncodeDefault` 与安全默认值，旧版 `note.json`、`meta.json` 与 WebDAV 远端 `_ink/<id>/ink.json` 无需迁移即可直接无损加载。
- **墨水屏低延迟直绘保护**：新增浮动栏（胶片抽屉、双栏面板、小地图）全部纳入 `updateExcludeRects()` 排除矩阵，确保文石 `TouchHelper` 固件直绘层不拦截浮动控件点按。
- **完整的单元测试覆盖**：新增 `SotaFeaturesTest.kt` 覆盖星标置顶排序、批量文件操作、一笔箭头几何识别、直尺 15° 磁吸、记忆胶带变换与隐私锁摘要屏蔽。
