/*
 * Copyright (C) 2015-2026 Sergey Udaltsov
 * All rights reserved.
 */
package ie.udaltsoft.musicwatch

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
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.google.android.gms.wearable.DataClient.OnDataChangedListener
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.HandKind
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.addDataListener
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.removeDataListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

class MusicWatchFace : WatchFaceService(), OnDataChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var styleRepository: CurrentUserStyleRepository

    override fun createUserStyleSchema(): UserStyleSchema {
        return MusicWatchFaceUtil.createUserStyleSchema(this)
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        styleRepository = currentUserStyleRepository
        
        val renderer = MusicCanvasRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            watchState = watchState,
            currentUserStyleRepository = currentUserStyleRepository
        )

        addDataListener(applicationContext, this)

        return WatchFace(
            watchFaceType = WatchFaceType.ANALOG,
            renderer = renderer
        )
    }

    override fun onDestroy() {
        removeDataListener(applicationContext, this)
        super.onDestroy()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (dataEvent in dataEvents) {
            if (dataEvent.type != DataEvent.TYPE_CHANGED) continue
            val dataItem = dataEvent.dataItem
            if (dataItem.uri.path != MusicWatchFaceUtil.PATH_WITH_FEATURE) continue
            
            val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
            val hourInstrument = dataMap.getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT)
            val minuteInstrument = dataMap.getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT)

            scope.launch {
                val currentStyle = styleRepository.userStyle.value.toMutableUserStyle()
                val schema = styleRepository.schema
                
                if (hourInstrument != null) {
                    val setting = schema[UserStyleSetting.Id(MusicWatchFaceUtil.ID_HOUR_INSTRUMENT)]!!
                    currentStyle[setting] = UserStyleSetting.Option.Id(hourInstrument)
                }
                
                if (minuteInstrument != null) {
                    val setting = schema[UserStyleSetting.Id(MusicWatchFaceUtil.ID_MINUTE_INSTRUMENT)]!!
                    currentStyle[setting] = UserStyleSetting.Option.Id(minuteInstrument)
                }
                
                val newUserStyle = currentStyle.toUserStyle()
                (styleRepository.userStyle as? MutableStateFlow<UserStyle>)?.value = newUserStyle
            }
        }
    }
}

class MusicCanvasRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    private val watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository
) : Renderer.CanvasRenderer2<Renderer.SharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    interactiveDrawModeUpdateDelayMillis = 16L,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = true
) {
    private val resources = context.resources
    
    private val mBackgroundPaint = Paint().apply {
        color = ResourcesCompat.getColor(resources, R.color.analog_background, null)
    }
    private val mBackgroundPaintAmbient = Paint().apply {
        color = ResourcesCompat.getColor(resources, R.color.analog_background_ambient, null)
    }
    private val mStaffPaint = Paint().apply {
        color = ResourcesCompat.getColor(resources, R.color.analog_hands, null)
        strokeWidth = resources.getDimension(R.dimen.staff_stroke)
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }
    private val mHandPaint = Paint().apply {
        color = ResourcesCompat.getColor(resources, R.color.analog_hands, null)
        strokeWidth = resources.getDimension(R.dimen.analog_hand_stroke)
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        textSize = 24f
    }

    private var mDateFormat: DateFormat = SimpleDateFormat(resources.getString(R.string.date_format), Locale.getDefault())
    private val mTime = GregorianCalendar()

    private var center = PointF()
    private var secLength = 0f
    
    private var hourHandSvg: SVG? = null
    private var minuteHandSvg: SVG? = null
    private var ambientHourHandSvg: SVG? = null
    private var ambientMinuteHandSvg: SVG? = null
    
    private var hourRotationPoint = PointF()
    private var minuteRotationPoint = PointF()
    private var hourHandRect = RectF()
    private var minuteHandRect = RectF()
    
    private var markBounds = PointF()
    private var mark12Bounds = PointF()
    private var markHourBounds = PointF()
    private var markNoteBounds = PointF()

    private lateinit var threeOCSvg: SVG
    private lateinit var sixOCSvg: SVG
    private lateinit var nineOCSvg: SVG
    private lateinit var twelveOCSvg: SVG
    private lateinit var hourSvg: SVG
    private lateinit var noteSvg: SVG
    private lateinit var noteAcSvg: SVG

    private var scales = FloatArray(7)
    private var majorBitmap = arrayOfNulls<Bitmap>(7)
    
    private var ambientBaseBitmap: Bitmap? = null
    private var normalBaseBitmap: Bitmap? = null

    private var batteryPct = 0f
    private var chargePlug = 0
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPct = if (scale == 0) 0f else level / scale.toFloat()
            chargePlug = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            invalidate()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try {
            createStaticSvgs()
        } catch (e: SVGParseException) {
            Log.e("MusicWatchFace", "Error loading static SVGs", e)
        }
        
        scope.launch {
            currentUserStyleRepository.userStyle.collect { userStyle ->
                updateHandsFromStyle(userStyle)
            }
        }
    }

    private fun updateHandsFromStyle(userStyle: UserStyle) {
        val hourInstrument = userStyle[UserStyleSetting.Id(MusicWatchFaceUtil.ID_HOUR_INSTRUMENT)]?.id?.value?.decodeToString() ?: MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT
        val minuteInstrument = userStyle[UserStyleSetting.Id(MusicWatchFaceUtil.ID_MINUTE_INSTRUMENT)]?.id?.value?.decodeToString() ?: MusicWatchFaceUtil.MINUTE_INSTRUMENT_DEFAULT
        
        try {
            hourHandSvg = createHand(hourInstrument, false)
            minuteHandSvg = createHand(minuteInstrument, false)
            ambientHourHandSvg = createHand(hourInstrument, true)
            ambientMinuteHandSvg = createHand(minuteInstrument, true)
            invalidate()
        } catch (e: SVGParseException) {
            Log.e("MusicWatchFace", "Error loading hand SVGs", e)
        }
    }

    private fun createHand(instrument: String, ambient: Boolean): SVG? {
        val id = resources.getIdentifier(
            instrument + (if (ambient) "_ambient" else "") + "_hand",
            "raw",
            context.packageName
        )
        return if (id == 0) null else SVG.getFromResource(resources, id)
    }

    private fun createStaticSvgs() {
        threeOCSvg = SVG.getFromResource(resources, R.raw.three_oc)
        sixOCSvg = SVG.getFromResource(resources, R.raw.six_oc)
        nineOCSvg = SVG.getFromResource(resources, R.raw.nine_oc)
        twelveOCSvg = SVG.getFromResource(resources, R.raw.twelve_oc)
        hourSvg = SVG.getFromResource(resources, R.raw.hour)
        noteSvg = SVG.getFromResource(resources, R.raw.note)
        noteAcSvg = SVG.getFromResource(resources, R.raw.note_ac)
    }

    override suspend fun createSharedAssets(): Renderer.SharedAssets {
        return object : Renderer.SharedAssets {
            override fun onDestroy() {}
        }
    }

    private var lastBounds = Rect()

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Renderer.SharedAssets) {
        if (bounds != lastBounds) {
            lastBounds.set(bounds)
            updateLayout(bounds)
        }
        
        val now = Date(zonedDateTime.toInstant().toEpochMilli())
        mTime.time = now
        mTime.timeZone = TimeZone.getTimeZone(zonedDateTime.zone.id)

        val isAmbient = watchState.isAmbient.value == true

        if (isAmbient) {
            if (ambientBaseBitmap == null || ambientBaseBitmap?.width != bounds.width()) {
                val (bitmap, bCanvas) = createBaseBitmap(bounds.width(), bounds.height(), mBackgroundPaintAmbient)
                ambientBaseBitmap = bitmap
            }
            canvas.drawBitmap(ambientBaseBitmap!!, 0f, 0f, null)
        } else {
            if (normalBaseBitmap == null || normalBaseBitmap?.width != bounds.width()) {
                val (bitmap, bCanvas) = createBaseBitmap(bounds.width(), bounds.height(), mBackgroundPaint)
                normalBaseBitmap = bitmap
                draw12369(bCanvas)
                val markHourLocations = calcMarkHourLocations(majorBitmap[4]!!)
                for (loc in markHourLocations) {
                    bCanvas.drawBitmap(majorBitmap[4]!!, loc.x, loc.y, null)
                }
                displayBatteryStaff(bCanvas)
            }
            canvas.drawBitmap(normalBaseBitmap!!, 0f, 0f, null)
            displayDate(canvas, now)
            displayBattery(canvas)
            
            // Second hand
            val secRot = mTime[GregorianCalendar.SECOND] / 30f * Math.PI.toFloat()
            val secX = Math.sin(secRot.toDouble()).toFloat() * secLength
            val secY = -Math.cos(secRot.toDouble()).toFloat() * secLength
            canvas.drawLine(center.x, center.y, center.x + secX, center.y + secY, mHandPaint)
        }

        // Hands
        val hourAngle = (mTime[GregorianCalendar.HOUR] + mTime[GregorianCalendar.MINUTE] / 60f) * 30f
        val minuteAngle = (mTime[GregorianCalendar.MINUTE] * 6).toFloat()

        if (isAmbient) {
            renderHand(canvas, minuteAngle, minuteHandRect, minuteRotationPoint, ambientMinuteHandSvg)
            renderHand(canvas, hourAngle, hourHandRect, hourRotationPoint, ambientHourHandSvg)
        } else {
            renderHand(canvas, minuteAngle, minuteHandRect, minuteRotationPoint, minuteHandSvg)
            renderHand(canvas, hourAngle, hourHandRect, hourRotationPoint, hourHandSvg)
        }
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Renderer.SharedAssets) {
    }

    private fun createBaseBitmap(width: Int, height: Int, paint: Paint): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return Pair(bitmap, canvas)
    }

    private fun draw12369(canvas: Canvas) {
        // 12
        canvas.drawBitmap(majorBitmap[0]!!, center.x - twelveOCSvg.documentWidth * scales[0] / 2f, center.y * MARK_OFFSET_RATIO, null)
        // 3
        canvas.drawBitmap(majorBitmap[1]!!, center.x * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.x - threeOCSvg.documentWidth * scales[1], center.y - threeOCSvg.documentHeight * scales[1] / 2f, null)
        // 6
        canvas.drawBitmap(majorBitmap[2]!!, center.x - sixOCSvg.documentWidth * scales[2] / 2f, center.y * (2 - MARK_RATIO - MARK_OFFSET_RATIO) + markBounds.y - sixOCSvg.documentHeight * scales[2], null)
        // 9
        canvas.drawBitmap(majorBitmap[3]!!, center.x * MARK_OFFSET_RATIO, center.y - nineOCSvg.documentHeight * scales[3] / 2f, null)
    }

    private fun renderHand(canvas: Canvas, angle: Float, rect: RectF, rotationPoint: PointF, svg: SVG?) {
        if (svg == null) return
        canvas.save()
        canvas.translate(center.x - rotationPoint.x, center.y - rotationPoint.y)
        canvas.rotate(angle, rotationPoint.x, rotationPoint.y)
        svg.renderToCanvas(canvas, rect)
        canvas.restore()
    }

    private fun displayDate(canvas: Canvas, d: Date) {
        val dateFormatted = mDateFormat.format(d)
        val bounds = Rect()
        mHandPaint.getTextBounds(dateFormatted, 0, dateFormatted.length, bounds)
        canvas.drawText(dateFormatted, center.x - bounds.width() / 2f, center.y * 1.5f + bounds.height() / 2f, mHandPaint)
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
        val charging = chargePlug == BatteryManager.BATTERY_PLUGGED_AC || chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val noteBmp = if (charging) majorBitmap[6] else majorBitmap[5]
        val xmin = center.x * (1 + STAFF_X_RATIO_START)
        val xmax = center.x * (1 + STAFF_X_RATIO_END)
        val ymax = center.y * STAFF_Y_RATIO_END
        val ystep = center.y * (STAFF_Y_RATIO_END - STAFF_Y_RATIO_START) / 4
        val ynote = ymax - ystep * (Math.floor((batteryPct * 10).toDouble()).toInt() / 2f)
        canvas.drawBitmap(noteBmp!!, xmin + (xmax - xmin) * 0.5f - noteBmp.width / 2f, ynote, mStaffPaint)
    }

    override fun onDestroy() {
        context.unregisterReceiver(batteryReceiver)
        super.onDestroy()
    }

    private fun updateLayout(bounds: Rect) {
        center = PointF(bounds.width() / 2f, bounds.height() / 2f)
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

    private fun createBitmapsFromSvgs() {
        createBitmapFromSvg(twelveOCSvg, mark12Bounds, 0, false)
        createBitmapFromSvg(threeOCSvg, markBounds, 1, false)
        createBitmapFromSvg(sixOCSvg, markBounds, 2, false)
        createBitmapFromSvg(nineOCSvg, markBounds, 3, false)
        createBitmapFromSvg(hourSvg, markHourBounds, 4, false)
        createBitmapFromSvg(noteSvg, markNoteBounds, 5, true)
        createBitmapFromSvg(noteAcSvg, markNoteBounds, 6, true)
    }

    private fun createBitmapFromSvg(svg: SVG, bounds: PointF, idx: Int, isForcedY: Boolean) {
        scales[idx] = if (isForcedY) bounds.y / svg.documentHeight else Math.min(bounds.x / svg.documentWidth, bounds.y / svg.documentHeight)
        val bmp = Bitmap.createBitmap(Math.round(svg.documentWidth * scales[idx]), Math.round(svg.documentHeight * scales[idx]), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scales[idx], scales[idx])
        svg.renderToCanvas(canvas)
        majorBitmap[idx] = bmp
    }

    private fun calcMarkHourLocations(bitmap: Bitmap): Array<PointF> {
        val locations = Array(HOUR_ANGLES.size) { PointF() }
        val isRound = context.resources.configuration.isScreenRound
        val hourRatio = if (isRound) MARK_HOUR_RATIO else 0f
        val offset = 1 - hourRatio - MARK_OFFSET_RATIO
        val halfSize = PointF(bitmap.width / 2f, bitmap.height / 2f)
        for (i in HOUR_ANGLES.indices) {
            locations[i].offset(
                center.x * (1 + Math.sin(HOUR_ANGLES[i]) * offset).toFloat() - halfSize.x,
                center.y * (1 - Math.cos(HOUR_ANGLES[i]) * offset).toFloat() - halfSize.y
            )
        }
        return locations
    }

    companion object {
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
            30 * Math.PI / 180, 60 * Math.PI / 180, 120 * Math.PI / 180, 150 * Math.PI / 180,
            210 * Math.PI / 180, 240 * Math.PI / 180, 300 * Math.PI / 180, 330 * Math.PI / 180
        )
    }
}
