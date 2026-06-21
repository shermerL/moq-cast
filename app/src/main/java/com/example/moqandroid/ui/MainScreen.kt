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

class MainScreen(
    activity: Activity,
    initialStatus: String,
    initialBroadcast: String,
    private val onSubscribe: () -> Unit,
    private val onPublishScreen: () -> Unit,
    private val onStopPublish: () -> Unit,
    private val onConfigureRelay: () -> Unit,
) {
    val status: TextView = TextView(activity).apply {
        text = initialStatus
        textSize = 16f
        setPadding(24, 24, 24, 16)
    }

    private val broadcastInput: EditText = EditText(activity).apply {
        id = View.generateViewId()
        setText(initialBroadcast)
        hint = "Broadcast name, for example bbb.hang or me.hang"
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
            if (submit) onSubscribe()
            submit
        }
        setPadding(24, 8, 24, 8)
    }

    private val subscribeButton: Button = Button(activity).apply {
        id = View.generateViewId()
        text = "Subscribe"
        textSize = 18f
        minHeight = 64
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onSubscribe() }
        setOnKeyListener { _, keyCode, event ->
            val submit = event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (submit) onSubscribe()
            submit
        }
    }

    private val publishButton: Button = Button(activity).apply {
        id = View.generateViewId()
        text = "Publish screen"
        textSize = 18f
        minHeight = 64
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onPublishScreen() }
        setOnKeyListener { _, keyCode, event ->
            val submit = event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (submit) onPublishScreen()
            submit
        }
    }

    private val stopPublishButton: Button = Button(activity).apply {
        id = View.generateViewId()
        text = "Stop publish"
        textSize = 16f
        minHeight = 58
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onStopPublish() }
        setOnKeyListener { _, keyCode, event ->
            val submit = event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (submit) onStopPublish()
            submit
        }
    }

    private val configureRelayButton: Button = Button(activity).apply {
        id = View.generateViewId()
        text = "Relay settings"
        textSize = 16f
        minHeight = 58
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onConfigureRelay() }
        setOnKeyListener { _, keyCode, event ->
            val submit = event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (submit) onConfigureRelay()
            submit
        }
    }

    val root: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(40, 40, 40, 40)
        addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(broadcastInput, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(subscribeButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(publishButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(stopPublishButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(configureRelayButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    init {
        wireFocus()
    }

    fun broadcastName(): String = broadcastInput.text.toString()

    fun requestInitialFocus() {
        broadcastInput.requestFocus()
    }

    private fun wireFocus() {
        broadcastInput.nextFocusDownId = subscribeButton.id
        subscribeButton.nextFocusUpId = broadcastInput.id
        subscribeButton.nextFocusDownId = publishButton.id
        publishButton.nextFocusUpId = subscribeButton.id
        publishButton.nextFocusDownId = stopPublishButton.id
        stopPublishButton.nextFocusUpId = publishButton.id
        stopPublishButton.nextFocusDownId = configureRelayButton.id
        configureRelayButton.nextFocusUpId = stopPublishButton.id
    }
}
