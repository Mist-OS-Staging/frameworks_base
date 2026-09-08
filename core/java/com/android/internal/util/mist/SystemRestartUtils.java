/*
 * Copyright (C) 2023 Rising OS Android Project
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

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.statusbar.IStatusBarService;

import java.lang.ref.WeakReference;

public class SystemRestartUtils {

    private static final String TAG = "MistifyFluid";
    private static final int RESTART_TIMEOUT = 800;

    public static void showSystemRestartDialog(Context context) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.system_restart_title)
                .setMessage(R.string.system_restart_message)
                .setPositiveButton(R.string.ok, (dialog, id) -> {
                    Handler handler = new Handler();
                    handler.postDelayed(() -> restartSystem(context), RESTART_TIMEOUT);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void powerOffSystem(Context context) {
        new PowerOffSystemTask(context).execute();
    }

    private static class PowerOffSystemTask extends AsyncTask<Void, Void, Void> {
        private final WeakReference<Context> mContext;

        PowerOffSystemTask(Context context) {
            mContext = new WeakReference<>(context);
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                IStatusBarService mBarService = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE));
                if (mBarService != null) {
                    try {
                        Thread.sleep(RESTART_TIMEOUT);
                        mBarService.shutdown();
                    } catch (RemoteException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    public static void restartSystem(Context context) {
        new RestartSystemTask(context).execute();
    }

    private static class RestartSystemTask extends AsyncTask<Void, Void, Void> {
        private final WeakReference<Context> mContext;

        RestartSystemTask(Context context) {
            mContext = new WeakReference<>(context);
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                IStatusBarService mBarService = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService(Context.STATUS_BAR_SERVICE));
                if (mBarService != null) {
                    try {
                        Thread.sleep(RESTART_TIMEOUT);
                        mBarService.reboot(false, null);
                    } catch (RemoteException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private static void showRestartDialog(Context context, int title, int message, Runnable action) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, (dialog, id) -> {
                    Handler handler = new Handler();
                    handler.postDelayed(action, RESTART_TIMEOUT);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void restartProcess(Context context, String packageName) {
        new ForceStopTask(context, packageName).execute();
    }

    private static class ForceStopTask extends AsyncTask<Void, Void, Void> {
        private final WeakReference<Context> mContext;
        private final String mPackageName;

        ForceStopTask(Context context, String packageName) {
            mContext = new WeakReference<>(context);
            mPackageName = packageName;
        }

        @Override
        protected Void doInBackground(Void... params) {
            Context ctx = mContext.get();
            if (ctx == null) return null;
            try {
                ActivityManager am = ctx.getSystemService(ActivityManager.class);
                if (am != null) {
                    Log.d(TAG, "restart executed - forceStopPackage: " + mPackageName);
                    am.forceStopPackage(mPackageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "restart failed for " + mPackageName + ": " + e.getMessage());
            }
            return null;
        }
    }

    public static void showSettingsRestartDialog(Context context) {
        showRestartDialog(context, R.string.settings_restart_title, R.string.settings_restart_message, () -> restartProcess(context, "com.android.settings"));
    }

    public static void showSystemUIRestartDialog(Context context) {
        Log.d(TAG, "SystemUI restart requested");
        showRestartDialog(context, R.string.systemui_restart_title, R.string.systemui_restart_message, () -> restartProcess(context, "com.android.systemui"));
    }
}

