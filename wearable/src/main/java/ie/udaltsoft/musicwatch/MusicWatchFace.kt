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
package ie.udaltsoft.musicwatch

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowInsets
import androidx.core.content.res.ResourcesCompat
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.google.android.gms.wearable.DataClient.OnDataChangedListener
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import ie.udaltsoft.musicwatch.MusicWatchFaceConfigActivity.Companion.buildAllBitmaps
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.HandKind
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.addDataListener
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.fetchConfigDataMap
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.putConfigDataItem
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.removeDataListener
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.setDefaultValuesForMissingConfigKeys
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
class MusicWatchFace : CanvasWatchFaceService() {
    override fun onCreateEngine(): Engine {
        return Engine()
    }

    inner class Engine constructor() : CanvasWatchFaceService.Engine(),
        OnDataChangedListener {

        /**
         * Handler to update the time once a second in interactive mode.
         */
        @SuppressLint("HandlerLeak")
        val mUpdateTimeHandler: Handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                when (message.what) {
                    MSG_UPDATE_TIME -> {
                        invalidate()
                        if (shouldTimerBeRunning()) {
                            val timeMs = System.currentTimeMillis()
                            val delayMs =
                                INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                            sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
                        }
                    }
                }
            }
        }

        var mBackgroundPaint = Paint()
        var mBackgroundPaintAmbient = Paint()
        var mStaffPaint = Paint()
        var mHandPaint = Paint()

        var mAmbient = false
        var mTime = GregorianCalendar()

        private val mTimeZoneReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                mTime.timeZone = TimeZone.getTimeZone(intent.getStringExtra("time-zone"))
            }
        }
        var center = PointF()
        var secLength = 0f
        var mRegisteredTimeZoneReceiver = false

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private var mLowBitAmbient = false
        private var hourHandSvg: SVG? = null
        private var minuteHandSvg: SVG? = null
        private var ambientHourHandSvg: SVG? = null
        private var ambientMinuteHandSvg: SVG? = null
        private var hourRotationPoint: PointF? = null
        private var minuteRotationPoint: PointF? = null

        private var markBounds = PointF()
        private var mark12Bounds = PointF()
        private var markHourBounds = PointF()
        private var markNoteBounds = PointF()

        private var hourHandRect: RectF? = null
        private var minuteHandRect: RectF? = null
        private var mDateFormat: DateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

        private lateinit var threeOCSvg: SVG
        private lateinit var sixOCSvg: SVG
        private lateinit var nineOCSvg: SVG
        private lateinit var twelveOCSvg: SVG
        private lateinit var hourSvg: SVG
        private lateinit var noteSvg: SVG
        private lateinit var noteAcSvg: SVG

        private var scales: FloatArray = FloatArray(7)
        private var majorBitmap: Array<Bitmap?> = arrayOfNulls(7)

        private var isRound = false
        private var batteryPct = 0f
        private var chargePlug = 0

        private var batFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        private var tzFilter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)

        val mBatteryReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val batteryStatus = this@MusicWatchFace.registerReceiver(null, batFilter)
                if (batteryStatus != null) {
                    val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    batteryPct = if (scale == 0) 0f else level / scale.toFloat()
                    val newChargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                    if (newChargePlug != chargePlug) {
                        // enforce it
                        chargePlug = newChargePlug
                        invalidate()
                    }
                }
            }
        }
        private var mHourInstrument: String? = null
        private var mMinuteInstrument: String? = null
        private var ambientBaseBitmap: Bitmap? = null
        private var normalBaseBitmap: Bitmap? = null

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            mHourInstrument = MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT
            mMinuteInstrument = MusicWatchFaceUtil.MINUTE_INSTRUMENT_DEFAULT
            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@MusicWatchFace)
                    .build()
            )
            val resources = this@MusicWatchFace.resources
            mBackgroundPaintAmbient.color =
                ResourcesCompat.getColor(resources, R.color.analog_background_ambient, null)
            mBackgroundPaint.color =
                ResourcesCompat.getColor(resources, R.color.analog_background, null)

            mHandPaint.color = ResourcesCompat.getColor(resources, R.color.analog_hands, null)
            mHandPaint.strokeWidth = resources.getDimension(R.dimen.analog_hand_stroke)
            mHandPaint.isAntiAlias = true
            mHandPaint.strokeCap = Paint.Cap.ROUND
            mHandPaint.textSize = 24f

            mStaffPaint.color = ResourcesCompat.getColor(resources, R.color.analog_hands, null)
            mStaffPaint.strokeWidth = resources.getDimension(R.dimen.staff_stroke)
            mStaffPaint.isAntiAlias = true
            mStaffPaint.strokeCap = Paint.Cap.ROUND

            mTime = GregorianCalendar()
            try {
                createHands()
                createStaticSvgs()
            } catch (ex: SVGParseException) {
                ex.printStackTrace()
            }
            mDateFormat = SimpleDateFormat(resources.getString(R.string.date_format), Locale.getDefault())
            Log.d(TAG, "=== Loading all config bitmaps ===")
            buildAllBitmaps(getResources(), applicationContext)
            Log.d(TAG, "=== Done loading all config bitmaps ===")
            addDataListener(applicationContext, this)
            updateConfigDataItemAndUiOnStartup()
        }

        @Throws(SVGParseException::class)
        private fun createHand(kind: HandKind, ambient: Boolean): SVG? {
            @SuppressLint("DiscouragedApi") val id = resources.getIdentifier(
                (if (kind == HandKind.HOUR) mHourInstrument else mMinuteInstrument) +
                        (if (ambient) "_ambient" else "") + "_hand", "raw", packageName
            )
            return if (id == 0) null else SVG.getFromResource(resources, id)
        }

        @Throws(SVGParseException::class)
        private fun createStaticSvgs() {
            threeOCSvg = SVG.getFromResource(getResources(), R.raw.three_oc)
            sixOCSvg = SVG.getFromResource(getResources(), R.raw.six_oc)
            nineOCSvg = SVG.getFromResource(getResources(), R.raw.nine_oc)
            twelveOCSvg = SVG.getFromResource(getResources(), R.raw.twelve_oc)
            hourSvg = SVG.getFromResource(getResources(), R.raw.hour)
            noteSvg = SVG.getFromResource(getResources(), R.raw.note)
            noteAcSvg = SVG.getFromResource(getResources(), R.raw.note_ac)
        }

        @Throws(SVGParseException::class)
        private fun createHands() {
            hourHandSvg = createHand(HandKind.HOUR, false)
            minuteHandSvg = createHand(HandKind.MINUTE, false)
            ambientHourHandSvg = createHand(HandKind.HOUR, true)
            ambientMinuteHandSvg = createHand(HandKind.MINUTE, true)
        }

        override fun onApplyWindowInsets(wi: WindowInsets) {
            isRound = wi.isRound
            ambientBaseBitmap = null
            normalBaseBitmap = null
        }

        override fun onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            removeDataListener(applicationContext, this)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false)
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode
                if (mLowBitAmbient) {
                    mHandPaint.isAntiAlias = !inAmbientMode
                }
                invalidate()
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        // Draw hours
        private fun draw12369(canvas: Canvas) {
            // 12
            canvas.drawBitmap(
                majorBitmap[0]!!,
                center.x - twelveOCSvg.documentWidth * scales[0] / 2f,
                center.y * MARK_OFFSET_RATIO,
                null
            )

            // 3
            canvas.drawBitmap(
                majorBitmap[1]!!,
                center.x * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.x - threeOCSvg.documentWidth * scales[1],
                center.y - threeOCSvg.documentHeight * scales[1] / 2f,
                null
            )

            // 6
            canvas.drawBitmap(
                majorBitmap[2]!!,
                center.x - sixOCSvg.documentWidth * scales[2] / 2f,
                center.y * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.y - sixOCSvg.documentHeight * scales[2],
                null
            )

            // 9
            canvas.drawBitmap(
                majorBitmap[3]!!,
                center.x * MARK_OFFSET_RATIO,
                center.y - nineOCSvg.documentHeight * scales[3] / 2f,
                null
            )
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = Date()
            //now.setHours(19);now.setMinutes(50);
            mTime.time = now
            if (mAmbient) {
                if (ambientBaseBitmap == null) {
                    Log.d(TAG, "Creating ambient base bitmap")
                    val abb =
                        Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
                    ambientBaseBitmap = abb
                    val abc = Canvas(abb)
                    abc.drawRect(
                        0f,
                        0f,
                        canvas.width.toFloat(),
                        canvas.height.toFloat(),
                        mBackgroundPaintAmbient
                    )
                }
                canvas.drawBitmap(ambientBaseBitmap!!, 0f, 0f, null)
            } else {
                if (normalBaseBitmap == null) {
                    Log.d(TAG, "Creating normal base bitmap")
                    val nbb =
                        Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
                    normalBaseBitmap = nbb
                    val nbc = Canvas(nbb)
                    nbc.drawRect(
                        0f,
                        0f,
                        canvas.width.toFloat(),
                        canvas.height.toFloat(),
                        mBackgroundPaint
                    )
                    draw12369(nbc)
                    val markHourLocations = calcMarkHourLocations(majorBitmap[4]!!)
                    for (markHourLocation in markHourLocations) {
                        nbc.drawBitmap(
                            majorBitmap[4]!!,
                            markHourLocation.x,
                            markHourLocation.y,
                            null
                        )
                    }
                    displayBatteryStaff(nbc)
                }
                canvas.drawBitmap(normalBaseBitmap!!, 0f, 0f, null)
                displayDate(canvas, now)
            }
            if (!mAmbient) {
                displayBattery(canvas)

                // second hand
                val secRot = mTime[GregorianCalendar.SECOND] / 30f * Math.PI.toFloat()
                val secX = Math.sin(secRot.toDouble()).toFloat() * secLength
                val secY = -Math.cos(secRot.toDouble()).toFloat() * secLength
                canvas.drawLine(
                    center.x,
                    center.y,
                    center.x + secX,
                    center.y + secY,
                    mHandPaint
                )

                // minute hand
                canvas.save()
                renderHand(
                    canvas,
                    (
                            mTime[GregorianCalendar.MINUTE] * 6).toFloat(),
                    minuteHandRect!!,
                    minuteRotationPoint!!,
                    minuteHandSvg
                )
                canvas.restore()

                // hour hand
                canvas.save()
                renderHand(
                    canvas,
                    (mTime[GregorianCalendar.HOUR] + mTime[GregorianCalendar.MINUTE] / 60f) * 30,
                    hourHandRect!!,
                    hourRotationPoint!!,
                    hourHandSvg
                )
                canvas.restore()
            } else {
                // minute hand
                canvas.save()
                renderHand(
                    canvas,
                    (
                            mTime[GregorianCalendar.MINUTE] * 6).toFloat(),
                    minuteHandRect!!,
                    minuteRotationPoint!!,
                    ambientMinuteHandSvg
                )
                canvas.restore()

                // hour hand
                canvas.save()
                renderHand(
                    canvas,
                    (mTime[GregorianCalendar.HOUR] + mTime[GregorianCalendar.MINUTE] / 60f) * 30,
                    hourHandRect!!,
                    hourRotationPoint!!,
                    ambientHourHandSvg
                )
                canvas.restore()
            }
        }

        private fun renderHand(
            canvas: Canvas,
            angle: Float,
            rect: RectF,
            rotationPoint: PointF,
            svg: SVG?
        ) {
            if (svg == null) return
            val p = PointF(
                center.x - rotationPoint.x,
                center.y - rotationPoint.y
            )
            canvas.translate(p.x, p.y)
            canvas.rotate(angle, rotationPoint.x, rotationPoint.y)
            svg.renderToCanvas(canvas, rect)
        }

        private fun displayDate(canvas: Canvas, d: Date) {
            val dateFormatted = mDateFormat.format(d)
            val bounds = Rect()
            mHandPaint.getTextBounds(dateFormatted, 0, dateFormatted.length, bounds)
            canvas.drawText(
                dateFormatted, center.x - bounds.width() / 2f,
                center.y * 3 / 2 + bounds.height() / 2f, mHandPaint
            )
        }

        private fun displayBatteryStaff(canvas: Canvas) {
            val xmin = center.x * (1 + STAFF_X_RATIO_START)
            val xmax = center.x * (1 + STAFF_X_RATIO_END)
            var ycur = center.y * STAFF_Y_RATIO_START // ymin
            val ystep =
                center.y * (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4
            for (i in 0..4) {
                canvas.drawLine(
                    xmin,
                    ycur,
                    xmax,
                    ycur,
                    mStaffPaint
                )
                ycur += ystep
            }
        }

        private fun displayBattery(canvas: Canvas) {
            val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
            val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
            // either
            val noteBmp = if (acCharge || usbCharge) majorBitmap[6] else majorBitmap[5]
            val xmin = center.x * (1 + STAFF_X_RATIO_START)
            val xmax = center.x * (1 + STAFF_X_RATIO_END)
            val ymax = center.y * STAFF_Y_RATIO_END
            val ystep =
                center.y * (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4
            val watchBatteryNoteLevel = Math.floor((batteryPct * 10).toDouble()).toInt()
            val ynote = ymax - ystep * (watchBatteryNoteLevel / 2f)
            canvas.drawBitmap(
                noteBmp!!,
                xmin + (xmax - xmin) * 0.5f - noteBmp!!.width / 2.0f,
                ynote, mStaffPaint
            )
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                registerReceivers()
                mTime.timeZone = TimeZone.getDefault()
            } else {
                unregisterReceivers()
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer()
        }

        private fun registerReceivers() {
            if (mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = true
            this@MusicWatchFace.registerReceiver(mTimeZoneReceiver, tzFilter)
            this@MusicWatchFace.registerReceiver(mBatteryReceiver, batFilter)
        }

        private fun unregisterReceivers() {
            if (!mRegisteredTimeZoneReceiver) {
                return
            }
            mRegisteredTimeZoneReceiver = false
            unregisterReceiver(mTimeZoneReceiver)
            unregisterReceiver(mBatteryReceiver)
        }

        /**
         * Starts the [.mUpdateTimeHandler] timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private fun updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            center = PointF(width / 2f, height / 2f)
            secLength = center.x - 20
            hourRotationPoint = PointF(
                center.x * HOUR_HAND_RATIO / 2,
                center.y * HOUR_HAND_RATIO * NAIL_RATIO
            )
            minuteRotationPoint = PointF(
                center.x * MINUTE_HAND_RATIO / 2,
                center.y * MINUTE_HAND_RATIO * NAIL_RATIO
            )
            hourHandRect = RectF(
                0f, 0f,
                center.x * HOUR_HAND_RATIO,
                center.y * HOUR_HAND_RATIO
            )
            minuteHandRect = RectF(
                0f, 0f,
                center.x * MINUTE_HAND_RATIO,
                center.y * MINUTE_HAND_RATIO
            )
            markBounds =
                PointF(MARK_RATIO * center.x, MARK_RATIO * center.y)
            mark12Bounds =
                PointF(MARK12_RATIO * center.x, MARK12_RATIO * center.y)
            markHourBounds = PointF(
                MARK_HOUR_RATIO * center.x,
                MARK_HOUR_RATIO * center.y
            )
            markNoteBounds = PointF(
                MARK_NOTE_RATIO * center.x,
                MARK_NOTE_RATIO * center.y
            )
            createBitmapsFromSvgs()
            ambientBaseBitmap = null
            normalBaseBitmap = null
        }

        private fun createBitmapsFromSvgs() {
            createBitmapFromSvg(twelveOCSvg, mark12Bounds, 0, false)
            createBitmapFromSvg(threeOCSvg, markBounds, 1, false)
            createBitmapFromSvg(sixOCSvg, markBounds, 2, false)
            createBitmapFromSvg(nineOCSvg, markBounds, 3, false)
            createBitmapFromSvg(hourSvg, markHourBounds, 4, false)
            createBitmapFromSvg(noteSvg, markNoteBounds, 5, true)
            createBitmapFromSvg(noteAcSvg, markNoteBounds, 6, true)
        }

        private fun calcMarkHourLocations(bitmap: Bitmap): Array<PointF> {
            val markHourLocations = Array<PointF>(HOUR_ANGLES.size) { PointF() }
            val hourRatio: Float = if (isRound) MARK_HOUR_RATIO else 0f
            val offset = 1 - hourRatio - MARK_OFFSET_RATIO
            val halfSize = PointF(bitmap.width / 2f, bitmap.height / 2f)
            for (i in HOUR_ANGLES.indices) {
                markHourLocations[i].offset(
                    center.x * (1 + Math.sin(HOUR_ANGLES[i]) * offset).toFloat() - halfSize.x,
                    center.y * (1 - Math.cos(HOUR_ANGLES[i]) * offset).toFloat() - halfSize.y
                )
            }
            return markHourLocations
        }

        private fun createBitmapFromSvg(svg: SVG, bounds: PointF, idx: Int, isForcedY: Boolean) {
            with(svg) {
                scales[idx] = if (isForcedY) bounds.y / documentHeight else Math.min(
                    bounds.x / documentWidth, bounds.y / documentHeight
                )
                majorBitmap[idx] = Bitmap.createBitmap(
                    Math.round(documentWidth * scales[idx]),
                    Math.round(documentHeight * scales[idx]),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(majorBitmap[idx]!!)
                canvas.scale(scales[idx], scales[idx])
                renderToCanvas(canvas)
            }
        }

        /**
         * Returns whether the [.mUpdateTimeHandler] timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !isInAmbientMode
        }

        private fun updateConfigDataItemAndUiOnStartup() {
            fetchConfigDataMap(
                applicationContext
            ) { startupConfig: DataMap ->
                // If the DataItem hasn't been created yet or some keys are missing,
                // use the default values.
                with(startupConfig) {
                    val initialHourInstrument =
                        getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT)
                    val initialMinuteInstrument =
                        getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT)
                    Log.d(
                        TAG,
                        "!!!!!! Fetched startup config: $initialHourInstrument/$initialMinuteInstrument"
                    )
                    setDefaultValuesForMissingConfigKeys(this)
                    if (initialHourInstrument == null || initialMinuteInstrument == null) {
                        Log.d(
                            TAG, "Completing config initialization with: " +
                                    getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT) + "/" +
                                    getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT)
                        )
                        putConfigDataItem(
                            applicationContext,
                            this
                        )
                    }
                    updateUiForConfigDataMap(this)
                }
            }
        }

        private fun updateUiForConfigDataMap(config: DataMap) {
            if (config.containsKey(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT)) {
                val instrument = config.get<String>(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT)
                if (mHourInstrument == null || mHourInstrument != instrument) {
                    setHourInstrument(instrument)
                    Log.d(TAG, "Invalidating after new hour instrument: $instrument")
                    invalidate()
                } else {
                    Log.d(TAG, "Same hour instrument, no change in config")
                }
            }
            if (config.containsKey(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT)) {
                val instrument = config.get<String>(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT)
                if (mMinuteInstrument == null || mMinuteInstrument != instrument) {
                    setMinuteInstrument(instrument)
                    Log.d(TAG, "Invalidating after new minute instrument: $instrument")
                    invalidate()
                } else {
                    Log.d(TAG, "Same minute instrument, no change in config")
                }
            }
        }

        private fun setHourInstrument(instrument: String?) {
            try {
                if (mHourInstrument == null || mHourInstrument != instrument) {
                    mHourInstrument = instrument
                    Log.i(TAG, "Hands to be created for hand instrument: $instrument")
                    createHands()
                }
            } catch (e: SVGParseException) {
                e.printStackTrace()
            }
        }

        private fun setMinuteInstrument(instrument: String?) {
            try {
                if (mMinuteInstrument == null || mMinuteInstrument != instrument) {
                    mMinuteInstrument = instrument
                    Log.i(TAG, "Hands to be created for minute instrument: $instrument")
                    createHands()
                }
            } catch (e: SVGParseException) {
                e.printStackTrace()
            }
        }

        override fun onDataChanged(dataEvents: DataEventBuffer) {
            Log.d(TAG, "onDataChanged: $dataEvents")
            for (dataEvent in dataEvents) {
                if (dataEvent.type != DataEvent.TYPE_CHANGED) {
                    continue
                }
                val dataItem = dataEvent.dataItem
                if (dataItem.uri.path != MusicWatchFaceUtil.PATH_WITH_FEATURE) {
                    continue
                }
                val dataMapItem = DataMapItem.fromDataItem(dataItem)
                val config = dataMapItem.dataMap
                updateUiForConfigDataMap(config)
            }
        }
    }

    companion object {
        private const val TAG = "MusicWatchFace"

        /**
         * Update rate in milliseconds for interactive mode. We update once a second to advance the
         * second hand.
         */
        private val INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1)

        const val MSG_UPDATE_TIME = 0

        // Hands
        const val HOUR_HAND_RATIO = 0.90f
        const val MINUTE_HAND_RATIO = 1.15f

        // Where is the nail on the hands?
        const val NAIL_RATIO = 0.7f

        // 12 o'clock size
        const val MARK12_RATIO = 0.35f

        // 3, 6, 9 o'clock mark size
        const val MARK_RATIO = 0.21f

        // offset of marks from the border
        const val MARK_OFFSET_RATIO = 0.1f

        // 1, 2, 4, 5, 7, 8, 10, 11 o'clock size
        const val MARK_HOUR_RATIO = 0.04f
        const val STAFF_X_RATIO_START = 0.35f
        const val STAFF_X_RATIO_END = 0.55f
        const val STAFF_Y_RATIO_START = 0.45f
        const val STAFF_Y_RATIO_END = 0.75f

        // 0.5 0.575 0.65 0.725 0.8
        // battery note size
        const val MARK_NOTE_RATIO = (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4

        val HOUR_ANGLES = doubleArrayOf(
            30 * Math.PI / 180,
            60 * Math.PI / 180,
            120 * Math.PI / 180,
            150 * Math.PI / 180,
            210 * Math.PI / 180,
            240 * Math.PI / 180,
            300 * Math.PI / 180,
            330 * Math.PI / 180
        )
    }
}
