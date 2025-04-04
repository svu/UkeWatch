/*
 * Copyright (C) 2024 Sergey Udaltsov
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

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnGenericMotionListener
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import androidx.core.view.ViewConfigurationCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import com.google.android.gms.wearable.DataMap
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.FetchConfigDataMapCallback
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.fetchConfigDataMap
import ie.udaltsoft.musicwatch.MusicWatchFaceUtil.putConfigDataItem
import kotlin.math.roundToInt

/**
 * Wearable config
 */
class MusicWatchFaceConfigActivity : Activity() {
    private lateinit var mHeader: TextView
    private lateinit var mListView: WearableRecyclerView
    private val mSelectedInstruments = HashMap<String, String>()
    private lateinit var mCurrentConfigKey: String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.music_watch_config)
        mHeader = findViewById(R.id.header)
        mListView = preparePicker()
        mCurrentConfigKey = MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT
        fetchConfigDataMap(applicationContext, FetchConfigDataMapCallback { config: DataMap ->
            val hi = config.getString(
                MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT,
                MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT
            )
            val mi = config.getString(
                MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT,
                MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT
            )
            Log.i(TAG, "Initial set of instruments: $hi/$mi")
            mSelectedInstruments[MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT] = hi
            mSelectedInstruments[MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT] = mi
            scrollToSelected(hi, mListView)
        })
    }

    private fun scrollToSelected(
        instrument: String,
        view: WearableRecyclerView,
    ) {
        Log.d(TAG, "Scrolling to instrument /$instrument/")
        var idx = 0
        for (i in mAllInstrumentIds) {
            if (i == instrument) {
                Log.d(TAG, "Scrolling to position /$idx/")
                view.scrollToPosition(idx)
                break
            }
            idx++
        }
    }

    private fun preparePicker(): WearableRecyclerView {
        val listView = findViewById<WearableRecyclerView>(R.id.instrument_picker)
        val ctx = listView.context
        with(listView) {
            layoutManager = WearableLinearLayoutManager(ctx)
            isEdgeItemsCenteringEnabled = true

            // Improves performance because we know changes in content do not change the layout size of
            // the RecyclerView.
            setHasFixedSize(true)
            initAllInstrumentIds(resources)
            adapter = InstrumentAdapter(mAllInstrumentIds)
            requestFocus()
            setOnGenericMotionListener(OnGenericMotionListener { v, ev ->
                with(ev) {
                    if (action == MotionEvent.ACTION_SCROLL && isFromSource(InputDeviceCompat.SOURCE_ROTARY_ENCODER)) {
                        val delta =
                            -getAxisValue(MotionEventCompat.AXIS_SCROLL) * ViewConfigurationCompat.getScaledVerticalScrollFactor(
                                ViewConfiguration.get(ctx), ctx
                            )
                        v.scrollBy(0, delta.roundToInt())
                        return@OnGenericMotionListener true
                    }
                }
                false
            })
        }
        return listView
    }

    private fun updateConfigDataItem(key: String, value: String) {
        databaseList()
        mSelectedInstruments[key] = value
        val configKeysToOverwrite = DataMap()
        with(configKeysToOverwrite) {
            putString(
                MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT,
                mSelectedInstruments[MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT]!!
            )
            putString(
                MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT,
                mSelectedInstruments[MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT]!!
            )
            Log.i(
                TAG,
                "Instrument just overwritten from UI: " + getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT) + "/" + getString(
                    MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT
                )
            )
        }
        putConfigDataItem(applicationContext, configKeysToOverwrite)
    }

    private inner class InstrumentAdapter(private val mInstrumentsList: List<String>) :
        RecyclerView.Adapter<InstrumentAdapter.ViewHolder>() {
        /**
         * Provides reference to the views for each data item. We don't maintain a reference to the
         * [ImageView] (representing the icon), because it does not change for each item. We
         * wanted to keep the sample simple, but you could add extra code to customize each icon.
         */
        private inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val mInstrumentPreview: ImageView = view.findViewById(R.id.instrument_preview)
            private lateinit var mInstrumentId: String

            override fun toString(): String {
                return mInstrumentId
            }

            fun setInstrumentId(instrumentId: String) {
                mInstrumentId = instrumentId
                val bmp = mBitmaps[instrumentId]
                with(mInstrumentPreview) {
                    if (bmp != null) {
                        setImageBitmap(bmp)
                    } else {
                        Log.e(TAG, "Could not find bitmap for $instrumentId")
                    }
                    cropToPadding = false
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            }

            fun setOnClickListener(listener: View.OnClickListener) {
                mInstrumentPreview.setOnClickListener(listener)
            }
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.instrument_picker_item, viewGroup, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
            val instr = mInstrumentsList[position]
            Log.d(TAG, "Element $position set: $instr")
            viewHolder.setOnClickListener {
                updateConfigDataItem(mCurrentConfigKey, instr)
                if (mCurrentConfigKey == MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT) {
                    mHeader.setText(R.string.minutes)
                    mCurrentConfigKey = MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT
                    scrollToSelected(mSelectedInstruments[mCurrentConfigKey]!!, mListView)
                } else {
                    finish()
                }
            }

            // Replaces content of view with correct element from data set
            viewHolder.setInstrumentId(instr)
        }

        // Return the size of your dataset (invoked by the layout manager)
        override fun getItemCount(): Int {
            return mInstrumentsList.size
        }
    }

    companion object {
        private const val TAG = "MusicWatchFaceConfig"
        private lateinit var mAllInstrumentIds: List<String>
        private lateinit var mCircleBorderPaint: Paint
        private val mBitmaps = HashMap<String, Bitmap>()
        private const val MAX_BMP_SIZE = 150f
        private const val REDUCED_INSTRUMENT_RATIO = 0.5f
        private fun initAllInstrumentIds(res: Resources) {
            if (!this::mAllInstrumentIds.isInitialized) {
                mAllInstrumentIds = listOf(*res.getStringArray(R.array.all_instruments_array))
                Log.d(TAG, "Loaded all instrument ids, total " + mAllInstrumentIds.size)
            }
        }

        @JvmStatic
        fun buildAllBitmaps(res: Resources, context: Context) {
            if (!this::mCircleBorderPaint.isInitialized) {
                mCircleBorderPaint = createBorderPaint(res)
            }
            initAllInstrumentIds(res)
            for (instrumentId in mAllInstrumentIds) {
                try {
                    Log.i(TAG, "The instrument bitmap was not built yet, building $instrumentId")
                    val bmp = buildBitmap(res, context, instrumentId)
                    mBitmaps[instrumentId] = bmp
                } catch (ex: SVGParseException) {
                    Log.e(TAG, "Could not build bitmap: $ex")
                    ex.printStackTrace()
                }
            }
        }

        private fun createBorderPaint(res: Resources): Paint {
            val paint = Paint()
            with(paint) {
                strokeWidth = 4f
                color = ResourcesCompat.getColor(res, R.color.config_activity_circle_border, null)
                isAntiAlias = true
                style = Paint.Style.STROKE
            }
            return paint
        }

        @Throws(SVGParseException::class)
        private fun buildBitmap(res: Resources, context: Context, instrument: String): Bitmap {
            val svgResourceId = res.getIdentifier(instrument + "_hand", "raw", context.packageName)
            val svg = SVG.getFromResource(res, svgResourceId)

            // for vertical instruments, <1
            val svgWHAspectRatio = svg.documentAspectRatio
            val svgSize = PointF(
                svg.documentViewBox.width(), svg.documentViewBox.height()
            )

            /*Log.i(TAG, "Hand: " + instrument +
                ", SVG w/h ratio: " + svgWHAspectRatio +
                " or may be " + (svgSize.x / svgSize.y) +
                ", SVG: " + svgSize.x + ":" + svgSize.y);*/
            // for vertical instruments, bmpSize.x < bmpSize.y, they will be horizontal
            val bmpSize = PointF(MAX_BMP_SIZE, MAX_BMP_SIZE / 2f)
            val scaledH = bmpSize.x * svgWHAspectRatio
            //Log.i(TAG, "BMP sizes: " + bmpSize.x + ":" + bmpSize.y + "/scaled height:" + scaledH);
            val bmp = Bitmap.createBitmap(
                bmpSize.x.toInt(), bmpSize.y.toInt(), Bitmap.Config.ARGB_8888
            )
            val scale = scaledH / svgSize.x * REDUCED_INSTRUMENT_RATIO
            //Log.i(TAG, "scale, converting svg to bmp: " + scale);
            val canvas = Canvas(bmp)

            with(canvas) {
                /*Paint pb = new Paint();
                pb.setStrokeWidth(10);
                pb.setColor(Color.BLUE);

                Paint pg = new Paint();
                pg.setStrokeWidth(10);
                pg.setColor(Color.GREEN);*/

                //drawRect(0, 0, bmpSize.x, bmpSize.y, pr);
                // V from left-top -> centre -> right-top
                drawOval(2f, 2f, bmpSize.x - 2, bmpSize.y - 2, mCircleBorderPaint)

                //canvas.drawLine(0, 0, bmpSize.x / 2, bmpSize.y / 2, pb);
                //canvas.drawLine(bmpSize.x, 0, bmpSize.x / 2, bmpSize.y / 2, pb);
                save()
                // This is to make sure scaled and unscaled centres are the same
                translate(
                    (bmpSize.x - svgSize.y * scale) / 2f, (bmpSize.y - svgSize.x * scale) / 2f
                )
                scale(scale, scale)

                // ^ from left-bottom -> centre -> right-bottom
                /*drawLine(0,
                        svgSize.x,
                        svgSize.y / 2,
                        svgSize.x / 2, pr);

                drawLine(svgSize.y / 2,
                        svgSize.x / 2,
                        svgSize.y,
                        svgSize.x, pr);*/
                val offset = PointF(svgSize.y / 2f, svgSize.x / 2f)
                //Log.i(TAG, "offset: " + offset.x + ":" + offset.y);
                translate(-offset.x + offset.y, -offset.x + offset.y)
                rotate(-90f, offset.x, offset.y)

                svg.renderToCanvas(this, RectF(0f, 0f, svgSize.x, svgSize.y))
                restore()
            }
            return bmp
        }
    }
}
