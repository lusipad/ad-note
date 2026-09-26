package com.adnote.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adnote.R
import com.adnote.pen.EinkRefresher
import com.adnote.pen.PenInputFactory
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
    private lateinit var tvPenStatus: TextView
    private lateinit var btnFullRefreshTest: Button
    private lateinit var tvModelStatus: TextView
    private lateinit var btnDownloadModel: Button

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
        tvPenStatus = findViewById(R.id.tvPenStatus)
        btnFullRefreshTest = findViewById(R.id.btnFullRefreshTest)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
    }

    private fun loadCurrentSettings() {
        val current = AdNoteApp.instance.syncSettings
        etServerUrl.setText(current.serverUrl)
        etUsername.setText(current.username)
        etPassword.setText(current.password)
        etRemoteDir.setText(current.remoteRootDir)
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
    }

    private fun checkStatus() {
        tvPenStatus.text = "当前画笔通道：${PenInputFactory.activeInputName}"

        lifecycleScope.launch {
            val downloaded = AdNoteApp.instance.recognizer.isModelDownloaded()
            tvModelStatus.text = if (downloaded) {
                "模型状态：已下载并可用 (zh-Hani-CN)"
            } else {
                "模型状态：未下载（首次识别前需联网下载）"
            }
        }
    }
}
