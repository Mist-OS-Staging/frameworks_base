/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.notifications.ui.viewmodel

import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.compose.animation.scene.UserActionResult.HideOverlay
import com.android.compose.animation.scene.UserActionResult.ShowOverlay
import com.android.compose.animation.scene.UserActionResult.ShowOverlay.HideCurrentOverlays
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.ui.viewmodel.SceneContainerArea.TopEdgeEndHalf
import com.android.systemui.scene.ui.viewmodel.UserActionsViewModel
import com.android.systemui.shade.data.repository.DualShadeSwipeGestureRepository
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.collect

/** Models the UI state for the user actions for navigating to other scenes or overlays. */
class NotificationsShadeOverlayActionsViewModel @AssistedInject constructor(
    private val swipeGestureRepository: DualShadeSwipeGestureRepository,
    private val sceneInteractor: SceneInteractor,
) : UserActionsViewModel() {

    val isSwipeGestureEnabled = swipeGestureRepository.isSwipeGestureEnabled

    fun forceSwitchToQuickSettings() {
        sceneInteractor.replaceOverlay(
            from = Overlays.NotificationsShade,
            to = Overlays.QuickSettingsShade,
            loggingReason = "force bypass horizontal swipe",
        )
    }

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        swipeGestureRepository.isSwipeGestureEnabled.collect { swipeEnabled ->
            setActions(
                buildMap {
                    put(Swipe.Up, HideOverlay(Overlays.NotificationsShade))
                    put(Back, HideOverlay(Overlays.NotificationsShade))
                    put(
                        Swipe.Down(fromSource = TopEdgeEndHalf),
                        ShowOverlay(
                            Overlays.QuickSettingsShade,
                            hideCurrentOverlays =
                                HideCurrentOverlays.Some(Overlays.NotificationsShade),
                        ),
                    )
                    if (swipeEnabled) {
                    }
                }
            )
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): NotificationsShadeOverlayActionsViewModel
    }
}
