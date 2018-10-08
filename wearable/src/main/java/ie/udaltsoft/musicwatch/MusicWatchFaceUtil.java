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

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

public final class MusicWatchFaceUtil {

    public enum HandKind {HOUR, MINUTE}

    private static final String TAG = "MusicWatchFaceUtil";

    public static final String KEY_HOUR_INSTRUMENT = "HOUR_INSTRUMENT";
    public static final String KEY_MINUTE_INSTRUMENT = "MINUTE_INSTRUMENT";

    public static final String PATH_WITH_FEATURE = "/music_face_config";

    public static final String HOUR_INSTRUMENT_DEFAULT = "wood_uke";
    public static final String MINUTE_INSTRUMENT_DEFAULT = "pink_uke";

    public interface FetchConfigDataMapCallback {

        void onConfigDataMapFetched(DataMap config);
    }

    public static void fetchConfigDataMap(final Context context,
                                          final FetchConfigDataMapCallback callback) {
        Log.d(TAG, "fetchConfigDataMap: " + context);

        final DataClient dataClient = Wearable.getDataClient(context);
        final NodeClient nodeClient = Wearable.getNodeClient(context);
        final Task<Node> task = nodeClient.getLocalNode();
        task.addOnSuccessListener(new OnSuccessListener<Node>() {
            @Override
            public void onSuccess(Node result) {
                String localNode = result.getId();
                Uri uri = new Uri.Builder()
                        .scheme("wear")
                        .path(PATH_WITH_FEATURE)
                        .authority(localNode)
                        .build();
                final Task<DataItem> diTask = dataClient.getDataItem(uri);
                diTask.addOnSuccessListener(new DataItemSuccessCallback(callback));
            }
        });
    }

    public static void setDefaultValuesForMissingConfigKeys(DataMap config) {
        if (!config.containsKey(KEY_HOUR_INSTRUMENT)) {
            config.putString(KEY_HOUR_INSTRUMENT, HOUR_INSTRUMENT_DEFAULT);
        }
        if (!config.containsKey(KEY_MINUTE_INSTRUMENT)) {
            config.putString(KEY_MINUTE_INSTRUMENT, MINUTE_INSTRUMENT_DEFAULT);
        }
    }


    public static void putConfigDataItem(Context context, DataMap newConfig) {
        final DataClient dataClient = Wearable.getDataClient(context);
        final PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(PATH_WITH_FEATURE);
        final DataMap configToPut = putDataMapRequest.getDataMap();
        configToPut.putAll(newConfig);
        Log.i(TAG, "Instruments to be put as data item: " +
                configToPut.getString(KEY_HOUR_INSTRUMENT) +
                "/" +
                configToPut.getString(KEY_MINUTE_INSTRUMENT) + " to " + dataClient.getInstanceId());
        dataClient.putDataItem(putDataMapRequest.asPutDataRequest())
                .addOnSuccessListener(new OnSuccessListener<DataItem>() {
                    @Override
                    public void onSuccess(DataItem dataItem) {
                        Log.d(TAG, "putDataItem.onSuccess: " + dataItem);
                    }
                });
    }

    private static class DataItemSuccessCallback implements OnSuccessListener<DataItem> {

        private final FetchConfigDataMapCallback mCallback;

        DataItemSuccessCallback(FetchConfigDataMapCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onSuccess(DataItem configDataItem) {
            Log.d(TAG, "DataItemSuccessCallback.onSuccess: " + configDataItem);
            if (configDataItem != null) {
                final DataMapItem dataMapItem = DataMapItem.fromDataItem(configDataItem);
                final DataMap config = dataMapItem.getDataMap();
                mCallback.onConfigDataMapFetched(config);
            } else {
                mCallback.onConfigDataMapFetched(new DataMap());
            }
        }
    }

    public static void addDataListener(Context context, DataClient.OnDataChangedListener listener) {
        final DataClient dataClient = Wearable.getDataClient(context);
        final Uri uri = new Uri.Builder()
                .scheme("wear")
                .path(MusicWatchFaceUtil.PATH_WITH_FEATURE)
                .build();
        Log.d(TAG, "<<< Adding listener to data client: " + uri);
        dataClient.addListener(listener, uri, DataClient.FILTER_PREFIX);
    }

    public static void removeDataListener(Context context, DataClient.OnDataChangedListener listener) {
        final DataClient dataClient = Wearable.getDataClient(context);
        Log.d(TAG, ">>> Removing listener from data client");
        dataClient.removeListener(listener);
    }

    private MusicWatchFaceUtil() {
    }
}
