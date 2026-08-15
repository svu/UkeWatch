/*
 * Copyright (C) 2024-2026 Sergey Udaltsov
 * All rights reserved.
 */
package ie.udaltsoft.musicwatch

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.watchface.editor.EditorSession
import androidx.wear.watchface.style.UserStyleSetting
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.fetchConfigDataMap
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.putConfigDataItem
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import androidx.lifecycle.lifecycleScope

class MusicWatchFaceConfigActivity : ComponentActivity() {

    private lateinit var editorSession: EditorSession
    private val bitmaps = mutableStateOf<Map<String, Bitmap>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MusicWatchTheme {
                ConfigScreen()
            }
        }

        lifecycleScope.launch {
            editorSession = EditorSession.createOnWatchEditorSession(this@MusicWatchFaceConfigActivity)
            loadBitmaps()
        }
    }

    private fun loadBitmaps() {
        val res = resources
        val allInstruments = res.getStringArray(R.array.all_instruments_array)
        val borderPaint = createBorderPaint(res)
        val map = mutableMapOf<String, Bitmap>()
        
        for (instrumentId in allInstruments) {
            try {
                map[instrumentId] = buildBitmap(res, applicationContext, instrumentId, borderPaint)
            } catch (e: Exception) {
                Log.e(TAG, "Error building bitmap for $instrumentId", e)
            }
        }
        bitmaps.value = map
    }

    @Composable
    private fun ConfigScreen() {
        var currentKey by remember { mutableStateOf(MusicWatchFaceUtil.ID_HOUR_INSTRUMENT) }
        val columnState = rememberTransformingLazyColumnState()
        val transformationSpec = rememberTransformationSpec()
        val scope = rememberCoroutineScope()

        AppScaffold {
            ScreenScaffold(scrollState = columnState) { contentPadding ->
                TransformingLazyColumn(
                    state = columnState,
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                                .minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            Text(
                                text = if (currentKey == MusicWatchFaceUtil.ID_HOUR_INSTRUMENT) 
                                    stringResource(R.string.hours) else stringResource(R.string.minutes),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    val allInstruments = resources.getStringArray(R.array.all_instruments_array)
                    items(allInstruments.size) { index ->
                        val instrumentId = allInstruments[index]
                        val bitmap = bitmaps.value[instrumentId]

                        Button(
                            onClick = {
                                scope.launch {
                                    updateStyle(currentKey, instrumentId)
                                    if (currentKey == MusicWatchFaceUtil.ID_HOUR_INSTRUMENT) {
                                        currentKey = MusicWatchFaceUtil.ID_MINUTE_INSTRUMENT
                                        columnState.scrollToItem(0)
                                    } else {
                                        finish()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                                .minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                            transformation = SurfaceTransformation(transformationSpec)
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = instrumentId,
                                    modifier = Modifier.fillMaxSize().padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateStyle(settingId: String, optionId: String) {
        val currentStyle = editorSession.userStyle.value.toMutableUserStyle()
        val setting = editorSession.userStyleSchema[UserStyleSetting.Id(settingId)]!!
        currentStyle[setting] = UserStyleSetting.Option.Id(optionId)
        editorSession.userStyle.value = currentStyle.toUserStyle()
        
        // Also update DataClient for phone sync
        // Get current data map and update it
        val config = com.google.android.gms.wearable.DataMap().apply {
            putString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT, 
                currentStyle[editorSession.userStyleSchema[UserStyleSetting.Id(MusicWatchFaceUtil.ID_HOUR_INSTRUMENT)]!!]!!.id.value.decodeToString())
            putString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT, 
                currentStyle[editorSession.userStyleSchema[UserStyleSetting.Id(MusicWatchFaceUtil.ID_MINUTE_INSTRUMENT)]!!]!!.id.value.decodeToString())
        }
        putConfigDataItem(applicationContext, config)
    }

    companion object {
        private const val TAG = "MusicWatchFaceConfig"
        private const val MAX_BMP_SIZE = 150f
        private const val REDUCED_INSTRUMENT_RATIO = 0.5f

        private fun createBorderPaint(res: Resources): Paint {
            return Paint().apply {
                strokeWidth = 4f
                color = ResourcesCompat.getColor(res, R.color.config_activity_circle_border, null)
                isAntiAlias = true
                style = Paint.Style.STROKE
            }
        }

        private fun buildBitmap(res: Resources, context: Context, instrument: String, borderPaint: Paint): Bitmap {
            val svgResourceId = res.getIdentifier(instrument + "_hand", "raw", context.packageName)
            val svg = SVG.getFromResource(res, svgResourceId)
            val svgSize = PointF(svg.documentViewBox.width(), svg.documentViewBox.height())
            val svgWHAspectRatio = svg.documentAspectRatio

            val bmpSize = PointF(MAX_BMP_SIZE, MAX_BMP_SIZE / 2f)
            val scaledH = bmpSize.x * svgWHAspectRatio
            val bmp = Bitmap.createBitmap(bmpSize.x.toInt(), bmpSize.y.toInt(), Bitmap.Config.ARGB_8888)
            val scale = scaledH / svgSize.x * REDUCED_INSTRUMENT_RATIO
            val canvas = Canvas(bmp)

            with(canvas) {
                drawOval(2f, 2f, bmpSize.x - 2, bmpSize.y - 2, borderPaint)
                save()
                translate((bmpSize.x - svgSize.y * scale) / 2f, (bmpSize.y - svgSize.x * scale) / 2f)
                scale(scale, scale)
                val offset = PointF(svgSize.y / 2f, svgSize.x / 2f)
                translate(-offset.x + offset.y, -offset.x + offset.y)
                rotate(-90f, offset.x, offset.y)
                svg.renderToCanvas(this, RectF(0f, 0f, svgSize.x, svgSize.y))
                restore()
            }
            return bmp
        }
        
        fun buildAllBitmaps(res: Resources, context: Context) {
            // No-op for compatibility, but the activity handles it now.
        }
    }
}
