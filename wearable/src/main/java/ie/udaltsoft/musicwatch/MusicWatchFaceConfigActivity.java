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

/**
 * Wearable config
 */
public class MusicWatchFaceConfigActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "MusicWatchFaceCfgActvty";

    private GoogleApiClient mGoogleApiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.music_watch_config_wvs);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
    }

    private ImageButton getButtonForInstrument(String instrument) {
        final int id = getResources().getIdentifier(instrument + "_btn", "id", getPackageName());
        return (ImageButton) findViewById(id);
    }

    private void configureClickListeners() {
        final String[] instruments = getResources().getStringArray(R.array.instruments_array);
        for (final String instrument : instruments) {
            final ImageButton btn = this.getButtonForInstrument(instrument);
            if (btn != null) {
                btn.setTag(instrument);
                btn.setOnClickListener(this);
            }
        }
    }

    private void highlightSelectedInstrument() {
        MusicWatchFaceUtil.fetchConfigDataMap(mGoogleApiClient, new MusicWatchFaceUtil.FetchConfigDataMapCallback() {
            @Override
            public void onConfigDataMapFetched(DataMap config) {
                final String selectedInstrument = config.getString(MusicWatchFaceUtil.KEY_INSTRUMENT,
                        MusicWatchFaceUtil.INSTRUMENT_DEFAULT);
                final ImageButton btn = getButtonForInstrument(selectedInstrument);
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

    private void updateConfigDataItem(final String instument) {
        DataMap configKeysToOverwrite = new DataMap();
        configKeysToOverwrite.putString(MusicWatchFaceUtil.KEY_INSTRUMENT,
                instument);
        MusicWatchFaceUtil.overwriteKeysInConfigDataMap(mGoogleApiClient, configKeysToOverwrite);
    }

    @Override
    public void onClick(View v) {
        final String instrument = (String)v.getTag();
        Log.i(TAG, "Selected instrument: " + instrument);
        updateConfigDataItem(instrument);
        finish();
    }
}
