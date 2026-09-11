package com.nativOS.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nativOS.bridge.AndroidAppIntegration
import com.nativOS.launcher.KioskActivity
import com.nativOS.runtime.ChrootManager
import com.nativOS.runtime.DisplayController
import com.nativOS.runtime.GpuDetector
import com.nativOS.runtime.RootfsManager
import com.nativOS.setup.SetupWizardActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var desktopCheck: TextView
    private lateinit var homeCheck: TextView
    private lateinit var deGoogleStatus: TextView
    private lateinit var alpineCheck: TextView
    private lateinit var debianCheck: TextView
    private lateinit var displayScaleSubtitle: TextView
    private lateinit var gpuDriverSubtitle: TextView
    private lateinit var resolutionScaleSubtitle: TextView
    private lateinit var wlrRendererSubtitle: TextView
    private lateinit var desktopEnvSubtitle: TextView
    private lateinit var touchModeSubtitle: TextView
    private lateinit var storageModeSubtitle: TextView
    private lateinit var touchRotationSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }

        content.addView(PremiumUi.text(this, "Settings", 34f, bold = true))
        summary = PremiumUi.text(this, "", 15f, PremiumUi.muted).apply {
            setPadding(0, dp(6), 0, 0)
        }
        content.addView(summary)
        content.addView(PremiumUi.verticalSpace(this, 28))

        content.addView(PremiumUi.sectionLabel(this, "NativOS mode"))
        content.addView(group().apply {
            desktopCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Linux desktop",
                subtitle = "Open NativOS like a normal app",
                trailing = desktopCheck
            ) {
                NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.DESKTOP_APP)
                if (HomeRoleManager.isDefaultHome(this@SettingsActivity)) {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }
                updateState()
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            homeCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Home launcher",
                subtitle = "Use NativOS as your phone's Home screen",
                trailing = homeCheck
            ) {
                NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.HOME_LAUNCHER)
                HomeRoleManager.requestDefaultHome(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "De-Googled mode",
                subtitle = "Coming later",
                trailingText = ""
            ).apply {
                isEnabled = false
                alpha = 0.42f
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Linux distribution"))
        content.addView(group().apply {
            alpineCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Alpine Linux 3.21 (postmarketOS)",
                subtitle = "Recommended · Lightweight mobile stack · OpenRC · fast",
                trailing = alpineCheck
            ) {
                NativOSPreferences.setPreferredDistro(this@SettingsActivity, "alpine")
                updateState()
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            debianCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Debian 13 (Trixie)",
                subtitle = "Next-gen Debian · glibc · broad package compatibility",
                trailing = debianCheck
            ) {
                NativOSPreferences.setPreferredDistro(this@SettingsActivity, "debian")
                updateState()
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Окружение рабочего стола"))
        content.addView(group().apply {
            desktopEnvSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Графическая оболочка (postmarketOS)",
                subtitleView = desktopEnvSubtitle,
                trailingText = "›"
            ) { showDesktopEnvironmentDialog() })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Privacy"))
        content.addView(group().apply {
            deGoogleStatus = PremiumUi.text(this@SettingsActivity, "Not scanned", 13f, PremiumUi.muted)
            addView(row(
                title = "Google components",
                subtitleView = deGoogleStatus,
                trailingText = "Scan"
            ) { scanGoogleComponents() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "System App Remover",
                subtitle = "Review and safely disable preinstalled apps",
                trailingText = "›"
            ) {
                startActivity(Intent(this@SettingsActivity, SystemAppRemoverActivity::class.java))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Android integration"))
        content.addView(group().apply {
            addView(switchRow(
                "Immersive desktop",
                "Hide Android system bars while Linux is active",
                NativOSPreferences.hideSystemBars(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setHideSystemBars(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Show Android apps",
                "Include installed Android apps in the Linux drawer",
                NativOSPreferences.showAndroidApps(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setShowAndroidApps(this@SettingsActivity, checked)
                AndroidAppIntegration.sync(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("Default Home app", "Choose which launcher Android uses", trailingText = "›") {
                HomeRoleManager.requestDefaultHome(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("Android settings", "Open the underlying system settings", trailingText = "›") {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Дисплей и плавность"))
        content.addView(group().apply {
            val maxHz = DisplayController.getMaxSupportedRefreshRate(this@SettingsActivity)
            addView(switchRow(
                "Высокая частота обновления (${maxHz.toInt()} Гц)",
                "Максимальная плавность отображения Phosh",
                NativOSPreferences.highRefreshRate(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setHighRefreshRate(this@SettingsActivity, checked)
                DisplayController.applyRefreshRate(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            displayScaleSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Масштаб интерфейса (DPI)",
                subtitleView = displayScaleSubtitle,
                trailingText = "›"
            ) { showScaleDialog() })
            addView(PremiumUi.separator(this@SettingsActivity))
            touchModeSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Режим сенсора (частота опроса)",
                subtitleView = touchModeSubtitle,
                trailingText = "›"
            ) { showTouchModeDialog() })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Калибровка сенсора (Тач)"))
        content.addView(group().apply {
            addView(switchRow(
                "Инвертировать ось X",
                "Зеркальное отражение по горизонтали",
                NativOSPreferences.touchInvertX(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setTouchInvertX(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Инвертировать ось Y",
                "Зеркальное отражение по вертикали",
                NativOSPreferences.touchInvertY(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setTouchInvertY(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Поменять оси местами (Swap X/Y)",
                "Для планшетов и устройств с перевернутым дигитайзером",
                NativOSPreferences.touchSwapAxes(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setTouchSwapAxes(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            touchRotationSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Поворот сенсора",
                subtitleView = touchRotationSubtitle,
                trailingText = "›"
            ) { showTouchRotationDialog() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "Сбросить калибровку тача",
                subtitle = "Вернуть стандартные оси и координаты",
                trailingText = "Сброс"
            ) {
                NativOSPreferences.resetTouchCalibration(this@SettingsActivity)
                updateState()
                Toast.makeText(this@SettingsActivity, "Калибровка тача сброшена", Toast.LENGTH_SHORT).show()
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Производительность и ядро"))
        content.addView(group().apply {
            addView(switchRow(
                "Режим максимальной производительности",
                "Фиксация частот CPU/GPU и защита от OOM killer",
                NativOSPreferences.performanceModeEnabled(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setPerformanceModeEnabled(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Расширение памяти Swap/ZRAM",
                "Выделение дополнительного swap-файла под тяжелые программы",
                NativOSPreferences.swapEnabled(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setSwapEnabled(this@SettingsActivity, checked)
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Хранилище системы"))
        content.addView(group().apply {
            storageModeSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Режим хранения rootfs",
                subtitleView = storageModeSubtitle,
                trailingText = "›"
            ) { showStorageModeDialog() })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Графический ускоритель и дисплей"))
        content.addView(group().apply {
            gpuDriverSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Режим GPU и видеодрайвер",
                subtitleView = gpuDriverSubtitle,
                trailingText = "›"
            ) { showGpuDialog() })
            addView(PremiumUi.separator(this@SettingsActivity))
            wlrRendererSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Рендерер рабочего стола (wlroots)",
                subtitleView = wlrRendererSubtitle,
                trailingText = "›"
            ) { showWlrRendererDialog() })
            addView(PremiumUi.separator(this@SettingsActivity))
            resolutionScaleSubtitle = PremiumUi.text(this@SettingsActivity, "", 13f, PremiumUi.muted)
            addView(row(
                title = "Разрешение экрана (Downsampling)",
                subtitleView = resolutionScaleSubtitle,
                trailingText = "›"
            ) { showResolutionScaleDialog() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Многопоточный OpenGL (mesa_glthread)",
                "Асинхронный поток обработки вызовов графики Mesa",
                NativOSPreferences.mesaGlThread(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setMesaGlThread(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Принудительные 120 Гц и Edge-Touch",
                "Фиксация экрана M17 на 120 Гц и снятие мертвых зон тача у границ",
                NativOSPreferences.lock120HzAndTouchBoost(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setLock120HzAndTouchBoost(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Аппаратный лок шины Adreno GPU",
                "Предотвращение микрофризов Adreno KGSL при выводе окон",
                NativOSPreferences.adrenoGpuBusLock(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setAdrenoGpuBusLock(this@SettingsActivity, checked)
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Аппаратные интерфейсы и мультимедиа"))
        content.addView(group().apply {
            addView(switchRow(
                "Прямой звук через ALSA (Low Latency)",
                "Прямой доступ к звуковой карте (/dev/snd) без задержек PulseAudio",
                NativOSPreferences.alsaDirectAudio(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setAlsaDirectAudio(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Аппаратный видеодекодер Snapdragon (VPU)",
                "Аппаратное ускорение H.264/HEVC/VP9 (/dev/video32)",
                NativOSPreferences.hardwareVideoDecoding(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setHardwareVideoDecoding(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Прямой вывод Direct DRM/KMS (Экспериментально)",
                "Остановка SurfaceFlinger и вывод на панель M17 напрямую @ 120Hz",
                NativOSPreferences.directDrmMode(this@SettingsActivity)
            ) { checked ->
                if (checked) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Direct DRM/KMS Режим")
                        .setMessage("Внимание: этот режим останавливает SurfaceFlinger (графику Android) на время работы сессии Linux и отдает экран /dev/dri/card0 напрямую Wayland-композитору на 120 FPS.\n\nВстроенный аварийный сторож (Safety Watchdog) автоматически восстановит SurfaceFlinger при любом сбое или сворачивании приложения. Включить?")
                        .setPositiveButton("Включить") { _, _ ->
                            NativOSPreferences.setDirectDrmMode(this@SettingsActivity, true)
                            updateState()
                        }
                        .setNegativeButton("Отмена") { _, _ ->
                            updateState()
                        }
                        .show()
                } else {
                    NativOSPreferences.setDirectDrmMode(this@SettingsActivity, false)
                    updateState()
                }
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Снимки и резервные копии"))
        content.addView(group().apply {
            addView(row(
                title = "Создать снимок системы",
                subtitle = "Сохранить полный бэкап в Download/nativOS-backups",
                trailingText = "Создать"
            ) { startBackup() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "Восстановить систему из снимка",
                subtitle = "Откатить Linux-окружение к сохраненному состоянию",
                trailingText = "›"
            ) { showRestoreDialog() })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Мастер настройки"))
        content.addView(group().apply {
            addView(row(
                title = "Мастер первоначальной настройки",
                subtitle = "Повторно пройти пошаговую настройку NativOS",
                trailingText = "›"
            ) {
                startActivity(Intent(this@SettingsActivity, SetupWizardActivity::class.java))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(group().apply {
            addView(row("Return to desktop", trailingText = "›") {
                startActivity(Intent(this@SettingsActivity, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.text(this, "System changes are explicit and reversible.", 12f, PremiumUi.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), 0)
        }, PremiumUi.matchWidth())

        setContentView(ScrollView(this).apply {
            background = PremiumUi.pageBackground()
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(PremiumUi.surface)
        }
        clipToOutline = true
    }

    private fun row(
        title: String,
        subtitle: String? = null,
        subtitleView: TextView? = null,
        trailingText: String = "",
        trailing: TextView? = null,
        destructive: Boolean = false,
        action: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(if (subtitle != null || subtitleView != null) 66 else 54)
        setPadding(dp(16), dp(10), dp(14), dp(10))
        if (action != null) {
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            setOnClickListener { action() }
        }

        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(PremiumUi.text(
                this@SettingsActivity,
                title,
                16f,
                if (destructive) PremiumUi.danger else PremiumUi.text
            ))
            if (subtitleView != null) {
                subtitleView.setPadding(0, dp(4), 0, 0)
                addView(subtitleView)
            } else if (subtitle != null) {
                addView(PremiumUi.text(this@SettingsActivity, subtitle, 13f, PremiumUi.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(trailing ?: PremiumUi.text(
            this@SettingsActivity,
            trailingText,
            if (trailingText == "›") 28f else 15f,
            if (trailingText == "›") PremiumUi.muted else PremiumUi.primary
        ).apply { gravity = Gravity.CENTER })
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(66)
        setPadding(dp(16), dp(10), dp(10), dp(10))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(PremiumUi.text(this@SettingsActivity, title, 16f))
            addView(PremiumUi.text(this@SettingsActivity, subtitle, 13f, PremiumUi.muted).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(SwitchMaterial(this@SettingsActivity).apply {
            text = ""
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
    }

    private fun scanGoogleComponents() {
        deGoogleStatus.text = "Scanning…"
        Thread({
            val result = runCatching { DeGoogleManager(this).scan() }
            runOnUiThread {
                deGoogleStatus.text = result.fold(
                    onSuccess = { "${it.coreServices.size} core services · ${it.googleApps.size} Google apps" },
                    onFailure = { "Scan failed" }
                )
            }
        }, "nativOS-degoogle-scan").start()
    }

    private fun updateState() {
        if (!::summary.isInitialized) return
        val mode = NativOSPreferences.operatingMode(this)
        desktopCheck.text = if (mode == NativOSPreferences.OperatingMode.DESKTOP_APP) "✓" else ""
        homeCheck.text = if (mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER) "✓" else ""
        summary.text = when {
            mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER && HomeRoleManager.isDefaultHome(this) ->
                "NativOS is your Home launcher"
            mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER ->
                "Home launcher selected · Android approval needed"
            else -> "Linux desktop with Android underneath"
        }
        val distro = NativOSPreferences.preferredDistro(this)
        if (::alpineCheck.isInitialized) alpineCheck.text = if (distro == "alpine") "✓" else ""
        if (::debianCheck.isInitialized) debianCheck.text = if (distro == "debian") "✓" else ""

        val scale = NativOSPreferences.displayScale(this)
        if (::displayScaleSubtitle.isInitialized) {
            displayScaleSubtitle.text = if (scale == 0f) "Автоматически (по DPI экрана)" else "${(scale * 100).toInt()}%"
        }
        val gpu = NativOSPreferences.gpuDriver(this)
        if (::gpuDriverSubtitle.isInitialized) {
            val detected = GpuDetector.detect(this)
            gpuDriverSubtitle.text = if (gpu == "auto") "Авто: ${detected.modelName} (${detected.recommendedDriver})" else "Принудительно: $gpu"
        }

        if (::wlrRendererSubtitle.isInitialized) {
            val rend = NativOSPreferences.wlrRenderer(this)
            wlrRendererSubtitle.text = when (rend) {
                "gles2" -> "Принудительно GPU GLES2"
                "pixman" -> "Принудительно CPU Pixman"
                else -> "Авто (GPU GLES2 с защитой)"
            }
        }

        if (::resolutionScaleSubtitle.isInitialized) {
            val resScale = NativOSPreferences.resolutionScalePercent(this)
            resolutionScaleSubtitle.text = when (resScale) {
                80 -> "80% (Сбалансированный режим, -36% нагрузки)"
                67 -> "67% (Турбо-режим ~720p, 2.5x FPS)"
                else -> "100% (Нативное разрешение)"
            }
        }

        val currentDe = NativOSPreferences.desktopEnvironment(this)
        if (::desktopEnvSubtitle.isInitialized) {
            val deInfo = RootfsManager.SUPPORTED_DESKTOPS.find { it.id == currentDe }
            val deName = deInfo?.name ?: currentDe
            val rootfsManager = RootfsManager(this)
            val installed = rootfsManager.isDesktopInstalled(currentDe)
            desktopEnvSubtitle.text = "$deName · ${if (installed) "Установлено" else "Требуется установка"}"
        }

        val touch = NativOSPreferences.touchMode(this)
        if (::touchModeSubtitle.isInitialized) {
            touchModeSubtitle.text = when (touch) {
                "touch" -> "Прямой Multi-Touch (120-240 Гц, без задержек)"
                "simulated" -> "Эмуляция мыши (со стрелкой)"
                "trackpad" -> "Режим трекпада"
                else -> touch
            }
        }

        if (::touchRotationSubtitle.isInitialized) {
            val rot = NativOSPreferences.touchRotationCorrection(this)
            touchRotationSubtitle.text = when (rot) {
                90 -> "90° (По часовой стрелке)"
                180 -> "180° (Вверх ногами)"
                270 -> "270° (Против часовой стрелки)"
                else -> "0° (Стандартно)"
            }
        }

        if (::storageModeSubtitle.isInitialized) {
            val mode = NativOSPreferences.storageMode(this)
            val fs = NativOSPreferences.storageFsType(this)
            storageModeSubtitle.text = if (mode == "image") "Образ диска .img ($fs)" else "Каталог файлов (Directory)"
        }
    }

    private fun showScaleDialog() {
        val options = arrayOf("Автоматически", "125%", "150%", "175%", "200%", "250%")
        val values = floatArrayOf(0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f)
        val current = NativOSPreferences.displayScale(this)
        val selectedIdx = values.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Масштаб интерфейса (DPI)")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                NativOSPreferences.setDisplayScale(this, values[which])
                updateState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showGpuDialog() {
        val options = arrayOf("Автоопределение (Рекомендуется)", "Qualcomm Turnip (Vulkan)", "VirGL (Host OpenGL)", "Zink (Vulkan to GL)", "Software (LLVMpipe)")
        val values = arrayOf("auto", "turnip", "virgl", "zink", "software")
        val current = NativOSPreferences.gpuDriver(this)
        val selectedIdx = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Выбор видеодрайвера")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                NativOSPreferences.setGpuDriver(this, values[which])
                updateState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showWlrRendererDialog() {
        val options = arrayOf(
            "Авто (GPU GLES2 с защитой и авто-откатом)",
            "Принудительно GPU (GLES2 аппаратное ускорение)",
            "Принудительно CPU (Pixman софтверный растеризатор)"
        )
        val values = arrayOf("auto", "gles2", "pixman")
        val current = NativOSPreferences.wlrRenderer(this)
        val selectedIdx = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Рендерер рабочего стола (wlroots)")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                NativOSPreferences.setWlrRenderer(this, values[which])
                updateState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showResolutionScaleDialog() {
        val options = arrayOf(
            "100% — Нативное разрешение (Максимальная четкость)",
            "80% — Сбалансированный режим (Снижение нагрузки на ~36%)",
            "67% — Турбо-режим ~720p (Прирост FPS в 2.5 раза, для игр)"
        )
        val values = arrayOf(100, 80, 67)
        val current = NativOSPreferences.resolutionScalePercent(this)
        val selectedIdx = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Масштаб разрешения экрана (Downsampling)")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                NativOSPreferences.setResolutionScalePercent(this, values[which])
                updateState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDesktopEnvironmentDialog() {
        val rootfsManager = RootfsManager(this)
        val chrootManager = ChrootManager(this)
        val current = NativOSPreferences.desktopEnvironment(this)

        val items = RootfsManager.SUPPORTED_DESKTOPS.map { de ->
            val isInstalled = rootfsManager.isDesktopInstalled(de.id)
            val status = if (isInstalled) "✓ Установлено" else "Требуется загрузка"
            val active = if (de.id == current) " [АКТИВНО]" else ""
            "${de.name}$active\n$status · ${de.category}"
        }.toTypedArray()

        val selectedIdx = RootfsManager.SUPPORTED_DESKTOPS.indexOfFirst { it.id == current }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Выбор окружения рабочего стола")
            .setSingleChoiceItems(items, selectedIdx) { dialog, which ->
                dialog.dismiss()
                val targetDe = RootfsManager.SUPPORTED_DESKTOPS[which]
                if (targetDe.id == current) return@setSingleChoiceItems

                val isInstalled = rootfsManager.isDesktopInstalled(targetDe.id)
                if (isInstalled) {
                    NativOSPreferences.setDesktopEnvironment(this, targetDe.id)
                    updateState()
                    AlertDialog.Builder(this)
                        .setTitle("Окружение переключено")
                        .setMessage("Выбрана оболочка ${targetDe.name}. Перезапустить сессию Linux сейчас?")
                        .setPositiveButton("Перезапустить") { _, _ ->
                            Thread({
                                chrootManager.stopSession()
                                runOnUiThread {
                                    startActivity(Intent(this, KioskActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    })
                                    finish()
                                }
                            }, "nativOS-restart-session").start()
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Установка ${targetDe.name}")
                        .setMessage("Окружение ${targetDe.name} еще не установлено. Загрузить и установить необходимые пакеты сейчас?")
                        .setPositiveButton("Установить") { _, _ ->
                            installAndSwitchDesktop(targetDe)
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun installAndSwitchDesktop(de: RootfsManager.Companion.DesktopEnvironmentInfo) {
        val rootfsManager = RootfsManager(this)
        val chrootManager = ChrootManager(this)
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("Установка ${de.name}")
            setMessage("Подготовка пакетов...")
            setCancelable(false)
            show()
        }

        Thread({
            val ok = rootfsManager.installDesktopEnvironment(chrootManager, de.id) { progress, status ->
                runOnUiThread { progressDialog.setMessage(status) }
            }
            runOnUiThread {
                progressDialog.dismiss()
                if (ok) {
                    NativOSPreferences.setDesktopEnvironment(this, de.id)
                    updateState()
                    AlertDialog.Builder(this)
                        .setTitle("Установка завершена")
                        .setMessage("${de.name} успешно установлена! Перезапустить сессию сейчас?")
                        .setPositiveButton("Перезапустить") { _, _ ->
                            Thread({
                                chrootManager.stopSession()
                                runOnUiThread {
                                    startActivity(Intent(this, KioskActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    })
                                    finish()
                                }
                            }, "nativOS-restart-session").start()
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                } else {
                    Toast.makeText(this, "Ошибка при установке ${de.name}", Toast.LENGTH_LONG).show()
                }
            }
        }, "nativOS-install-de").start()
    }

    private fun showTouchModeDialog() {
        val options = arrayOf(
            "⚡ Прямой Multi-Touch (120-240 Гц, минимальная задержка)",
            "🖱️ Эмуляция курсора мыши (со стрелкой)",
            "📱 Режим трекпада (относительное перемещение)"
        )
        val values = arrayOf("touch", "simulated", "trackpad")
        val current = NativOSPreferences.touchMode(this)
        val selectedIdx = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Режим сенсора (частота опроса)")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                NativOSPreferences.setTouchMode(this, values[which])
                updateState()
                dialog.dismiss()
                Toast.makeText(this, "Режим сенсора: ${options[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startBackup() {
        val rootfsManager = RootfsManager(this)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val targetDir = File("/sdcard/Download/nativOS-backups")
        val targetFile = File(targetDir, "nativos-backup-$timestamp.tar.gz")

        Toast.makeText(this, "Создание снимка системы...", Toast.LENGTH_SHORT).show()
        Thread({
            val success = rootfsManager.createBackup(targetFile) { progress, status ->
                Log.d("Settings", "Backup: $status ($progress)")
            }
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Резервная копия сохранена в Download/nativOS-backups", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Ошибка создания копии", Toast.LENGTH_SHORT).show()
                }
            }
        }, "nativOS-backup").start()
    }

    private fun showRestoreDialog() {
        val rootfsManager = RootfsManager(this)
        val backups = rootfsManager.listBackups()
        if (backups.isEmpty()) {
            Toast.makeText(this, "Снимки не найдены в папке Download", Toast.LENGTH_LONG).show()
            return
        }

        val names = backups.map { "${it.name} (${it.length() / (1024 * 1024)} МБ)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выберите снимок для восстановления")
            .setItems(names) { _, which ->
                val chosen = backups[which]
                AlertDialog.Builder(this)
                    .setTitle("Подтверждение отката")
                    .setMessage("Текущее состояние системы будет заменено снимком ${chosen.name}. Продолжить?")
                    .setPositiveButton("Восстановить") { _, _ ->
                        performRestore(chosen)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun performRestore(file: File) {
        val rootfsManager = RootfsManager(this)
        val chrootManager = ChrootManager(this)
        Toast.makeText(this, "Восстановление снимка...", Toast.LENGTH_SHORT).show()
        Thread({
            val ok = rootfsManager.restoreBackup(file, chrootManager) { _, _ -> }
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "Система успешно восстановлена!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Ошибка восстановления", Toast.LENGTH_SHORT).show()
                }
            }
        }, "nativOS-restore").start()
    }

    private fun showTouchRotationDialog() {
        val options = arrayOf("0° (Стандартно)", "90° (По часовой стрелке)", "180° (Вверх ногами)", "270° (Против часовой стрелки)")
        val values = intArrayOf(0, 90, 180, 270)
        val current = NativOSPreferences.touchRotationCorrection(this)
        val selectedIdx = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Поворот сенсора (Тач)")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                NativOSPreferences.setTouchRotationCorrection(this, values[which])
                updateState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showStorageModeDialog() {
        val options = arrayOf(
            "Каталог файлов (Быстрый доступ, по умолчанию)",
            "Образ диска .img (ext4, защита от удаления при очистке)",
            "Образ диска .img (btrfs, сжатие zstd и снапшоты)"
        )
        val currentMode = NativOSPreferences.storageMode(this)
        val currentFs = NativOSPreferences.storageFsType(this)
        val selectedIdx = when {
            currentMode == "image" && currentFs == "btrfs" -> 2
            currentMode == "image" -> 1
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("Режим хранения файловой системы")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                when (which) {
                    0 -> {
                        NativOSPreferences.setStorageMode(this, "directory")
                    }
                    1 -> {
                        NativOSPreferences.setStorageMode(this, "image")
                        NativOSPreferences.setStorageFsType(this, "ext4")
                    }
                    2 -> {
                        NativOSPreferences.setStorageMode(this, "image")
                        NativOSPreferences.setStorageFsType(this, "btrfs")
                    }
                }
                updateState()
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun selectableBackground() = android.util.TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private fun dp(value: Int) = PremiumUi.dp(this, value)
}
