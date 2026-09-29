package com.adnote.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adnote.R
import com.adnote.model.StylusButtonAction
import com.adnote.pen.DeviceDetector
import com.adnote.pen.EinkRefresher
import com.adnote.pen.PenInputFactory
import com.adnote.recognition.RecognitionLanguages
import com.adnote.sync.LocalState
import com.adnote.sync.RemoteNoteInfo
import com.adnote.sync.RestoreEngine
import com.adnote.sync.SyncSettings
import com.adnote.sync.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var btnBack: Button
    private lateinit var etServerUrl: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etRemoteDir: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvPenStatus: TextView
    private lateinit var cbStylusOnly: CheckBox
    private lateinit var cbPreferOnyx: CheckBox
    private lateinit var cbVolumeKeyPaging: CheckBox
    private lateinit var cbFingerSwipePaging: CheckBox
    private lateinit var cbScratchOut: CheckBox
    private lateinit var cbShapeHold: CheckBox
    private lateinit var cbAutoRecognize: CheckBox
    private lateinit var cbAutoSync: CheckBox
    private lateinit var btnStylusButton: Button
    private lateinit var btnFullRefreshEvery: Button
    private lateinit var btnFullRefreshTest: Button
    private lateinit var tvModelStatus: TextView
    private lateinit var btnDownloadModel: Button
    private lateinit var btnRecognitionLanguage: Button
    private lateinit var btnRestoreFromCloud: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadCurrentSettings()
        setupListeners()
        checkStatus()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        etServerUrl = findViewById(R.id.etServerUrl)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etRemoteDir = findViewById(R.id.etRemoteDir)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvPenStatus = findViewById(R.id.tvPenStatus)
        cbStylusOnly = findViewById(R.id.cbStylusOnly)
        cbPreferOnyx = findViewById(R.id.cbPreferOnyx)
        cbVolumeKeyPaging = findViewById(R.id.cbVolumeKeyPaging)
        cbFingerSwipePaging = findViewById(R.id.cbFingerSwipePaging)
        cbScratchOut = findViewById(R.id.cbScratchOut)
        cbShapeHold = findViewById(R.id.cbShapeHold)
        cbAutoRecognize = findViewById(R.id.cbAutoRecognize)
        cbAutoSync = findViewById(R.id.cbAutoSync)
        btnStylusButton = findViewById(R.id.btnStylusButton)
        btnFullRefreshEvery = findViewById(R.id.btnFullRefreshEvery)
        btnFullRefreshTest = findViewById(R.id.btnFullRefreshTest)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        btnRecognitionLanguage = findViewById(R.id.btnRecognitionLanguage)
        btnRestoreFromCloud = findViewById(R.id.btnRestoreFromCloud)
    }

    private fun updateOptionButtons() {
        val app = AdNoteApp.instance
        btnStylusButton.text = "笔身按键：${app.stylusButtonAction.displayName}"
        btnFullRefreshEvery.text = "翻页全刷：" + if (app.fullRefreshEvery == 0) "从不" else "每 ${app.fullRefreshEvery} 页"
        btnRecognitionLanguage.text = "识别语言：${RecognitionLanguages.byTag(app.recognitionLanguage).displayName}"
    }

    private fun loadCurrentSettings() {
        val app = AdNoteApp.instance
        val current = app.syncSettings
        etServerUrl.setText(current.serverUrl)
        etUsername.setText(current.username)
        etPassword.setText(current.password)
        etRemoteDir.setText(current.remoteRootDir)

        cbStylusOnly.isChecked = app.stylusOnly
        cbPreferOnyx.isChecked = app.preferOnyx
        cbVolumeKeyPaging.isChecked = app.volumeKeyPaging
        cbFingerSwipePaging.isChecked = app.fingerSwipePaging
        cbScratchOut.isChecked = app.scratchOut
        cbShapeHold.isChecked = app.shapeHold
        cbAutoRecognize.isChecked = app.autoRecognize
        cbAutoSync.isChecked = app.autoSync
        updateOptionButtons()
    }

    private fun getSettingsFromInput(): SyncSettings {
        return SyncSettings(
            serverUrl = etServerUrl.text.toString().trim(),
            username = etUsername.text.toString().trim(),
            password = etPassword.text.toString().trim(),
            remoteRootDir = etRemoteDir.text.toString().trim().ifEmpty { "AdNote" },
            enabled = true
        )
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        cbStylusOnly.setOnCheckedChangeListener { _, isChecked ->
            AdNoteApp.instance.setStylusOnly(isChecked)
            updatePenStatus()
        }

        cbVolumeKeyPaging.setOnCheckedChangeListener { _, isChecked ->
            AdNoteApp.instance.setVolumeKeyPaging(isChecked)
        }

        cbFingerSwipePaging.setOnCheckedChangeListener { _, isChecked ->
            AdNoteApp.instance.setFingerSwipePaging(isChecked)
        }

        cbScratchOut.setOnCheckedChangeListener { _, v -> AdNoteApp.instance.setScratchOut(v) }
        cbShapeHold.setOnCheckedChangeListener { _, v -> AdNoteApp.instance.setShapeHold(v) }
        cbAutoRecognize.setOnCheckedChangeListener { _, v -> AdNoteApp.instance.setAutoRecognize(v) }
        cbAutoSync.setOnCheckedChangeListener { _, v -> AdNoteApp.instance.setAutoSync(v) }

        btnStylusButton.setOnClickListener {
            val actions = StylusButtonAction.entries
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("笔身按键（标准触控通道）")
                .setItems(actions.map { it.displayName }.toTypedArray()) { _, which ->
                    AdNoteApp.instance.setStylusButtonAction(actions[which])
                    updateOptionButtons()
                }
                .show()
        }

        btnFullRefreshEvery.setOnClickListener {
            val options = listOf(0, 3, 6, 10, 20)
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("墨水屏翻页自动全刷")
                .setItems(options.map { if (it == 0) "从不" else "每翻 $it 页" }.toTypedArray()) { _, which ->
                    AdNoteApp.instance.setFullRefreshEvery(options[which])
                    updateOptionButtons()
                }
                .show()
        }

        cbPreferOnyx.setOnCheckedChangeListener { _, isChecked ->
            AdNoteApp.instance.setPreferOnyx(isChecked)
            updatePenStatus()
        }

        btnSaveSettings.setOnClickListener {
            val settings = getSettingsFromInput()
            AdNoteApp.instance.updateSettings(settings)
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        }

        btnTestConnection.setOnClickListener {
            val settings = getSettingsFromInput()
            if (!settings.isConfigured) {
                Toast.makeText(this, "请先填写完整的服务器地址、用户名及密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "正在测试连接...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val client = WebDavClient(
                            baseUrl = settings.serverUrl,
                            username = settings.username,
                            password = settings.password,
                            remoteRootDir = settings.remoteRootDir
                        )
                        client.mkcol("")
                        true
                    }
                }
                if (ok.isSuccess) {
                    Toast.makeText(this@SettingsActivity, "连接成功！WebDAV 配置正常", Toast.LENGTH_LONG).show()
                } else {
                    val msg = ok.exceptionOrNull()?.message ?: "连接失败"
                    Toast.makeText(this@SettingsActivity, "连接失败: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnFullRefreshTest.setOnClickListener {
            EinkRefresher.fullRefresh(window.decorView)
            Toast.makeText(this, "已触发全刷刷新", Toast.LENGTH_SHORT).show()
        }

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

        btnDownloadModel.setOnClickListener {
            Toast.makeText(this, "正在下载手写识别模型（需要访问 Google 服务）...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val res = AdNoteApp.instance.recognizer.downloadModel()
                if (res.isSuccess) {
                    Toast.makeText(this@SettingsActivity, "模型下载成功！", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = res.exceptionOrNull()?.message ?: "下载失败"
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
                checkStatus()
            }
        }

        btnRestoreFromCloud.setOnClickListener { startRestoreFromCloud() }
    }

    private fun startRestoreFromCloud() {
        val settings = getSettingsFromInput()
        if (!settings.isConfigured) {
            Toast.makeText(this, "请先填写完整的服务器地址、用户名及密码", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在扫描远端笔记...", Toast.LENGTH_SHORT).show()
        val engine = RestoreEngine(
            AdNoteApp.instance.repository,
            WebDavClient(settings.serverUrl, settings.username, settings.password, settings.remoteRootDir),
        )
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { runCatching { engine.scan() } }
            val infos = scanned.getOrElse {
                Toast.makeText(this@SettingsActivity, "扫描失败: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (infos.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "远端没有找到 AdNote 笔记", Toast.LENGTH_LONG).show()
                return@launch
            }
            showRestoreDialog(engine, infos)
        }
    }

    private fun showRestoreDialog(engine: RestoreEngine, infos: List<RemoteNoteInfo>) {
        val labels = infos.map { "${it.note.title}  ·  ${it.folder.ifEmpty { "根目录" }}  ·  ${it.localState.displayName}" }.toTypedArray()
        val checked = BooleanArray(infos.size) { infos[it].localState != LocalState.SAME_OR_NEWER }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择要恢复的笔记（${infos.size} 篇）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("恢复") { _, _ ->
                val chosen = infos.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) return@setPositiveButton
                Toast.makeText(this, "正在恢复 ${chosen.size} 篇...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { engine.restore(chosen) }
                    val msg = if (result.failed == 0) "已恢复 ${result.restored} 篇笔记"
                    else "恢复 ${result.restored} 篇，失败 ${result.failed}\n${result.firstError}"
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun checkStatus() {
        val deviceInfo = DeviceDetector.detect()
        tvDeviceInfo.text = "设备检测：${deviceInfo.summary}"
        updatePenStatus()

        lifecycleScope.launch {
            val downloaded = AdNoteApp.instance.recognizer.isModelDownloaded()
            val lang = RecognitionLanguages.byTag(AdNoteApp.instance.recognitionLanguage)
            tvModelStatus.text = if (downloaded) {
                "模型状态：已下载并可用（${lang.displayName}）"
            } else {
                "模型状态：未下载（${lang.displayName}，首次识别前需联网下载）"
            }
        }
    }

    private fun updatePenStatus() {
        val app = AdNoteApp.instance
        val previewInput = PenInputFactory.create(
            preferOnyx = app.preferOnyx,
            stylusOnly = app.stylusOnly
        )
        tvPenStatus.text = "当前通道：${previewInput.name}"
    }
}
