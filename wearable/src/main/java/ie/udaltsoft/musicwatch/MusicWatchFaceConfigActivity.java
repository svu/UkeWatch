package ie.udaltsoft.musicwatch;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.os.Bundle;
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
import java.util.Comparator;

/**
 * Wearable config
 */
public class MusicWatchFaceConfigActivity extends Activity implements
        WearableListView.ClickListener, WearableListView.OnScrollListener {

    private static final String TAG = "MusicWatchFaceConfig";

    private GoogleApiClient mGoogleApiClient;
    private TextView mHeader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.music_watch_config);

        mHeader = (TextView) findViewById(R.id.header);
        final WearableListView listView = (WearableListView) findViewById(R.id.instrument_picker);
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

        final String[] instruments = getResources().getStringArray(R.array.instruments_array);
        listView.setAdapter(new InstrumentListAdapter(instruments));

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();


        MusicWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient, new MusicWatchFaceUtil.FetchConfigDataMapCallback() {
            @Override
            public void onConfigDataMapFetched(DataMap config) {
                final int newPos = Arrays.binarySearch(instruments,
                        config.getString(MusicWatchFaceUtil.KEY_INSTRUMENT,
                                MusicWatchFaceUtil.INSTRUMENT_DEFAULT));
                if (newPos != -1)
                    listView.scrollToPosition(newPos);
            }
        });
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
        updateConfigDataItem(colorItemViewHolder.mColorItem.getInstrumentId());
        finish();
    }

    @Override // WearableListView.ClickListener
    public void onTopEmptyRegionClick() {}

    @Override // WearableListView.OnScrollListener
    public void onScroll(int scroll) {}

    @Override // WearableListView.OnScrollListener
    public void onAbsoluteScrollChange(int scroll) {
        float newTranslation = Math.min(-scroll, 0);
        mHeader.setTranslationY(newTranslation);
    }

    @Override // WearableListView.OnScrollListener
    public void onScrollStateChanged(int scrollState) {}

    @Override // WearableListView.OnScrollListener
    public void onCentralPositionChanged(int centralPosition) {}

    private void updateConfigDataItem(final String instument) {
        DataMap configKeysToOverwrite = new DataMap();
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_INSTRUMENT,
                instument);
        MusicWatchFaceUtil.overwriteKeysInConfigDataMap(mGoogleApiClient, configKeysToOverwrite);
    }

    private class InstrumentListAdapter extends WearableListView.Adapter {
        private final String[] mInstruments;

        public InstrumentListAdapter(String[] instruments) {
            mInstruments = instruments;
        }

        @Override
        public InstrumentItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new InstrumentItemViewHolder(new InstrumentItem(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(WearableListView.ViewHolder holder, int position) {
            InstrumentItemViewHolder colorItemViewHolder = (InstrumentItemViewHolder) holder;
            String instrumentName = mInstruments[position];
            colorItemViewHolder.mColorItem.setInstrumentId(instrumentName);

            RecyclerView.LayoutParams layoutParams =
                    new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            int colorPickerItemMargin = (int) getResources()
                    .getDimension(R.dimen.config_instrument_picker_item_margin);
            // Add margins to first and last item to make it possible for user to tap on them.
            if (position == 0) {
                layoutParams.setMargins(0, colorPickerItemMargin, 0, 0);
            } else if (position == mInstruments.length - 1) {
                layoutParams.setMargins(0, 0, 0, colorPickerItemMargin);
            } else {
                layoutParams.setMargins(0, 0, 0, 0);
            }
            colorItemViewHolder.itemView.setLayoutParams(layoutParams);
        }

        @Override
        public int getItemCount() {
            return mInstruments.length;
        }
    }

    /** The layout of a instrument item. */
    private static class InstrumentItem extends LinearLayout implements
            WearableListView.OnCenterProximityListener {
        /** The duration of the expand/shrink animation. */
        private static final int ANIMATION_DURATION_MS = 150;

        private static final float SHRINK_PREVIEW_ALPHA = .5f;
        private static final float EXPAND_PREVIEW_ALPHA = 1f;

        private static final float MAX_BMP_SIZE = 1000;

        private final ImageView mInstrumentPreview;

        private final ObjectAnimator mExpandPreviewAnimator;

        private final ObjectAnimator mShrinkPreviewAnimator;

        private String mInstrumentId;

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

        private void setInstrumentId(String instrumentId) {
            mInstrumentId = instrumentId;

            try {
                int svgResourceId = -1;
                final Resources r = getResources();
                if (r.getString(R.string.instrument_uke).equals(instrumentId)) {
                    svgResourceId = R.raw.uke_hour_hand;
                } else if (r.getString(R.string.instrument_violin).equals(instrumentId)) {
                    svgResourceId = R.raw.violin_hour_hand;
                }
                final SVG svg = SVG.getFromResource(getResources(), svgResourceId);
                final float ar = svg.getDocumentAspectRatio();

                final float bmpw = MAX_BMP_SIZE;
                final float bmph = MAX_BMP_SIZE * ar;

                // scaled svg - vertically placed, height = bitmap
                final float ssvgw = bmph * ar;

                final Bitmap bmp = Bitmap.createBitmap((int) bmpw, (int) bmph,
                        Bitmap.Config.ARGB_8888);
                final float scale =  bmph / ssvgw;

                final Canvas canvas = new Canvas(bmp);
                canvas.save();
                canvas.scale(scale, scale);
                final PointF offset = new PointF((bmpw - ssvgw)/2, bmph);
                canvas.translate(-offset.x, -offset.y);
                canvas.rotate(90f, offset.x, offset.y);
                svg.renderToCanvas(canvas);
                canvas.restore();
                mInstrumentPreview.setImageBitmap(bmp);
            } catch (SVGParseException e) {
                e.printStackTrace();
            }
        }

        private String getInstrumentId() {
            return mInstrumentId;
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
