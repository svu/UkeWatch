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

package ie.udaltsoft.ukewatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;


import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import ie.udaltsoft.ukewatch.R;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class UkeWatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        static final int MSG_UPDATE_TIME = 0;

        static final float HOUR_HAND_SCALE = 0.95f;
        static final float MINUTE_HAND_SCALE = 1.15f;

        static final float NAIL_RATIO = 0.7f;

        static final float MARK12_RATIO = 0.5f;
        static final float MARK_RATIO = 0.3f;
        static final float MARK_OFFSET_RATIO = 0.1f;

        Paint mBackgroundPaint;
        Paint mBackgroundPaintAmbient;

        Paint mHandPaint;
        boolean mAmbient;
        GregorianCalendar mTime;

        float centerX;
        float centerY;

        float secLength;
        float minLength;
        float hrLength;

        private SVG hourSvg;
        private SVG minuteSvg;

        private PointF hourRotationPoint;
        private PointF minuteRotationPoint;

        private RectF hourHandRect;
        private RectF minuteHandRect;

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

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private SVG threeOCSvg;
        private SVG sixOCSvg;
        private SVG nineOCSvg;
        private SVG twelveOCSvg;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(UkeWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = UkeWatchFace.this.getResources();

            mBackgroundPaintAmbient = new Paint();
            mBackgroundPaintAmbient.setColor(resources.getColor(R.color.analog_background));

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.uke_colors));

            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new GregorianCalendar();

            try {
                hourSvg = SVG.getFromResource(getResources(), R.raw.hour_hand);
                minuteSvg = SVG.getFromResource(getResources(), R.raw.minute_hand);

                threeOCSvg = SVG.getFromResource(getResources(), R.raw.three_oc);
                sixOCSvg = SVG.getFromResource(getResources(), R.raw.six_oc);
                nineOCSvg = SVG.getFromResource(getResources(), R.raw.nine_oc);
                twelveOCSvg = SVG.getFromResource(getResources(), R.raw.twelve_oc);
            } catch (SVGParseException ex) {
                ex.printStackTrace();
            }
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
            mTime.setTime(new Date());

            // Draw the background.
            if (mAmbient)
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaintAmbient);
            else
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float secRot = mTime.get(GregorianCalendar.SECOND) / 30f * (float) Math.PI;
            float minRot = mTime.get(GregorianCalendar.MINUTE) / 30f * (float) Math.PI;
            float hrRot = ((mTime.get(GregorianCalendar.HOUR) + (mTime.get(GregorianCalendar.MINUTE) / 60f)) / 6f) * (float) Math.PI;

            if (!mAmbient) {
                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mHandPaint);
            }

            PointF markBounds = new PointF(MARK_RATIO * centerX, MARK_RATIO * centerY);
            PointF mark12Bounds = new PointF(MARK12_RATIO * centerX, MARK12_RATIO * centerY);

            // 12
            canvas.save();
            canvas.translate(centerX, centerY * MARK_OFFSET_RATIO);
            float scale = Math.min(mark12Bounds.x / twelveOCSvg.getDocumentWidth(),
                                   mark12Bounds.y / twelveOCSvg.getDocumentHeight());
            canvas.translate(-twelveOCSvg.getDocumentWidth() * scale / 2f, 0);
            canvas.scale(scale, scale);
            twelveOCSvg.renderToCanvas(canvas);
            canvas.restore();

            // 9
            canvas.save();
            canvas.translate(centerX * MARK_OFFSET_RATIO, centerY);
            scale = Math.min(markBounds.x / nineOCSvg.getDocumentWidth(),
                             markBounds.y / nineOCSvg.getDocumentHeight());
            canvas.translate(0, -nineOCSvg.getDocumentHeight() * scale / 2f);
            canvas.scale(scale, scale);
            nineOCSvg.renderToCanvas(canvas);
            canvas.restore();

            // 6
            canvas.save();
            canvas.translate(centerX, centerY * (2 - MARK_RATIO - MARK_OFFSET_RATIO));
            scale = Math.min(markBounds.x / sixOCSvg.getDocumentWidth(),
                             markBounds.y / sixOCSvg.getDocumentHeight());
            canvas.translate(-sixOCSvg.getDocumentWidth() * scale / 2f,
                             markBounds.y - sixOCSvg.getDocumentHeight() * scale);
            canvas.scale(scale, scale);
            sixOCSvg.renderToCanvas(canvas);
            canvas.restore();

            // 3
            canvas.save();
            canvas.translate(centerX * (2 - MARK_RATIO - MARK_OFFSET_RATIO), centerY);
            scale = Math.min(markBounds.x / threeOCSvg.getDocumentWidth(),
                             markBounds.y / threeOCSvg.getDocumentHeight());
            canvas.translate(markBounds.x - threeOCSvg.getDocumentWidth() * scale,
                             -threeOCSvg.getDocumentHeight() * scale / 2f);
            canvas.scale(scale, scale);
            threeOCSvg.renderToCanvas(canvas);
            canvas.restore();

            // minute
            canvas.save();
            renderHand(canvas,
                    mTime.get(GregorianCalendar.MINUTE) * 6,
                    MINUTE_HAND_SCALE,
                    minuteHandRect,
                    minuteRotationPoint,
                    minuteSvg);
            canvas.restore();

            // hour
            canvas.save();
            renderHand(canvas,
                    (mTime.get(GregorianCalendar.HOUR) + (mTime.get(GregorianCalendar.MINUTE) / 60f)) * 30,
                    HOUR_HAND_SCALE,
                    hourHandRect,
                    hourRotationPoint,
                    hourSvg);
            canvas.restore();
        }

        private void renderHand(Canvas canvas,
                                float angle,
                                float handScale,
                                RectF rect,
                                PointF rotationPoint,
                                SVG svg)
        {
            final PointF p = new PointF(centerX - rotationPoint.x,
                                      centerY - rotationPoint.y);
            canvas.translate(p.x, p.y);
            canvas.rotate(angle, rotationPoint.x, rotationPoint.y);
            svg.renderToCanvas(canvas, rect);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
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
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            UkeWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            UkeWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
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
            centerX = width / 2;
            centerY = height / 2;

            secLength = centerX - 20;
            minLength = centerX - 40;
            hrLength = centerX - 80;

            hourRotationPoint = new PointF(centerX * HOUR_HAND_SCALE / 2,
                    centerY * HOUR_HAND_SCALE * NAIL_RATIO);
            minuteRotationPoint = new PointF(centerX * MINUTE_HAND_SCALE / 2,
                    centerY * MINUTE_HAND_SCALE * NAIL_RATIO);

            hourHandRect = new RectF(0, 0,
                                     centerX * HOUR_HAND_SCALE,
                                     centerY * HOUR_HAND_SCALE);

            minuteHandRect = new RectF(0, 0,
                                       centerX * MINUTE_HAND_SCALE,
                                       centerY * MINUTE_HAND_SCALE);

        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }
    }
}
