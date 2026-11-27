package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Local MT-GNOME launcher lock. Credentials are salted and hashed; plaintext is never persisted.
 * This is an app/launcher lock, not an Android device lock or a replacement for system encryption.
 */
class LockSecurityActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("mt_gnome", MODE_PRIVATE) }
    private val blue = Color.rgb(89, 151, 255)
    private var selectedMode = "pin"
    private var pattern = StringBuilder()
    private lateinit var status: TextView
    private lateinit var credential: EditText
    private lateinit var patternLabel: TextView
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locked = intent.getBooleanExtra(EXTRA_LOCKED, false)
        selectedMode = prefs.getString(KEY_MODE, "pin") ?: "pin"
        buildUi(locked)
    }

    private fun buildUi(locked: Boolean) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(17, 21, 31))
        }
        root.addView(TextView(this).apply {
            text = if (locked) "MT-GNOME is locked" else "Desktop security"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = if (locked) "Enter your $selectedMode to continue" else
                "Choose how MT-GNOME protects access to your desktop."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(18))
        })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        root.addView(content, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            setTextColor(Color.rgb(255, 150, 130))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)
        if (locked) showUnlockForm() else showSettings()
        val pageScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(root, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        setContentView(pageScroll)
    }

    private fun showSettings() {
        content.removeAllViews()
        val modes = listOf("none" to "None — no lock", "password" to "Password",
            "pin" to "PIN", "pattern" to "Pattern")
        modes.forEach { (mode, title) ->
            content.addView(actionButton(if (selectedMode == mode) "✓  $title" else title) {
                if (mode == "none") {
                    prefs.edit().putBoolean(KEY_ENABLED, false).putString(KEY_MODE, "none")
                        .remove(KEY_HASH).remove(KEY_SALT).apply()
                    Toast.makeText(this, "Desktop lock disabled", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    selectedMode = mode
                    showCredentialSetup()
                }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        }
        content.addView(actionButton("Close") { finish() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
    }

    private fun showCredentialSetup() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "Set a new " + selectedMode
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        if (selectedMode == "pattern") {
            pattern.clear()
            patternLabel = TextView(this).apply {
                text = "Tap at least four dots to create your pattern"
                setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            }
            content.addView(patternLabel)
            val grid = GridLayout(this).apply { columnCount = 3; rowCount = 3 }
            repeat(9) { index ->
                grid.addView(actionButton("●") {
                    val node = (index + 1).toString()
                    if (!pattern.contains(node)) pattern.append(node)
                    patternLabel.text = "Pattern: " + "● ".repeat(pattern.length)
                }, GridLayout.LayoutParams().apply {
                    width = dp(66); height = dp(58)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                    rowSpec = GridLayout.spec(index / 3)
                    columnSpec = GridLayout.spec(index % 3)
                })
            }
            content.addView(grid)
            content.addView(actionButton("Clear pattern") {
                pattern.clear()
                patternLabel.text = "Tap at least four dots to create your pattern"
            })
        } else {
            credential = EditText(this).apply {
                setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
                hint = if (selectedMode == "pin") "At least 4 digits" else "Enter password"
                inputType = if (selectedMode == "pin")
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            content.addView(credential, LinearLayout.LayoutParams(-1, dp(52)))
        }
        content.addView(actionButton("Save lock") {
            val value = if (selectedMode == "pattern") pattern.toString() else credential.text.toString()
            if (selectedMode == "pin" && (value.length < 4 || !value.all { it.isDigit() })) {
                status.text = "PIN must contain at least 4 digits."
            } else if (value.length < 4) {
                status.text = if (selectedMode == "pattern") "Connect at least four dots." else "Use at least 4 characters."
            } else {
                saveCredential(value)
                prefs.edit().putString(KEY_MODE, selectedMode).putBoolean(KEY_ENABLED, true)
                    .putBoolean(KEY_SESSION_UNLOCKED, true).apply()
                Toast.makeText(this, selectedMode + " lock enabled", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        content.addView(actionButton("Back") { showSettings() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
    }

    private fun showUnlockForm() {
        content.removeAllViews()
        if (selectedMode == "pattern") {
            patternLabel = TextView(this).apply {
                text = "Tap at least four dots in your pattern"
                setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            }
            content.addView(patternLabel)
            val grid = GridLayout(this).apply { columnCount = 3; rowCount = 3; alignmentMode = GridLayout.ALIGN_BOUNDS }
            repeat(9) { index ->
                grid.addView(actionButton("●") {
                    val node = (index + 1).toString()
                    if (!pattern.contains(node)) pattern.append(node)
                    patternLabel.text = "Pattern: " + "● ".repeat(pattern.length)
                }, GridLayout.LayoutParams().apply {
                    width = dp(66); height = dp(58)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                    rowSpec = GridLayout.spec(index / 3)
                    columnSpec = GridLayout.spec(index % 3)
                })
            }
            content.addView(grid)
            content.addView(actionButton("Clear pattern") { pattern.clear(); patternLabel.text = "Tap at least four dots in your pattern" },
                LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })
        } else {
            credential = EditText(this).apply {
                setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
                hint = if (selectedMode == "pin") "PIN" else "Password"
                inputType = if (selectedMode == "pin")
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            content.addView(credential, LinearLayout.LayoutParams(-1, dp(52)))
        }
        content.addView(actionButton("Unlock") {
            val value = if (selectedMode == "pattern") pattern.toString() else credential.text.toString()
            if (selectedMode == "pattern" && value.length < 4) {
                status.text = "Connect at least four dots."
            } else if (verifyCredential(value)) {
                prefs.edit().putBoolean(KEY_SESSION_UNLOCKED, true).apply()
                setResult(RESULT_OK)
                finish()
            } else {
                status.text = "Incorrect $selectedMode. Try again."
                pattern.clear()
                if (selectedMode == "pattern") patternLabel.text = "Tap at least four dots in your pattern"
                else credential.text?.clear()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
    }

    private fun saveCredential(value: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SALT, salt.toHex()).putString(KEY_HASH, hash(value, salt)).apply()
    }

    private fun verifyCredential(value: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.hexToBytes() ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(value, salt).toByteArray())
    }

    private fun hash(value: String, salt: ByteArray): String {
        val spec = PBEKeySpec(value.toCharArray(), salt, 150_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray? = try {
        if (length % 2 != 0) null else chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (_: Exception) { null }

    private fun actionButton(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue; isAllCaps = false; textSize = 13f
        setTextColor(Color.WHITE)
        setBackgroundColor(blue)
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LOCKED = "locked"
        const val KEY_ENABLED = "desktop_lock_enabled"
        const val KEY_MODE = "desktop_lock_mode"
        const val KEY_HASH = "desktop_lock_hash"
        const val KEY_SALT = "desktop_lock_salt"
        const val KEY_SESSION_UNLOCKED = "desktop_lock_session_unlocked"
    }
}
