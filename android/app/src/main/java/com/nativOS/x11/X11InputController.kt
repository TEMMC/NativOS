package com.nativOS.x11

import android.view.MotionEvent
import android.view.View
import com.nativOS.settings.NativOSPreferences
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler

/** Routes Android touch, mouse, and keyboard events into the embedded X server. */
class X11InputController(private val lorieView: LorieView) {
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    init {
        applyTouchModePreference()
        applyTouchCalibration()
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent)

        lorieView.setCallback { width, height, transform ->
            inputHandler.handleInputTransformChanged(width, height, transform)
        }
        lorieView.setOnTouchListener(::handleMotionEvent)
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
    }

    fun applyTouchModePreference() {
        val modeStr = NativOSPreferences.touchMode(lorieView.context)
        val modeInt = when (modeStr) {
            "simulated" -> TouchInputHandler.InputMode.SIMULATED_TOUCH
            "trackpad" -> TouchInputHandler.InputMode.TRACKPAD
            else -> TouchInputHandler.InputMode.TOUCH // Default to direct Multi-Touch (120/240Hz, zero delay)
        }
        MainActivity.getPrefs().touchMode.put(modeInt.toString())
        inputHandler.reloadPreferences(MainActivity.getPrefs())
    }

    fun applyTouchCalibration() {
        val context = lorieView.context
        inputHandler.updateTouchCalibration(
            NativOSPreferences.touchInvertX(context),
            NativOSPreferences.touchInvertY(context),
            NativOSPreferences.touchSwapAxes(context),
            NativOSPreferences.touchRotationCorrection(context),
            NativOSPreferences.touchScaleX(context),
            NativOSPreferences.touchScaleY(context),
            NativOSPreferences.touchOffsetX(context),
            NativOSPreferences.touchOffsetY(context)
        )
    }

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean =
        inputHandler.handleTouchEvent(lorieView, view, event)
}
