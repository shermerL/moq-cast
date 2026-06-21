package com.example.moqandroid.input

import android.view.KeyEvent

fun KeyEvent?.isConfirmKeyDown(): Boolean {
    return this != null && action == KeyEvent.ACTION_UP &&
        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
}
