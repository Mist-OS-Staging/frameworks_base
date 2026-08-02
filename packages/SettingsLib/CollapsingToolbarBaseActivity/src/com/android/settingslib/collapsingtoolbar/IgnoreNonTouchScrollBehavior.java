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

package com.android.settingslib.collapsingtoolbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import com.google.android.material.appbar.AppBarLayout;

/**
 * A custom {@link AppBarLayout.Behavior} that filters nested-scroll events so that:
 * <ul>
 *   <li>Only user-initiated touch scrolls ({@link ViewCompat#TYPE_TOUCH}) drive the AppBar
 *       expansion or collapse during normal scrolling. This prevents flings triggered by
 *       keyboard/IME or accessibility services from unexpectedly collapsing the header.</li>
 *   <li>Programmatic calls to {@link AppBarLayout#setExpanded(boolean, boolean)} work correctly
 *       because they use {@code TYPE_NON_TOUCH} internally; we allow those through by not
 *       blocking the {@code onNestedPreScroll} path used by the programmatic API.</li>
 * </ul>
 */

public class IgnoreNonTouchScrollBehavior extends AppBarLayout.Behavior {

    public IgnoreNonTouchScrollBehavior() {
        super();
    }

    public IgnoreNonTouchScrollBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onStartNestedScroll(
            @NonNull CoordinatorLayout parent,
            @NonNull AppBarLayout child,
            @NonNull View directTargetChild,
            @NonNull View target,
            int nestedScrollAxes,
            int type) {
        // Accept only touch-driven scroll events so that accessibility/keyboard-driven
        // flings do not unexpectedly collapse or expand the app bar.
        if (type == ViewCompat.TYPE_TOUCH) {
            return super.onStartNestedScroll(
                    parent, child, directTargetChild, target, nestedScrollAxes, type);
        }
        return false;
    }

    @Override
    public void onNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull AppBarLayout child,
            @NonNull View target,
            int dxConsumed,
            int dyConsumed,
            int dxUnconsumed,
            int dyUnconsumed,
            int type,
            @NonNull int[] consumed) {
        // Forward only touch-driven nested scroll deltas. Non-touch events (e.g., from flings
        // triggered programmatically by the system) are intentionally dropped here. Note that
        // AppBarLayout.setExpanded() works via a separate internal mechanism (not nested scroll),
        // so it is unaffected by this filter.
        if (type == ViewCompat.TYPE_TOUCH) {
            super.onNestedScroll(
                    coordinatorLayout,
                    child,
                    target,
                    dxConsumed,
                    dyConsumed,
                    dxUnconsumed,
                    dyUnconsumed,
                    type,
                    consumed);
        }
    }
}
