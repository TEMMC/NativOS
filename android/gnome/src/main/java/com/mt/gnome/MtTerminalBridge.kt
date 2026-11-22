package com.mt.gnome

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.View
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** Bridges Termux's full VT emulator to Android input, clipboard and rendering. */
class MtTerminalBridge(
    private val context: Context,
    private val terminalView: TerminalView
) : TerminalSessionClient, TerminalViewClient {

    init {
        terminalView.setTerminalViewClient(this)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setTextSize(context.getSharedPreferences("mt_gnome", Context.MODE_PRIVATE).getInt("terminal_font_size", 13).coerceIn(9, 24))
        terminalView.setKeepScreenOn(false)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.post { terminalView.onScreenUpdated() }
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView.post { terminalView.onScreenUpdated() }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MT Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount > 0) {
            val value = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
            value.toByteArray(Charsets.UTF_8).let { session.write(it, 0, it.size) }
        }
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        terminalView.post { terminalView.invalidate() }
    }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null

    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message); Unit }
    override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message); Unit }
    override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message); Unit }
    override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message); Unit }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message); Unit }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "Terminal error", e) }
}