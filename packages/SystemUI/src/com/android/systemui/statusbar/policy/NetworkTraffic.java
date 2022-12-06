/*
 * Copyright (C) 2006 The Android Open Source Project
 *           (C) 2022 Paranoid Android
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

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Handler;
import android.text.BidiFormatter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;

/**
 * Network speed indicator for the status bar.
 */
public class NetworkTraffic extends TextView implements TunerService.Tunable,
        DarkIconDispatcher.DarkReceiver, ConfigurationController.ConfigurationListener {

    private static final boolean DEBUG = true;
    private static final String TAG = "NetworkTraffic";

    private static final String KEY = "network_traffic";
    private static final int REFRESH_INTERVAL_MS = 1500;

    private final Handler mHandler;
    private final ConnectivityManager mConnectivityManager;

    private boolean mHide, mScreenOff;
    private long mLastRxBytes, mLastTxBytes;

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    mScreenOff = true;
                    break;
                case Intent.ACTION_SCREEN_ON:
                    mScreenOff = false;
                    break;
            }
            updateVisibility();
        }
    };

    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mHandler = new Handler();
        mConnectivityManager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // TMP
        // Log.i(TAG, "multiplier=" + getLineSpacingMultiplier() + " extra=" + getLineSpacingExtra());
        setLineSpacing(0.0f, 0.85f);
        setIncludeFontPadding(false);

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        getContext().registerReceiver(mIntentReceiver, filter);

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_HIDE_LIST);

        // We don't need to call startUpdateRun() here as TunerService sends
        // initial value on addTunable, which triggers startUpdateRun().
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getContext().unregisterReceiver(mIntentReceiver);
        Dependency.get(TunerService.class).removeTunable(this);
        mHandler.removeCallbacksAndMessages(null);
    }

    private void startUpdateRun() {
        mHandler.removeCallbacksAndMessages(null);
        // Fetch the initial values
        mLastRxBytes = TrafficStats.getTotalRxBytes();
        mLastTxBytes = TrafficStats.getTotalTxBytes();
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "postDelayed run");
                updateNetworkTraffic();
                mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }, REFRESH_INTERVAL_MS);
    }

    private void updateNetworkTraffic() {
        long rxBytes = TrafficStats.getTotalRxBytes();
        long txBytes = TrafficStats.getTotalTxBytes();
        long rx = rxBytes - mLastRxBytes;
        long tx = txBytes - mLastTxBytes;
        if (DEBUG) {
            Log.d(TAG, "updateNetworkTraffic rx=" + rx + " tx=" + tx + " mLastRxBytes=" + mLastRxBytes
                + " mLastTxBytes=" + mLastTxBytes);
        }

        mLastRxBytes = rxBytes;
        mLastTxBytes = txBytes;

        final Formatter.BytesResult res = Formatter.formatBytes(getResources(),
                Math.max(rx, tx), Formatter.FLAG_IEC_UNITS);
        String[] size = BidiFormatter.getInstance().unicodeWrap(getContext().getString(
                com.android.internal.R.string.fileSizeSuffix, res.value, res.units)).split(" ");
        SpannableStringBuilder text = new SpannableStringBuilder()
                .append(size[0], new RelativeSizeSpan(0.7f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .append("\n")
                .append(size[1] + "/s",
                        new RelativeSizeSpan(0.5f), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Setting text actually triggers a layout pass (because the text view is set to
        // wrap_content width and TextView always relayouts for this). Avoid needless
        // relayout if the text didn't actually change.
        if (!TextUtils.equals(text, getText())) {
            setText(text);
        }
    }

    private void updateVisibility() {
        boolean connected = mConnectivityManager.getActiveNetworkInfo() != null;
        boolean show = !mHide && !mScreenOff && connected;
        if (DEBUG) {
            Log.d(TAG, "updateVisibility mHide=" + mHide + " mScreenOff=" + mScreenOff
                    + " connected=" + connected);
        }
        setVisibility(show ? View.VISIBLE : View.GONE);

        if (show) {
            startUpdateRun();
        } else {
            mHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            mHide = StatusBarIconController.getIconHideList(getContext(), newValue).contains(KEY);
            updateVisibility();
        }
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        setTextColor(DarkIconDispatcher.getTint(areas, this, tint));
    }

    // Update text color based when shade scrim changes color.
    public void onColorsChanged(boolean lightTheme) {
        final Context context = new ContextThemeWrapper(mContext,
                lightTheme ? R.style.Theme_SystemUI_LightWallpaper : R.style.Theme_SystemUI);
        setTextColor(Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColor));
    }
}
