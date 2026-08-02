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

    // -------------------------------------------------------------------------
    // One UI animation constants
    // -------------------------------------------------------------------------

    /**
     * Collapse fraction at which the large (expanded) title completes its fade-out.
     * At 0.45 the large title is fully invisible, matching One UI's fast fade-out.
     */
    private static final float EXPANDED_FADE_END = 0.45f;

    /**
     * Collapse fraction at which the small (collapsed) title starts fading in.
     * Matching One UI's delayed fade-in for the toolbar title.
     */
    private static final float COLLAPSED_FADE_START = 0.45f;

    /**
     * Maximum elevation applied when fully collapsed (in dp). Matches Material3 AppBar spec.
     */
    private static final float MAX_ELEVATION_DP = 4f;

    /**
     * Parallax translation applied to the expanded title as the header collapses (in dp).
     * A small upward shift gives depth without jarring movement.
     */
    private static final float EXPANDED_TITLE_PARALLAX_DP = 10f;

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    /** Interface to be implemented by the host of the Collapsing Toolbar. */
    public interface HostCallback {
        /**
         * Called when a Toolbar should be set on the host.
         *
         * <p>If the host wants action bar to be modified, it should return it.
         */
        @Nullable
        ActionBar setActionBar(Toolbar toolbar);

        /**
         * Sets support tool bar and return support action bar, this is for
         * AppCompatActivity.
         */
        @Nullable
        default androidx.appcompat.app.ActionBar setActionBar(
                androidx.appcompat.widget.Toolbar toolbar) {
            return null;
        }

        /** Sets a title on the host. */
        void setOuterTitle(CharSequence title);
    }

    /**
     * Samsung One UI cubic-Bezier interpolator: (0.25, 0.46, 0.45, 0.94).
     * Produces a smooth decelerate feel matching Samsung's signature animation curve.
     */
    private static final class OneUiInterpolator implements TimeInterpolator {
        // Cubic Bezier control points
        private static final float P1X = 0.25f;
        private static final float P1Y = 0.46f;
        private static final float P2X = 0.45f;
        private static final float P2Y = 0.94f;

        @Override
        public float getInterpolation(float t) {
            // Numerically solve the cubic Bezier using Newton's method.
            // We need t such that B_x(t) == input, then return B_y(t).
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

    /**
     * AppBarLayout.OnOffsetChangedListener that drives all One UI animations.
     */
    private class OneUiOffsetListener implements AppBarLayout.OnOffsetChangedListener {
        private final float mMaxElevationPx;
        private final float mParallaxPx;
        private final OneUiInterpolator mInterpolator = new OneUiInterpolator();

        OneUiOffsetListener(@NonNull Context context) {
            float density = context.getResources().getDisplayMetrics().density;
            mMaxElevationPx = MAX_ELEVATION_DP * density;
            mParallaxPx = EXPANDED_TITLE_PARALLAX_DP * density;
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

            // --- Expanded title animation ---
            if (mExpandedTitleView != null) {
                // Phase 1: fade out from 0 to EXPANDED_FADE_END
                float rawExpanded = 1f - collapseRatio / EXPANDED_FADE_END;
                float expandedAlpha = mInterpolator.getInterpolation(
                        Math.max(0f, Math.min(1f, rawExpanded)));
                mExpandedTitleView.setAlpha(expandedAlpha);

                // Parallax: slight upward translate as we collapse
                mExpandedTitleView.setTranslationY(-mParallaxPx * collapseRatio);
            }

            // --- Collapsed title animation ---
            if (mCollapsedTitleView != null) {
                // Phase 2: fade in from COLLAPSED_FADE_START to 1.0
                float rawCollapsed =
                        (collapseRatio - COLLAPSED_FADE_START) / (1f - COLLAPSED_FADE_START);
                float collapsedAlpha = mInterpolator.getInterpolation(
                        Math.max(0f, Math.min(1f, rawCollapsed)));
                mCollapsedTitleView.setAlpha(collapsedAlpha);
            }

            // --- Action Buttons animation ---
            // In One UI, action buttons (like Search) sit next to the large title in the viewing
            // area when expanded, and slide up into the Toolbar when collapsed.
            // By translating down exactly totalScrollRange / 2, the buttons sit at H/2 (center).
            if (mToolbarButtonsContainer != null) {
                float maxTranslationY = totalScrollRange / 2f;
                mToolbarButtonsContainer.setTranslationY(maxTranslationY * (1f - collapseRatio));
            }

            // --- Elevation animation ---
            // Ramp from 0 → mMaxElevationPx linearly; but only once the large title
            // has faded out (>= EXPANDED_FADE_END) so there's no elevation while the
            // expanded state is visible.
            if (appBarLayout != null) {
                float elevRatio = (collapseRatio - EXPANDED_FADE_END)
                        / (1f - EXPANDED_FADE_END);
                float elevation = mMaxElevationPx * Math.max(0f, Math.min(1f, elevRatio));
                appBarLayout.setElevation(elevation);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

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

    // One UI custom title views
    @Nullable
    private TextView mExpandedTitleView;
    @Nullable
    private TextView mCollapsedTitleView;

    // Stored listener reference to avoid leak on reinflation
    @Nullable
    private OneUiOffsetListener mOneUiOffsetListener;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback) {
        this(hostCallback, /* useCollapsingToolbar= */ true);
    }

    public CollapsingToolbarDelegate(@NonNull HostCallback hostCallback,
            boolean useCollapsingToolbar) {
        mHostCallback = hostCallback;
        mUseCollapsingToolbar = useCollapsingToolbar;
    }

    // -------------------------------------------------------------------------
    // View creation
    // -------------------------------------------------------------------------

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
                // In One UI mode, we show our own custom title views; disable the built-in title
                // to prevent a duplicate title from appearing in the toolbar.
                actionBar.setDisplayShowTitleEnabled(!mIsExpressiveTheme);
            }

            // Inject One UI collapsed title into the native Toolbar.
            if (mIsExpressiveTheme && mToolbar != null) {
                injectCollapsedTitleView(mToolbar);
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

    // -------------------------------------------------------------------------
    // Collapsing toolbar initialisation
    // -------------------------------------------------------------------------

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

        // One UI animation setup — only on Expressive theme with a real CTL
        if (mIsExpressiveTheme && collapsingToolbarLayout != null && appBarLayout != null) {
            setupOneUiTitleAnimation(collapsingToolbarLayout, appBarLayout);
        }

        autoSetCollapsingToolbarLayoutScrolling(appBarLayout);
    }

    /**
     * Sets up the One UI-style title animation on the given CollapsingToolbarLayout.
     *
     * <p>This method:
     * <ol>
     *   <li>Disables Material's built-in title handling (no more scale+slide).</li>
     *   <li>Creates and inserts the large {@link #mExpandedTitleView} at bottom-start of the CTL.
     *   <li>Registers a {@link OneUiOffsetListener} on the {@link AppBarLayout}.
     * </ol>
     *
     * <p>The collapsed title view ({@link #mCollapsedTitleView}) is injected separately after the
     * Toolbar is initialised (see {@link #injectCollapsedTitleView(View)}).
     */
    private void setupOneUiTitleAnimation(
            @NonNull CollapsingToolbarLayout collapsingToolbarLayout,
            @NonNull AppBarLayout appBarLayout) {

        // Step 1: Disable Material's native scale-slide title animation.
        collapsingToolbarLayout.setTitleEnabled(false);

        // Step 2: Build and add the expanded (large) title view.
        mExpandedTitleView = buildExpandedTitleView(collapsingToolbarLayout.getContext());
        CollapsingToolbarLayout.LayoutParams expandedParams =
                new CollapsingToolbarLayout.LayoutParams(
                        CollapsingToolbarLayout.LayoutParams.MATCH_PARENT,
                        CollapsingToolbarLayout.LayoutParams.WRAP_CONTENT);
        // Anchor to center — One UI places the large title in the middle of the expanded app bar.
        expandedParams.gravity = Gravity.CENTER;
        expandedParams.setCollapseMode(CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PARALLAX);
        expandedParams.setParallaxMultiplier(0f); // we drive parallax ourselves in the listener
        collapsingToolbarLayout.addView(mExpandedTitleView, expandedParams);

        // Step 3: Initialise elevation to 0 (we animate it in the listener).
        appBarLayout.setElevation(0f);

        // Step 4: Register the offset listener. Remove any previous one to avoid leaks.
        if (mOneUiOffsetListener != null) {
            appBarLayout.removeOnOffsetChangedListener(mOneUiOffsetListener);
        }
        mOneUiOffsetListener = new OneUiOffsetListener(appBarLayout.getContext());
        appBarLayout.addOnOffsetChangedListener(mOneUiOffsetListener);
    }

    /**
     * Injects the collapsed (small) title view into a Toolbar so it participates in the
     * One UI cross-fade. Call this after the Toolbar has been added to the view hierarchy.
     *
     * @param toolbarView the {@link Toolbar} or {@link androidx.appcompat.widget.Toolbar} view
     */
    private void injectCollapsedTitleView(@NonNull View toolbarView) {
        if (!mIsExpressiveTheme) return;

        mCollapsedTitleView = buildCollapsedTitleView(toolbarView.getContext());
        mCollapsedTitleView.setAlpha(0f); // starts invisible; fades in as header collapses

        if (toolbarView instanceof Toolbar) {
            Toolbar tb = (Toolbar) toolbarView;
            // Clear the toolbar's own title so we don't see duplicates.
            tb.setTitle(null);
            // Add our custom title, vertically centered in the toolbar.
            Toolbar.LayoutParams lp = new Toolbar.LayoutParams(
                    Toolbar.LayoutParams.WRAP_CONTENT,
                    Toolbar.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            tb.addView(mCollapsedTitleView, lp);
        } else if (toolbarView instanceof androidx.appcompat.widget.Toolbar) {
            androidx.appcompat.widget.Toolbar tb =
                    (androidx.appcompat.widget.Toolbar) toolbarView;
            tb.setTitle(null);
            androidx.appcompat.widget.Toolbar.LayoutParams lp =
                    new androidx.appcompat.widget.Toolbar.LayoutParams(
                            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            tb.addView(mCollapsedTitleView, lp);
        }
    }

    // -------------------------------------------------------------------------
    // One UI title view builders
    // -------------------------------------------------------------------------

    /**
     * Builds the large expanded title {@link TextView} styled for One UI.
     */
    @NonNull
    private TextView buildExpandedTitleView(@NonNull Context context) {
        TextView tv = new TextView(context);
        tv.setId(R.id.oneui_expanded_title);

        // One UI expanded title: 34sp, bold, color from ?attr/colorOnSurface
        float expandedSizeSp = getExpandedTitleSizeSp(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, expandedSizeSp);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(resolveColorOnSurface(context));
        tv.setSingleLine(false);
        tv.setMaxLines(2);
        tv.setLineSpacing(0f, TOOLBAR_LINE_SPACING_MULTIPLIER);
        tv.setGravity(Gravity.CENTER);

        // One UI margins: 24 dp start, 20 dp bottom, 24 dp end
        float density = context.getResources().getDisplayMetrics().density;
        int marginStartPx = Math.round(24f * density);
        int marginEndPx = Math.round(24f * density);

        // We apply margins via layout params set by the caller; store them as padding here
        // so they survive re-measure without requiring a separate LayoutParams subtype.
        tv.setPaddingRelative(marginStartPx, 0, marginEndPx, 0);

        return tv;
    }

    /**
     * Builds the small collapsed title {@link TextView} styled for One UI (injected into Toolbar).
     */
    @NonNull
    private TextView buildCollapsedTitleView(@NonNull Context context) {
        TextView tv = new TextView(context);
        tv.setId(R.id.oneui_collapsed_title);

        // One UI collapsed title: 20sp, medium weight, same color
        float collapsedSizeSp = getCollapsedTitleSizeSp(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, collapsedSizeSp);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(resolveColorOnSurface(context));
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);

        return tv;
    }

    // -------------------------------------------------------------------------
    // Dimension & color helpers
    // -------------------------------------------------------------------------

    private float getExpandedTitleSizeSp(@NonNull Context context) {
        try {
            return context.getResources()
                    .getDimension(R.dimen.settingslib_oneui_expanded_title_size)
                    / context.getResources().getDisplayMetrics().scaledDensity;
        } catch (android.content.res.Resources.NotFoundException e) {
            return 34f; // fallback
        }
    }

    private float getCollapsedTitleSizeSp(@NonNull Context context) {
        try {
            return context.getResources()
                    .getDimension(R.dimen.settingslib_oneui_collapsed_title_size)
                    / context.getResources().getDisplayMetrics().scaledDensity;
        } catch (android.content.res.Resources.NotFoundException e) {
            return 20f; // fallback
        }
    }

    /**
     * Resolves {@code ?attr/colorOnSurface} from the current theme, falling back to the
     * default text color if the attribute is not present.
     */
    private int resolveColorOnSurface(@NonNull Context context) {
        TypedArray ta = context.obtainStyledAttributes(
                new int[]{android.R.attr.textColorPrimary});
        int color = ta.getColor(0, 0xFF000000 /* fallback black */);
        ta.recycle();
        return color;
    }

    // -------------------------------------------------------------------------
    // Support toolbar helpers
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    /** Return an instance of CoordinatorLayout. */
    @Nullable
    public CoordinatorLayout getCoordinatorLayout() {
        return mCoordinatorLayout;
    }

    /** Sets the title on the collapsing layout and delegates to host. */
    public void setTitle(CharSequence title) {
        if (mIsExpressiveTheme) {
            // Route to our custom views instead of the CTL's own title system.
            if (mExpandedTitleView != null) {
                mExpandedTitleView.setText(title);
            }
            if (mCollapsedTitleView != null) {
                mCollapsedTitleView.setText(title);
            }
        } else if (mCollapsingToolbarLayout != null) {
            mCollapsingToolbarLayout.setTitle(title);
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
     *
     * @param visible
     */
    public void setFloatingToolbarVisibility(boolean visible) {
        if (mFloatingToolbarLayout == null) {
            return;
        }
        mFloatingToolbarLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Sets the toolbar items for the floating toolbar.
     *
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

    // -------------------------------------------------------------------------
    // Action bar initialisation (both paths)
    // -------------------------------------------------------------------------

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
            // In One UI mode we show our own title views, so disable the built-in title.
            actionBar.setDisplayShowTitleEnabled(!mIsExpressiveTheme);
        }

        // Inject the One UI collapsed title into the support toolbar.
        if (mIsExpressiveTheme && supportToolbar != null) {
            injectCollapsedTitleView(supportToolbar);
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

        if (mIsExpressiveTheme && supportToolbar != null) {
            injectCollapsedTitleView(supportToolbar);
        }
    }

    // -------------------------------------------------------------------------
    // Fragment lifecycle collapse behavior
    // -------------------------------------------------------------------------

    /**
     * Set the state of CollapsingToolbar to collapsed when multiple fragments share a single
     * FragmentManager within an activity.
     */
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
                        // Check if multiple fragments use the same activity
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

    // -------------------------------------------------------------------------
    // Scroll behavior setup
    // -------------------------------------------------------------------------

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
                        // Header can be scrolling while device in landscape mode and SDK > 33
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

    // -------------------------------------------------------------------------
    // Toolbar button helpers
    // -------------------------------------------------------------------------

    /**
     * Show/Hide the primary button on the Toolbar.
     *
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
     *
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
     *
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
     *
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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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

        // If at least one button inside toolbar buttons container is visible, make the
        // container visible, otherwise it should be invisible to remove the custom padding it
        // requires
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
