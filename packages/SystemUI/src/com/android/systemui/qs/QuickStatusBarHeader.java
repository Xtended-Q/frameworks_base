/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.annotation.ColorInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.StatsLog;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Space;
import android.widget.TextView;
import android.database.ContentObserver;
import android.content.ContentResolver;
import android.os.UserHandle;

import androidx.annotation.VisibleForTesting;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.util.xtended.FileUtils;
import com.android.internal.util.xtended.XtendedUtils;
import com.android.settingslib.Utils;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.DualToneHandler;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.privacy.PrivacyDialogBuilder;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyItemControllerKt;
import com.android.systemui.qs.QSDetail.Callback;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.info.DataUsageView;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.DateView;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View that contains the top-most bits of the screen (primarily the status bar with date, time, and
 * battery) and also contains the {@link QuickQSPanel} along with some of the panel's inner
 * contents.
 */
public class QuickStatusBarHeader extends RelativeLayout implements
        View.OnClickListener, NextAlarmController.NextAlarmChangeCallback,
        ZenModeController.Callback, Tunable {
    private static final String TAG = "QuickStatusBarHeader";
    private static final boolean DEBUG = false;
    public static final String QS_SHOW_INFO_HEADER = "qs_show_info_header";

    /** Delay for auto fading out the long press tooltip after it's fully visible (in ms). */
    private static final long AUTO_FADE_OUT_DELAY_MS = DateUtils.SECOND_IN_MILLIS * 6;
    private static final int FADE_ANIMATION_DURATION_MS = 300;
    private static final int TOOLTIP_NOT_YET_SHOWN_COUNT = 0;
    public static final int MAX_TOOLTIP_SHOWN_COUNT = 2;

    public static final String QS_SHOW_AUTO_BRIGHTNESS =
            QSPanel.QS_SHOW_AUTO_BRIGHTNESS;
    public static final String QS_SHOW_BRIGHTNESS_BUTTONS =
            QSPanel.QS_SHOW_BRIGHTNESS_BUTTONS;
    public static final String QQS_SHOW_BRIGHTNESS_SLIDER =
            "qqs_show_brightness_slider";

    private final Handler mHandler = new Handler();
    private final NextAlarmController mAlarmController;
    private final ZenModeController mZenController;
    private final StatusBarIconController mStatusBarIconController;
    private final ActivityStarter mActivityStarter;

    private QSPanel mQsPanel;

    private boolean mExpanded;
    private boolean mListening;
    private boolean mQsDisabled;

    private int isBattIconQsH;

    private QSCarrierGroup mCarrierGroup;
    protected QuickQSPanel mHeaderQsPanel;
    protected QSTileHost mHost;
    private TintedIconManager mIconManager;
    private TouchAnimator mStatusIconsAlphaAnimator;
    private TouchAnimator mHeaderTextContainerAlphaAnimator;
    private TouchAnimator mPrivacyChipAlphaAnimator;
    private DualToneHandler mDualToneHandler;

    private View mSystemIconsView;
    private View mQuickQsStatusIcons;
    private View mHeaderTextContainerView;

    private int mRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    private boolean mLandscape;
    private boolean mHeaderImageEnabled;
    private int mHeaderImageHeight;

    // Data Usage
    private View mDataUsageLayout;
    private ImageView mDataUsageImage;
    private DataUsageView mDataUsageView;

    private ImageView mNextAlarmIcon;
    /** {@link TextView} containing the actual text indicating when the next alarm will go off. */
    private TextView mNextAlarmTextView;
    private View mNextAlarmContainer;
    private View mStatusSeparator;
    private ImageView mRingerModeIcon;
    private TextView mRingerModeTextView;
    private View mRingerContainer;
    private Clock mClockView;
    private DateView mDateView;
    private OngoingPrivacyChip mPrivacyChip;
    private Space mSpace;
    private BatteryMeterView mBatteryRemainingIcon;
    private BatteryMeterView mBatteryRemainingIconQsH;
    private boolean mPermissionsHubEnabled;

    private View mQuickQsBrightness;
    private BrightnessController mBrightnessController;
    private boolean mIsQsAutoBrightnessEnabled;
    private boolean mBrightnessButton;
    private int mBrightnessSlider = 2;
    private ImageView mMinBrightness;
    private ImageView mMaxBrightness;

    private boolean mHideDragHandle;

    private PrivacyItemController mPrivacyItemController;

    private TextView mSystemInfoText;
    private int mSystemInfoMode;
    private ImageView mSystemInfoIcon;
    private View mSystemInfoLayout;
    // SystemInfo
    private String mSysCPUTemp;
    private String mSysBatTemp;
    private String mSysGPUFreq;
    private String mSysGPULoad;
    private int mSysCPUTempMultiplier;
    private int mSysBatTempMultiplier;

    // QS Logo
    private ImageView mXtendedLogo;
    private ImageView mXtendedLogoRight;
    private int mLogoStyle;
    private int mShowLogo;
    private int mLogoColor;

    protected ContentResolver mContentResolver;

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_SYSTEM_INFO), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_BATTERY_MODE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.STATUS_BAR_BATTERY_STYLE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_DATAUSAGE), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_BATTERY_LOCATION), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                   .getUriFor(Settings.System.QS_PANEL_LOGO), false,
                   this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                   .getUriFor(Settings.System.QS_PANEL_LOGO_STYLE), false,
                   this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                   .getUriFor(Settings.System.QS_PANEL_LOGO_COLOR), false,
		    this, UserHandle.USER_ALL);
            }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }
    private SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);

    private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mRingerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1);
            updateStatusText();
        }
    };
    private boolean mHasTopCutout = false;
    private boolean mPrivacyChipLogged = false;

    private final DeviceConfig.OnPropertyChangedListener mPropertyListener =
            new DeviceConfig.OnPropertyChangedListener() {
                @Override
                public void onPropertyChanged(String namespace, String name, String value) {
                    if (DeviceConfig.NAMESPACE_PRIVACY.equals(namespace)
                            && SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED.equals(
                            name)) {
                        mPermissionsHubEnabled = Boolean.valueOf(value);
                        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);
                        iconContainer.setIgnoredSlots(getIgnoredIconSlots());
                    }
                }
            };

    private PrivacyItemController.Callback mPICCallback = new PrivacyItemController.Callback() {
        @Override
        public void privacyChanged(List<PrivacyItem> privacyItems) {
            mPrivacyChip.setPrivacyList(privacyItems);
            setChipVisibility(!privacyItems.isEmpty());
        }
    };

    @Inject
    public QuickStatusBarHeader(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            NextAlarmController nextAlarmController, ZenModeController zenModeController,
            StatusBarIconController statusBarIconController,
            ActivityStarter activityStarter, PrivacyItemController privacyItemController) {
        super(context, attrs);
        mAlarmController = nextAlarmController;
        mZenController = zenModeController;
        mStatusBarIconController = statusBarIconController;
        mActivityStarter = activityStarter;
        mPrivacyItemController = privacyItemController;
        mDualToneHandler = new DualToneHandler(
                new ContextThemeWrapper(context, R.style.QSHeaderTheme));
        mSystemInfoMode = getQsSystemInfoMode();
        mContentResolver = context.getContentResolver();
        mSettingsObserver.observe();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mSystemIconsView = findViewById(R.id.quick_status_bar_system_icons);
        mQuickQsStatusIcons = findViewById(R.id.quick_qs_status_icons);
        StatusIconContainer iconContainer = findViewById(R.id.statusIcons);

        mPermissionsHubEnabled = PrivacyItemControllerKt.isPermissionsHubEnabled();

        // Ignore privacy icons because they show in the space above QQS
        iconContainer.addIgnoredSlots(getIgnoredIconSlots());
        iconContainer.setShouldRestrictIcons(false);
        mIconManager = new TintedIconManager(iconContainer);

        mQuickQsBrightness = findViewById(R.id.quick_qs_brightness_bar);
        mMinBrightness = mQuickQsBrightness.findViewById(R.id.brightness_left);
        mMaxBrightness = mQuickQsBrightness.findViewById(R.id.brightness_right);
        mMinBrightness.setOnClickListener(this::onClick);
        mMaxBrightness.setOnClickListener(this::onClick);
        ImageView brightnessIcon = (ImageView) mQuickQsBrightness.findViewById(R.id.brightness_icon);
        brightnessIcon.setVisibility(View.VISIBLE);
        mBrightnessController = new BrightnessController(getContext(),
                brightnessIcon,
                mQuickQsBrightness.findViewById(R.id.brightness_slider));

        // Views corresponding to the header info section (e.g. ringer and next alarm).
        mHeaderTextContainerView = findViewById(R.id.header_text_container);
        mStatusSeparator = findViewById(R.id.status_separator);
        mNextAlarmIcon = findViewById(R.id.next_alarm_icon);
        mNextAlarmTextView = findViewById(R.id.next_alarm_text);
        mNextAlarmContainer = findViewById(R.id.alarm_container);
        mNextAlarmContainer.setOnClickListener(this::onClick);
        mRingerModeIcon = findViewById(R.id.ringer_mode_icon);
        mRingerModeTextView = findViewById(R.id.ringer_mode_text);
        mRingerContainer = findViewById(R.id.ringer_container);
        mRingerContainer.setOnClickListener(this::onClick);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mPrivacyChip.setOnClickListener(this::onClick);
        mCarrierGroup = findViewById(R.id.carrier_group);
        mSystemInfoLayout = findViewById(R.id.system_info_layout);
        mSystemInfoIcon = findViewById(R.id.system_info_icon);
        mSystemInfoText = findViewById(R.id.system_info_text);
        mXtendedLogo = findViewById(R.id.qs_panel_logo);
	mXtendedLogoRight = findViewById(R.id.qs_panel_logo_right);

        updateResources();
        addQuickQSPanel();

        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);
        int fillColorWhite = getContext().getResources().getColor(android.R.color.white);

        // Set light text on the header icons because they will always be on a black background
        applyDarkness(R.id.clock, tintArea, 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        // Set the correct tint for the status icons so they contrast
        mIconManager.setTint(fillColor);
        mNextAlarmIcon.setImageTintList(ColorStateList.valueOf(fillColor));
        mRingerModeIcon.setImageTintList(ColorStateList.valueOf(fillColor));

        mClockView = findViewById(R.id.clock);
        mClockView.setOnClickListener(this);
	mClockView.setQsHeader();
        mDateView = findViewById(R.id.date);
        mDateView.setOnClickListener(this);
        mSpace = findViewById(R.id.space);
        mDataUsageLayout = findViewById(R.id.daily_data_usage_layout);
        mDataUsageImage = findViewById(R.id.daily_data_usage_icon);
        mDataUsageView = findViewById(R.id.data_sim_usage);

        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);
        mBatteryRemainingIconQsH = findViewById(R.id.batteryRemainingIconQsH);
        // Don't need to worry about tuner settings for this icon
        mBatteryRemainingIcon.setIgnoreTunerUpdates(true);
        mBatteryRemainingIconQsH.setIgnoreTunerUpdates(true);
        mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ON);
        mBatteryRemainingIconQsH.setPercentShowMode(BatteryMeterView.MODE_ON);
        mBatteryRemainingIcon.setOnClickListener(this);
        mBatteryRemainingIconQsH.setOnClickListener(this);
        mRingerModeTextView.setSelected(true);
        mNextAlarmTextView.setSelected(true);
        // Change the ignored slots when DeviceConfig flag changes
        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                mContext.getMainExecutor(), mPropertyListener);
        Dependency.get(TunerService.class).addTunable(this,
                QS_SHOW_AUTO_BRIGHTNESS,
                QQS_SHOW_BRIGHTNESS_SLIDER,
                QS_SHOW_BRIGHTNESS_BUTTONS,
                QSFooterImpl.QS_SHOW_DRAG_HANDLE);
        updateSettings();
    }

    private List<String> getIgnoredIconSlots() {
        ArrayList<String> ignored = new ArrayList<>();
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_camera));
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_microphone));
        ignored.add(mContext.getResources().getString(
                com.android.internal.R.string.status_bar_location));

        return ignored;
    }

    private void updateStatusText() {
        boolean changed = updateRingerStatus() || updateAlarmStatus();

        if (changed) {
            boolean alarmVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
            boolean ringerVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
            mStatusSeparator.setVisibility(alarmVisible && ringerVisible ? View.VISIBLE
                    : View.GONE);
        }
    }

    private int getQsSystemInfoMode() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.QS_SYSTEM_INFO, 0);
    }

    private void updateSystemInfoText() {
        mSystemInfoText.setVisibility(View.GONE);
        mSystemInfoIcon.setVisibility(View.GONE);
        mSystemInfoLayout.setVisibility(View.GONE);
        if (mSystemInfoMode == 0) return;
        int defaultMultiplier = 1;
        String systemInfoText = "";
        switch (mSystemInfoMode) {
            case 1:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_thermometer));
                systemInfoText = getSystemInfo(mSysCPUTemp, mSysCPUTempMultiplier, "\u2103", true);
                break;
            case 2:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_thermometer));
                systemInfoText = getSystemInfo(mSysBatTemp, mSysBatTempMultiplier, "\u2103", true);
                break;
            case 3:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_gpu));
                systemInfoText = getSystemInfo(mSysGPUFreq, defaultMultiplier, "Mhz", true);
                break;
            case 4:
                mSystemInfoIcon.setImageDrawable(getContext().getDrawable(R.drawable.ic_gpu));
                systemInfoText = getSystemInfo(mSysGPULoad, defaultMultiplier, "", false);
                break;
        }
        if (systemInfoText != null && !systemInfoText.isEmpty()) {
            mSystemInfoLayout.setVisibility(View.VISIBLE);
            mSystemInfoText.setText(systemInfoText);
            mSystemInfoIcon.setVisibility(View.VISIBLE);
            mSystemInfoText.setVisibility(View.VISIBLE);
        }
    }

    private String getSystemInfo(String sysPath, int multiplier, String unit, boolean returnFormatted) {
        if (!sysPath.isEmpty() && FileUtils.fileExists(sysPath)) {
            String value = FileUtils.readOneLine(sysPath);
            return returnFormatted ? String.format("%s", Integer.parseInt(value) / multiplier) + unit : value;
        }
        return null;
    }

    private void setChipVisibility(boolean chipVisible) {
        if (chipVisible && mPermissionsHubEnabled) {
            mPrivacyChip.setVisibility(View.VISIBLE);
            // Makes sure that the chip is logged as viewed at most once each time QS is opened
            // mListening makes sure that the callback didn't return after the user closed QS
            if (!mPrivacyChipLogged && mListening) {
                mPrivacyChipLogged = true;
                StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                        StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_VIEWED);
            }
        } else {
            mPrivacyChip.setVisibility(View.GONE);
        }
    }

    private boolean updateRingerStatus() {
        boolean isOriginalVisible = mRingerModeTextView.getVisibility() == View.VISIBLE;
        CharSequence originalRingerText = mRingerModeTextView.getText();

        boolean ringerVisible = false;
        if (!ZenModeConfig.isZenOverridingRinger(mZenController.getZen(),
                mZenController.getConsolidatedPolicy())) {
            if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                mRingerModeTextView.setText(R.string.qs_status_phone_vibrate);
                ringerVisible = true;
            } else if (mRingerMode == AudioManager.RINGER_MODE_SILENT) {
                mRingerModeIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                mRingerModeTextView.setText(R.string.qs_status_phone_muted);
                ringerVisible = true;
            }
        }
        mRingerModeIcon.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerModeTextView.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);
        mRingerContainer.setVisibility(ringerVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != ringerVisible ||
                !Objects.equals(originalRingerText, mRingerModeTextView.getText());
    }

    private boolean updateAlarmStatus() {
        boolean isOriginalVisible = mNextAlarmTextView.getVisibility() == View.VISIBLE;
        CharSequence originalAlarmText = mNextAlarmTextView.getText();

        boolean alarmVisible = false;
        if (mNextAlarm != null) {
            alarmVisible = true;
            mNextAlarmTextView.setText(formatNextAlarm(mNextAlarm));
        }
        mNextAlarmIcon.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmTextView.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);
        mNextAlarmContainer.setVisibility(alarmVisible ? View.VISIBLE : View.GONE);

        return isOriginalVisible != alarmVisible ||
                !Objects.equals(originalAlarmText, mNextAlarmTextView.getText());
    }

    private void applyDarkness(int id, Rect tintArea, float intensity, int color) {
        View v = findViewById(id);
        if (v instanceof DarkReceiver) {
            ((DarkReceiver) v).onDarkChanged(tintArea, intensity, color);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    /**
     * The height of QQS should always be the status bar height + 128dp. This is normally easy, but
     * when there is a notch involved the status bar can remain a fixed pixel size.
     */
    private void updateMinimumHeight() {
        int sbHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        int qqsHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.qs_quick_header_panel_height);

        if (mBrightnessSlider != 0) {
           if (!mHeaderImageEnabled) {
               qqsHeight += mContext.getResources().getDimensionPixelSize(
                       R.dimen.brightness_mirror_height)
                       + mContext.getResources().getDimensionPixelSize(
                       R.dimen.qs_tile_margin_top);
           } else {
               qqsHeight += mContext.getResources().getDimensionPixelSize(
                       R.dimen.brightness_mirror_height)
                       + mContext.getResources().getDimensionPixelSize(
                       R.dimen.qs_tile_margin_top) + mHeaderImageHeight;
           }
        }

        setMinimumHeight(sbHeight + qqsHeight);
    }

    private void addQuickQSPanel() {
        RelativeLayout.LayoutParams headerPanel = (RelativeLayout.LayoutParams)
                mHeaderQsPanel.getLayoutParams();

        RelativeLayout.LayoutParams lpQuickQsBrightness = (RelativeLayout.LayoutParams)
                mQuickQsBrightness.getLayoutParams();
        lpQuickQsBrightness.addRule(RelativeLayout.BELOW, R.id.header_text_container);

        switch (mBrightnessSlider) {
            case 1:
                headerPanel.addRule(RelativeLayout.BELOW, R.id.quick_qs_brightness_bar);
                lpQuickQsBrightness.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 0);
                break;
            case 2:
            default:
                headerPanel.addRule(RelativeLayout.BELOW, R.id.quick_qs_status_icons);
                lpQuickQsBrightness.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                break;
        }
        mHeaderQsPanel.setLayoutParams(headerPanel);
        mQuickQsBrightness.setLayoutParams(lpQuickQsBrightness);
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        updateMinimumHeight();

        // Update height for a few views, especially due to landscape mode restricting space.
        /*mHeaderTextContainerView.getLayoutParams().height =
                resources.getDimensionPixelSize(R.dimen.qs_header_tooltip_height);
        mHeaderTextContainerView.setLayoutParams(mHeaderTextContainerView.getLayoutParams());*/

        int topMargin = resources.getDimensionPixelSize(
                com.android.internal.R.dimen.quick_qs_offset_height) + (mHeaderImageEnabled ?
                mHeaderImageHeight : 0);

        mSystemIconsView.getLayoutParams().height = topMargin;
        mSystemIconsView.setLayoutParams(mSystemIconsView.getLayoutParams());

        addQuickQSPanel();
        if (mBrightnessSlider != 0) {
           if (mIsQsAutoBrightnessEnabled && resources.getBoolean(
                    com.android.internal.R.bool.config_automatic_brightness_available)) {
               ImageView brightnessIcon = (ImageView) mQuickQsBrightness.findViewById(R.id.brightness_icon);
               brightnessIcon.setVisibility(View.VISIBLE);
            } else {
               ImageView brightnessIcon = (ImageView) mQuickQsBrightness.findViewById(R.id.brightness_icon);
               brightnessIcon.setVisibility(View.GONE);
            }
            if (mQuickQsBrightness.getVisibility() == View.GONE) {
                mQuickQsBrightness.setVisibility(View.VISIBLE);
            }
            mMinBrightness.setVisibility(mBrightnessButton ? VISIBLE : GONE);
            mMaxBrightness.setVisibility(mBrightnessButton ? VISIBLE : GONE);
        } else {
            mQuickQsBrightness.setVisibility(View.GONE);
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (mQsDisabled) {
            lp.height = topMargin;
        } else {
            int qsHeight = resources.getDimensionPixelSize(
                    com.android.internal.R.dimen.quick_qs_total_height);

            if (mHeaderImageEnabled) {
                qsHeight += mHeaderImageHeight;
            }

            // always add the margin below the statusbar with or without image
            lp.height = Math.max(getMinimumHeight(), qsHeight);
        }

        setLayoutParams(lp);

        updateStatusIconAlphaAnimator();
        updateHeaderTextContainerAlphaAnimator();
        updatePrivacyChipAlphaAnimator();
    }

    private void updateSettings() {
        Resources resources = mContext.getResources();
        mHeaderImageEnabled = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.OMNI_STATUS_BAR_CUSTOM_HEADER, 0,
                UserHandle.USER_CURRENT) == 1;
        mHeaderImageHeight = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_CUSTOM_HEADER_HEIGHT, 25,
                UserHandle.USER_CURRENT);
        isBattIconQsH = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.QS_BATTERY_LOCATION, 1,
                UserHandle.USER_CURRENT);

        mSysCPUTemp = resources.getString(
                  com.android.internal.R.string.config_sysCPUTemp);
        mSysBatTemp = resources.getString(
                  com.android.internal.R.string.config_sysBatteryTemp);
        mSysGPUFreq = resources.getString(
                  com.android.internal.R.string.config_sysGPUFreq);
        mSysGPULoad = resources.getString(
                  com.android.internal.R.string.config_sysGPULoad);
        mSysCPUTempMultiplier = resources.getInteger(
                  com.android.internal.R.integer.config_sysCPUTempMultiplier);
        mSysBatTempMultiplier = resources.getInteger(
                  com.android.internal.R.integer.config_sysBatteryTempMultiplier);

        mSystemInfoMode = getQsSystemInfoMode();
        updateHeaderImage(mHeaderImageHeight);
        updateQSBatteryMode();
        updateSBBatteryStyle();
        updateSystemInfoText();
        updateResources();
        addQuickQSPanel();
        updateDataUsageView();
        updateLogoSettings();
     }

     private void updateQSBatteryMode() {
        int showEstimate = Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.QS_BATTERY_MODE, 0);
        if (showEstimate == 0) {
            mBatteryRemainingIcon.mShowBatteryPercent = 0;
            mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_OFF);
            mBatteryRemainingIconQsH.mShowBatteryPercent = 0;
            mBatteryRemainingIconQsH.setPercentShowMode(BatteryMeterView.MODE_OFF);
        } else if (showEstimate == 1) {
            mBatteryRemainingIconQsH.mShowBatteryPercent = 0;
            mBatteryRemainingIconQsH.setPercentShowMode(BatteryMeterView.MODE_ON);
            mBatteryRemainingIcon.mShowBatteryPercent = 0;
            mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ON);
        } else if (showEstimate == 2) {
            mBatteryRemainingIcon.mShowBatteryPercent = 1;
            mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_OFF);
            mBatteryRemainingIconQsH.mShowBatteryPercent = 1;
            mBatteryRemainingIconQsH.setPercentShowMode(BatteryMeterView.MODE_OFF);
        } else if (showEstimate == 3 || showEstimate == 4) {
            mBatteryRemainingIcon.mShowBatteryPercent = 0;
            mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
            mBatteryRemainingIconQsH.mShowBatteryPercent = 0;
            mBatteryRemainingIconQsH.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
        }
        if (isBattIconQsH == 1) {
            mBatteryRemainingIconQsH.setVisibility(View.VISIBLE);
            mBatteryRemainingIcon.setVisibility(View.GONE);
	} else if (isBattIconQsH == 0) {
            mBatteryRemainingIcon.setVisibility(View.VISIBLE);
            mBatteryRemainingIconQsH.setVisibility(View.GONE);
        }
        mBatteryRemainingIcon.updatePercentView();
        mBatteryRemainingIcon.updateVisibility();
        mBatteryRemainingIconQsH.updatePercentView();
        mBatteryRemainingIconQsH.updateVisibility();
     }

     private void updateDataUsageView() {
        if (mDataUsageView.isDataUsageEnabled() != 0) {
            if (XtendedUtils.isConnected(mContext)) {
                DataUsageView.updateUsage();
                mDataUsageLayout.setVisibility(View.VISIBLE);
                mDataUsageImage.setVisibility(View.VISIBLE);
                mDataUsageView.setVisibility(View.VISIBLE);
            } else {
                mDataUsageView.setVisibility(View.GONE);
                mDataUsageImage.setVisibility(View.GONE);
                mDataUsageLayout.setVisibility(View.GONE);
            }
        } else {
            mDataUsageView.setVisibility(View.GONE);
            mDataUsageImage.setVisibility(View.GONE);
            mDataUsageLayout.setVisibility(View.GONE);
        }
    }

     private void updateSBBatteryStyle() {
        mBatteryRemainingIcon.mBatteryStyle = Settings.System.getInt(mContext.getContentResolver(),
        Settings.System.STATUS_BAR_BATTERY_STYLE, 0);
        if (isBattIconQsH == 1) {
            mBatteryRemainingIconQsH.setVisibility(View.VISIBLE);
            mBatteryRemainingIcon.setVisibility(View.GONE);
	} else if (isBattIconQsH == 0) {
            mBatteryRemainingIcon.setVisibility(View.VISIBLE);
            mBatteryRemainingIconQsH.setVisibility(View.GONE);
       }
        mBatteryRemainingIcon.updateBatteryStyle();
        mBatteryRemainingIcon.updatePercentView();
        mBatteryRemainingIcon.updateVisibility();
        mBatteryRemainingIconQsH.updateBatteryStyle();
        mBatteryRemainingIconQsH.updatePercentView();
        mBatteryRemainingIconQsH.updateVisibility();
     }

    private void updateStatusIconAlphaAnimator() {
        mStatusIconsAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mQuickQsStatusIcons, "alpha", 1, 0, 0)
                .build();
    }

    private void updateHeaderTextContainerAlphaAnimator() {
        mHeaderTextContainerAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mHeaderTextContainerView, "alpha", 0, 0, 1)
                .build();
    }

    private void updatePrivacyChipAlphaAnimator() {
        mPrivacyChipAlphaAnimator = new TouchAnimator.Builder()
                .addFloat(mPrivacyChip, "alpha", 1, 0, 1)
                .build();
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mHeaderQsPanel.setExpanded(expanded);
        updateSystemInfoText();
        updateEverything();
        updateDataUsageView();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;
        if (mStatusIconsAlphaAnimator != null) {
            mStatusIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }

        if (forceExpanded) {
            // If the keyguard is showing, we want to offset the text so that it comes in at the
            // same time as the panel as it slides down.
            mHeaderTextContainerView.setTranslationY(panelTranslationY);
        } else {
            mHeaderTextContainerView.setTranslationY(0f);
        }

        if (mHeaderTextContainerAlphaAnimator != null) {
            mHeaderTextContainerAlphaAnimator.setPosition(keyguardExpansionFraction);
            if (keyguardExpansionFraction > 0) {
                mHeaderTextContainerView.setVisibility(VISIBLE);
            } else {
                mHeaderTextContainerView.setVisibility(INVISIBLE);
            }
        }
        if (mPrivacyChipAlphaAnimator != null) {
            mPrivacyChip.setExpanded(expansionFraction > 0.5);
            mPrivacyChipAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
        if (mBrightnessSlider != 0) {
            if (keyguardExpansionFraction > 0) {
                mQuickQsBrightness.setVisibility(INVISIBLE);
            } else {
                mQuickQsBrightness.setVisibility(VISIBLE);
            }
        }
        updateSystemInfoText();
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mHeaderTextContainerView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsStatusIcons.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mQuickQsBrightness.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
        addQuickQSPanel();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mStatusBarIconController.addIconGroup(mIconManager);
        requestApplyInsets();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        DisplayCutout cutout = insets.getDisplayCutout();
        Pair<Integer, Integer> padding = PhoneStatusBarView.cornerCutoutMargins(
                cutout, getDisplay());
        int paddingStart = getResources().getDimensionPixelSize(R.dimen.status_bar_padding_start);
        int paddingEnd = getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end);
        if (padding == null) {
            mSystemIconsView.setPaddingRelative(paddingStart, 0, paddingEnd, 0);
        } else {
            mSystemIconsView.setPadding(Math.max(paddingStart, padding.first), 0,
                    Math.max(paddingEnd, padding.second), 0);
        }
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mSpace.getLayoutParams();
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty()) {
                mHasTopCutout = false;
                lp.width = 0;
                mSpace.setVisibility(View.GONE);
            } else {
                mHasTopCutout = true;
                lp.width = topCutout.width();
                mSpace.setVisibility(View.VISIBLE);
            }
        }
        mSpace.setLayoutParams(lp);
        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
        // Offset container padding to align with QS brightness bar.
        final int sp = getResources().getDimensionPixelSize(R.dimen.notification_side_paddings);
        RelativeLayout.LayoutParams lpQuickQsBrightness = (RelativeLayout.LayoutParams)
                mQuickQsBrightness.getLayoutParams();
        lpQuickQsBrightness.setMargins(sp - mPaddingLeft, 0, sp - mPaddingRight, 0);
        mQuickQsBrightness.setLayoutParams(lpQuickQsBrightness);
        addQuickQSPanel();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        mStatusBarIconController.removeIconGroup(mIconManager);
        super.onDetachedFromWindow();
    }

    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mHeaderQsPanel.setListening(listening);
        mListening = listening;
        mCarrierGroup.setListening(mListening);

        if (listening) {
            mZenController.addCallback(this);
            mAlarmController.addCallback(this);
            mContext.registerReceiver(mRingerReceiver,
                    new IntentFilter(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION));
            mPrivacyItemController.addCallback(mPICCallback);
            mBrightnessController.registerCallbacks();
        } else {
            mZenController.removeCallback(this);
            mAlarmController.removeCallback(this);
            mPrivacyItemController.removeCallback(mPICCallback);
            mContext.unregisterReceiver(mRingerReceiver);
            mBrightnessController.unregisterCallbacks();
            mPrivacyChipLogged = false;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mClockView || v == mNextAlarmTextView) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    AlarmClock.ACTION_SHOW_ALARMS), 0);
        } else if (v == mNextAlarmContainer && mNextAlarmContainer.isVisibleToUser()) {
            if (mNextAlarm.getShowIntent() != null) {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mNextAlarm.getShowIntent());
            } else {
                Log.d(TAG, "No PendingIntent for next alarm. Using default intent");
                mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                        AlarmClock.ACTION_SHOW_ALARMS), 0);
            }
        } else if (v == mPrivacyChip) {
            // Makes sure that the builder is grabbed as soon as the chip is pressed
            PrivacyDialogBuilder builder = mPrivacyChip.getBuilder();
            if (builder.getAppsAndTypes().size() == 0) return;
            Handler mUiHandler = new Handler(Looper.getMainLooper());
            StatsLog.write(StatsLog.PRIVACY_INDICATORS_INTERACTED,
                    StatsLog.PRIVACY_INDICATORS_INTERACTED__TYPE__CHIP_CLICKED);
            mUiHandler.post(() -> {
                mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(Intent.ACTION_REVIEW_ONGOING_PERMISSION_USAGE), 0);
                mHost.collapsePanels();
            });
        } else if (v == mRingerContainer && mRingerContainer.isVisibleToUser()) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                    Settings.ACTION_SOUND_SETTINGS), 0);
        } else if (v == mDateView) {
            Uri.Builder builder = CalendarContract.CONTENT_URI.buildUpon();
            builder.appendPath("time");
            builder.appendPath(Long.toString(System.currentTimeMillis()));
            Intent todayIntent = new Intent(Intent.ACTION_VIEW, builder.build());
            mActivityStarter.postStartActivityDismissingKeyguard(todayIntent, 0);
        } else if (v == mBatteryRemainingIcon) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                Intent.ACTION_POWER_USAGE_SUMMARY), 0);
        } else if (v == mBatteryRemainingIconQsH) {
            mActivityStarter.postStartActivityDismissingKeyguard(new Intent(
                Intent.ACTION_POWER_USAGE_SUMMARY), 0);
        } else if (v == mMinBrightness) {
            final ContentResolver resolver = getContext().getContentResolver();
            int currentValue = Settings.System.getIntForUser(resolver,
                    Settings.System.SCREEN_BRIGHTNESS, 0, UserHandle.USER_CURRENT);
            int brightness = currentValue - 2;
            if (currentValue != 0) {
                int math = Math.max(0, brightness);
                Settings.System.putIntForUser(resolver,
                        Settings.System.SCREEN_BRIGHTNESS, math, UserHandle.USER_CURRENT);
            }
        } else if (v == mMaxBrightness) {
            final ContentResolver resolver = getContext().getContentResolver();
            int currentValue = Settings.System.getIntForUser(resolver,
                    Settings.System.SCREEN_BRIGHTNESS, 0, UserHandle.USER_CURRENT);
            int brightness = currentValue + 2;
            if (currentValue != 255) {
                int math = Math.min(255, brightness);
                Settings.System.putIntForUser(resolver,
                        Settings.System.SCREEN_BRIGHTNESS, math, UserHandle.USER_CURRENT);
            }
        }
    }

    @Override
    public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
        mNextAlarm = nextAlarm;
        updateStatusText();
    }

    @Override
    public void onZenChanged(int zen) {
        updateStatusText();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        updateStatusText();
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
    }

    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setupHost(qsPanel.getHost());
    }

    public void setupHost(final QSTileHost host) {
        mHost = host;
        //host.setHeaderView(mExpandIndicator);
        mHeaderQsPanel.setQSPanelAndHeader(mQsPanel, this);
        mHeaderQsPanel.setHost(host, null /* No customization in header */);


        Rect tintArea = new Rect(0, 0, 0, 0);
        int colorForeground = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.colorForeground);
        float intensity = getColorIntensity(colorForeground);
        int fillColor = mDualToneHandler.getSingleColor(intensity);
        mBatteryRemainingIcon.onDarkChanged(tintArea, intensity, fillColor);
        if(mSystemInfoText != null &&  mSystemInfoIcon != null) {
            updateSystemInfoText();
        }
    }

    public void setCallback(Callback qsPanelCallback) {
        mHeaderQsPanel.setCallback(qsPanelCallback);
    }

    private String formatNextAlarm(AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = android.text.format.DateFormat
                .is24HourFormat(mContext, ActivityManager.getCurrentUser()) ? "EHm" : "Ehma";
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), skeleton);
        return android.text.format.DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    public static float getColorIntensity(@ColorInt int color) {
        return color == Color.WHITE ? 0 : 1;
    }

    public void setMargins(int sideMargins) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            // Prevents these views from getting set a margin.
            // The Icon views all have the same padding set in XML to be aligned.
            if (v == mSystemIconsView || v == mQuickQsStatusIcons || v == mHeaderQsPanel
                    || v == mHeaderTextContainerView) {
                continue;
            }
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) v.getLayoutParams();
            lp.leftMargin = sideMargins;
            lp.rightMargin = sideMargins;
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QQS_SHOW_BRIGHTNESS_SLIDER:
                mBrightnessSlider = TunerService.parseInteger(newValue, 2);
                addQuickQSPanel();
                updateResources();
                break;
            case QS_SHOW_AUTO_BRIGHTNESS:
                mIsQsAutoBrightnessEnabled = TunerService.parseIntegerSwitch(newValue, true);
                updateResources();
                break;
            case QS_SHOW_BRIGHTNESS_BUTTONS:
                mBrightnessButton = TunerService.parseIntegerSwitch(newValue, true);
                updateResources();
                break;
            case QSFooterImpl.QS_SHOW_DRAG_HANDLE:
                mHideDragHandle =
                        TunerService.parseIntegerSwitch(newValue, true);
                updateResources();
                break;
            default:
                break;
        }
    }

    private void updateHeaderImage(int height) {
        switch (height) {
            case 0:
                mHeaderImageHeight = 0;
                break;
            case 1:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_1);
                break;
            case 2:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_2);
                break;
            case 3:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_3);
                break;
            case 4:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_4);
                break;
            case 5:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_5);
                break;
            case 6:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_6);
                break;
            case 7:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_7);
                break;
            case 8:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_8);
                break;
            case 9:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_9);
                break;
            case 10:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_10);
                break;
            case 11:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_11);
                break;
            case 12:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_12);
                break;
            case 13:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_13);
                break;
            case 14:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_14);
                break;
            case 15:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_15);
                break;
            case 16:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_16);
                break;
            case 17:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_17);
                break;
            case 18:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_18);
                break;
            case 19:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_19);
                break;
            case 20:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_20);
                break;
            case 21:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_21);
                break;
            case 22:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_22);
                break;
            case 23:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_23);
                break;
            case 24:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_24);
                break;
            case 25:
            default:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_25);
                break;
            case 26:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_26);
                break;
            case 27:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_27);
                break;
            case 28:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_28);
                break;
            case 29:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_29);
                break;
            case 30:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_30);
                break;
            case 31:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_31);
                break;
            case 32:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_32);
                break;
            case 33:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_33);
                break;
            case 34:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_34);
                break;
            case 35:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_35);
                break;
            case 36:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_36);
                break;
            case 37:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_37);
                break;
            case 38:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_38);
                break;
            case 39:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_39);
                break;
            case 40:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_40);
                break;
            case 41:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_41);
                break;
            case 42:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_42);
                break;
            case 43:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_43);
                break;
            case 44:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_44);
                break;
            case 45:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_45);
                break;
            case 46:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_46);
                break;
            case 47:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_47);
                break;
            case 48:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_48);
                break;
            case 49:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_49);
                break;
            case 50:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_date_font_size_50);
                break;
            case 51:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_51);
                break;
            case 52:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_52);
                break;
            case 53:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_53);
                break;
            case 54:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_54);
                break;
            case 55:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_55);
                break;
            case 56:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_56);
                break;
            case 57:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_57);
                break;
            case 58:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_58);
                break;
            case 59:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_59);
                break;
            case 60:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_60);
                break;
            case 61:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_61);
                break;
            case 62:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_62);
                break;
            case 63:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_63);
                break;
            case 64:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_64);
                break;
            case 65:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_65);
                break;
            case 66:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_66);
                break;
            case 67:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_67);
                break;
            case 68:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_68);
                break;
            case 69:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_69);
                break;
            case 70:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_70);
                break;
            case 71:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_71);
                break;
            case 72:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_72);
                break;
            case 73:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_73);
                break;
            case 74:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_74);
                break;
            case 75:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_75);
                break;
            case 76:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_76);
                break;
            case 77:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_77);
                break;
            case 78:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_78);
                break;
            case 79:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_79);
                break;
            case 80:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_80);
                break;
            case 81:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_81);
                break;
            case 82:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_82);
                break;
            case 83:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_83);
                break;
            case 84:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_84);
                break;
            case 85:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_85);
                break;
            case 86:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_86);
                break;
            case 87:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_87);
                break;
            case 88:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_88);
                break;
            case 89:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_89);
                break;
            case 90:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_90);
                break;
            case 91:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_91);
                break;
            case 92:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_92);
                break;
            case 93:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_93);
                break;
            case 94:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_94);
                break;
            case 95:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_95);
                break;
            case 96:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_96);
                break;
            case 97:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_97);
                break;
            case 98:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_98);
                break;
            case 99:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_99);
                break;
            case 100:
                mHeaderImageHeight = mContext.getResources().getDimensionPixelSize(R.dimen.lock_clock_font_size_100);
                break;
        }
    }

    public void updateLogoSettings() {
        Drawable logo = null;

        if (mContext == null) {
            return;
        }
        mShowLogo = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_PANEL_LOGO, 0,
                UserHandle.USER_CURRENT);
        mLogoColor = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_PANEL_LOGO_COLOR, 0xffff8800,
                UserHandle.USER_CURRENT);
        mLogoStyle = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.QS_PANEL_LOGO_STYLE, 0,
                UserHandle.USER_CURRENT);

        switch(mLogoStyle) {
                // Xtnd Old
            case 1:
                logo = mContext.getDrawable(R.drawable.ic_xtnd_logo);
                break;
                // XTND Short
            case 2:
                logo = mContext.getDrawable(R.drawable.ic_xtnd_short);
                break;
                // GZR Skull
            case 3:
                logo = mContext.getResources().getDrawable(R.drawable.status_bar_gzr_skull_logo);
                break;
                // GZR Circle
            case 4:
                logo = mContext.getResources().getDrawable(R.drawable.status_bar_gzr_circle_logo);
                break;
                // Batman
            case 5:
                logo = mContext.getDrawable(R.drawable.ic_batman_logo);
                break;
                // Deadpool
            case 6:
                logo = mContext.getDrawable(R.drawable.ic_deadpool_logo);
                break;
                // Superman
            case 7:
                logo = mContext.getDrawable(R.drawable.ic_superman_logo);
                break;
                // Ironman
            case 8:
                logo = mContext.getDrawable(R.drawable.ic_ironman_logo);
                break;
                // Spiderman
            case 9:
                logo = mContext.getDrawable(R.drawable.ic_spiderman_logo);
                break;
                // Decepticons
            case 10:
                logo = mContext.getDrawable(R.drawable.ic_decpeticons_logo);
                break;
                // Minions
            case 11:
                logo = mContext.getDrawable(R.drawable.ic_minions_logo);
                break;
            case 12:
                logo = mContext.getDrawable(R.drawable.ic_android_logo);
                break;
                // Shit
            case 13:
                logo = mContext.getDrawable(R.drawable.ic_apple_logo);
                break;
                // Shitty Logo
            case 14:
                logo = mContext.getDrawable(R.drawable.ic_ios_logo);
                break;
                // Others
            case 15:
                logo = mContext.getDrawable(R.drawable.ic_blackberry);
                break;
            case 16:
                logo = mContext.getDrawable(R.drawable.ic_cake);
                break;
            case 17:
                logo = mContext.getDrawable(R.drawable.ic_blogger);
                break;
            case 18:
                logo = mContext.getDrawable(R.drawable.ic_biohazard);
                break;
            case 19:
                logo = mContext.getDrawable(R.drawable.ic_linux);
                break;
            case 20:
                logo = mContext.getDrawable(R.drawable.ic_yin_yang);
                break;
            case 21:
                logo = mContext.getDrawable(R.drawable.ic_windows);
                break;
            case 22:
                logo = mContext.getDrawable(R.drawable.ic_robot);
                break;
            case 23:
                logo = mContext.getDrawable(R.drawable.ic_ninja);
                break;
            case 24:
                logo = mContext.getDrawable(R.drawable.ic_heart);
                break;
            case 25:
                logo = mContext.getDrawable(R.drawable.ic_ghost);
                break;
            case 26:
                logo = mContext.getDrawable(R.drawable.ic_google);
                break;
            case 27:
                logo = mContext.getDrawable(R.drawable.ic_human_male);
                break;
            case 28:
                logo = mContext.getDrawable(R.drawable.ic_human_female);
                break;
            case 29:
                logo = mContext.getDrawable(R.drawable.ic_human_male_female);
                break;
            case 30:
                logo = mContext.getDrawable(R.drawable.ic_gender_male);
                break;
            case 31:
                logo = mContext.getDrawable(R.drawable.ic_gender_female);
                break;
            case 32:
                logo = mContext.getDrawable(R.drawable.ic_gender_male_female);
                break;
            case 33:
                logo = mContext.getDrawable(R.drawable.ic_guitar_electric);
                break;
            case 34:
                logo = mContext.getDrawable(R.drawable.ic_emoticon);
                break;
            case 35:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_neutral);
                break;
            case 36:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_happy);
                break;
            case 37:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_sad);
                break;
            case 38:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_tongue);
                break;
            case 39:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_dead);
                break;
            case 40:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_cool);
                break;
            case 41:
                logo = mContext.getDrawable(R.drawable.ic_emoticon_devil);
                break;
            case 0:
            default: // Default (Xtended Main)
                logo = mContext.getDrawable(R.drawable.status_bar_logo);
                break;
        }
        if (mShowLogo == 1) {
	    mXtendedLogoRight.setImageDrawable(null);
            mXtendedLogoRight.setVisibility(View.GONE);
            mXtendedLogo.setVisibility(View.VISIBLE);
            mXtendedLogo.setImageDrawable(logo);
            mXtendedLogo.setColorFilter(mLogoColor, PorterDuff.Mode.MULTIPLY);
	} else if (mShowLogo == 2) {
            mXtendedLogo.setImageDrawable(null);
            mXtendedLogo.setVisibility(View.GONE);
            mXtendedLogoRight.setVisibility(View.VISIBLE);
	    mXtendedLogoRight.setImageDrawable(logo);
	    mXtendedLogoRight.setColorFilter(mLogoColor, PorterDuff.Mode.MULTIPLY);
	} else {
            mXtendedLogo.setImageDrawable(null);
            mXtendedLogo.setVisibility(View.GONE);
            mXtendedLogoRight.setImageDrawable(null);
            mXtendedLogoRight.setVisibility(View.GONE);
        }
    }
}
