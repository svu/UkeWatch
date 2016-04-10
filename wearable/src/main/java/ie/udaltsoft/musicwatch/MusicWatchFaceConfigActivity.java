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

import android.app.Activity;
import android.os.Bundle;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Wearable;

import java.util.Arrays;
import java.util.List;

/**
 * Wearable config
 */
public class MusicWatchFaceConfigActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "MusicWatchFaceCfgActvty";

    private GoogleApiClient mGoogleApiClient;
    private List<String> mHourInstruments;
    private List<String> mMinuteInstruments;
    private List<String> mPairedInstruments;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.music_watch_config_wvs);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();

        mHourInstruments = Arrays.asList(getResources().getStringArray(R.array.hour_instruments_array));
        mMinuteInstruments = Arrays.asList(getResources().getStringArray(R.array.minute_instruments_array));
        mPairedInstruments = Arrays.asList(getResources().getStringArray(R.array.instruments_pairs_array));
    }

    private ImageButton getButtonForInstrumentPair(String instrumentPair) {
        final int id = getResources().getIdentifier(instrumentPair + "_btn", "id", getPackageName());
        return (ImageButton) findViewById(id);
    }

    private void configureClickListeners() {
        int idx = 0;
        for (final String instrumentPair : mPairedInstruments) {
            final ImageButton btn = this.getButtonForInstrumentPair(instrumentPair);
            if (btn != null) {
                btn.setTag(idx++);
                btn.setOnClickListener(this);
            }
        }
    }

    private void highlightSelectedInstrument() {
        MusicWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient, new MusicWatchFaceUtil.FetchConfigDataMapCallback() {
            @Override
            public void onConfigDataMapFetched(DataMap config) {
                final String selectedHourInstrument = config.getString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT,
                        MusicWatchFaceUtil.HOUR_INSTRUMENT_DEFAULT);
                final int idx = mHourInstruments.indexOf(selectedHourInstrument);
                final ImageButton btn = getButtonForInstrumentPair(mPairedInstruments.get(idx));
                btn.setPressed(true);

                // Unfortunately findViewById does not work earlier...
                configureClickListeners();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
        highlightSelectedInstrument();
    }

    @Override
    protected void onStop() {
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        super.onStop();
    }

    private void updateConfigDataItem(final int instrumentPairIdx) {
        DataMap configKeysToOverwrite = new DataMap();
        final String hourInstrument = mHourInstruments.get(instrumentPairIdx);
        final String minuteInstrument = mMinuteInstruments.get(instrumentPairIdx);
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_HOUR_INSTRUMENT, hourInstrument);
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_MINUTE_INSTRUMENT, minuteInstrument);
        MusicWatchFaceUtil.overwriteKeysInConfigDataMap(mGoogleApiClient, configKeysToOverwrite);
    }

    @Override
    public void onClick(View v) {
        final int instrumentPairIdx = (Integer) v.getTag();
        Log.i(TAG, "Selected instrument pair: " + instrumentPairIdx);
        updateConfigDataItem(instrumentPairIdx);
        finish();
    }
}
