/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.pulse

import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup

class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PulseView"
    }

    private var renderer: PulseRenderer? = null
    private var engine: PulseEngine? = null
    // isAttachedToWin is true if either onAttachedToWindow fired OR
    // we were manually marked attached (e.g. when in a ViewOverlay)
    private var isAttachedToWin = false
    private var isShowing = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Choreographer is always accessed from the main thread
    private val choreographer: Choreographer by lazy {
        Choreographer.getInstance()
    }

    // Declared as lateinit to break the self-referential type inference cycle
    private lateinit var frameCallback: Choreographer.FrameCallback

    init {
        frameCallback = Choreographer.FrameCallback {
            if (isAttachedToWin && isShowing) {
                invalidate()
                choreographer.postFrameCallback(frameCallback)
            }
        }
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setWillNotDraw(false)
        elevation = 9999f
        translationZ = 9999f
    }

    fun initialize(settingsRepo: PulseSettingsRepository) {
        renderer = PulseRenderer(context, settingsRepo)
        engine = PulseEngine(context, settingsRepo) { processedHeights ->
            // Called on Dispatchers.Main — Choreographer drives the frame loop
            renderer?.updateHeights(processedHeights)
        }
        Log.d(TAG, "initialize done")
    }

    // Called when added to a ViewOverlay (no onAttachedToWindow from framework)
    fun markAttached() {
        isAttachedToWin = true
        Log.d(TAG, "markAttached")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttachedToWin = true
        Log.d(TAG, "onAttachedToWindow: w=$width h=$height")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttachedToWin = false
        stopDrawLoop()
        engine?.stop()
        renderer?.cleanup()
        Log.d(TAG, "onDetachedFromWindow")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "onSizeChanged: w=$w h=$h")
        if (w > 0 && h > 0) {
            renderer?.notifySizeChanged(w, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isAttachedToWin && isShowing && width > 0 && height > 0) {
            renderer?.onDraw(canvas, width, height)
        }
    }

    fun updateVisualizerData(data: PulseData) {
        if (!isAttachedToWin || !isShowing) return
        val bytes = data.fftBytes ?: return
        engine?.processFFT(bytes)
    }

    fun onMediaColorsChanged(color: Int) {
        post { renderer?.onMediaColorsChanged(color) }
    }

    fun setVisibility(visible: Boolean) {
        Log.d(TAG, "setVisibility: $visible (was $isShowing) w=$width h=$height attached=$isAttachedToWin")
        isShowing = visible
        if (visible) {
            visibility = VISIBLE
            if (width > 0 && height > 0) {
                renderer?.notifySizeChanged(width, height)
            }
            startDrawLoop()
        } else {
            stopDrawLoop()
            visibility = GONE
        }
    }

    private fun startDrawLoop() {
        // Must run on main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startDrawLoop() }
            return
        }
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
        Log.d(TAG, "Choreographer draw loop started")
    }

    private fun stopDrawLoop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopDrawLoop() }
            return
        }
        choreographer.removeFrameCallback(frameCallback)
        Log.d(TAG, "Choreographer draw loop stopped")
    }
}
