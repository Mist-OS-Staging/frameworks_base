/*
 * Copyright (C) 2026 MistOS
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

package com.android.internal.util.mist;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

public final class MistifyFluidMotionHelper {

    public static final String SETTING_KEY = "mist_fluid_animation_enabled";
    private static final Uri SETTING_URI = Settings.System.getUriFor(SETTING_KEY);

    public static final float SPRING_STIFFNESS_DEFAULT = 700.0f;
    public static final float SPRING_DAMPING_RATIO_DEFAULT = 0.9f;
    public static final float SPRING_STIFFNESS_FLUID = 420.0f;
    public static final float SPRING_DAMPING_RATIO_FLUID = 0.72f;

    private static final PathInterpolator SPRING_INTERPOLATOR =
            new PathInterpolator(0.175f, 0.885f, 0.32f, 1.275f);
    private static final PathInterpolator DAMPED_SPRING_INTERPOLATOR =
            new PathInterpolator(0.2f, 0.9f, 0.3f, 1.15f);
    private static final PathInterpolator APP_LAUNCH_INTERPOLATOR =
            new PathInterpolator(0.15f, 0.95f, 0.28f, 1.035f);

    private static volatile boolean sFluidAnimationEnabled = false;
    private static volatile boolean sObserverRegistered = false;
    private static final Object sLock = new Object();

    private MistifyFluidMotionHelper() {
    }

    public static void init(Context context) {
        if (context == null || sObserverRegistered) {
            return;
        }
        synchronized (sLock) {
            if (sObserverRegistered) {
                return;
            }
            Context appContext = context.getApplicationContext();
            ContentResolver resolver = (appContext != null ? appContext : context).getContentResolver();
            if (resolver == null) {
                return;
            }
            updateState(resolver);
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    updateState(resolver);
                }
            };
            try {
                resolver.registerContentObserver(
                        SETTING_URI,
                        false,
                        observer,
                        UserHandle.USER_ALL
                );
                sObserverRegistered = true;
            } catch (Exception e) {
                try {
                    resolver.registerContentObserver(
                            SETTING_URI,
                            false,
                            observer
                    );
                    sObserverRegistered = true;
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void updateState(ContentResolver resolver) {
        try {
            sFluidAnimationEnabled = Settings.System.getIntForUser(
                    resolver,
                    SETTING_KEY,
                    0,
                    UserHandle.USER_CURRENT
            ) == 1;
        } catch (Exception e) {
            try {
                sFluidAnimationEnabled = Settings.System.getInt(
                        resolver,
                        SETTING_KEY,
                        0
                ) == 1;
            } catch (Exception ignored) {
                sFluidAnimationEnabled = false;
            }
        }
    }

    public static boolean isFluidAnimationEnabled() {
        if (!sObserverRegistered) {
            Context app = android.app.ActivityThread.currentApplication();
            if (app != null) {
                init(app);
            }
        }
        return sFluidAnimationEnabled;
    }

    public static boolean isFluidAnimationEnabled(Context context) {
        if (!sObserverRegistered && context != null) {
            init(context);
        }
        return sFluidAnimationEnabled;
    }

    public static void setFluidAnimationEnabled(boolean enabled) {
        sFluidAnimationEnabled = enabled;
    }

    public static Interpolator getSpringInterpolator() {
        return SPRING_INTERPOLATOR;
    }

    public static Interpolator getDampedSpringInterpolator() {
        return DAMPED_SPRING_INTERPOLATOR;
    }

    public static Interpolator getAppLaunchInterpolator() {
        return APP_LAUNCH_INTERPOLATOR;
    }

    public static float getSpringStiffness() {
        return sFluidAnimationEnabled ? SPRING_STIFFNESS_FLUID : SPRING_STIFFNESS_DEFAULT;
    }

    public static float getSpringDampingRatio() {
        return sFluidAnimationEnabled ? SPRING_DAMPING_RATIO_FLUID : SPRING_DAMPING_RATIO_DEFAULT;
    }
}
