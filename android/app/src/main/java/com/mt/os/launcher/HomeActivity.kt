package com.mt.os.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Native Android HOME screen for MT-OS.
 *
 * This screen is deliberately independent of the Linux rootfs, chroot and
 * Phosh startup path. Android apps remain usable even when Linux is offline
 * or has not been installed.
 */
class HomeActivity : Activity() {
    private data class LaunchableApp(val info: ResolveInfo, val label: String)

    private lateinit var appGrid: GridLayout
    private lateinit var searchBox: EditText
    private lateinit var emptyLabel: TextView
    private var allApps: List<LaunchableApp> = emptyList()

    private val backgroundColor = Color.rgb(10, 15, 29)
    private val panelColor = Color.rgb(22, 30, 49)
    private val accentColor = Color.rgb(88, 166, 255)
    private val mutedColor = Color.rgb(157, 171, 194)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = 0
        }
        buildHomeScreen()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (::appGrid.isInitialized) loadApps()
    }

    private fun buildHomeScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(12))
            setBackgroundColor(backgroundColor)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "MT-OS"
            setTextColor(Color.WHITE)
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        brand.addView(TextView(this).apply {
            text = "ANDROID DESKTOP"
            setTextColor(accentColor)
            textSize = 10f
            letterSpacing = 0.18f
            typeface = Typeface.DEFAULT_BOLD
        })
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        })
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "Your phone. Your workspace."
            setTextColor(mutedColor)
            textSize = 13f
            setPadding(0, dp(5), 0, dp(18))
        })

        searchBox = EditText(this).apply {
            hint = "Search apps"
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(mutedColor)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(panelColor, dp(16))
            compoundDrawablePadding = dp(8)
        }
        root.addView(searchBox, LinearLayout.LayoutParams(-1, dp(50)).apply {
            bottomMargin = dp(14)
        })

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        section.addView(TextView(this).apply {
            text = "APPLICATIONS"
            setTextColor(Color.WHITE)
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(TextView(this).apply {
            text = "Refresh"
            setTextColor(accentColor)
            textSize = 12f
            setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener { loadApps() }
        })
        root.addView(section)

        appGrid = GridLayout(this).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        emptyLabel = TextView(this).apply {
            text = "No apps found"
            setTextColor(mutedColor)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(36), 0, dp(36))
        }

        val appContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(appGrid, LinearLayout.LayoutParams(-1, -2))
            addView(emptyLabel, LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            addView(appContent)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(8)
        })

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(panelColor, dp(20))
        }
        dock.addView(dockButton("MT Linux", "Launch the MT-OS Linux desktop") {
            startActivity(Intent(this, KioskActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginEnd = dp(6)
        })
        dock.addView(dockButton("Android Settings", "Open Android settings") {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(6)
        })
        root.addView(dock)

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderApps(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        setContentView(root)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dockButton(label: String, description: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            contentDescription = description
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(37, 53, 79), dp(14))
            isFocusable = true
            isClickable = true
            setOnClickListener { action() }
        }

    @Suppress("DEPRECATION")
    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }

        allApps = matches
            .filter { it.activityInfo?.packageName != packageName }
            .map { LaunchableApp(it, it.loadLabel(packageManager).toString()) }
            .distinctBy { "${it.info.activityInfo.packageName}/${it.info.activityInfo.name}" }
            .sortedBy { it.label.lowercase() }
        renderApps(searchBox.text?.toString().orEmpty())
    }

    private fun renderApps(filter: String) {
        if (!::appGrid.isInitialized) return
        appGrid.removeAllViews()
        val visible = allApps.filter { it.label.contains(filter.trim(), ignoreCase = true) }
        emptyLabel.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        val columns = 4
        visible.forEachIndexed { index, app ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                isFocusable = true
                background = rounded(Color.TRANSPARENT, dp(14))
                contentDescription = app.label
                setOnClickListener { launchApp(app) }
            }
            val icon = ImageView(this).apply {
                setImageDrawable(app.info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            tile.addView(TextView(this).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(34)))

            val row = index / columns
            val col = index % columns
            val params = GridLayout.LayoutParams(
                GridLayout.spec(row),
                GridLayout.spec(col, 1f)
            ).apply {
                width = 0
                height = dp(92)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            appGrid.addView(tile, params)
        }
    }

    private fun launchApp(app: LaunchableApp) {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.info.activityInfo.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(
                    app.info.activityInfo.packageName,
                    app.info.activityInfo.name
                )
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } catch (error: Exception) {
            Toast.makeText(this, "Unable to open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }
}
