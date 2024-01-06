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

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataClient.OnDataChangedListener
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object MusicWatchFaceUtil {
    private const val TAG = "MusicWatchFaceUtil"
    const val KEY_HOUR_INSTRUMENT = "HOUR_INSTRUMENT"
    const val KEY_MINUTE_INSTRUMENT = "MINUTE_INSTRUMENT"
    const val PATH_WITH_FEATURE = "/music_face_config"
    const val HOUR_INSTRUMENT_DEFAULT = "wood_uke"
    const val MINUTE_INSTRUMENT_DEFAULT = "pink_uke"

    @JvmStatic
    fun fetchConfigDataMap(
        context: Context,
        callback: FetchConfigDataMapCallback
    ) {
        Log.d(TAG, "fetchConfigDataMap: $context")
        val dataClient = Wearable.getDataClient(context)
        val nodeClient = Wearable.getNodeClient(context)
        val task = nodeClient.localNode
        task.addOnSuccessListener {
            val localNode = it.id
            val uri = Uri.Builder()
                .scheme("wear")
                .path(PATH_WITH_FEATURE)
                .authority(localNode)
                .build()
            val diTask = dataClient.getDataItem(uri)
            diTask.addOnSuccessListener(DataItemSuccessCallback(callback))
        }
    }

    @JvmStatic
    fun setDefaultValuesForMissingConfigKeys(config: DataMap) {
        with(config) {
            if (!containsKey(KEY_HOUR_INSTRUMENT)) {
                putString(KEY_HOUR_INSTRUMENT, HOUR_INSTRUMENT_DEFAULT)
            }
            if (!containsKey(KEY_MINUTE_INSTRUMENT)) {
                putString(KEY_MINUTE_INSTRUMENT, MINUTE_INSTRUMENT_DEFAULT)
            }
        }
    }

    @JvmStatic
    fun putConfigDataItem(context: Context, newConfig: DataMap) {
        val dataClient = Wearable.getDataClient(context)
        val putDataMapRequest = PutDataMapRequest.create(PATH_WITH_FEATURE)
        val configToPut = putDataMapRequest.dataMap
        with (configToPut) {
            putAll(newConfig)
            Log.i(
                TAG, "Instruments to be put as data item: " +
                        getString(KEY_HOUR_INSTRUMENT) +
                        "/" +
                        getString(KEY_MINUTE_INSTRUMENT) + " to " + dataClient
            )
        }
        dataClient.putDataItem(putDataMapRequest.asPutDataRequest())
            .addOnSuccessListener { dataItem: DataItem ->
                Log.d(
                    TAG,
                    "putDataItem.onSuccess: $dataItem"
                )
            }
    }

    @JvmStatic
    fun addDataListener(context: Context, listener: OnDataChangedListener) {
        val dataClient = Wearable.getDataClient(context)
        val uri = Uri.Builder()
            .scheme("wear")
            .path(PATH_WITH_FEATURE)
            .build()
        Log.d(TAG, "<<< Adding listener to data client: $uri")
        dataClient.addListener(listener, uri, DataClient.FILTER_PREFIX)
    }

    @JvmStatic
    fun removeDataListener(context: Context, listener: OnDataChangedListener) {
        val dataClient = Wearable.getDataClient(context)
        Log.d(TAG, ">>> Removing listener from data client")
        dataClient.removeListener(listener)
    }

    enum class HandKind {
        HOUR,
        MINUTE
    }

    interface FetchConfigDataMapCallback {
        fun onConfigDataMapFetched(config: DataMap?)
    }

    private class DataItemSuccessCallback(private val mCallback: FetchConfigDataMapCallback) :
        OnSuccessListener<DataItem?> {
        override fun onSuccess(configDataItem: DataItem?) {
            Log.d(TAG, "DataItemSuccessCallback.onSuccess: $configDataItem")
            if (configDataItem != null) {
                val dataMapItem = DataMapItem.fromDataItem(configDataItem)
                mCallback.onConfigDataMapFetched(dataMapItem.dataMap)
            } else {
                mCallback.onConfigDataMapFetched(DataMap())
            }
        }
    }
}
