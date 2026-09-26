# AdNote

<p align="center">
  <b>面向墨水屏阅读器的低延迟手写笔记与 Obsidian 互联工具</b>
</p>

<p align="center">
  <a href="https://github.com/lusipad/ad-note/actions/workflows/build.yml"><img src="https://github.com/lusipad/ad-note/actions/workflows/build.yml/badge.svg" alt="Build Status" /></a>
  <a href="https://github.com/lusipad/ad-note/releases/latest"><img src="https://img.shields.io/github/v/release/lusipad/ad-note?color=blue" alt="Latest Release" /></a>
  <a href="https://opensource.org/licenses/MIT"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License: MIT" /></a>
</p>

---

## 💡 为什么做 AdNote？

在很多墨水屏设备（如 **文石 Onyx 代工的得到阅读器 Max** 等定制固件设备）上，自带的手写笔记软件往往存在以下痛点：
1. **跨设备查看极难**：笔记封闭在设备内，无法在电脑（Mac/Windows）或手机上直接以纯文本或轻量矢量格式查看；
2. **知识组织能力弱**：缺乏灵活的标签（Tags）与层级目录支持；
3. **检索困难**：手写内容无法离线被全局搜索引擎索引。

**AdNote** 旨在彻底解决这些问题：
- 在设备上保留**原生的流畅手写体验**（基于文石官方低延迟固件通道）；
- 每次同步直接推送至个人的 **WebDAV** 服务（如坚果云、NAS）；
- 远端以 **Obsidian 原生兼容目录** 存储（`.md` + 矢量 SVG 图 + 原始笔迹 `ink.json`）；
- **双向保护合并**：你在电脑 Obsidian 里补充的打字文字、修改的标签，在下一次同步时**绝对不会被覆盖**。

---

- ✍️ **跨设备手写与自适应适配**：
  - **文石 (BOOX) / 得到阅读器 Max**：接入官方 `TouchHelper` 固件级低延迟通道，支持电磁笔压感曲线与笔身按键整笔擦除；
  - **掌阅 (iReader) / 汉王墨水屏**：智能识别墨水屏环境，配备**通用物理反转闪刷**，解决第三方应用无硬件 SDK 时的残影问题；
  - **小米平板 (Xiaomi / Redmi) / VIVO 平板 (vivo / iQOO)**：支持 120Hz/144Hz 历史高频点采集，内置「防手掌误触模式」（仅手写笔响应，手掌压屏不误画）；
  - **智能机型感知（`DeviceDetector`）**：自动识别设备品牌、型号与屏幕材质（E-ink 墨水屏 vs 普通彩屏），自适应切换最佳通道与刷新策略。
- 🔄 **WebDAV / Obsidian 互联同步**：
  - 自动渲染每页笔迹为轻量高清矢量 **SVG**；
  - 导出格式为标准 **Obsidian Markdown**，内置嵌入式图片引用与识别引用区；
  - 独创 **Front matter 保护 + 用户区保护 + 智能标签合并** 引擎。
- 🔍 **离线中文手写识别**：封装 Google ML Kit Digital Ink Recognition（`zh-Hani-CN` 模型），一键离线识别手写文字，识别文本直接参与本地即时搜索，并写入 Markdown。
- 🖤 **墨水屏专属 UI**：纯白底高对比无动画样式（零过渡动画），防拖影；在非墨水屏（小米/vivo）上自适应普通平滑重绘。
- 🚀 **自动化 CI/CD**：由 GitHub Actions 提供全自动化构建与验证，代码推送即运行单元测试，打标签即自动发布 Release APK。

---

## 📐 架构设计

代码采用模块化清晰分包，核心算法与领域模型不依赖 Android Framework，全部具备完整的 JVM 自动化测试：

```
com.adnote
├── model/        Note、Page、Stroke、InkPoint（纯数据模型，kotlinx.serialization）
├── ink/          StrokeGeometry（Canvas 与 SVG 共享多边形轮廓算法）、Eraser（整笔擦除相交算法）
├── storage/      NoteRepository（本地原子文件存储、删除墓碑 Tombstone、全文检索）
├── export/       SvgExporter（矢量 SVG 导出）、MarkdownComposer（Obsidian 合并引擎）、RemotePaths
├── sync/         WebDavClient（基于 OkHttp 的标准 WebDAV 实现）、SyncEngine（同步调度器）
├── recognition/  Recognizer 接口 + MlKitRecognizer（Google ML Kit 中文离线手写识别）
├── pen/          PenInput 接口、OnyxPenInput（文石低延迟）、MotionPenInput（触控兜底）、EinkRefresher
└── ui/           MainActivity（笔记管理）、EditorActivity（画布手写）、SettingsActivity、InkCanvasView
```

---

## 📂 云端（WebDAV & Obsidian）文件布局

同步到 WebDAV 根目录后，可直接将该目录作为 **Obsidian 仓库（Vault）** 打开：

```text
<WebDAV 远端根目录>/                   （例如 /dav/AdNote/）
  ├── 工作/
  │    ├── 2026战略会议.md             （Obsidian 文档，含标签、识别文本与图片引用）
  │    └── _ink/
  │         └── 8f3c10a2/
  │              ├── page-001.svg      （第 1 页高保真矢量图）
  │              ├── page-002.svg      （第 2 页高保真矢量图）
  │              └── ink.json          （原始笔迹备份，可用于恢复）
  └── 读书笔记/
       └── 三体.md
```

### Markdown 文件结构示例

```markdown
---
adnote-id: 8f3c10a2b5e6
title: 2026战略会议
tags:
  - 工作
  - 规划
created: 2026-09-26T10:00:00+08:00
updated: 2026-09-26T11:30:00+08:00
custom-obsidian-meta: 这一行自定义 Front matter 不会被 AdNote 覆盖
---

（这里是用户编辑区：你在 Obsidian 里自由书写的所有分析、补充、卡片链接，AdNote 下次同步时都会完整保留！）

<!-- adnote:begin 以下内容由 AdNote 自动生成，请勿编辑 -->
## 第 1 页

![第 1 页](_ink/8f3c10a2/page-001.svg)

> 这是由 ML Kit 离线手写识别提取出的文字内容……
<!-- adnote:end -->
```

---

## 📲 安装与使用

### 1. 下载 APK
访问 GitHub Releases 页面下载最新安装包：
- 👉 [最新发布版本（Releases）](https://github.com/lusipad/ad-note/releases/latest)
- 文件名为 `app-debug.apk`，内置调试签名，支持直接在得到阅读器 Max 或 Android 设备上侧载安装。

### 2. 坚果云 / WebDAV 同步配置
在 AdNote「设置」页面中填写：
- **服务器 URL**：例如 `https://dav.jianguoyun.com/dav/`
- **用户名**：坚果云账号邮箱
- **密码**：坚果云生成的 **应用专用密码**（非网页登录密码）
- **远端根目录**：默认为 `AdNote`
- 点击「测试连接」确保配置成功。

### 3. 手写离线模型
在「设置」页点击「下载手写模型（中文简体）」，首次下载需确保网络能够访问 Google 服务；下载完成后后续所有手写识别均完全在本地离线运行。

---

## 🛠 本地开发与构建

### 运行环境
- JDK 17
- Android SDK (compileSdk 34, targetSdk 30, minSdk 26)
- Gradle 8.9

### 执行单元测试
```bash
./gradlew testDebugUnitTest
```

### 构建 APK
```bash
./gradlew assembleDebug
```
产物将输出在 `app/build/outputs/apk/debug/app-debug.apk`。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 开源。
