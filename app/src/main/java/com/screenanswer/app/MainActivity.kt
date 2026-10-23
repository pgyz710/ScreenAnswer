package com.screenanswer.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.screenanswer.app.accessibility.ScreenReaderService
import com.screenanswer.app.data.Prefs
import com.screenanswer.app.databinding.ActivityMainBinding
import com.screenanswer.app.service.ScreenAnswerService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private var actionAfterNotificationPermission: (() -> Unit)? = null
    private var actionAfterMicPermission: (() -> Unit)? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val action = actionAfterNotificationPermission
        actionAfterNotificationPermission = null
        action?.invoke()
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        val action = actionAfterMicPermission
        actionAfterMicPermission = null
        action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.apiKeyInput.setText(prefs.apiKey)
        binding.baseUrlInput.setText(prefs.baseUrl)
        selectModel(prefs.model, persist = false)

        binding.apiKeyInput.doAfterTextChanged {
            prefs.apiKey = it?.toString().orEmpty()
            updateUi()
        }
        binding.baseUrlInput.doAfterTextChanged {
            val value = it?.toString().orEmpty()
            if (value.isNotBlank()) prefs.baseUrl = value
        }

        binding.modelChat.setOnClickListener { selectModel(Prefs.MODEL_CHAT) }
        binding.modelReasoner.setOnClickListener { selectModel(Prefs.MODEL_REASONER) }
        selectLanguage(prefs.englishMode, persist = false)
        binding.langChinese.setOnClickListener { selectLanguage(english = false) }
        binding.langEnglish.setOnClickListener { selectLanguage(english = true) }

        var advancedOpen = false
        binding.advancedToggle.setOnClickListener {
            advancedOpen = !advancedOpen
            binding.advancedGroup.isVisible = advancedOpen
            binding.advancedArrow.animate()
                .rotation(if (advancedOpen) 180f else 0f)
                .setDuration(160L)
                .start()
        }

        binding.startButton.setOnClickListener {
            if (ScreenAnswerService.isRunning && ScreenAnswerService.isBubbleHidden) {
                ScreenAnswerService.showBubble(this)
                binding.statusHint.text = "正在恢复悬浮球…"
                binding.root.postDelayed({ updateUi() }, 300L)
            } else {
                startFloating()
            }
        }
        binding.stopButton.setOnClickListener {
            stopService(Intent(this, ScreenAnswerService::class.java))
            binding.statusHint.text = "正在停止悬浮球…"
            binding.root.postDelayed({
                updateUi()
                binding.statusHint.text = getString(R.string.status_hint)
            }, 400L)
        }
        binding.accessibilityRow.setOnClickListener { openAccessibilityDetails() }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        // The accessibility service can take a moment to connect after the user returns
        // from system settings.
        binding.root.postDelayed({ updateUi() }, 700L)
    }

    private fun selectModel(model: String, persist: Boolean = true) {
        if (persist) prefs.model = model
        val chatSelected = model != Prefs.MODEL_REASONER
        binding.modelChat.setBackgroundResource(
            if (chatSelected) R.drawable.bg_pill_selected else R.drawable.bg_pill
        )
        binding.modelChat.setTextColor(
            ContextCompat.getColor(this, if (chatSelected) R.color.white else R.color.text_secondary)
        )
        binding.modelReasoner.setBackgroundResource(
            if (chatSelected) R.drawable.bg_pill else R.drawable.bg_pill_selected
        )
        binding.modelReasoner.setTextColor(
            ContextCompat.getColor(this, if (chatSelected) R.color.text_secondary else R.color.white)
        )
    }

    private fun selectLanguage(english: Boolean, persist: Boolean = true) {
        if (persist) prefs.englishMode = english
        binding.langChinese.setBackgroundResource(
            if (english) R.drawable.bg_pill else R.drawable.bg_pill_selected
        )
        binding.langChinese.setTextColor(
            ContextCompat.getColor(this, if (english) R.color.text_secondary else R.color.white)
        )
        binding.langEnglish.setBackgroundResource(
            if (english) R.drawable.bg_pill_selected else R.drawable.bg_pill
        )
        binding.langEnglish.setTextColor(
            ContextCompat.getColor(this, if (english) R.color.white else R.color.text_secondary)
        )
    }

    private fun startFloating() {
        val key = binding.apiKeyInput.text?.toString().orEmpty().trim()
        if (key.isEmpty()) {
            binding.apiKeyLayout.error = "请先填写 DeepSeek API Key"
            binding.apiKeyInput.requestFocus()
            return
        }
        binding.apiKeyLayout.error = null
        prefs.apiKey = key
        prefs.baseUrl = binding.baseUrlInput.text?.toString().orEmpty().trim()

        if (ScreenAnswerService.isRunning) {
            Toast.makeText(this, "悬浮球已经在运行了", Toast.LENGTH_SHORT).show()
            updateUi()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }

        val accessibilityConnected = ScreenReaderService.instance != null
        if (!accessibilityConnected && !isAccessibilityServiceEnabled()) {
            showAccessibilityGuide()
            return
        }

        // On HyperOS the system sometimes enables the service but only binds it a moment
        // later. Don't force the user to toggle it off/on again — start the bubble and let
        // the service connect while they return to their app.
        if (!accessibilityConnected) {
            Toast.makeText(
                this,
                "无障碍已开启，服务正在连接。悬浮球会先启动，等 1～2 秒再点它。",
                Toast.LENGTH_LONG
            ).show()
        }

        requestNotificationPermissionThen {
            requestMicPermissionThen {
                runCatching { ScreenAnswerService.start(this) }
                    .onFailure {
                        Toast.makeText(
                            this,
                            "悬浮球启动失败：${it.message ?: "未知错误"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                binding.statusHint.text = "悬浮球已开启，回到桌面点击它即可识屏"
                binding.root.postDelayed({ updateUi() }, 350L)
            }
        }
    }

    /** The mic is only used by the optional 「听一段」 speech input. */
    private fun requestMicPermissionThen(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            actionAfterMicPermission = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestNotificationPermissionThen(action: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            actionAfterNotificationPermission = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = normalizeComponent(
            ComponentName(this, ScreenReaderService::class.java)
        )
        val manager = getSystemService(AccessibilityManager::class.java)
        val enabledFromManager = manager
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                normalizeComponent(
                    ComponentName(serviceInfo.packageName, serviceInfo.name)
                ) == expected
            }
        if (enabledFromManager) return true

        // Fallback for OEMs whose AccessibilityManager list lags behind the secure setting.
        // Some ROMs store a shortened class name (pkg/.Service), so compare normalized names.
        val flat = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return flat.split(':').any { raw ->
            val component = ComponentName.unflattenFromString(raw) ?: return@any false
            normalizeComponent(component) == expected
        }
    }

    private fun normalizeComponent(component: ComponentName): String {
        val packageName = component.packageName
        val className = component.className.let {
            if (it.startsWith(".")) packageName + it else it
        }
        return "$packageName/$className"
    }

    private fun showAccessibilityGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_guide_title)
            .setMessage(R.string.accessibility_guide_message)
            .setPositiveButton(R.string.go_settings) { _, _ -> openAccessibilityDetails() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openAccessibilityDetails() {
        // This settings action is available on Android 8+, but the constant is not part
        // of the public SDK on all platform versions, so use its documented action string.
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
            putExtra(
                "android.intent.extra.COMPONENT_NAME",
                ComponentName(this@MainActivity, ScreenReaderService::class.java)
            )
        }
        runCatching { startActivity(details) }.onFailure {
            openAccessibilitySettings()
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(
                this,
                "打不开无障碍设置，请手动进入：设置 → 无障碍",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openOverlaySettings() {
        val packageUri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.onFailure {
                Toast.makeText(this, "无法打开悬浮窗权限设置，请到系统设置中手动授权", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun updateUi() {
        if (prefs.apiKey.isNotBlank()) binding.apiKeyLayout.error = null
        val overlayGranted = Settings.canDrawOverlays(this)
        binding.overlayState.text =
            getString(if (overlayGranted) R.string.authorized else R.string.not_authorized)
        binding.overlayState.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayGranted) R.color.success else R.color.warning
            )
        )

        val accessibilityConnected = ScreenReaderService.instance != null
        // A live binding is stronger evidence than the OEM's settings string; some HyperOS
        // builds report the component in a shortened form or omit it temporarily.
        val accessibilityEnabled = accessibilityConnected || isAccessibilityServiceEnabled()
        when {
            !accessibilityEnabled -> {
                binding.accessibilityState.text = getString(R.string.disabled)
                binding.accessibilityState.setTextColor(
                    ContextCompat.getColor(this, R.color.warning)
                )
            }

            !accessibilityConnected -> {
                binding.accessibilityState.text = getString(R.string.connecting)
                binding.accessibilityState.setTextColor(
                    ContextCompat.getColor(this, R.color.warning)
                )
            }

            else -> {
                binding.accessibilityState.text = getString(R.string.enabled)
                binding.accessibilityState.setTextColor(
                    ContextCompat.getColor(this, R.color.success)
                )
            }
        }

        val configured = prefs.apiKey.isNotBlank()
        binding.apiState.text =
            getString(if (configured) R.string.configured else R.string.not_configured)
        binding.apiState.setTextColor(
            ContextCompat.getColor(
                this,
                if (configured) R.color.success else R.color.warning
            )
        )

        val running = ScreenAnswerService.isRunning
        val bubbleHidden = running && ScreenAnswerService.isBubbleHidden
        binding.serviceState.text =
            getString(if (running) R.string.running else R.string.not_running)
        binding.serviceState.setTextColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.success else R.color.text_muted
            )
        )

        binding.startButton.isEnabled = !running || bubbleHidden
        binding.startButton.alpha = if (running && !bubbleHidden) 0.55f else 1f
        binding.startButton.text = when {
            bubbleHidden -> "显示悬浮球"
            running -> "悬浮球运行中"
            else -> getString(R.string.start_button)
        }
        binding.stopButton.isVisible = running

        when {
            bubbleHidden -> binding.statusHint.text =
                "悬浮球已隐藏，点「显示悬浮球」或下拉通知栏恢复"
            running -> binding.statusHint.text = "点悬浮球看答案；再点一次看解析；长按重新识屏"
            !overlayGranted -> binding.statusHint.text = "第 1 步：授予「显示在其他应用上层」权限"
            !accessibilityEnabled -> binding.statusHint.text =
                "第 2 步：点「无障碍服务」这一行，开启服务"
            !accessibilityConnected -> binding.statusHint.text =
                "无障碍已开启，正在连接；点状态行可重新打开系统开关"
            !configured -> binding.statusHint.text = "第 3 步：填写 DeepSeek API Key"
            else -> binding.statusHint.text = getString(R.string.status_hint)
        }
    }
}
