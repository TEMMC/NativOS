package com.nativOS.setup

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nativOS.launcher.KioskActivity
import com.nativOS.runtime.ChrootManager
import com.nativOS.runtime.DisplayController
import com.nativOS.runtime.GpuDetector
import com.nativOS.runtime.RootfsManager
import com.nativOS.settings.NativOSPreferences
import com.nativOS.settings.PremiumUi

class SetupWizardActivity : Activity() {

    private var currentStep = 1
    private val totalSteps = 5

    private var selectedDistro = "alpine"
    private var selectedDesktop = "phosh"
    private var selectedLanguage = "system"
    private var selectedProfile = "minimal"
    private var enable120Hz = true
    private var selectedTouchMode = "touch"
    private var selectedScale = 0f

    private lateinit var contentLayout: LinearLayout
    private lateinit var stepIndicator: TextView
    private lateinit var nextButton: View
    private lateinit var prevButton: View

    private lateinit var rootfsManager: RootfsManager
    private lateinit var chrootManager: ChrootManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background

        rootfsManager = RootfsManager(this)
        chrootManager = ChrootManager(this)

        selectedDistro = NativOSPreferences.preferredDistro(this)
        selectedDesktop = NativOSPreferences.desktopEnvironment(this)
        selectedTouchMode = NativOSPreferences.touchMode(this)
        enable120Hz = DisplayController.getMaxSupportedRefreshRate(this) > 65f

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PremiumUi.background)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // Top Navigation Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stepIndicator = PremiumUi.text(this, "Шаг 1 из $totalSteps", 13f, PremiumUi.primary, bold = true)
        header.addView(stepIndicator, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        root.addView(PremiumUi.verticalSpace(this, 16))

        // Dynamic content in ScrollView
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(contentLayout)
        }
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Bottom Navigation Buttons
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }

        prevButton = PremiumUi.button(this, "Назад").apply {
            visibility = View.INVISIBLE
            setOnClickListener {
                if (currentStep > 1) {
                    currentStep--
                    renderCurrentStep()
                }
            }
        }
        footer.addView(prevButton, LinearLayout.LayoutParams(dp(110), dp(48)))
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        nextButton = PremiumUi.button(this, "Далее", primaryAction = true).apply {
            setOnClickListener {
                if (currentStep < totalSteps) {
                    currentStep++
                    renderCurrentStep()
                } else if (currentStep == totalSteps) {
                    startInstallationFlow()
                }
            }
        }
        footer.addView(nextButton, LinearLayout.LayoutParams(dp(130), dp(48)))
        root.addView(footer)

        setContentView(root)
        renderCurrentStep()
    }

    private fun renderCurrentStep() {
        contentLayout.removeAllViews()
        prevButton.visibility = if (currentStep > 1) View.VISIBLE else View.INVISIBLE
        stepIndicator.text = "Шаг $currentStep из $totalSteps"

        when (currentStep) {
            1 -> renderStepDistro()
            2 -> renderStepDesktop()
            3 -> renderStepLanguageAndProfile()
            4 -> renderStepDisplayAndGpu()
            5 -> renderStepSummary()
        }
    }

    private fun renderStepDistro() {
        contentLayout.addView(PremiumUi.text(this, "Выберите дистрибутив", 26f, bold = true))
        contentLayout.addView(PremiumUi.text(this, "NativOS работает на нативном Linux в chroot.", 14f, PremiumUi.muted).apply {
            setPadding(0, dp(4), 0, dp(20))
        })

        contentLayout.addView(selectableCard(
            title = "Alpine Linux 3.21 (postmarketOS)",
            badge = "РЕКОМЕНДУЕТСЯ",
            desc = "Ультралегкая система (~250 МБ). Оптимизирована для мобильных устройств, мгновенный старт через OpenRC, пакетный менеджер apk.",
            isSelected = selectedDistro == "alpine"
        ) {
            selectedDistro = "alpine"
            NativOSPreferences.setPreferredDistro(this, "alpine")
            renderStepDistro()
        })

        contentLayout.addView(PremiumUi.verticalSpace(this, 12))

        contentLayout.addView(selectableCard(
            title = "Debian 13 (Trixie)",
            badge = "GLIBC",
            desc = "Классический Linux с полной glibc-совместимостью (~2 ГБ). Огромная библиотека пакетов apt, Phosh, поддержка любых десктопных утилит.",
            isSelected = selectedDistro == "debian"
        ) {
            selectedDistro = "debian"
            NativOSPreferences.setPreferredDistro(this, "debian")
            renderStepDistro()
        })
    }

    private fun renderStepDesktop() {
        contentLayout.addView(PremiumUi.text(this, "Окружение рабочего стола", 26f, bold = true))
        contentLayout.addView(PremiumUi.text(this, "Выберите графическую оболочку (все доступные в postmarketOS).", 14f, PremiumUi.muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        contentLayout.addView(PremiumUi.sectionLabel(this, "Мобильные интерфейсы (Сенсорный экран)"))
        RootfsManager.SUPPORTED_DESKTOPS.filter { it.category == "Mobile UI" }.forEach { de ->
            val badge = when (de.id) {
                "phosh" -> "ПО УМОЛЧАНИЮ"
                "sxmo" -> "120 FPS / ТАЙЛИНГ"
                "plasma-mobile" -> "KDE MOBILE"
                else -> "MOBILE"
            }
            contentLayout.addView(selectableCard(
                title = de.name,
                badge = badge,
                desc = de.description,
                isSelected = selectedDesktop == de.id
            ) {
                selectedDesktop = de.id
                NativOSPreferences.setDesktopEnvironment(this, de.id)
                renderStepDesktop()
            })
            contentLayout.addView(PremiumUi.verticalSpace(this, 10))
        }

        contentLayout.addView(PremiumUi.verticalSpace(this, 8))
        contentLayout.addView(PremiumUi.sectionLabel(this, "Классические рабочие столы (Десктоп)"))
        RootfsManager.SUPPORTED_DESKTOPS.filter { it.category == "Desktop UI" }.forEach { de ->
            val badge = when (de.id) {
                "xfce4" -> "ЛЕГКОВЕСНЫЙ"
                "gnome" -> "СОВРЕМЕННЫЙ"
                "plasma" -> "КАСТОМИЗАЦИЯ"
                "lxqt" -> "БЫСТРЫЙ"
                else -> "КЛАССИКА"
            }
            contentLayout.addView(selectableCard(
                title = de.name,
                badge = badge,
                desc = de.description,
                isSelected = selectedDesktop == de.id
            ) {
                selectedDesktop = de.id
                NativOSPreferences.setDesktopEnvironment(this, de.id)
                renderStepDesktop()
            })
            contentLayout.addView(PremiumUi.verticalSpace(this, 10))
        }
    }

    private fun renderStepLanguageAndProfile() {
        contentLayout.addView(PremiumUi.text(this, "Язык и программы", 26f, bold = true))
        contentLayout.addView(PremiumUi.text(this, "Настройте локализацию и начальный набор софта.", 14f, PremiumUi.muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        contentLayout.addView(PremiumUi.sectionLabel(this, "Язык интерфейса"))
        val langGroup = cardGroup()
        langGroup.addView(radioRow("Русский (ru_RU.UTF-8)", selectedLanguage == "ru") {
            selectedLanguage = "ru"
            NativOSPreferences.setSystemLanguage(this, "ru")
            renderStepLanguageAndProfile()
        })
        langGroup.addView(PremiumUi.separator(this))
        langGroup.addView(radioRow("English (en_US.UTF-8)", selectedLanguage == "en") {
            selectedLanguage = "en"
            NativOSPreferences.setSystemLanguage(this, "en")
            renderStepLanguageAndProfile()
        })
        langGroup.addView(PremiumUi.separator(this))
        langGroup.addView(radioRow("Системный язык устройства", selectedLanguage == "system") {
            selectedLanguage = "system"
            NativOSPreferences.setSystemLanguage(this, "system")
            renderStepLanguageAndProfile()
        })
        contentLayout.addView(langGroup)

        contentLayout.addView(PremiumUi.verticalSpace(this, 20))
        contentLayout.addView(PremiumUi.sectionLabel(this, "Профиль программ"))

        val profiles = listOf(
            Triple("minimal", "⚡ Минимальный", "Только выбранное окружение, терминал и базовые утилиты"),
            Triple("standard", "💼 Стандартный (Офис)", "+ Firefox ESR, LibreOffice, просмотрщик PDF"),
            Triple("developer", "🛠️ Разработка", "+ Git, Python 3, Neovim, GCC, SSH-сервер"),
            Triple("media", "🎬 Мультимедиа", "+ GIMP, VLC, MPV медиаплеер")
        )

        val profGroup = cardGroup()
        profiles.forEachIndexed { i, (id, name, desc) ->
            if (i > 0) profGroup.addView(PremiumUi.separator(this))
            profGroup.addView(radioRow(name, selectedProfile == id, desc) {
                selectedProfile = id
                NativOSPreferences.setSoftwareProfile(this, id)
                renderStepLanguageAndProfile()
            })
        }
        contentLayout.addView(profGroup)
    }

    private fun renderStepDisplayAndGpu() {
        contentLayout.addView(PremiumUi.text(this, "Дисплей и сенсор", 26f, bold = true))
        contentLayout.addView(PremiumUi.text(this, "Аппаратное ускорение, частота экрана и опрос сенсора.", 14f, PremiumUi.muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        val gpuInfo = GpuDetector.detect(this)
        val gpuCard = cardGroup().apply {
            addView(LinearLayout(this@SetupWizardActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(PremiumUi.text(this@SetupWizardActivity, "Видеочип: ${gpuInfo.modelName}", 16f, bold = true))
                addView(PremiumUi.text(this@SetupWizardActivity, gpuInfo.description, 13f, PremiumUi.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
                val badge = if (gpuInfo.recommendedDriver == "turnip") "Turnip Vulkan (KGSL)" else "Zink / LLVMpipe"
                addView(PremiumUi.text(this@SetupWizardActivity, "Драйвер: $badge", 13f, PremiumUi.success, bold = true).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            })
        }
        contentLayout.addView(gpuCard)

        contentLayout.addView(PremiumUi.verticalSpace(this, 16))
        contentLayout.addView(PremiumUi.sectionLabel(this, "Частота экрана"))

        val maxHz = DisplayController.getMaxSupportedRefreshRate(this)
        val hzGroup = cardGroup()
        hzGroup.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(LinearLayout(this@SetupWizardActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(PremiumUi.text(this@SetupWizardActivity, "Плавный режим (${maxHz.toInt()} Гц)", 16f))
                addView(PremiumUi.text(this@SetupWizardActivity, "Максимальная плавность анимаций и скроллинга (120 FPS)", 13f, PremiumUi.muted).apply {
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(SwitchMaterial(this@SetupWizardActivity).apply {
                isChecked = enable120Hz && maxHz > 65f
                isEnabled = maxHz > 65f
                setOnCheckedChangeListener { _, checked ->
                    enable120Hz = checked
                    NativOSPreferences.setHighRefreshRate(this@SetupWizardActivity, checked)
                }
            })
        })
        contentLayout.addView(hzGroup)

        contentLayout.addView(PremiumUi.verticalSpace(this, 16))
        contentLayout.addView(PremiumUi.sectionLabel(this, "Режим сенсора (частота опроса)"))

        val touchGroup = cardGroup()
        val touchModes = listOf(
            Triple("touch", "⚡ Прямой Multi-Touch (120-240 Гц)", "Мгновенный отклик, мультитач жесты, без задержек эмуляции"),
            Triple("simulated", "🖱️ Эмуляция курсора мыши", "Касания преобразуются в клики мыши"),
            Triple("trackpad", "📱 Режим трекпада", "Относительное перемещение курсора")
        )
        touchModes.forEachIndexed { idx, (id, title, desc) ->
            if (idx > 0) touchGroup.addView(PremiumUi.separator(this))
            touchGroup.addView(radioRow(title, selectedTouchMode == id, desc) {
                selectedTouchMode = id
                NativOSPreferences.setTouchMode(this, id)
                renderStepDisplayAndGpu()
            })
        }
        contentLayout.addView(touchGroup)

        contentLayout.addView(PremiumUi.verticalSpace(this, 16))
        contentLayout.addView(PremiumUi.sectionLabel(this, "Масштабирование интерфейса (DPI)"))

        val scaleGroup = cardGroup()
        val scales = listOf(0f to "Автоматически (по DPI экрана)", 1.25f to "125% (Мелкий)", 1.5f to "150% (Сбалансированный)", 2.0f to "200% (Крупный)")
        scales.forEachIndexed { idx, (scale, label) ->
            if (idx > 0) scaleGroup.addView(PremiumUi.separator(this))
            scaleGroup.addView(radioRow(label, selectedScale == scale) {
                selectedScale = scale
                NativOSPreferences.setDisplayScale(this, scale)
                renderStepDisplayAndGpu()
            })
        }
        contentLayout.addView(scaleGroup)
    }

    private fun renderStepSummary() {
        contentLayout.addView(PremiumUi.text(this, "Всё готово к установке", 26f, bold = true))
        contentLayout.addView(PremiumUi.text(this, "Проверьте выбранные параметры перед началом.", 14f, PremiumUi.muted).apply {
            setPadding(0, dp(4), 0, dp(20))
        })

        val deName = RootfsManager.SUPPORTED_DESKTOPS.find { it.id == selectedDesktop }?.name ?: selectedDesktop
        val summaryCard = cardGroup().apply {
            addView(summaryRow("Дистрибутив", if (selectedDistro == "alpine") "Alpine 3.21 (postmarketOS)" else "Debian 13 (Trixie)"))
            addView(PremiumUi.separator(this@SetupWizardActivity))
            addView(summaryRow("Окружение", deName))
            addView(PremiumUi.separator(this@SetupWizardActivity))
            addView(summaryRow("Язык системы", when (selectedLanguage) { "ru" -> "Русский"; "en" -> "English"; else -> "Системный" }))
            addView(PremiumUi.separator(this@SetupWizardActivity))
            addView(summaryRow("Профиль ПО", selectedProfile.replaceFirstChar { it.uppercase() }))
            addView(PremiumUi.separator(this@SetupWizardActivity))
            addView(summaryRow("Экран и сенсор", "${if (enable120Hz) "120 Гц" else "60 Гц"} · ${if (selectedTouchMode == "touch") "Multi-Touch 120-240Гц" else selectedTouchMode}"))
        }
        contentLayout.addView(summaryCard)

        contentLayout.addView(PremiumUi.verticalSpace(this, 24))
        contentLayout.addView(PremiumUi.text(this, "Нажмите «Установить», чтобы загрузить образ и настроить рабочее окружение.", 13f, PremiumUi.muted).apply {
            gravity = Gravity.CENTER
        })

        nextButton.apply {
            (this as? com.google.android.material.button.MaterialButton)?.text = "Установить"
        }
    }

    private fun startInstallationFlow() {
        // Switch UI to installation progress screen
        contentLayout.removeAllViews()
        stepIndicator.text = "Установка NativOS"
        prevButton.visibility = View.GONE
        nextButton.visibility = View.GONE

        contentLayout.addView(PremiumUi.text(this, "Подготовка системы...", 24f, bold = true))
        val statusText = PremiumUi.text(this, "Инициализация...", 14f, PremiumUi.muted).apply {
            setPadding(0, dp(6), 0, dp(24))
        }
        contentLayout.addView(statusText)

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 5
        }
        contentLayout.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(10)
        ))

        Thread({
            try {
                // Step 1: Download
                runOnUiThread {
                    statusText.text = "Загрузка базового образа ($selectedDistro)..."
                    progressBar.progress = 10
                }
                var failed = false
                rootfsManager.downloadRootfs(selectedDistro) { progress, status ->
                    runOnUiThread {
                        if (progress < 0) failed = true
                        statusText.text = status
                        progressBar.progress = (10 + (progress * 25).toInt()).coerceIn(10, 35)
                    }
                }
                if (failed) throw RuntimeException("Download failed")

                // Step 2: Extract
                runOnUiThread {
                    statusText.text = "Распаковка файловой системы..."
                    progressBar.progress = 40
                }
                rootfsManager.extractRootfs { progress, status ->
                    runOnUiThread {
                        if (progress < 0) failed = true
                        statusText.text = status
                        progressBar.progress = (40 + (progress * 20).toInt()).coerceIn(40, 60)
                    }
                }
                if (failed) throw RuntimeException("Extraction failed")

                // Step 3: Install Desktop Environment
                val deName = RootfsManager.SUPPORTED_DESKTOPS.find { it.id == selectedDesktop }?.name ?: selectedDesktop
                runOnUiThread {
                    statusText.text = "Установка окружения $deName..."
                    progressBar.progress = 65
                }
                val deSuccess = rootfsManager.installDesktopEnvironment(chrootManager, selectedDesktop) { progress, status ->
                    runOnUiThread {
                        statusText.text = status
                        progressBar.progress = (65 + (progress * 20).toInt()).coerceIn(65, 85)
                    }
                }
                if (!deSuccess) Log.w("SetupWizard", "Desktop installation completed with warnings")

                // Step 4: Software profile installation
                if (selectedProfile != "minimal") {
                    runOnUiThread {
                        statusText.text = "Установка профиля софта: $selectedProfile..."
                        progressBar.progress = 88
                    }
                    rootfsManager.installSoftwareProfile(chrootManager, selectedProfile) { progress, status ->
                        runOnUiThread { statusText.text = status }
                    }
                }

                // Complete
                NativOSPreferences.setSetupWizardCompleted(this, true)
                runOnUiThread {
                    progressBar.progress = 100
                    statusText.text = "Система и $deName успешно настроены!"
                    statusText.setTextColor(PremiumUi.success)

                    contentLayout.addView(PremiumUi.verticalSpace(this, 24))
                    val launchBtn = PremiumUi.button(this, "Запустить NativOS", primaryAction = true).apply {
                        setOnClickListener {
                            startActivity(Intent(this@SetupWizardActivity, KioskActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            finish()
                        }
                    }
                    contentLayout.addView(launchBtn, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
                    ))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Ошибка установки: ${e.message}"
                    statusText.setTextColor(PremiumUi.danger)
                }
            }
        }, "nativOS-setup-install").start()
    }

    private fun selectableCard(title: String, badge: String, desc: String, isSelected: Boolean, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isSelected) Color.rgb(20, 36, 60) else PremiumUi.surface)
                setStroke(dp(2), if (isSelected) PremiumUi.primary else Color.TRANSPARENT)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            val top = LinearLayout(this@SetupWizardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(PremiumUi.text(this@SetupWizardActivity, title, 16f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(PremiumUi.text(this@SetupWizardActivity, badge, 11f, PremiumUi.primary, bold = true).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.rgb(10, 40, 75))
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })
            addView(top)
            addView(PremiumUi.text(this@SetupWizardActivity, desc, 13f, PremiumUi.muted).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun cardGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(PremiumUi.surface)
        }
        clipToOutline = true
    }

    private fun radioRow(title: String, isSelected: Boolean, subtitle: String? = null, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(LinearLayout(this@SetupWizardActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(PremiumUi.text(this@SetupWizardActivity, title, 15f))
                if (subtitle != null) {
                    addView(PremiumUi.text(this@SetupWizardActivity, subtitle, 12f, PremiumUi.muted).apply {
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(PremiumUi.text(this@SetupWizardActivity, if (isSelected) "✓" else "", 18f, PremiumUi.primary, bold = true))
        }

    private fun summaryRow(label: String, value: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(PremiumUi.text(this@SetupWizardActivity, label, 14f, PremiumUi.muted), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(PremiumUi.text(this@SetupWizardActivity, value, 14f, bold = true))
        }

    private fun dp(value: Int) = PremiumUi.dp(this, value)
}
