/*
 * Copyright (C) 2014 The Android Open Source Project
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

package ie.udaltsoft.musicwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Wearable;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class MusicWatchFace extends CanvasWatchFaceService {

    private static final String TAG = "MusicWatchFaceConfig";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
    GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        static final int MSG_UPDATE_TIME = 0;

        // Hands
        static final float HOUR_HAND_RATIO = 0.95f;
        static final float MINUTE_HAND_RATIO = 1.15f;
        // Where is the nail on the hands?
        static final float NAIL_RATIO = 0.7f;

        // 12 o'clock size
        static final float MARK12_RATIO = 0.4f;
        // 3, 6, 9 o'clock mark size
        static final float MARK_RATIO = 0.2f;
        // offset of marks from the border
        static final float MARK_OFFSET_RATIO = 0.1f;
        // 1, 2, 4, 5, 7, 8, 10, 11 o'clock size
        static final float MARK_HOUR_RATIO = 0.04f;

        static final float STAFF_X_RATIO_START = 0.2f;
        static final float STAFF_X_RATIO_END = 0.55f;

        static final float STAFF_Y_RATIO_START = 0.45f;
        static final float STAFF_Y_RATIO_END = 0.75f;
        // 0.5 0.575 0.65 0.725 0.8
        // battery note size
        static final float MARK_NOTE_RATIO = (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4;

        final double HOUR_ANGELS[] = new double[] {
                30 * Math.PI / 180,
                60 * Math.PI / 180,
                120 * Math.PI / 180,
                150 * Math.PI / 180,
                210 * Math.PI / 180,
                240 * Math.PI / 180,
                300 * Math.PI / 180,
                330 * Math.PI / 180
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MusicWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        Paint mBackgroundPaint;
        Paint mBackgroundPaintAmbient;
        Paint mStaffPaint;

        Paint mHandPaint;
        boolean mAmbient;
        GregorianCalendar mTime;

        PointF center;

        float secLength;

        private SVG hourHandSvg;
        private SVG minuteHandSvg;
        private SVG ambientHourHandSvg;
        private SVG ambientMinuteHandSvg;

        private PointF hourRotationPoint;
        private PointF minuteRotationPoint;

        private PointF markBounds;
        private PointF mark12Bounds;
        private PointF markHourBounds;
        private PointF markNoteBounds;

        private RectF hourHandRect;
        private RectF minuteHandRect;

        private DateFormat mDateFormat = new SimpleDateFormat("EEE, MMM d");
        private NumberFormat mBatteryFormat = NumberFormat.getPercentInstance();


        /**
         * Handler to update the time once a second in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.setTimeZone(TimeZone.getTimeZone(intent.getStringExtra("time-zone")));
            }
        };
        boolean mRegisteredTimeZoneReceiver = false;

        final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Intent batteryStatus = MusicWatchFace.this.registerReceiver(null, batFilter);
                try {
                    final int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    final int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    batteryPct = level / (float)scale;
                } catch (NullPointerException ex) {
                    batteryPct = 0f;
                }
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private SVG threeOCSvg;
        private SVG sixOCSvg;
        private SVG nineOCSvg;
        private SVG twelveOCSvg;

        private SVG hourSvg;
        private SVG noteSvg;

        private float[] scales;
        private Bitmap[] majorBitmap;
        private PointF[] markHourLocations;
        private boolean isRound;
        private float batteryPct;

        private IntentFilter batFilter;
        private IntentFilter tzFilter;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MusicWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = MusicWatchFace.this.getResources();

            mBackgroundPaintAmbient = new Paint();
            mBackgroundPaintAmbient.setColor(resources.getColor(R.color.analog_background_ambient));

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.analog_background));

            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);
            mHandPaint.setTextSize(24);

            mStaffPaint = new Paint();
            mStaffPaint.setColor(resources.getColor(R.color.analog_hands));
            mStaffPaint.setStrokeWidth(resources.getDimension(R.dimen.staff_stroke));
            mStaffPaint.setAntiAlias(true);
            mStaffPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new GregorianCalendar();

            try {
                createViolinHands();

                threeOCSvg = SVG.getFromResource(getResources(), R.raw.three_oc);
                sixOCSvg = SVG.getFromResource(getResources(), R.raw.six_oc);
                nineOCSvg = SVG.getFromResource(getResources(), R.raw.nine_oc);
                twelveOCSvg = SVG.getFromResource(getResources(), R.raw.twelve_oc);

                hourSvg = SVG.getFromResource(getResources(), R.raw.hour);

                noteSvg = SVG.getFromResource(getResources(), R.raw.note);
            } catch (SVGParseException ex) {
                ex.printStackTrace();
            }
            initFormats();
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, MMM d");
            mBatteryFormat = NumberFormat.getPercentInstance();
        }

        private void createUkeHands() throws SVGParseException {
            hourHandSvg = SVG.getFromResource(getResources(), R.raw.uke_hour_hand);
            minuteHandSvg = SVG.getFromResource(getResources(), R.raw.uke_minute_hand);
            ambientHourHandSvg = SVG.getFromResource(getResources(), R.raw.uke_ambient_hand);
            ambientMinuteHandSvg = SVG.getFromResource(getResources(), R.raw.uke_ambient_hand);
        }

        private void createViolinHands() throws SVGParseException {
            hourHandSvg = SVG.getFromResource(getResources(), R.raw.violin_hour_hand);
            minuteHandSvg = SVG.getFromResource(getResources(), R.raw.violin_minute_hand);
            ambientHourHandSvg = SVG.getFromResource(getResources(), R.raw.violin_ambient_hour_hand);
            ambientMinuteHandSvg = SVG.getFromResource(getResources(), R.raw.violin_ambient_minute_hand);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets wi) {
            isRound = wi.isRound();
            markHourLocations = null;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            Date now = new Date();
            //now.setHours(19);now.setMinutes(50);
            mTime.setTime(now);

            // Draw the background.
            if (mAmbient)
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaintAmbient);
            else
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            // 12
            canvas.drawBitmap(majorBitmap[0],
                    center.x - twelveOCSvg.getDocumentWidth() * scales[0] / 2f,
                    center.y * MARK_OFFSET_RATIO,
                    null);

            // 3
            canvas.drawBitmap(majorBitmap[1],
                    center.x * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.x - threeOCSvg.getDocumentWidth() * scales[1],
                    center.y - threeOCSvg.getDocumentHeight() * scales[1] / 2f,
                    null);

            // 6
            canvas.drawBitmap(majorBitmap[2],
                    center.x - sixOCSvg.getDocumentWidth() * scales[2] / 2f,
                    center.y * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.y - sixOCSvg.getDocumentHeight() * scales[2],
                    null);

            // 9
            canvas.drawBitmap(majorBitmap[3],
                    center.x * MARK_OFFSET_RATIO,
                    center.y - nineOCSvg.getDocumentHeight() * scales[3] / 2f,
                    null);

            displayDate(canvas, now);

            if (!mAmbient) {
                displayBattery(canvas);

                // delayed calculation
                if (markHourLocations == null) {
                    calcMarkHourLocations(majorBitmap[4]);
                }

                for (PointF markHourLocation : markHourLocations) {
                    canvas.drawBitmap(majorBitmap[4], markHourLocation.x, markHourLocation.y, null);
                }

                // second hand
                float secRot = mTime.get(GregorianCalendar.SECOND) / 30f * (float) Math.PI;

                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                canvas.drawLine(center.x, center.y, center.x + secX, center.y + secY, mHandPaint);

                // minute hand
                canvas.save();
                renderHand(canvas,
                        mTime.get(GregorianCalendar.MINUTE) * 6,
                        MINUTE_HAND_RATIO,
                        minuteHandRect,
                        minuteRotationPoint,
                        minuteHandSvg);
                canvas.restore();

                // hour hand
                canvas.save();
                renderHand(canvas,
                        (mTime.get(GregorianCalendar.HOUR) + (mTime.get(GregorianCalendar.MINUTE) / 60f)) * 30,
                        HOUR_HAND_RATIO,
                        hourHandRect,
                        hourRotationPoint,
                        hourHandSvg);
                canvas.restore();
            } else {
                // minute hand
                canvas.save();
                renderHand(canvas,
                        mTime.get(GregorianCalendar.MINUTE) * 6,
                        MINUTE_HAND_RATIO,
                        minuteHandRect,
                        minuteRotationPoint,
                        ambientMinuteHandSvg);
                canvas.restore();

                // hour hand
                canvas.save();
                renderHand(canvas,
                        (mTime.get(GregorianCalendar.HOUR) + (mTime.get(GregorianCalendar.MINUTE) / 60f)) * 30,
                        HOUR_HAND_RATIO,
                        hourHandRect,
                        hourRotationPoint,
                        ambientHourHandSvg);
                canvas.restore();
            }
        }

        private void renderHand(Canvas canvas,
                                float angle,
                                float handScale,
                                RectF rect,
                                PointF rotationPoint,
                                SVG svg)
        {
            final PointF p = new PointF(center.x - rotationPoint.x,
                                      center.y - rotationPoint.y);
            canvas.translate(p.x, p.y);
            canvas.rotate(angle, rotationPoint.x, rotationPoint.y);
            svg.renderToCanvas(canvas, rect);
        }

        private void displayDate(Canvas canvas, Date d) {
            final String dateFormatted = mDateFormat.format(d);

            final Rect bounds = new Rect();
            mHandPaint.getTextBounds(dateFormatted, 0, dateFormatted.length(), bounds);
            canvas.drawText(dateFormatted, center.x - bounds.width() / 2f,
                    center.y * 3 / 2 + bounds.height() / 2f, mHandPaint);
        }

        private void displayBattery(Canvas canvas) {
            final Bitmap noteBmp = majorBitmap[5];

            final float xmin = center.x * (1 + STAFF_X_RATIO_START);
            final float xmax = center.x * (1 + STAFF_X_RATIO_END);
            final float ymin = center.y * STAFF_Y_RATIO_START;
            final float ymax = center.y * STAFF_Y_RATIO_END;
            float ycur = ymin;
            final float ystep = center.y * (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4;
            for (int i = 0;i<5;i++) {
                canvas.drawLine(xmin,
                        ycur,
                        xmax,
                        ycur,
                        mStaffPaint);
                ycur += ystep;
            }
            final int watchBatteryNoteLevel = (int)Math.floor(this.batteryPct * 10);
            float ynote = ymax - ystep * (watchBatteryNoteLevel / 2f);
            canvas.drawBitmap(noteBmp,
                    xmin + (xmax - xmin) * 0.5f - noteBmp.getWidth() / 2,
                    ynote, mStaffPaint);

            // TODO
            /*final int phoneBatteryNoteLevel = 2;
            ynote = ymax - ystep * (phoneBatteryNoteLevel / 2f);
            canvas.drawBitmap(noteBmp,
                    xmin + (xmax - xmin)*0.75f - noteBmp.getWidth()/2,
                    ynote, mStaffPaint);*/

        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                mTime.setTimeZone(TimeZone.getDefault());

                initFormats();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            tzFilter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MusicWatchFace.this.registerReceiver(mTimeZoneReceiver, tzFilter);

            batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            MusicWatchFace.this.registerReceiver(mBatteryReceiver, batFilter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MusicWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
            MusicWatchFace.this.unregisterReceiver(mBatteryReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            center = new PointF (width / 2f, height / 2f);

            secLength = center.x - 20;

            hourRotationPoint = new PointF(center.x * HOUR_HAND_RATIO / 2,
                    center.y * HOUR_HAND_RATIO * NAIL_RATIO);
            minuteRotationPoint = new PointF(center.x * MINUTE_HAND_RATIO / 2,
                    center.y * MINUTE_HAND_RATIO * NAIL_RATIO);

            hourHandRect = new RectF(0, 0,
                                     center.x * HOUR_HAND_RATIO,
                                     center.y * HOUR_HAND_RATIO);

            minuteHandRect = new RectF(0, 0,
                                       center.x * MINUTE_HAND_RATIO,
                                       center.y * MINUTE_HAND_RATIO);

            markBounds = new PointF(MARK_RATIO * center.x, MARK_RATIO * center.y);
            mark12Bounds = new PointF(MARK12_RATIO * center.x, MARK12_RATIO * center.y);
            markHourBounds = new PointF(MARK_HOUR_RATIO * center.x, MARK_HOUR_RATIO * center.y);
            markNoteBounds = new PointF(MARK_NOTE_RATIO * center.x, MARK_NOTE_RATIO * center.y);

            createBitmapsFromSvgs();

            markHourLocations = null;
        }

        private void createBitmapsFromSvgs() {
            majorBitmap = new Bitmap[6];
            scales = new float[6];

            createBitmapFromSvg(twelveOCSvg, mark12Bounds, 0, false, false);
            createBitmapFromSvg(threeOCSvg, markBounds, 1, false, false);
            createBitmapFromSvg(sixOCSvg, markBounds, 2, false, false);
            createBitmapFromSvg(nineOCSvg, markBounds, 3, false, false);
            createBitmapFromSvg(hourSvg, markHourBounds, 4, false, false);
            createBitmapFromSvg(noteSvg, markNoteBounds, 5, false, true);
        }

        private void calcMarkHourLocations(Bitmap bitmap) {
            markHourLocations = new PointF[8];
            final float hourRatio = isRound ? MARK_HOUR_RATIO : 0;
            final float offset = 1 - hourRatio - MARK_OFFSET_RATIO;
            final PointF halfSize = new PointF(bitmap.getWidth()/2f, bitmap.getHeight()/2f);
            for (int i=0; i<HOUR_ANGELS.length; i++) {
                markHourLocations[i] = new PointF(center.x * (float) (1 + Math.sin(HOUR_ANGELS[i]) * offset) - halfSize.x,
                        center.y * (float) (1 - Math.cos(HOUR_ANGELS[i]) * offset) - halfSize.y);
            }
        }

        private void createBitmapFromSvg(SVG svg, PointF bounds, int idx, boolean isForcedX, boolean isForcedY) {
            scales[idx] = isForcedX ? bounds.x / svg.getDocumentWidth() :
                    isForcedY ? bounds.y / svg.getDocumentHeight() :
                            Math.min(bounds.x / svg.getDocumentWidth(), bounds.y / svg.getDocumentHeight());

            majorBitmap[idx] = Bitmap.createBitmap(Math.round(svg.getDocumentWidth() * scales[idx]),
                    Math.round(svg.getDocumentHeight() * scales[idx]),
                    Bitmap.Config.ARGB_8888);

            Canvas canvas = new Canvas(majorBitmap[idx]);
            canvas.scale(scales[idx], scales[idx]);
            svg.renderToCanvas(canvas);
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            updateConfigDataItemAndUiOnStartup();
        }

        private void updateConfigDataItemAndUiOnStartup() {
            MusicWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient,
                    new MusicWatchFaceUtil.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.
                            setDefaultValuesForMissingConfigKeys(startupConfig);
                            MusicWatchFaceUtil.putConfigDataItem(mGoogleApiClient, startupConfig);

                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }

        private void updateUiForConfigDataMap(DataMap config) {
            boolean uiUpdated = false;
            for (String configKey : config.keySet()) {
                if (!config.containsKey(configKey)) {
                    continue;
                }
                if (configKey.equals(MusicWatchFaceUtil.KEY_INSTRUMENT)) {
                    setInstrument(config.getString(configKey));
                    uiUpdated = true;
                }
            }
            if (uiUpdated) {
                invalidate();
            }

        }

        private void setInstrument(String instrument) {
            try {
                if (getResources().getString(R.string.instrument_uke).equals(instrument))
                    createUkeHands();
                else if (getResources().getString(R.string.instrument_violin).equals(instrument))
                    createViolinHands();
            } catch (SVGParseException e) {
                e.printStackTrace();
            }
        }

        private void setDefaultValuesForMissingConfigKeys(DataMap config) {
            if (!config.containsKey(MusicWatchFaceUtil.KEY_INSTRUMENT)) {
                config.putString(MusicWatchFaceUtil.KEY_INSTRUMENT, MusicWatchFaceUtil.INSTRUMENT_DEFAULT);
            }
        }

        @Override
        public void onConnectionSuspended(int cause) {
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
        }
    }
}
