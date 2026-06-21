package com.example.moqandroid.ui

import android.app.Activity
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.moqandroid.input.isConfirmKeyDown

class ConfigScreen(
    activity: Activity,
    initialStatus: String,
    initialRelayUrl: String,
    private val onContinue: () -> Unit,
) {
    val status: TextView = TextView(activity).apply {
        text = initialStatus
        textSize = 16f
        setPadding(24, 24, 24, 16)
    }

    private val relayInput: EditText = EditText(activity).apply {
        id = View.generateViewId()
        setText(initialRelayUrl)
        hint = "Relay URL, for example http://host:4443/anon"
        setSingleLine(true)
        textSize = 18f
        isFocusable = true
        isFocusableInTouchMode = true
        setSelectAllOnFocus(true)
        imeOptions = EditorInfo.IME_ACTION_GO
        setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                event.isConfirmKeyDown()
            if (submit) onContinue()
            submit
        }
        setPadding(24, 8, 24, 8)
    }

    private val continueButton: Button = Button(activity).apply {
        id = View.generateViewId()
        text = "Continue"
        textSize = 18f
        minHeight = 64
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onContinue() }
        setOnKeyListener { _, keyCode, event ->
            val submit = event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (submit) onContinue()
            submit
        }
    }

    val root: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(40, 40, 40, 40)
        addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(
            TextView(activity).apply {
                text = "Relay URL"
                textSize = 16f
                setPadding(24, 12, 24, 0)
            },
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        addView(relayInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(continueButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    init {
        relayInput.nextFocusDownId = continueButton.id
        continueButton.nextFocusUpId = relayInput.id
    }

    fun relayUrl(): String = relayInput.text.toString()

    fun setStatus(message: String) {
        status.text = message
    }

    fun requestInitialFocus() {
        relayInput.requestFocus()
    }
}
