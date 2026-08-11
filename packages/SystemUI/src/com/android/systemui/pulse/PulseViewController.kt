/*
 * Copyright (C) 2025 The AxionAOSP Project
 *           (C) 2026 crDroid Android Project
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
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.media.MediaSessionManager
import com.android.systemui.util.ScrimUtils
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@SysUISingleton
class PulseViewController @Inject constructor(
    private val context: Context
) : PulseAudioDataProcessor.DataListener,
    MediaSessionManager.MediaDataListener,
    ScrimUtils.ScrimEventListener {

    companion object {
        private const val TAG = "PulseViewController"

        @Volatile
        private var INSTANCE: PulseViewController? = null

        @JvmStatic
        fun get(context: Context): PulseViewController {
            return INSTANCE ?: throw IllegalStateException("PulseViewController not initialized")
        }
    }

    private val mainScope = MainScope()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenersRegistered = false

    // Screen/UI state — assume screen ON until told otherwise
    private var isScreenOn = true
    private var bouncerShowing = false
    private var isDozing = false

    // Periodic poll to catch music start/stop events that miss callbacks
    private val musicPollRunnable = object : Runnable {
        override fun run() {
            if (pulseEnabled && listenersRegistered) {
                updateState()
            }
            mainHandler.postDelayed(this, 2000L)
        }
    }

    private val settingsRepository: PulseSettingsRepository = PulseSettingsRepository(context)

    private val view: PulseView = PulseView(context)

    private val audioProcessor: PulseAudioDataProcessor =
        PulseAudioDataProcessor(context).apply {
            setDataListener(this@PulseViewController)
        }

    private val bassHaptics: PulseBassHaptics = PulseBassHaptics(context)

    val pulseEnabled: Boolean
        get() = settingsRepository.isPulseEnabled()

    val ambientEnabled: Boolean
        get() = settingsRepository.isPulseAmbientEnabled()

    private val hapticsMode: Int
        get() = settingsRepository.getPulseHapticsMode()

    /** Whether Pulse should currently be running. */
    private var pulseRunning: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            Log.d(TAG, "pulseRunning → $value")
            onPulseRunningChanged(value)
        }

    init {
        INSTANCE = this
        view.initialize(settingsRepository)
        settingsRepository.setOnSettingsChangedListener { onSettingsChanged() }
        settingsRepository.startObserving()
        onSettingsChanged()
        Log.d(TAG, "PulseViewController initialized, pulseEnabled=$pulseEnabled")
    }

    fun getPulseView(): PulseView = view

    // -------------------------------------------------------------------------
    // Core state machine
    // -------------------------------------------------------------------------

    private fun isMusicActive(): Boolean {
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val audioActive = am?.isMusicActive == true
        val mediaPlaying = MediaSessionManager.get().isMediaPlaying
        Log.d(TAG, "isMusicActive: audioMgr=$audioActive mediaMgr=$mediaPlaying")
        return audioActive || mediaPlaying
    }

    private fun updateState() {
        if (!pulseEnabled) {
            Log.d(TAG, "updateState: pulse disabled, stopping")
            pulseRunning = false
            return
        }
        val music = isMusicActive()
        // Show on: homescreen, lockscreen, QS — any screen that's ON and not bouncer
        // Show on AOD/ambient only if ambient pulse is enabled
        val screenOk = isScreenOn && !bouncerShowing
        val locationOk = if (isDozing) ambientEnabled else true
        val should = music && screenOk && locationOk
        Log.d(TAG, "updateState: music=$music screenOn=$isScreenOn dozing=$isDozing " +
                "bouncer=$bouncerShowing ambient=$ambientEnabled → pulseRunning=$should")
        pulseRunning = should
    }

    private fun onPulseRunningChanged(running: Boolean) {
        mainScope.launch {
            view.setVisibility(running)
            if (pulseEnabled && (running || hapticsMode > 1)) {
                Log.d(TAG, "Starting audio capture")
                audioProcessor.startCapture()
            } else {
                Log.d(TAG, "Stopping audio capture")
                audioProcessor.stopCapture()
                bassHaptics.reset()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    private fun onSettingsChanged() {
        val enabled = pulseEnabled
        Log.d(TAG, "onSettingsChanged: enabled=$enabled listenersRegistered=$listenersRegistered")
        if (enabled && !listenersRegistered) {
            ScrimUtils.get().addListener(this)
            MediaSessionManager.get().addListener(this)
            mainHandler.post(musicPollRunnable)
            listenersRegistered = true
            Log.d(TAG, "Listeners registered, poll started")
        } else if (!enabled && listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            MediaSessionManager.get().removeListener(this)
            mainHandler.removeCallbacks(musicPollRunnable)
            listenersRegistered = false
            pulseRunning = false
            mainScope.launch {
                view.setVisibility(false)
                audioProcessor.stopCapture()
            }
            return
        }
        updateState()
        // Force-apply even if state didn't change value
        onPulseRunningChanged(pulseRunning)
    }

    // -------------------------------------------------------------------------
    // DataListener — FFT data from audio processor (already on main thread)
    // -------------------------------------------------------------------------

    override fun onDataUpdate(data: PulseData) {
        if (hapticsMode > 0) {
            bassHaptics.process(data.fftBytes)
        }
        if (pulseRunning) {
            view.updateVisualizerData(data)
        }
    }

    // -------------------------------------------------------------------------
    // MediaDataListener
    // -------------------------------------------------------------------------

    override fun onPlaybackStateChanged(state: Int) {
        Log.d(TAG, "onPlaybackStateChanged: state=$state")
        updateState()
    }

    override fun onMediaColorsChanged(color: Int) {
        if (pulseEnabled) view.onMediaColorsChanged(color)
    }

    // -------------------------------------------------------------------------
    // ScrimEventListener
    // -------------------------------------------------------------------------

    override fun onKeyguardShowingChanged(showing: Boolean) {
        updateState()
    }

    override fun onDozingChanged(dozing: Boolean) {
        isDozing = dozing
        updateState()
    }

    override fun onExpandedFractionChanged(expandedFraction: Float) {
        updateState()
    }

    override fun onBarStateChanged(state: Int) {
        updateState()
    }

    override fun onQsVisibilityChanged(visible: Boolean) {
        updateState()
    }

    override fun onKeyguardFadingAwayChanged(fadingAway: Boolean) {
        bouncerShowing = fadingAway
        updateState()
    }

    override fun onKeyguardGoingAwayChanged(goingAway: Boolean) {
        if (!goingAway) bouncerShowing = false
        updateState()
    }

    override fun onPrimaryBouncerShowingChanged(showing: Boolean) {
        bouncerShowing = showing
        updateState()
    }

    override fun onScreenTurnedOff() {
        isScreenOn = false
        updateState()
    }

    override fun onStartedWakingUp() {
        isScreenOn = true
        updateState()
    }

    override fun onUserChanged() {
        settingsRepository.invalidateCache()
        bassHaptics.reset()
        updateState()
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun destroy() {
        pulseRunning = false
        settingsRepository.stopObserving()
        mainHandler.removeCallbacks(musicPollRunnable)
        if (listenersRegistered) {
            ScrimUtils.get().removeListener(this)
            MediaSessionManager.get().removeListener(this)
            listenersRegistered = false
        }
        audioProcessor.cleanup()
        bassHaptics.reset()
        mainScope.cancel()
    }
}
