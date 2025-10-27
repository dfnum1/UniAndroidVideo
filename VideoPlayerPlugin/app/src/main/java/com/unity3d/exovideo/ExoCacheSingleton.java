package com.unity3d.exovideo;

import android.content.Context;
import android.util.Log;

import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;

public final class ExoCacheSingleton {
    private static final String TAG = "ExoCacheSingleton";
    private static SimpleCache sCache;
    private static File sDownloadDir;

    public static synchronized Cache getCache(Context context) {
        if (sCache == null) {
            File dir = getDownloadDir(context);
            sCache = new SimpleCache(
                    new File(dir, "exocache"),
                    new NoOpCacheEvictor(),
                    new ExoDatabaseProvider(context)
            );
            Log.d(TAG, "SimpleCache created @ " + dir.getAbsolutePath());
        }
        return sCache;
    }

    private static File getDownloadDir(Context context) {
        if (sDownloadDir == null) {
            sDownloadDir = context.getExternalFilesDir(null);
            if (sDownloadDir == null) sDownloadDir = context.getFilesDir();
        }
        return sDownloadDir;
    }
}