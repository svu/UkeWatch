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
import android.support.v7.widget.RecyclerView;
import android.support.wearable.view.BoxInsetLayout;
import android.support.wearable.view.WearableListView;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
public class MusicWatchFaceConfigActivity extends Activity implements
        WearableListView.ClickListener, WearableListView.OnScrollListener {

    private static final String TAG = "MusicWatchFaceConfig";

    private GoogleApiClient mGoogleApiClient;
    private List<String> mAllInstruments;
    private TextView mHeader;
    private WearableListView mListView;

    private HashMap<String, String> mInstruments = new HashMap<>();
    private String mCurrentConfigKey;
    private Paint mCircleBorderPaint;
    private Paint mCirclePaint;
    private HashMap<String, Bitmap> mBitmaps = new HashMap<>();

    public static final float MAX_BMP_SIZE = 150;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.music_watch_config);

        mHeader = (TextView) findViewById(R.id.header);

        mAllInstruments = Arrays.asList(getResources().getStringArray(R.array.all_instruments_array));

        mListView = preparePicker(R.id.instrument_picker);

        mCurrentConfigKey = MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT;

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API).build();

        MusicWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient, new MusicWatchFaceUtil.FetchConfigDataMapCallback() {
            @Override
            public void onConfigDataMapFetched(DataMap config) {
                final String hi = config.getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT,
                        MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT);
                final String mi = config.getString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT,
                        MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT);
                Log.i(TAG, "Initial set of instruments: " + hi + "/" + mi);
                mInstruments.put(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT, hi);
                mInstruments.put(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT, mi);
                scrollToSelected(hi, mListView);
            }
        });

        mCircleBorderPaint = new Paint();
        mCircleBorderPaint.setStrokeWidth(10);
        mCircleBorderPaint.setColor(getResources().getColor(R.color.config_activity_circle_border));
        mCircleBorderPaint.setAntiAlias(true);

        mCirclePaint = new Paint();
        mCirclePaint.setColor(getResources().getColor(R.color.config_activity_circle_background));
        mCirclePaint.setAntiAlias(true);
    }

    private void scrollToSelected(String instrument,
                                  WearableListView view) {
        int idx = 0;
        for (String i : mAllInstruments) {
            if (i.equals(instrument)) {
                view.scrollToPosition(idx);
                break;
            }
            idx++;
        }
    }

    @NonNull
    private WearableListView preparePicker(int id) {
        final WearableListView listView = (WearableListView) findViewById(id);
        BoxInsetLayout content = (BoxInsetLayout) findViewById(R.id.content);
        // BoxInsetLayout adds padding by default on round devices. Add some on square devices.
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (!insets.isRound()) {
                    v.setPaddingRelative(
                            getResources().getDimensionPixelSize(R.dimen.config_content_padding_start),
                            v.getPaddingTop(),
                            v.getPaddingEnd(),
                            v.getPaddingBottom());
                }
                return v.onApplyWindowInsets(insets);
            }
        });

        listView.setHasFixedSize(true);
        listView.setClickListener(this);
        listView.addOnScrollListener(this);
        listView.setAdapter(new InstrumentListAdapter());

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

    @Override // WearableListView.ClickListener
    public void onClick(WearableListView.ViewHolder viewHolder) {
        InstrumentItemViewHolder colorItemViewHolder = (InstrumentItemViewHolder) viewHolder;
        updateConfigDataItem(mCurrentConfigKey, colorItemViewHolder.mColorItem.getInstrument());

        if (mCurrentConfigKey.equals(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT)) {
            mHeader.setText(R.string.minute);
            mCurrentConfigKey = MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT;
            scrollToSelected(mInstruments.get(mCurrentConfigKey), mListView);
        } else {
            finish();
        }
    }

    @Override // WearableListView.ClickListener
    public void onTopEmptyRegionClick() {
    }

    @Override // WearableListView.OnScrollListener
    public void onScroll(int scroll) {
    }

    @Override // WearableListView.OnScrollListener
    public void onAbsoluteScrollChange(int scroll) {
        float newTranslation = Math.min(-scroll, 0);
        mHeader.setTranslationY(newTranslation);
    }

    @Override // WearableListView.OnScrollListener
    public void onScrollStateChanged(int scrollState) {
    }

    @Override // WearableListView.OnScrollListener
    public void onCentralPositionChanged(int centralPosition) {
    }

    private void updateConfigDataItem(String key, String value) {
        databaseList();

        mInstruments.put(key, value);
        DataMap configKeysToOverwrite = new DataMap();
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT,
                mInstruments.get(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT));
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT,
                mInstruments.get(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT));

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
        final PointF bmpSize = new PointF(MAX_BMP_SIZE, MAX_BMP_SIZE);

        final float scaledH = MAX_BMP_SIZE * svgWHAspectRatio;
        //Log.i(TAG, "BMP sizes: " + bmpSize.x + ":" + bmpSize.y + "/scaled height:" + scaledH);

        final Bitmap bmp = Bitmap.createBitmap((int) bmpSize.x,
                (int) bmpSize.y,
                Bitmap.Config.ARGB_8888);
        final float reduceInstrumentRatio = 0.8f;
        final float scale = scaledH / svgSize.x * reduceInstrumentRatio;
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
        canvas.drawCircle(bmpSize.x / 2, bmpSize.y / 2, bmpSize.x / 2, mCircleBorderPaint);
        canvas.drawCircle(bmpSize.x / 2, bmpSize.y / 2, bmpSize.x / 2 * 0.95f, mCirclePaint);

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

    private class InstrumentListAdapter extends WearableListView.Adapter {

        public InstrumentListAdapter() {
        }

        @Override
        public InstrumentItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new InstrumentItemViewHolder(new InstrumentItem(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(WearableListView.ViewHolder holder, int position) {
            InstrumentItemViewHolder colorItemViewHolder = (InstrumentItemViewHolder) holder;
            colorItemViewHolder.mColorItem.setInstrument(mAllInstruments.get(position));

            RecyclerView.LayoutParams layoutParams =
                    new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);

            int colorPickerItemMargin = (int) getResources()
                    .getDimension(R.dimen.config_instrument_picker_item_margin);
            // Add margins to first and last item to make it possible for user to tap on them.
            if (position == 0) {
                layoutParams.setMargins(0, colorPickerItemMargin, 0, 0);
            } else if (position == mAllInstruments.size() - 1) {
                layoutParams.setMargins(0, 0, 0, colorPickerItemMargin);
            } else {
                layoutParams.setMargins(0, 0, 0, 0);
            }
            colorItemViewHolder.itemView.setLayoutParams(layoutParams);
        }

        @Override
        public int getItemCount() {
            return mAllInstruments.size();
        }
    }

    /**
     * The layout of a instrument item.
     */
    private class InstrumentItem extends LinearLayout implements
            WearableListView.OnCenterProximityListener {
        /**
         * The duration of the expand/shrink animation.
         */
        private static final int ANIMATION_DURATION_MS = 150;

        private static final float SHRINK_PREVIEW_ALPHA = .5f;
        private static final float EXPAND_PREVIEW_ALPHA = 1f;

        private final ImageView mInstrumentPreview;

        private final ObjectAnimator mExpandPreviewAnimator;

        private final ObjectAnimator mShrinkPreviewAnimator;

        private String mInstrument;

        public InstrumentItem(Context context) {
            super(context);
            View.inflate(context, R.layout.instrument_picker_item, this);

            mInstrumentPreview = (ImageView) findViewById(R.id.instrument_preview);

            mShrinkPreviewAnimator = ObjectAnimator.ofFloat(mInstrumentPreview, "alpha",
                    EXPAND_PREVIEW_ALPHA, SHRINK_PREVIEW_ALPHA).setDuration(ANIMATION_DURATION_MS);

            mExpandPreviewAnimator = ObjectAnimator.ofFloat(mInstrumentPreview, "alpha",
                    SHRINK_PREVIEW_ALPHA, EXPAND_PREVIEW_ALPHA).setDuration(ANIMATION_DURATION_MS);
        }

        @Override
        public void onCenterPosition(boolean animate) {
            if (animate) {
                mShrinkPreviewAnimator.cancel();
                if (!mExpandPreviewAnimator.isRunning()) {
                    mExpandPreviewAnimator.setFloatValues(mInstrumentPreview.getAlpha(), EXPAND_PREVIEW_ALPHA);
                    mExpandPreviewAnimator.start();
                }
            } else {
                mExpandPreviewAnimator.cancel();
                mInstrumentPreview.setAlpha(EXPAND_PREVIEW_ALPHA);
            }
        }

        @Override
        public void onNonCenterPosition(boolean animate) {
            if (animate) {
                mExpandPreviewAnimator.cancel();
                if (!mShrinkPreviewAnimator.isRunning()) {
                    mShrinkPreviewAnimator.setFloatValues(mInstrumentPreview.getAlpha(), SHRINK_PREVIEW_ALPHA);
                    mShrinkPreviewAnimator.start();
                }
            } else {
                mShrinkPreviewAnimator.cancel();
                mInstrumentPreview.setAlpha(SHRINK_PREVIEW_ALPHA);
            }
        }

        private void setInstrument(String instrument) {
            mInstrument = instrument;

            Bitmap bmp = mBitmaps.get(instrument);
            if (bmp == null) {
                try {
                    Log.i(TAG, "The instrument bitmap was not built yet, building " + instrument);
                    bmp = buildBitmap(getContext(), instrument);
                    mBitmaps.put(instrument, bmp);
                } catch (SVGParseException ex) {
                    Log.e(TAG, "Could not build bitmap: " + ex);
                    ex.printStackTrace();
                }
            }
            if (bmp != null) {
                mInstrumentPreview.setImageBitmap(bmp);
            } else {
                Log.e(TAG, "Could not find bitmap for " + instrument);
            }
            mInstrumentPreview.setCropToPadding(false);
            mInstrumentPreview.setAdjustViewBounds(true);
            mInstrumentPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        private String getInstrument() {
            return mInstrument;
        }
    }

    private static class InstrumentItemViewHolder extends WearableListView.ViewHolder {
        private final InstrumentItem mColorItem;

        public InstrumentItemViewHolder(InstrumentItem colorItem) {
            super(colorItem);
            mColorItem = colorItem;
        }
    }
}
