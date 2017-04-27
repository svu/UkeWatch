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

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.content.res.ResourcesCompat;
import android.support.wearable.view.CurvedChildLayoutManager;
import android.support.wearable.view.DefaultOffsettingHelper;
import android.support.wearable.view.WearableRecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Wearable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Wearable config
 */
public class MusicWatchFaceConfigActivity extends Activity {

    private static final String TAG = "MusicWatchFaceConfig";

    private GoogleApiClient mGoogleApiClient;
    private List<String> mAllInstrumentIds;
    private TextView mHeader;
    private WearableRecyclerView mListView;

    private final HashMap<String, String> mSelectedInstruments = new HashMap<>();
    private String mCurrentConfigKey;
    private Paint mCircleBorderPaint;
    private final HashMap<String, Bitmap> mBitmaps = new HashMap<>();

    private static final float MAX_BMP_SIZE = 150;
    private static final float REDUCED_INSTRUMENT_RATIO = 0.5f;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.music_watch_config);

        mHeader = (TextView) findViewById(R.id.header);

        mAllInstrumentIds = Arrays.asList(getResources().getStringArray(R.array.all_instruments_array));

        mListView = preparePicker();

        mCurrentConfigKey = MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT;

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        Log.i(TAG, "=== LOADING INSTRUMENTS ===");
        MusicWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient, new MusicWatchFaceUtil.FetchConfigDataMapCallback() {
            @Override
            public void onConfigDataMapFetched(DataMap config) {
                final String hi = config.getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT,
                        MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT);
                final String mi = config.getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT,
                        MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT);
                Log.i(TAG, "Initial set of instruments: " + hi + "/" + mi);
                mSelectedInstruments.put(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT, hi);
                mSelectedInstruments.put(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT, mi);
                scrollToSelected(hi, mListView);
            }
        });

        mCircleBorderPaint = createBorderPaint();
    }

    private Paint createBorderPaint() {
        final Paint paint = new Paint();
        paint.setStrokeWidth(4);
        paint.setColor(ResourcesCompat.getColor(getResources(), R.color.config_activity_circle_border, null));
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        return paint;

    }
    private void scrollToSelected(String instrument,
                                  WearableRecyclerView view) {
        Log.d(TAG, "Scrolling to instrument /" + instrument + "/");

        int idx = 0;
        for (String i : mAllInstrumentIds) {
            if (i.equals(instrument)) {
                Log.d(TAG, "Scrolling to position /" + idx + "/");
                view.scrollToPosition(idx);
                break;
            }
            idx++;
        }
    }

    @NonNull
    private WearableRecyclerView preparePicker() {
        final WearableRecyclerView listView = (WearableRecyclerView) findViewById(R.id.instrument_picker);

        listView.setCenterEdgeItems(true);

        listView.setLayoutManager(new CurvedChildLayoutManager(listView.getContext()));

        // Improves performance because we know changes in content do not change the layout size of
        // the RecyclerView.
        listView.setHasFixedSize(true);

        listView.setAdapter(new InstrumentAdapter(mAllInstrumentIds));

        return listView;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    protected void onStop() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        super.onStop();
    }

    private void updateConfigDataItem(String key, String value) {
        databaseList();

        mSelectedInstruments.put(key, value);
        DataMap configKeysToOverwrite = new DataMap();
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT,
                mSelectedInstruments.get(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT));
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT,
                mSelectedInstruments.get(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT));

        Log.i(TAG, "Instrument just overwritten from UI: " +
                configKeysToOverwrite.getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT) +
                "/" +
                configKeysToOverwrite.getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT));

        MusicWatchFaceUtil.putConfigDataItem(mGoogleApiClient, configKeysToOverwrite);
    }

    private Bitmap buildBitmap(Context context, String instrument) throws SVGParseException {
        final Resources r = getResources();
        final int svgResourceId = r.getIdentifier(instrument + "_hand", "raw", context.getPackageName());

        final SVG svg = SVG.getFromResource(r, svgResourceId);

        // for vertical instruments, <1
        final float svgWHAspectRatio = svg.getDocumentAspectRatio();
        final PointF svgSize = new PointF(svg.getDocumentViewBox().width(),
                svg.getDocumentViewBox().height());

        /*Log.i(TAG, "Hand: " + instrument +
                ", SVG w/h ratio: " + svgWHAspectRatio +
                " or may be " + (svgSize.x / svgSize.y) +
                ", SVG: " + svgSize.x + ":" + svgSize.y);*/
        // for vertical instruments, bmpSize.x < bmpSize.y, they will be horizontal
        final PointF bmpSize = new PointF(MAX_BMP_SIZE, MAX_BMP_SIZE/2f);

        final float scaledH = bmpSize.x * svgWHAspectRatio;
        //Log.i(TAG, "BMP sizes: " + bmpSize.x + ":" + bmpSize.y + "/scaled height:" + scaledH);

        final Bitmap bmp = Bitmap.createBitmap((int) bmpSize.x,
                (int) bmpSize.y,
                Bitmap.Config.ARGB_8888);
        final float scale = scaledH / svgSize.x * REDUCED_INSTRUMENT_RATIO;
        //Log.i(TAG, "scale, converting svg to bmp: " + scale);

        final Canvas canvas = new Canvas(bmp);

                /*Paint pb = new Paint();
                pb.setStrokeWidth(10);
                pb.setColor(Color.BLUE);

                Paint pg = new Paint();
                pg.setStrokeWidth(10);
                pg.setColor(Color.GREEN);*/

        //canvas.drawRect(0, 0, bmpSize.x, bmpSize.y, pr);
        // V from left-top -> centre -> right-top
        canvas.drawOval(2, 2, bmpSize.x - 2, bmpSize.y - 2, mCircleBorderPaint);

        //canvas.drawLine(0, 0, bmpSize.x / 2, bmpSize.y / 2, pb);
        //canvas.drawLine(bmpSize.x, 0, bmpSize.x / 2, bmpSize.y / 2, pb);

        canvas.save();
        // This is to make sure scaled and unscaled centres are the same
        canvas.translate((bmpSize.x - svgSize.y * scale) / 2f,
                (bmpSize.y - svgSize.x * scale) / 2f);
        canvas.scale(scale, scale);

        // ^ from left-bottom -> centre -> right-bottom
                /*canvas.drawLine(0,
                        svgSize.x,
                        svgSize.y / 2,
                        svgSize.x / 2, pr);

                canvas.drawLine(svgSize.y / 2,
                        svgSize.x / 2,
                        svgSize.y,
                        svgSize.x, pr);*/

        final PointF offset = new PointF(svgSize.y / 2f, svgSize.x / 2f);
        //Log.i(TAG, "offset: " + offset.x + ":" + offset.y);
        canvas.translate(-offset.x + offset.y, -offset.x + offset.y);
        canvas.rotate(-90f, offset.x, offset.y);

                /*canvas.drawRect(0, 0, svgw, svgh/2, pr);
                canvas.drawLine(svgw/4, 0, svgw/4, svgh/2, pg);
                canvas.drawCircle(svgw/2, 0, svgw/2, pb);
*/
        svg.renderToCanvas(canvas, new RectF(0, 0, svgSize.x, svgSize.y));
        canvas.restore();

        return bmp;
    }

    private class InstrumentAdapter extends
            WearableRecyclerView.Adapter<InstrumentAdapter.ViewHolder> {

        private static final String TAG = "InstrumentAdapter";

        private List<String> mInstrumentsList;

        /**
         * Provides reference to the views for each data item. We don't maintain a reference to the
         * {@link ImageView} (representing the icon), because it does not change for each item. We
         * wanted to keep the sample simple, but you could add extra code to customize each icon.
         */
        protected class ViewHolder extends WearableRecyclerView.ViewHolder {

            private final ImageView mInstrumentPreview;
            private final Context mContext;

            private String mInstrumentId;

            private ViewHolder(View view) {
                super(view);
                mInstrumentPreview = (ImageView) view.findViewById(R.id.instrument_preview);
                mContext = view.getContext();
            }

            @Override
            public String toString() { return mInstrumentId; }

            private void setInstrumentId(String instrumentId) {
                mInstrumentId = instrumentId;

                Bitmap bmp = mBitmaps.get(instrumentId);
                if (bmp == null) {
                    try {
                        Log.i(TAG, "The instrument bitmap was not built yet, building " + instrumentId);
                        bmp = buildBitmap(mContext, instrumentId);
                        mBitmaps.put(instrumentId, bmp);
                    } catch (SVGParseException ex) {
                        Log.e(TAG, "Could not build bitmap: " + ex);
                        ex.printStackTrace();
                    }
                }
                if (bmp != null) {
                    mInstrumentPreview.setImageBitmap(bmp);
                } else {
                    Log.e(TAG, "Could not find bitmap for " + instrumentId);
                }
                mInstrumentPreview.setCropToPadding(false);
                mInstrumentPreview.setAdjustViewBounds(true);
                mInstrumentPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }

            private void setOnClickListener(View.OnClickListener listener) {
                mInstrumentPreview.setOnClickListener(listener);
            }
        }

        private InstrumentAdapter(List<String> instruments) {
            mInstrumentsList = instruments;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
            View view = LayoutInflater.from(viewGroup.getContext())
                    .inflate(R.layout.instrument_picker_item, viewGroup, false);

            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder viewHolder, final int position) {
            final String instr = mInstrumentsList.get(position);
            Log.d(TAG, "Element " + position + " set: " + instr);

            viewHolder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                        updateConfigDataItem(mCurrentConfigKey, instr);

                        if (mCurrentConfigKey.equals(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT)) {
                            mHeader.setText(R.string.minutes);
                            mCurrentConfigKey = MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT;
                            scrollToSelected(mSelectedInstruments.get(mCurrentConfigKey), mListView);
                        } else {
                            finish();
                        }
                    }
            });

            // Replaces content of view with correct element from data set
            viewHolder.setInstrumentId(instr);
        }

        // Return the size of your dataset (invoked by the layout manager)
        @Override
        public int getItemCount() {
            return mInstrumentsList.size();
        }
    }
}
