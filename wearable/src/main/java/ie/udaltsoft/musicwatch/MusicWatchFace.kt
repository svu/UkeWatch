/*
 * Copyright (C) 2025 Sergey Udaltsov
 * All rights reserved.
 *
 * ... (Your license text remains here) ...
 *
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
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.res.ResourcesCompat
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.MINUTE_INSTRUMENT_DEFAULT
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import androidx.core.graphics.withSave
import androidx.core.graphics.createBitmap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Modern implementation of the Music Watch Face using the AndroidX Watch Face API.
 */
class MusicWatchFace : WatchFaceService() {

    // UPDATED SIGNATURE: This method is now a suspend function and takes new parameters.
    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = MusicCanvasRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            currentUserStyleRepository = currentUserStyleRepository,
            watchState = watchState // Pass the watchState to the renderer
        )

        return WatchFace(
            watchFaceType = WatchFaceType.ANALOG,
            renderer = renderer
        )
    }
    override fun createUserStyleSchema(): UserStyleSchema {
        // Defines the customizable elements of the watch face.
        val hourHandStyle = UserStyleSetting.ListUserStyleSetting(
            id = UserStyleSetting.Id(HOUR_HAND_STYLE_ID),
            displayName = "Hour Hand",
            description = "Select the instrument for the hour hand",
            icon = null,
            // UPDATED: Use the direct constructor with a simple String
            options = listOf(
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("guitar"),
                    displayName = "Guitar",
                    screenReaderName = "Guitar",
                    icon=null
                ),
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("bass"),
                    displayName = "Bass",
                    screenReaderName = "Bass",
                    icon=null
                ),
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("ukulele"),
                    displayName = "Ukulele",
                    screenReaderName = "Ukulele",
                    icon=null
                )
            ),
            affectedLayers = listOf(WatchFaceLayer.BASE)
        )

        val minuteHandStyle = UserStyleSetting.ListUserStyleSetting(
            id = UserStyleSetting.Id(MINUTE_HAND_STYLE_ID),
            displayName = "Minute Hand",
            description = "Select the instrument for the minute hand",
            icon = null,
            // UPDATED: Use the direct constructor with a simple String
            options = listOf(
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("guitar"),
                    displayName = "Guitar",
                    screenReaderName = "Guitar",
                    icon = null
                ),
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("bass"),
                    displayName = "Bass",
                    screenReaderName = "Bass",
                    icon = null
                ),
                UserStyleSetting.ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("ukulele"),
                    displayName = "Ukulele",
                    screenReaderName = "Ukulele",
                    icon = null

                )
            ),
            affectedLayers = listOf(WatchFaceLayer.BASE)
        )

        return UserStyleSchema(listOf(hourHandStyle, minuteHandStyle))
    }

    private inner class MusicCanvasRenderer(
        val context: Context,
        surfaceHolder: SurfaceHolder,
        private val currentUserStyleRepository: CurrentUserStyleRepository,
        watchState: WatchState, // Accept watchState in the constructor
    ) : Renderer.CanvasRenderer(
        surfaceHolder = surfaceHolder,
        currentUserStyleRepository = currentUserStyleRepository,
        watchState = watchState, // Pass watchState to the super constructor
        canvasType = CanvasType.HARDWARE,
        interactiveDrawModeUpdateDelayMillis = 1000L
    ) {
        private val mBackgroundPaint = Paint()
        private val mBackgroundPaintAmbient = Paint()
        private val mStaffPaint = Paint()
        private val mHandPaint = Paint()

        private var hourHandSvg: SVG? = null
        private var minuteHandSvg: SVG? = null
        private var ambientHourHandSvg: SVG? = null
        private var ambientMinuteHandSvg: SVG? = null

        private var center = PointF()
        private var secLength = 0f
        private var hourRotationPoint = PointF()
        private var minuteRotationPoint = PointF()
        private var markBounds = PointF()
        private var mark12Bounds = PointF()
        private var markHourBounds = PointF()
        private var markNoteBounds = PointF()
        private var hourHandRect = RectF()
        private var minuteHandRect = RectF()

        private lateinit var threeOCSvg: SVG
        private lateinit var sixOCSvg: SVG
        private lateinit var nineOCSvg: SVG
        private lateinit var twelveOCSvg: SVG
        private lateinit var hourSvg: SVG
        private lateinit var noteSvg: SVG
        private lateinit var noteAcSvg: SVG

        private var scales: FloatArray = FloatArray(7)
        private var majorBitmap: Array<Bitmap?> = arrayOfNulls(7)

        private var batteryPct = 0f
        private var chargePlug = 0
        private var isReceiverRegistered = false

        private var mHourInstrument: String = HOUR_INSTRUMENT_DEFAULT
        private var mMinuteInstrument: String = MINUTE_INSTRUMENT_DEFAULT

        private var ambientBaseBitmap: Bitmap? = null
        private var normalBaseBitmap: Bitmap? = null

        private val dateFormat: SimpleDateFormat =
            SimpleDateFormat(context.getString(R.string.date_format), Locale.getDefault())

        private val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryPct = if (scale > 0) level / scale.toFloat() else 0f

                val newChargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                if (newChargePlug != chargePlug) {
                    chargePlug = newChargePlug
                    invalidate()
                }
            }
        }

        init {
            val resources = context.resources
            mBackgroundPaintAmbient.color = ResourcesCompat.getColor(resources, R.color.analog_background_ambient, null)
            mBackgroundPaint.color = ResourcesCompat.getColor(resources, R.color.analog_background, null)

            with(mHandPaint) {
                color = ResourcesCompat.getColor(resources, R.color.analog_hands, null)
                strokeWidth = resources.getDimension(R.dimen.analog_hand_stroke)
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                textSize = 24f
            }
            with(mStaffPaint) {
                color = ResourcesCompat.getColor(resources, R.color.analog_hands, null)
                strokeWidth = resources.getDimension(R.dimen.staff_stroke)
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
            }

            try {
                createStaticSvgs()
            } catch (ex: SVGParseException) {
                Log.e(TAG, "Failed to parse static SVGs", ex)
            }

            currentUserStyleRepository.addUserStyleListener(this::onUserStyleChanged)
            onUserStyleChanged(currentUserStyleRepository.userStyle.value)
        }

        private fun onUserStyleChanged(userStyle: Map<UserStyleSetting, UserStyleSetting.Option>) {
            var needsRedraw = false

            val newHourInstrument = userStyle[UserStyleSetting.Id(HOUR_HAND_STYLE_ID)]?.id?.toString() ?: HOUR_INSTRUMENT_DEFAULT
            if (mHourInstrument != newHourInstrument) {
                mHourInstrument = newHourInstrument
                needsRedraw = true
            }

            val newMinuteInstrument = userStyle[UserStyleSetting.Id(MINUTE_HAND_STYLE_ID)]?.id?.toString() ?: MINUTE_INSTRUMENT_DEFAULT
            if (mMinuteInstrument != newMinuteInstrument) {
                mMinuteInstrument = newMinuteInstrument
                needsRedraw = true
            }

            if (needsRedraw) {
                try {
                    createHands()
                } catch (e: SVGParseException) {
                    Log.e(TAG, "Failed to create hands after style change", e)
                }
                invalidate()
            }
        }

        override fun onDestroy() {
            if(isReceiverRegistered) {
                context.unregisterReceiver(batteryReceiver)
                isReceiverRegistered = false
            }
            currentUserStyleRepository.removeUserStyleListener(this::onUserStyleChanged)
            super.onDestroy()
        }

        override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {
            if (renderParameters.watchFaceVisibility.value.isVisible && !isReceiverRegistered) {
                isReceiverRegistered = true
                context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            } else if (!renderParameters.watchFaceVisibility.value.isVisible && isReceiverRegistered) {
                context.unregisterReceiver(batteryReceiver)
                isReceiverRegistered = false
            }

            val isAmbient = renderParameters.drawMode == DrawMode.AMBIENT

            if (isAmbient) {
                if (ambientBaseBitmap == null) {
                    ambientBaseBitmap = createBaseBitmap("ambient", bounds, mBackgroundPaintAmbient)
                }
                canvas.drawBitmap(ambientBaseBitmap!!, 0f, 0f, null)
            } else {
                if (normalBaseBitmap == null) {
                    val (bitmap, baseCanvas) = createBaseBitmapWithCanvas(bounds, mBackgroundPaint)
                    normalBaseBitmap = bitmap
                    draw12369(baseCanvas)
                    val markHourLocations = calcMarkHourLocations(majorBitmap[4]!!)
                    for (markHourLocation in markHourLocations) {
                        baseCanvas.drawBitmap(majorBitmap[4]!!, markHourLocation.x, markHourLocation.y, null)
                    }
                    displayBatteryStaff(baseCanvas)
                }
                canvas.drawBitmap(normalBaseBitmap!!, 0f, 0f, null)
                displayDate(canvas, zonedDateTime)
            }

            val hour = zonedDateTime.hour % 12
            val minute = zonedDateTime.minute
            val second = zonedDateTime.second

            val hourRotation = (hour + minute / 60f) * 30f
            val minuteRotation = minute * 6f

            val currentHourHand = if (isAmbient) ambientHourHandSvg else hourHandSvg
            val currentMinuteHand = if (isAmbient) ambientMinuteHandSvg else minuteHandSvg

            canvas.withSave {
                renderHand(
                    canvas,
                    minuteRotation,
                    minuteHandRect,
                    minuteRotationPoint,
                    currentMinuteHand
                )
            }

            canvas.withSave {
                renderHand(this, hourRotation, hourHandRect, hourRotationPoint, currentHourHand)
            }

            if (!isAmbient) {
                displayBattery(canvas)

                val secRot = second / 30f * Math.PI.toFloat()
                val secX = sin(secRot.toDouble()).toFloat() * secLength
                val secY = -cos(secRot.toDouble()).toFloat() * secLength
                canvas.drawLine(center.x, center.y, center.x + secX, center.y + secY, mHandPaint)
            }
        }

        override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime) {}

        override fun onLayout(screenBounds: Rect, isForRoundScreen: Boolean) {
            super.onLayout(screenBounds, isForRoundScreen)
            center = PointF(screenBounds.width() / 2f, screenBounds.height() / 2f)
            secLength = center.x - 20

            hourRotationPoint = PointF(center.x * HOUR_HAND_RATIO / 2, center.y * HOUR_HAND_RATIO * NAIL_RATIO)
            minuteRotationPoint = PointF(center.x * MINUTE_HAND_RATIO / 2, center.y * MINUTE_HAND_RATIO * NAIL_RATIO)

            hourHandRect = RectF(0f, 0f, center.x * HOUR_HAND_RATIO, center.y * HOUR_HAND_RATIO)
            minuteHandRect = RectF(0f, 0f, center.x * MINUTE_HAND_RATIO, center.y * MINUTE_HAND_RATIO)

            markBounds = PointF(MARK_RATIO * center.x, MARK_RATIO * center.y)
            mark12Bounds = PointF(MARK12_RATIO * center.x, MARK12_RATIO * center.y)
            markHourBounds = PointF(MARK_HOUR_RATIO * center.x, MARK_HOUR_RATIO * center.y)
            markNoteBounds = PointF(MARK_NOTE_RATIO * center.x, MARK_NOTE_RATIO * center.y)

            createBitmapsFromSvgs()
            ambientBaseBitmap = null
            normalBaseBitmap = null
        }

        // --- Drawing and Helper Functions (mostly unchanged) ---
        @SuppressLint("DiscouragedApi")
        @Throws(SVGParseException::class)
        private fun createHand(ambient: Boolean, instrument: String): SVG? {
            val resourceName = "${instrument}${if (ambient) "_ambient" else ""}_hand"
            val id = context.resources.getIdentifier(resourceName, "raw", context.packageName)
            return if (id == 0) null else SVG.getFromResource(context.resources, id)
        }

        @Throws(SVGParseException::class)
        private fun createHands() {
            hourHandSvg = createHand(false, mHourInstrument)
            minuteHandSvg = createHand(false, mMinuteInstrument)
            ambientHourHandSvg = createHand(true, mHourInstrument)
            ambientMinuteHandSvg = createHand(true, mMinuteInstrument)
        }

        @Throws(SVGParseException::class)
        private fun createStaticSvgs() {
            threeOCSvg = SVG.getFromResource(context.resources, R.raw.three_oc)
            sixOCSvg = SVG.getFromResource(context.resources, R.raw.six_oc)
            nineOCSvg = SVG.getFromResource(context.resources, R.raw.nine_oc)
            twelveOCSvg = SVG.getFromResource(context.resources, R.raw.twelve_oc)
            hourSvg = SVG.getFromResource(context.resources, R.raw.hour)
            noteSvg = SVG.getFromResource(context.resources, R.raw.note)
            noteAcSvg = SVG.getFromResource(context.resources, R.raw.note_ac)
        }

        private fun createBaseBitmap(type: String, bounds: Rect, paint: Paint): Bitmap {
            Log.d(TAG, "Creating $type base bitmap")
            val bitmap = createBitmap(bounds.width(), bounds.height())
            val canvas = Canvas(bitmap)
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
            return bitmap
        }

        private fun createBaseBitmapWithCanvas(bounds: Rect, paint: Paint): Pair<Bitmap, Canvas> {
            val bitmap = createBaseBitmap("normal", bounds, paint)
            return Pair(bitmap, Canvas(bitmap))
        }

        private fun draw12369(canvas: Canvas) {
            canvas.drawBitmap(majorBitmap[0]!!, center.x - twelveOCSvg.documentWidth * scales[0] / 2f, center.y * MARK_OFFSET_RATIO, null)
            canvas.drawBitmap(majorBitmap[1]!!, center.x * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.x - threeOCSvg.documentWidth * scales[1], center.y - threeOCSvg.documentHeight * scales[1] / 2f, null)
            canvas.drawBitmap(majorBitmap[2]!!, center.x - sixOCSvg.documentWidth * scales[2] / 2f, center.y * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.y - sixOCSvg.documentHeight * scales[2], null)
            canvas.drawBitmap(majorBitmap[3]!!, center.x * MARK_OFFSET_RATIO, center.y - nineOCSvg.documentHeight * scales[3] / 2f, null)
        }

        private fun displayDate(canvas: Canvas, zonedDateTime: ZonedDateTime) {
            val dateFormatted = dateFormat.format(Date.from(zonedDateTime.toInstant()))
            val bounds = Rect()
            mHandPaint.getTextBounds(dateFormatted, 0, dateFormatted.length, bounds)
            canvas.drawText(dateFormatted, center.x - bounds.width() / 2f, center.y * 3 / 2 + bounds.height() / 2f, mHandPaint)
        }

        private fun displayBatteryStaff(canvas: Canvas) {
            val xmin = center.x * (1 + STAFF_X_RATIO_START)
            val xmax = center.x * (1 + STAFF_X_RATIO_END)
            var ycur = center.y * STAFF_Y_RATIO_START
            val ystep = center.y * (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4
            for (i in 0..4) {
                canvas.drawLine(xmin, ycur, xmax, ycur, mStaffPaint)
                ycur += ystep
            }
        }

        private fun displayBattery(canvas: Canvas) {
            val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
            val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
            val noteBmp = if (acCharge || usbCharge) majorBitmap[6] else majorBitmap[5]
            val xmin = center.x * (1 + STAFF_X_RATIO_START)
            val xmax = center.x * (1 + STAFF_X_RATIO_END)
            val ymax = center.y * STAFF_Y_RATIO_END
            val ystep = center.y * (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4
            val watchBatteryNoteLevel = floor((batteryPct * 10).toDouble()).toInt()
            val ynote = ymax - ystep * (watchBatteryNoteLevel / 2f)
            canvas.drawBitmap(noteBmp!!, xmin + (xmax - xmin) * 0.5f - noteBmp.width / 2.0f, ynote, mStaffPaint)
        }

        private fun renderHand(canvas: Canvas, angle: Float, rect: RectF, rotationPoint: PointF, svg: SVG?) {
            if (svg == null) return
            val p = PointF(center.x - rotationPoint.x, center.y - rotationPoint.y)
            canvas.translate(p.x, p.y)
            canvas.rotate(angle, rotationPoint.x, rotationPoint.y)
            svg.renderToCanvas(canvas, rect)
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
            val markHourLocations = Array(HOUR_ANGLES.size) { PointF() }
            val hourRatio: Float = if (watchState.isRound) MARK_HOUR_RATIO else 0f
            val offset = 1 - hourRatio - MARK_OFFSET_RATIO
            val halfSize = PointF(bitmap.width / 2f, bitmap.height / 2f)
            for (i in HOUR_ANGLES.indices) {
                markHourLocations[i].offset(
                    center.x * (1 + sin(HOUR_ANGLES[i]) * offset).toFloat() - halfSize.x,
                    center.y * (1 - cos(HOUR_ANGLES[i]) * offset).toFloat() - halfSize.y
                )
            }
            return markHourLocations
        }

        private fun createBitmapFromSvg(svg: SVG, bounds: PointF, idx: Int, isForcedY: Boolean) {
            with(svg) {
                scales[idx] = if (isForcedY) bounds.y / documentHeight else min(
                    bounds.x / documentWidth,
                    bounds.y / documentHeight
                )
                majorBitmap[idx] = createBitmap(
                    (documentWidth * scales[idx]).roundToInt(),
                    (documentHeight * scales[idx]).roundToInt()
                )
                val canvas = Canvas(majorBitmap[idx]!!)
                canvas.scale(scales[idx], scales[idx])
                renderToCanvas(canvas)
            }
        }
    }

    companion object {
        private const val TAG = "MusicWatchFace"
        private const val HOUR_HAND_STYLE_ID = "hour_hand_style"
        private const val MINUTE_HAND_STYLE_ID = "minute_hand_style"
        const val HOUR_HAND_RATIO = 0.90f
        const val MINUTE_HAND_RATIO = 1.15f
        const val NAIL_RATIO = 0.7f
        const val MARK12_RATIO = 0.35f
        const val MARK_RATIO = 0.21f
        const val MARK_OFFSET_RATIO = 0.1f
        const val MARK_HOUR_RATIO = 0.04f
        const val STAFF_X_RATIO_START = 0.35f
        const val STAFF_X_RATIO_END = 0.55f
        const val STAFF_Y_RATIO_START = 0.45f
        const val STAFF_Y_RATIO_END = 0.75f
        const val MARK_NOTE_RATIO = (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4
        val HOUR_ANGLES = doubleArrayOf(
            30 * Math.PI / 180, 60 * Math.PI / 180, 120 * Math.PI / 180,
            150 * Math.PI / 180, 210 * Math.PI / 180, 240 * Math.PI / 180,
            300 * Math.PI / 180, 330 * Math.PI / 180
        )
    }
}