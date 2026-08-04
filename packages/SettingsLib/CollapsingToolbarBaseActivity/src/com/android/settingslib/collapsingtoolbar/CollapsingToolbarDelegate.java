/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.text.Layout.HYPHENATION_FREQUENCY_NORMAL_FAST;

import android.animation.TimeInterpolator;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.text.LineBreakConfig;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.android.settingslib.collapsingtoolbar.widget.ScrollableToolbarItemLayout;
import com.android.settingslib.widget.SettingsThemeHelper;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingtoolbar.FloatingToolbarLayout;

import java.util.List;

/**
 * A delegate that allows to use the collapsing toolbar layout in hosts that
 * doesn't want/need to extend from {@link CollapsingToolbarBaseActivity} or from
 * {@link CollapsingToolbarBaseFragment}.
 *
 * <p>When running on SDK 36+ with the Expressive theme, this delegate replaces the standard
 * Material CollapsingToolbarLayout animation with a Samsung One UI-style animation:
 * <ul>
 *   <li>A large bold title anchored at the bottom-start of the expanded header.</li>
 *   <li>A smooth cross-fade to a compact toolbar title (no scale/slide jitter).</li>
 *   <li>One UI cubic-Bezier easing applied to all alpha transitions.</li>
 *   <li>Linear elevation interpolation (0 dp → 4 dp) as the header collapses.</li>
 * </ul>
 */
public class CollapsingToolbarDelegate {
    private static final String TAG = "CTBdelegate";
    private static final float EXPANDED_FADE_END = 0.45f;
    private static final float COLLAPSED_FADE_START = 0.45f;
    private static final float MAX_ELEVATION_DP = 4f;

    /** Interface to be implemented by the host of the Collapsing Toolbar. */
    public interface HostCallback {
        /**
         * Called when a Toolbar should be set on the host.
         *
         * <p>If the host wants action bar to be modified, it should return it.
         */
        @Nullable
        ActionBar setActionBar(Toolbar toolbar);

        /** Sets support tool bar and return support action bar, this is for AppCompatActivity. */
        @Nullable
        default androidx.appcompat.app.ActionBar setActionBar(
                androidx.appcompat.widget.Toolbar toolbar) {
            return null;
        }

        /** Sets a title on the host. */
        void setOuterTitle(CharSequence title);
    }

    private static final class OneUiInterpolator implements TimeInterpolator {
        private static final float P1X = 0.25f;
        private static final float P1Y = 0.46f;
        private static final float P2X = 0.45f;
        private static final float P2Y = 0.94f;

        @Override
        public float getInterpolation(float t) {
            float u = t;
            for (int i = 0; i < 8; i++) {
                float ux2 = u * u;
                float ux3 = ux2 * u;
                float bx = 3f * P1X * u * (1f - u) * (1f - u)
                        + 3f * P2X * ux2 * (1f - u)
                        + ux3;
                float dbx = 3f * P1X * (1f - 4f * u + 3f * ux2)
                        + 3f * P2X * (2f * u - 3f * ux2)
                        + 3f * ux2;
                if (Math.abs(dbx) < 1e-6f) break;
                u = u - (bx - t) / dbx;
                u = Math.max(0f, Math.min(1f, u));
            }
            float ux2 = u * u;
            float ux3 = ux2 * u;
            return 3f * P1Y * u * (1f - u) * (1f - u)
                    + 3f * P2Y * ux2 * (1f - u)
                    + ux3;
        }
    }

    private class OneUiOffsetListener implements AppBarLayout.OnOffsetChangedListener {
        private final float mMaxElevationPx;
        private final OneUiInterpolator mInterpolator = new OneUiInterpolator();

        OneUiOffsetListener(@NonNull Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            mMaxElevationPx = MAX_ELEVATION_DP * density;
        }

        @Override
        public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
            final int totalScrollRange = appBarLayout.getTotalScrollRange();
            if (totalScrollRange == 0) {
                return;
            }

            // collapseRatio: 0.0 = fully expanded, 1.0 = fully collapsed
            final float collapseRatio =
                    Math.abs(verticalOffset) / (float) totalScrollRange;

            // --- Elevation: 0 when expanded, MAX_ELEVATION when any scroll has occurred ---
            appBarLayout.setElevation(verticalOffset < 0 ? mMaxElevationPx : 0f);

            // --- Expanded title: fade out across [0, EXPANDED_FADE_END] ---
            if (mExpandedTitleView != null) {
                // Map collapseRatio [0, EXPANDED_FADE_END] → alpha [1, 0]
                final float expandedRaw = 1f - (collapseRatio / EXPANDED_FADE_END);
                final float expandedClamped = Math.max(0f, Math.min(1f, expandedRaw));
                // Apply One UI easing only while mid-transition
                final float expandedAlpha = (expandedClamped <= 0f || expandedClamped >= 1f)
                        ? expandedClamped
                        : mInterpolator.getInterpolation(expandedClamped);
                mExpandedTitleView.setAlpha(expandedAlpha);
            }

            // --- Collapsed title: fade in across [COLLAPSED_FADE_START, 1.0] ---
            if (mCollapsedTitleView != null) {
                // Map collapseRatio [COLLAPSED_FADE_START, 1.0] → alpha [0, 1]
                final float collapsedRaw =
                        (collapseRatio - COLLAPSED_FADE_START) / (1f - COLLAPSED_FADE_START);
                final float collapsedClamped = Math.max(0f, Math.min(1f, collapsedRaw));
                // Apply One UI easing only while mid-transition
                final float collapsedAlpha = (collapsedClamped <= 0f || collapsedClamped >= 1f)
                        ? collapsedClamped
                        : mInterpolator.getInterpolation(collapsedClamped);
                mCollapsedTitleView.setAlpha(collapsedAlpha);
            }
        }
    }

    private static final float TOOLBAR_LINE_SPACING_MULTIPLIER = 1.1f;

    @Nullable
    private CoordinatorLayout mCoordinatorLayout;
    @Nullable
    private CollapsingToolbarLayout mCollapsingToolbarLayout;
    @Nullable
    private AppBarLayout mAppBarLayout;
    @NonNull
    private Toolbar mToolbar;
    @Nullable
    private View mToolbarButtonsContainer;
    @Nullable
    private MaterialButton mPrimaryButton;
    @Nullable
    private MaterialButton mSecondaryButton;
    @Nullable
    private MaterialButton mActionButton;
    @Nullable
    private MaterialButton mActionIconOnlyButton;
    @NonNull
    private FrameLayout mContentFrameLayout;
    @NonNull
    private final HostCallback mHostCallback;

    private boolean mUseCollapsingToolbar;
    private boolean mIsExpressiveTheme;

    @Nullable
    private FloatingToolbarLayout mFloatingToolbarLayout;

    @Nullable
    private OneUiOffsetListener mOneUiOffsetListener;

    /** Custom large title view injected for One UI style animation (SDK 36+ Expressive only). */
    @Nullable
    private TextView mExpandedTitleView;

    /**
     * Custom compact title view injected into the pinned toolbar area for One UI style animation.
     * Fades in as {@link #mExpandedTitleView} fades out.
     */
    @Nullable
    private TextView mCollapsedTitleView;

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback) {
        this(hostCallback, /* useCollapsingToolbar= */ true);
    }

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback,
            boolean useCollapsingToolbar) {
        mHostCallback = hostCallback;
        mUseCollapsingToolbar = useCollapsingToolbar;
    }

    /** Method to call that creates the root view of the collapsing toolbar. */
    @SuppressWarnings("RestrictTo")
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return onCreateView(inflater, container, null);
    }

    /** Method to call that creates the root view of the collapsing toolbar. */
    @SuppressWarnings("RestrictTo")
    View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            Activity activity) {
        int layoutId;
        boolean useCollapsingToolbar =
                mUseCollapsingToolbar || Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
        Context context = (activity != null) ? activity : inflater.getContext();
        mIsExpressiveTheme = SettingsThemeHelper.isExpressiveTheme(context);
        if (useCollapsingToolbar) {
            if (mIsExpressiveTheme) {
                if (activity instanceof AppCompatActivity) {
                    layoutId = R.layout.settingslib_expressive_collapsing_toolbar_appcompat_layout;
                } else {
                    layoutId = R.layout.settingslib_expressive_collapsing_toolbar_base_layout;
                }
            } else {
                layoutId = R.layout.collapsing_toolbar_base_layout;
            }
        } else {
            layoutId = R.layout.non_collapsing_toolbar_base_layout;
        }

        final View view = inflater.inflate(layoutId, container, false);
        if (view instanceof CoordinatorLayout) {
            mCoordinatorLayout = (CoordinatorLayout) view;
        }
        mCollapsingToolbarLayout = view.findViewById(R.id.collapsing_toolbar);
        mAppBarLayout = view.findViewById(R.id.app_bar);

        if (!useCollapsingToolbar) {
            // In the non-collapsing toolbar layout, set the background of the app bar to the same
            // as the activity background so that it covers items extending above the bounds of the
            // list for edge-to-edge.
            TypedArray ta = container.getContext().obtainStyledAttributes(new int[]{
                    android.R.attr.windowBackground});
            Drawable background = ta.getDrawable(0);
            ta.recycle();
            mAppBarLayout.setBackground(background);
        }

        initCollapsingToolbar(mCollapsingToolbarLayout, mAppBarLayout);
        mContentFrameLayout = view.findViewById(R.id.content_frame);

        if (activity instanceof AppCompatActivity) {
            Log.d(TAG, "onCreateView: from AppCompatActivity and sub-class.");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                initSupportActionBar(inflater);
            } else {
                initRSupportActionBar(view);
            }
        } else {
            Log.d(TAG, "onCreateView: from NonAppCompatActivity.");
            mToolbar = view.findViewById(R.id.action_bar);
            final ActionBar actionBar = mHostCallback.setActionBar(mToolbar);
            // Enable title and home button by default
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.setHomeButtonEnabled(true);
                if (useCollapsingToolbar && mIsExpressiveTheme) {
                    actionBar.setHomeAsUpIndicator(R.drawable.settingslib_expressive_icon_back);
                }
            }
        }

        initToolbarButtonsContainer(view.findViewById(R.id.toolbar_buttons_container));
        initToolbarPrimaryButton(view.findViewById(R.id.primary_button));
        initToolbarSecondaryButton(view.findViewById(R.id.secondary_button));
        initToolbarActionButton(view.findViewById(R.id.action_button));
        initToolbarActionIconOnlyButton(view.findViewById(R.id.action_icon_only_button));

        FloatingToolbarLayout floatingToolbar = view.findViewById(R.id.floating_toolbar);
        if (floatingToolbar != null) {
            initFloatingToolbar(context, floatingToolbar);
        }
        return view;
    }

    /**
     * Initialize the collapsing toolbar layout.
     *
     * <p>On SDK 36+ with the Expressive theme, this method also:
     * <ul>
     *   <li>Disables Material's native title animations.</li>
     *   <li>Injects the One UI custom expanded and collapsed title views.</li>
     *   <li>Attaches the {@link OneUiOffsetListener}.</li>
     * </ul>
     */
    public void initCollapsingToolbar(CollapsingToolbarLayout collapsingToolbarLayout,
            AppBarLayout appBarLayout) {
        if (collapsingToolbarLayout != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            collapsingToolbarLayout.setLineSpacingMultiplier(TOOLBAR_LINE_SPACING_MULTIPLIER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                collapsingToolbarLayout.setHyphenationFrequency(HYPHENATION_FREQUENCY_NORMAL_FAST);
                collapsingToolbarLayout.setStaticLayoutBuilderConfigurer(
                        builder -> builder.setLineBreakConfig(
                                new LineBreakConfig.Builder()
                                        .setLineBreakWordStyle(
                                                LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                                        .build()));
            }
        }

        if (mIsExpressiveTheme && collapsingToolbarLayout != null && appBarLayout != null) {
            setupOneUiTitleAnimation(collapsingToolbarLayout, appBarLayout);
        }

        autoSetCollapsingToolbarLayoutScrolling(appBarLayout);
    }

    private void setupOneUiTitleAnimation(
            @NonNull CollapsingToolbarLayout collapsingToolbarLayout,
            @NonNull AppBarLayout appBarLayout) {
        final Context context = appBarLayout.getContext();

        // 1. Disable Material's own title rendering entirely — we draw our own.
        collapsingToolbarLayout.setTitleEnabled(false);

        // 2. Remove any previously injected views (e.g. on re-inflate).
        View existingExpanded = collapsingToolbarLayout.findViewById(R.id.oneui_expanded_title);
        if (existingExpanded != null) {
            collapsingToolbarLayout.removeView(existingExpanded);
        }

        // 3. Build the custom large expanded-title TextView (bottom-start of header).
        TextView expandedTitle = new TextView(context);
        expandedTitle.setId(R.id.oneui_expanded_title);
        expandedTitle.setTextColor(resolveColorOnSurface(context));
        expandedTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, getExpandedTitleSizeSp(context));
        expandedTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        expandedTitle.setMaxLines(3);
        expandedTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        expandedTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        // 4. Build the compact collapsed-title TextView (pinned in the toolbar area).
        //    Starts fully transparent; fades in as the header collapses.
        TextView collapsedTitle = new TextView(context);
        collapsedTitle.setId(R.id.oneui_collapsed_title);
        collapsedTitle.setTextColor(resolveColorOnSurface(context));
        collapsedTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, getCollapsedTitleSizeSp(context));
        collapsedTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        collapsedTitle.setSingleLine(true);
        collapsedTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        collapsedTitle.setAlpha(0f); // invisible until we scroll
        collapsedTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        // Populate both views with the current title.
        CharSequence currentTitle = collapsingToolbarLayout.getTitle();
        if (currentTitle != null) {
            expandedTitle.setText(currentTitle);
            collapsedTitle.setText(currentTitle);
        }

        // 5. Layout params for expanded title: bottom-start with parallax collapse mode.
        final int marginStart = getDimensionPixelSize(context,
                R.dimen.settingslib_oneui_expanded_title_margin_start);
        final int marginBottom = getDimensionPixelSize(context,
                R.dimen.settingslib_oneui_expanded_title_margin_bottom);
        final int marginEnd = getDimensionPixelSize(context,
                R.dimen.settingslib_oneui_expanded_title_margin_start); // symmetric

        CollapsingToolbarLayout.LayoutParams expandedLp =
                new CollapsingToolbarLayout.LayoutParams(
                        CollapsingToolbarLayout.LayoutParams.MATCH_PARENT,
                        CollapsingToolbarLayout.LayoutParams.WRAP_CONTENT);
        expandedLp.gravity = Gravity.BOTTOM | Gravity.START;
        expandedLp.setMarginStart(marginStart);
        expandedLp.setMarginEnd(marginEnd);
        expandedLp.bottomMargin = marginBottom;
        expandedLp.setCollapseMode(CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PARALLAX);
        expandedLp.setParallaxMultiplier(0.3f); // subtle upward drift

        collapsingToolbarLayout.addView(expandedTitle, expandedLp);
        mExpandedTitleView = expandedTitle;

        // 6. Layout params for collapsed title: pin inside the toolbar row (top of CTL),
        //    horizontally centered after the nav icon, matching the action bar height.
        //    We look for the pinned toolbar to parent the collapsed title inside it.
        View toolbar = collapsingToolbarLayout.findViewById(R.id.action_bar);
        if (toolbar == null) {
            toolbar = collapsingToolbarLayout.findViewById(R.id.support_action_bar);
        }
        if (toolbar instanceof ViewGroup) {
            // Remove a stale collapsed title from the toolbar if present.
            View existingCollapsed = toolbar.findViewById(R.id.oneui_collapsed_title);
            if (existingCollapsed != null) {
                ((ViewGroup) toolbar).removeView(existingCollapsed);
            }
            FrameLayout.LayoutParams collapsedLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            collapsedLp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
            collapsedLp.setMarginStart(marginStart);
            ((ViewGroup) toolbar).addView(collapsedTitle, collapsedLp);
        } else {
            // Fallback: place collapsed title inside CTL with pin mode at toolbar height.
            CollapsingToolbarLayout.LayoutParams collapsedLp =
                    new CollapsingToolbarLayout.LayoutParams(
                            CollapsingToolbarLayout.LayoutParams.WRAP_CONTENT,
                            CollapsingToolbarLayout.LayoutParams.WRAP_CONTENT);
            collapsedLp.gravity = Gravity.TOP | Gravity.START;
            collapsedLp.setMarginStart(marginStart);
            collapsedLp.topMargin = getDimensionPixelSize(context,
                    R.dimen.settingslib_oneui_expanded_title_margin_bottom); // ~center vertically
            collapsedLp.setCollapseMode(CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN);
            collapsingToolbarLayout.addView(collapsedTitle, collapsedLp);
        }
        mCollapsedTitleView = collapsedTitle;

        // 7. Reset elevation to 0 initially (fully expanded).
        appBarLayout.setElevation(0f);

        // 8. Attach / re-attach the offset listener.
        if (mOneUiOffsetListener != null) {
            appBarLayout.removeOnOffsetChangedListener(mOneUiOffsetListener);
        }
        mOneUiOffsetListener = new OneUiOffsetListener(context);
        appBarLayout.addOnOffsetChangedListener(mOneUiOffsetListener);
    }

    private int getDimensionPixelSize(@NonNull Context context, int resId) {
        try {
            return context.getResources().getDimensionPixelSize(resId);
        } catch (android.content.res.Resources.NotFoundException e) {
            return 0;
        }
    }

    private float getExpandedTitleSizeSp(@NonNull Context context) {
        try {
            return context.getResources()
                    .getDimension(R.dimen.settingslib_oneui_expanded_title_size)
                    / context.getResources().getDisplayMetrics().scaledDensity;
        } catch (android.content.res.Resources.NotFoundException e) {
            return 34f;
        }
    }

    private float getCollapsedTitleSizeSp(@NonNull Context context) {
        try {
            return context.getResources()
                    .getDimension(R.dimen.settingslib_oneui_collapsed_title_size)
                    / context.getResources().getDisplayMetrics().scaledDensity;
        } catch (android.content.res.Resources.NotFoundException e) {
            return 20f;
        }
    }

    private int resolveColorOnSurface(@NonNull Context context) {
        TypedArray ta = context.obtainStyledAttributes(
                new int[]{android.R.attr.textColorPrimary});
        int color = ta.getColor(0, 0xFF000000 /* fallback black */);
        ta.recycle();
        return color;
    }

    /** Initialize toolbar buttons container. */
    public void initToolbarButtonsContainer(View toolbarButtonsContainer) {
        mToolbarButtonsContainer = toolbarButtonsContainer;
    }

    /** Initialize toolbar's primary button. */
    public void initToolbarPrimaryButton(MaterialButton primaryButton) {
        mPrimaryButton = primaryButton;
    }

    /** Initialize toolbar's secondary button. */
    public void initToolbarSecondaryButton(MaterialButton secondaryButton) {
        mSecondaryButton = secondaryButton;
    }

    /** Initialize toolbar's action button. */
    public void initToolbarActionButton(MaterialButton actionButton) {
        mActionButton = actionButton;
    }

    /** Initialize toolbar's action icon only button. */
    public void initToolbarActionIconOnlyButton(MaterialButton actionButtonIconOnly) {
        mActionIconOnlyButton = actionButtonIconOnly;
    }

    /**
     * Initialize the floating toolbar.
     *
     * @param context
     * @param floatingToolbarLayout may be null on layouts that don't include the floating toolbar
     */
    public void initFloatingToolbar(@NonNull Context context,
            @Nullable FloatingToolbarLayout floatingToolbarLayout) {
        mFloatingToolbarLayout = floatingToolbarLayout;
    }

    /** Return an instance of CoordinatorLayout. */
    @Nullable
    public CoordinatorLayout getCoordinatorLayout() {
        return mCoordinatorLayout;
    }

    /** Sets the title on the collapsing layout and delegates to host. */
    public void setTitle(CharSequence title) {
        if (mCollapsingToolbarLayout != null) {
            // Store the title in CTL so getTitle() still works (e.g. for accessibility),
            // but rendering is owned by our custom views in expressive mode.
            mCollapsingToolbarLayout.setTitle(title);
            if (mIsExpressiveTheme) {
                // Keep CTL's own rendering suppressed; we manage the title TextViews.
                mCollapsingToolbarLayout.setTitleEnabled(false);
            }
        }
        // Update both custom title views (One UI style).
        if (mExpandedTitleView != null) {
            mExpandedTitleView.setText(title);
        }
        if (mCollapsedTitleView != null) {
            mCollapsedTitleView.setText(title);
        }
        mHostCallback.setOuterTitle(title);
    }

    /** Returns an instance of collapsing toolbar. */
    @Nullable
    public CollapsingToolbarLayout getCollapsingToolbarLayout() {
        return mCollapsingToolbarLayout;
    }

    /** Return the content frame layout. */
    @NonNull
    public FrameLayout getContentFrameLayout() {
        return mContentFrameLayout;
    }

    public Toolbar getToolbar() {
        return mToolbar;
    }

    /** Return an instance of app bar. */
    @Nullable
    public AppBarLayout getAppBarLayout() {
        return mAppBarLayout;
    }

    /**
     * Sets the visibility of the floating toolbar.
     * @param visible
     */
    public void setFloatingToolbarVisibility(boolean visible) {
        if (mFloatingToolbarLayout == null) {
            return;
        }
        mFloatingToolbarLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Sets the toolbar items  for the floating toolbar.
     * @param itemList
     */
    public void setToolbarItems(List<ScrollableToolbarItemLayout.ToolbarItem> itemList) {
        if (mFloatingToolbarLayout == null) {
            return;
        }

        ScrollableToolbarItemLayout layout = mFloatingToolbarLayout.findViewById(
                R.id.floating_toolbar_items);
        if (layout == null) {
            return;
        }

        layout.onItemSelected(itemList);
    }

    private @Nullable ScrollableToolbarItemLayout getToolbarItemsLayout() {
        if (mFloatingToolbarLayout == null) {
            return null;
        }
        return mFloatingToolbarLayout.findViewById(R.id.floating_toolbar_items);
    }

    /**
     * Sets the item selected listener for the floating toolbar.
     */
    public void setOnItemSelectedListener(
            ScrollableToolbarItemLayout.OnItemSelectedListener listener) {
        var layout = getToolbarItemsLayout();
        if (layout != null) {
            layout.setOnItemSelectedListener(listener);
        }
    }

    /**
     * Removes the item selected listener for the floating toolbar.
     */
    public void removeOnItemSelectedListener() {
        var layout = getToolbarItemsLayout();
        if (layout != null) {
            layout.removeOnItemSelectedListener();
        }
    }

    /**
     * Sets the selected toolbar item by its zero-based index.
     */
    public void setSelectedItem(int position) {
        var layout = getToolbarItemsLayout();
        if (layout != null) {
            layout.setSelectedItem(position);
        }
    }

    private void initSupportActionBar(@NonNull LayoutInflater inflater) {
        if (mCollapsingToolbarLayout == null) {
            return;
        }

        if (!SettingsThemeHelper.isExpressiveTheme(inflater.getContext())) {
            mCollapsingToolbarLayout.removeAllViews();
            inflater.inflate(R.layout.support_toolbar, mCollapsingToolbarLayout);
        }

        final androidx.appcompat.widget.Toolbar supportToolbar =
                mCollapsingToolbarLayout.findViewById(R.id.support_action_bar);
        final androidx.appcompat.app.ActionBar actionBar =
                mHostCallback.setActionBar(supportToolbar);
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            if (mIsExpressiveTheme) {
                actionBar.setHomeAsUpIndicator(R.drawable.settingslib_expressive_icon_back);
            }
            actionBar.setDisplayShowTitleEnabled(!mIsExpressiveTheme);
        }

        if (mIsExpressiveTheme && mCollapsingToolbarLayout != null) {
            View buttonsContainer = mCollapsingToolbarLayout.findViewById(R.id.toolbar_buttons_container);
            if (buttonsContainer != null) {
                initToolbarButtonsContainer(buttonsContainer);
            }
        }
    }

    private void initRSupportActionBar(View view) {
        view.findViewById(R.id.action_bar).setVisibility(View.GONE);
        final androidx.appcompat.widget.Toolbar supportToolbar =
                view.findViewById(R.id.support_action_bar);
        supportToolbar.setVisibility(View.VISIBLE);
        final androidx.appcompat.app.ActionBar actionBar =
                mHostCallback.setActionBar(supportToolbar);
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
            if (mIsExpressiveTheme) {
                actionBar.setHomeAsUpIndicator(R.drawable.settingslib_expressive_icon_back);
            }
            actionBar.setDisplayShowTitleEnabled(!mIsExpressiveTheme);
        }

        if (mIsExpressiveTheme && mCollapsingToolbarLayout != null) {
            View buttonsContainer = mCollapsingToolbarLayout.findViewById(R.id.toolbar_buttons_container);
            if (buttonsContainer != null) {
                initToolbarButtonsContainer(buttonsContainer);
            }
        }
    }

    public void registerToolbarCollapseBehavior(@NonNull Activity activity) {
        if (!(activity instanceof FragmentActivity)) {
            return;
        }
        FragmentManager fragmentManager = ((FragmentActivity) activity).getSupportFragmentManager();
        fragmentManager.registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull FragmentManager fm,
                            @NonNull Fragment f, @NonNull View v,
                            @Nullable Bundle savedInstanceState) {
                        super.onFragmentViewCreated(fm, f, v, savedInstanceState);
                        if (!SettingsThemeHelper.isExpressiveTheme(activity)) {
                            return;
                        }
                        if (fm.getBackStackEntryCount() > 0) {
                            AppBarLayout appBarLayout = getAppBarLayout();
                            if (appBarLayout != null) {
                                appBarLayout.post(() -> appBarLayout.setExpanded(false, true));
                            } else {
                                Log.e(TAG, "AppBarLayout is null, can't collapse toolbar.");
                            }
                        }
                    }
                }, false);
    }

    private void autoSetCollapsingToolbarLayoutScrolling(AppBarLayout appBarLayout) {
        if (appBarLayout == null) {
            return;
        }
        final CoordinatorLayout.LayoutParams params =
                (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
        final AppBarLayout.Behavior behavior = new IgnoreNonTouchScrollBehavior();
        behavior.setDragCallback(
                new AppBarLayout.Behavior.DragCallback() {
                    @Override
                    public boolean canDrag(@NonNull AppBarLayout appBarLayout) {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU
                                || SettingsThemeHelper.isExpressiveTheme(
                                        appBarLayout.getContext())) {
                            return false;
                        } else {
                            return appBarLayout.getResources()
                                    .getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE;
                        }
                    }
                });
        params.setBehavior(behavior);
    }

    /**
     * Show/Hide the primary button on the Toolbar.
     * @param enabled true to show the button, otherwise it's hidden.
     */
    public void setPrimaryButtonEnabled(boolean enabled) {
        if (mPrimaryButton == null) {
            return;
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mPrimaryButton.setVisibility(visibility);
        showOrHideToolbarButtonsContainer();
    }

    /** Set the icon to the primary button */
    public void setPrimaryButtonIcon(@NonNull Context context, @DrawableRes int drawableRes) {
        if (mPrimaryButton == null) {
            return;
        }
        mPrimaryButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
    }

    /** Set the OnClick listener to the primary button */
    public void setPrimaryButtonOnClickListener(@Nullable View.OnClickListener listener) {
        if (mPrimaryButton == null) {
            return;
        }
        mPrimaryButton.setOnClickListener(listener);
    }

    /** Set the content description to the primary button */
    public void setPrimaryButtonContentDescription(
            @Nullable CharSequence contentDescription) {
        if (mPrimaryButton == null) {
            return;
        }
        mPrimaryButton.setContentDescription(contentDescription);
    }

    /**
     * Show/Hide the secondary button on the Toolbar.
     * @param enabled true to show the button, otherwise it's hidden.
     */
    public void setSecondaryButtonEnabled(boolean enabled) {
        if (mSecondaryButton == null) {
            return;
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;
        mSecondaryButton.setVisibility(visibility);
        showOrHideToolbarButtonsContainer();
    }

    /** Set the icon to the secondary button */
    public void setSecondaryButtonIcon(@NonNull Context context, @DrawableRes int drawableRes) {
        if (mSecondaryButton == null) {
            return;
        }
        mSecondaryButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
    }

    /** Set the OnClick listener to the secondary button */
    public void setSecondaryButtonOnClickListener(@Nullable View.OnClickListener listener) {
        if (mSecondaryButton == null) {
            return;
        }
        mSecondaryButton.setOnClickListener(listener);
    }

    /** Set the content description to the secondary button */
    public void setSecondaryButtonContentDescription(
            @Nullable CharSequence contentDescription) {
        if (mSecondaryButton == null) {
            return;
        }
        mSecondaryButton.setContentDescription(contentDescription);
    }

    /**
     * Show/Hide the action button on the Toolbar.
     * @param enabled true to show the button, otherwise it's hidden.
     */
    public void setActionButtonEnabled(boolean enabled) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        int visibility = enabled ? View.VISIBLE : View.GONE;

        updateActionButton(visibility);
        showOrHideToolbarButtonsContainer();
    }

    /**
     * Enable/Disable the action button on the Toolbar (being clickable or not).
     * @param clickable true to enable the button, otherwise it's disabled.
     */
    public void setActionButtonClickable(boolean clickable) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setEnabled(clickable);
        mActionIconOnlyButton.setEnabled(clickable);
    }

    /** Set the icon to the action button */
    public void setActionButtonIcon(@NonNull Context context, @DrawableRes int drawableRes) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
        mActionIconOnlyButton.setIcon(
                context.getResources().getDrawable(drawableRes, context.getTheme()));
    }

    /** Set the text to the action button */
    public void setActionButtonText(@Nullable CharSequence text) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }

        boolean isTextNull = text == null;

        if (!isTextNull) {
            mActionButton.setText(text);
        }

        int visibility;
        if (mActionButton.getVisibility() == View.VISIBLE
                || mActionIconOnlyButton.getVisibility() == View.VISIBLE) {
            visibility = View.VISIBLE;
        } else {
            visibility = View.GONE;
        }

        updateActionButton(visibility);
    }

    /** Set the OnClick listener to the action button */
    public void setActionButtonOnClickListener(@Nullable View.OnClickListener listener) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setOnClickListener(listener);
        mActionIconOnlyButton.setOnClickListener(listener);
    }

    /** Set the content description to the action button */
    public void setActionButtonContentDescription(
            @Nullable CharSequence contentDescription) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }
        mActionButton.setContentDescription(contentDescription);
        mActionIconOnlyButton.setContentDescription(contentDescription);
    }

    private void updateActionButton(int visibility) {
        if (mActionButton == null || mActionIconOnlyButton == null) {
            return;
        }

        if (mActionButton.getText().length() == 0) {
            mActionButton.setVisibility(View.GONE);
            mActionIconOnlyButton.setVisibility(visibility);
        } else {
            mActionIconOnlyButton.setVisibility(View.GONE);
            mActionButton.setVisibility(visibility);
        }
    }

    private void showOrHideToolbarButtonsContainer() {
        if (mToolbarButtonsContainer == null) {
            return;
        }

        boolean enabled = false;

        // If at least one button inside toolbar buttons container is visible, make the container
        // visible, otherwise it should be invisible to remove the custom padding it requires
        if (mPrimaryButton != null) {
            enabled |= mPrimaryButton.getVisibility() == View.VISIBLE;
        }

        if (mSecondaryButton != null) {
            enabled |= mSecondaryButton.getVisibility() == View.VISIBLE;
        }

        if (mActionButton != null) {
            enabled |= mActionButton.getVisibility() == View.VISIBLE;
        }

        if (mActionIconOnlyButton != null) {
            enabled |= mActionIconOnlyButton.getVisibility() == View.VISIBLE;
        }

        int visibility = enabled ? View.VISIBLE : View.GONE;
        mToolbarButtonsContainer.setVisibility(visibility);
    }
}
