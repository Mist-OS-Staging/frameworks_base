/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.shade.ui.composable

import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.systemui.res.R
import platform.test.motion.compose.values.MotionTestValueKey
import platform.test.motion.compose.values.motionTestValues
import kotlin.math.abs

/** Renders a scrim for an overlay. */
@Composable
fun ContentScope.OverlayScrim(
    showBackgroundColor: Boolean,
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
    onEmptySpaceSwipe: ((isRightSwipe: Boolean) -> Unit)? = null,
) {
    val closeOverlayActionLabel = stringResource(R.string.accessibility_close_overlay_action)
    val closeOverlayBoundingBoxDescription =
        stringResource(R.string.accessibility_close_overlay_box_description)

    val scrimBackgroundColor = OverlayShade.Colors.ScrimBackground
    Spacer(
        modifier =
            modifier
                .element(OverlayShade.Elements.Scrim)
                .motionTestValues {
                    OverlayShade.Elements.Scrim.currentAlpha()?.let { alpha ->
                        alpha exportAs OverlayShadeMotionTestKeys.scrimAlpha
                    }
                }
                .fillMaxSize()
                .thenIf(showBackgroundColor) { Modifier.background(scrimBackgroundColor) }
                .thenIf(onEmptySpaceSwipe != null) {
                    Modifier.pointerInput(onEmptySpaceSwipe) {
                        // Use awaitEachGesture so we handle exactly one gesture at a time.
                        // We use PointerEventPass.Initial so we see events BEFORE clickable does,
                        // but we only CONSUME the pointer after confirming it's a clear horizontal
                        // swipe — so taps still reach clickable and the SceneFramework still sees
                        // the down event for transition tracking.
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            var totalX = 0f
                            var totalY = 0f
                            var decided = false
                            val slop = viewConfiguration.touchSlop

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break

                                if (!change.pressed) break // finger lifted

                                val dx = change.positionChange().x
                                val dy = change.positionChange().y
                                totalX += dx
                                totalY += dy

                                if (!decided) {
                                    if (abs(totalX) > slop && abs(totalX) > abs(totalY) * 1.5f) {
                                        // Clear horizontal swipe — fire callback once and bail out.
                                        // Do NOT consume the pointer so the SceneFramework keeps
                                        // receiving events and can run its transition animation.
                                        decided = true
                                        onEmptySpaceSwipe?.invoke(totalX > 0f)
                                        break
                                    } else if (abs(totalY) > slop) {
                                        // Vertical gesture — not ours, give it up immediately.
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
                .clickable(
                    onClick = onClicked,
                    interactionSource = null,
                    indication = null,
                    onClickLabel = closeOverlayActionLabel,
                )
                .semantics { contentDescription = closeOverlayBoundingBoxDescription }
    )
}

@VisibleForTesting
object OverlayShadeMotionTestKeys {
    val scrimAlpha = MotionTestValueKey<Float>("scrim_alpha")
}
