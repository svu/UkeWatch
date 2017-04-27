/*
 * Copyright (C) 2015 Sergey Udaltsov
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions
 *    and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 *    conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package ie.udaltsoft.musicwatch;

import android.annotation.SuppressLint;
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
import android.support.annotation.NonNull;
import android.support.v4.content.res.ResourcesCompat;
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
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class MusicWatchFace extends CanvasWatchFaceService {

    private static final String TAG = "MusicWatchFace";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {
        static final int MSG_UPDATE_TIME = 0;

        // Hands
        static final float HOUR_HAND_RATIO = 0.90f;
        static final float MINUTE_HAND_RATIO = 1.15f;
        // Where is the nail on the hands?
        static final float NAIL_RATIO = 0.7f;

        // 12 o'clock size
        static final float MARK12_RATIO = 0.35f;
        // 3, 6, 9 o'clock mark size
        static final float MARK_RATIO = 0.18f;
        // offset of marks from the border
        static final float MARK_OFFSET_RATIO = 0.1f;
        // 1, 2, 4, 5, 7, 8, 10, 11 o'clock size
        static final float MARK_HOUR_RATIO = 0.04f;

        static final float STAFF_X_RATIO_START = 0.35f;
        static final float STAFF_X_RATIO_END = 0.55f;

        static final float STAFF_Y_RATIO_START = 0.45f;
        static final float STAFF_Y_RATIO_END = 0.75f;
        // 0.5 0.575 0.65 0.725 0.8
        // battery note size
        static final float MARK_NOTE_RATIO = (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4;

        final double HOUR_ANGLES[] = new double[]{
                30 * Math.PI / 180,
                60 * Math.PI / 180,
                120 * Math.PI / 180,
                150 * Math.PI / 180,
                210 * Math.PI / 180,
                240 * Math.PI / 180,
                300 * Math.PI / 180,
                330 * Math.PI / 180
        };

        private final GoogleApiClient mGoogleApiClient;

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

        private DateFormat mDateFormat = new SimpleDateFormat("EEE, MMM d", Locale.getDefault());

        /**
         * Handler to update the time once a second in interactive mode.
         */
        @SuppressLint("HandlerLeak")
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

                if (batteryStatus != null) {
                    final int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    final int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    batteryPct = (scale == 0) ? 0 : level / (float) scale;
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
        private String mHourInstrument;
        private String mMinuteInstrument;

        private Engine() {
            mGoogleApiClient = new GoogleApiClient.Builder(MusicWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mHourInstrument = MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT;
            mMinuteInstrument = MusicWatchFaceUtil.MINUTE_INSTRUMENT_DEFAULT;

            tzFilter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            batFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MusicWatchFace.this)
                    .build());

            final Resources resources = MusicWatchFace.this.getResources();

            mBackgroundPaintAmbient = new Paint();
            mBackgroundPaintAmbient.setColor(ResourcesCompat.getColor(resources, R.color.analog_background_ambient, null));

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ResourcesCompat.getColor(resources, R.color.analog_background, null));

            mHandPaint = new Paint();
            mHandPaint.setColor(ResourcesCompat.getColor(resources, R.color.analog_hands, null));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);
            mHandPaint.setTextSize(24);

            mStaffPaint = new Paint();
            mStaffPaint.setColor(ResourcesCompat.getColor(resources, R.color.analog_hands, null));
            mStaffPaint.setStrokeWidth(resources.getDimension(R.dimen.staff_stroke));
            mStaffPaint.setAntiAlias(true);
            mStaffPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new GregorianCalendar();

            try {
                createHands();

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

            Log.d(TAG, "=== Loading all config bitmaps ===");
            MusicWatchFaceConfigActivity.buildAllBitmaps(getResources(), getApplicationContext());
            Log.d(TAG, "=== Done loading all config bitmaps ===");
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat(getResources().getString(R.string.date_format), Locale.getDefault());
        }

        private SVG createHand(MusicWatchFaceUtil.HandKind kind, boolean ambient) throws SVGParseException {
            int id = getResources().getIdentifier((kind == MusicWatchFaceUtil.HandKind.HOUR ?
                    mHourInstrument : mMinuteInstrument) +
                    (ambient ? "_ambient" : "") + "_hand", "raw", getPackageName());
            return id == 0 ? null : SVG.getFromResource(getResources(), id);
        }

        private void createHands() throws SVGParseException {
            hourHandSvg = createHand(MusicWatchFaceUtil.HandKind.HOUR, false);
            minuteHandSvg = createHand(MusicWatchFaceUtil.HandKind.MINUTE, false);
            ambientHourHandSvg = createHand(MusicWatchFaceUtil.HandKind.HOUR, true);
            ambientMinuteHandSvg = createHand(MusicWatchFaceUtil.HandKind.MINUTE, true);
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
                        minuteHandRect,
                        minuteRotationPoint,
                        minuteHandSvg);
                canvas.restore();

                // hour hand
                canvas.save();
                renderHand(canvas,
                        (mTime.get(GregorianCalendar.HOUR) + (mTime.get(GregorianCalendar.MINUTE) / 60f)) * 30,
                        hourHandRect,
                        hourRotationPoint,
                        hourHandSvg);
                canvas.restore();
            } else {
                // minute hand
                canvas.save();
                renderHand(canvas,
                        mTime.get(GregorianCalendar.MINUTE) * 6,
                        minuteHandRect,
                        minuteRotationPoint,
                        ambientMinuteHandSvg);
                canvas.restore();

                // hour hand
                canvas.save();
                renderHand(canvas,
                        (mTime.get(GregorianCalendar.HOUR) + (mTime.get(GregorianCalendar.MINUTE) / 60f)) * 30,
                        hourHandRect,
                        hourRotationPoint,
                        ambientHourHandSvg);
                canvas.restore();
            }
        }

        private void renderHand(Canvas canvas,
                                float angle,
                                RectF rect,
                                PointF rotationPoint,
                                SVG svg) {
            if (svg == null)
                return;
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
            for (int i = 0; i < 5; i++) {
                canvas.drawLine(xmin,
                        ycur,
                        xmax,
                        ycur,
                        mStaffPaint);
                ycur += ystep;
            }
            final int watchBatteryNoteLevel = (int) Math.floor(this.batteryPct * 10);
            float ynote = ymax - ystep * (watchBatteryNoteLevel / 2f);
            canvas.drawBitmap(noteBmp,
                    xmin + (xmax - xmin) * 0.5f - noteBmp.getWidth() / 2,
                    ynote, mStaffPaint);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceivers();

                mTime.setTimeZone(TimeZone.getDefault());

                initFormats();
            } else {
                unregisterReceivers();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceivers() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            MusicWatchFace.this.registerReceiver(mTimeZoneReceiver, tzFilter);
            MusicWatchFace.this.registerReceiver(mBatteryReceiver, batFilter);
        }

        private void unregisterReceivers() {
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
            center = new PointF(width / 2f, height / 2f);

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

            createBitmapFromSvg(twelveOCSvg, mark12Bounds, 0, false);
            createBitmapFromSvg(threeOCSvg, markBounds, 1, false);
            createBitmapFromSvg(sixOCSvg, markBounds, 2, false);
            createBitmapFromSvg(nineOCSvg, markBounds, 3, false);
            createBitmapFromSvg(hourSvg, markHourBounds, 4, false);
            createBitmapFromSvg(noteSvg, markNoteBounds, 5, true);
        }

        private void calcMarkHourLocations(Bitmap bitmap) {
            markHourLocations = new PointF[8];
            final float hourRatio = isRound ? MARK_HOUR_RATIO : 0;
            final float offset = 1 - hourRatio - MARK_OFFSET_RATIO;
            final PointF halfSize = new PointF(bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
            for (int i = 0; i < HOUR_ANGLES.length; i++) {
                markHourLocations[i] = new PointF(center.x * (float) (1 + Math.sin(HOUR_ANGLES[i]) * offset) - halfSize.x,
                        center.y * (float) (1 - Math.cos(HOUR_ANGLES[i]) * offset) - halfSize.y);
            }
        }

        private void createBitmapFromSvg(SVG svg, PointF bounds, int idx, boolean isForcedY) {
            scales[idx] = isForcedY ? bounds.y / svg.getDocumentHeight() :
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

        private void updateConfigDataItemAndUiOnStartup() {
            MusicWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient,
                    new MusicWatchFaceUtil.FetchConfigDataMapCallback() {
                        @Override
                        public void onConfigDataMapFetched(DataMap startupConfig) {
                            // If the DataItem hasn't been created yet or some keys are missing,
                            // use the default values.

                            final String initialHourInstrument = startupConfig.getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT);
                            final String initialMinuteInstrument = startupConfig.getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT);
                            Log.d(TAG, "Fetched startup config: " + initialHourInstrument + "/" + initialMinuteInstrument);
                            MusicWatchFaceUtil.setDefaultValuesForMissingConfigKeys(startupConfig);

                            if (initialHourInstrument == null || initialMinuteInstrument == null) {
                                Log.d(TAG, "Completing config initialization with: " +
                                        startupConfig.getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT) + "/" +
                                        startupConfig.getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT));
                                MusicWatchFaceUtil.putConfigDataItem(mGoogleApiClient, startupConfig);
                            }

                            updateUiForConfigDataMap(startupConfig);
                        }
                    }
            );
        }

        private void updateUiForConfigDataMap(DataMap config) {
            if (config.containsKey(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT)) {
                final String instrument = config.get(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT);
                if (mHourInstrument == null || !mHourInstrument.equals(instrument)) {
                    setHourInstrument(instrument);
                    Log.d(TAG, "Invalidating after new hour instrument: " + instrument);
                    invalidate();
                } else {
                    Log.d(TAG, "Same hour instrument, no change in config");
                }
            }
            if (config.containsKey(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT)) {
                final String instrument = config.get(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT);
                if (mMinuteInstrument == null || !mMinuteInstrument.equals(instrument)) {
                    setMinuteInstrument(instrument);
                    Log.d(TAG, "Invalidating after new minute instrument: " + instrument);
                    invalidate();
                } else {
                    Log.d(TAG, "Same minute instrument, no change in config");
                }
            }
        }

        private void setHourInstrument(String instrument) {
            try {
                if (mHourInstrument == null || !mHourInstrument.equals(instrument)) {
                    mHourInstrument = instrument;
                    Log.i(TAG, "Hands to be created for hand instrument: " + instrument);
                    createHands();
                }
            } catch (SVGParseException e) {
                e.printStackTrace();
            }
        }

        private void setMinuteInstrument(String instrument) {
            try {
                if (mMinuteInstrument == null || !mMinuteInstrument.equals(instrument)) {
                    mMinuteInstrument = instrument;
                    Log.i(TAG, "Hands to be created for minute instrument: " + instrument);
                    createHands();
                }
            } catch (SVGParseException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        MusicWatchFaceUtil.PATH_WITH_FEATURE)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();

                updateUiForConfigDataMap(config);
            }
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            updateConfigDataItemAndUiOnStartup();
        }

        @Override
        public void onConnectionSuspended(int cause) {
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        }
    }
}
