package com.unity3d.exovideo;

import android.content.Context;
import android.net.Uri;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.NoOpCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;
import com.google.android.exoplayer2.util.Util;
import com.twobigears.audio360.AudioEngine;
import com.twobigears.audio360.SpatDecoderQueue;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.MediaMetadata;
import com.google.android.exoplayer2.device.DeviceInfo;
import com.google.android.exoplayer2.video.VideoSize;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import java.io.File;

public class VideoPlayer
{
    private final String TAG = "ExoVideoPlayer";
    final float SAMPLE_RATE = 48000.f;
    final int BUFFER_SIZE = 1024;
    final int QUEUE_SIZE_IN_SAMPLES = 40960;

    private Surface mySurface;
    private Context myContext;
    private SimpleExoPlayer exoPlayer;
    private AudioEngine engine;
    private SpatDecoderQueue spat;
    private String filePath;
    private boolean readyToPlay;
    private volatile boolean isPlaying;
    private volatile int currentPlaybackState;
    private volatile int stereoMode = -1;
    private volatile int width;
    private volatile int height;
    private volatile long duration;
    private volatile boolean isLooping;
    private volatile long lastPlaybackPosition;
    private volatile long lastPlaybackUpdateTime;
    private volatile float lastPlaybackSpeed;
    private volatile boolean isDirtySurfaceSize;

    private ExoPlayerUnity m_ExoPlayerUnity;


    public VideoPlayer(ExoPlayerUnity unity, Context context, String url)
    {
        isDirtySurfaceSize = false;
        m_ExoPlayerUnity = unity;
        myContext = context;
        filePath = url;
    }

    public void PrepareBytes(byte[] buffers)
    {
        /*
        ByteArrayDataSource byteArrayDataSource = new ByteArrayDataSource(buffers);
        DataSource.Factory factory = new DataSource.Factory() {
            @Override
            public DataSource createDataSource() {
                return byteArrayDataSource;
            }
        };
        MediaSource videoSource = new ExtractorMediaSource.Factory(factory)
            .setExtractorsFactory(new DefaultExtractorsFactory())
            .createMediaSource(Uri.EMPTY);
        if(videoSource == null)
        {
            Log.e(TAG, "Failed to create MediaSource from byte array");
            return;
        }

        // 1. AudioEngine
        if (engine == null)
        {
            engine = AudioEngine.create(SAMPLE_RATE, BUFFER_SIZE, QUEUE_SIZE_IN_SAMPLES, myContext);
            spat = engine.createSpatDecoderQueue();
            engine.start();
        }

        // 2. VideoSource type
        DataSource.Factory dataSourceFactory = buildDataSourceFactory(myContext);
        Uri uri = ParseFilePath();
      //  MediaSource videoSource = buildMediaSource(myContext, uri, null, dataSourceFactory);
        Log.d(TAG, "Requested play of " + filePath + " uri: " + uri.toString());

        // 3. Exoplayer
        if (exoPlayer != null)
        {
            exoPlayer.release();
        }

        exoPlayer = new SimpleExoPlayer.Builder(myContext).build(); // Pasarle trackSelector
        AddPlayerListener();
        if(mySurface!=null)exoPlayer.setVideoSurface(mySurface);
        exoPlayer.setMediaSource(videoSource);
        exoPlayer.prepare();

        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setPlayWhenReady(false);
        */
    }

    public void Prepare(Surface surface)
    {
        //send videoID and textureID back to unity to create external texture

        mySurface = surface;

        // 1. AudioEngine
        if (engine == null)
        {
            engine = AudioEngine.create(SAMPLE_RATE, BUFFER_SIZE, QUEUE_SIZE_IN_SAMPLES, myContext);
            spat = engine.createSpatDecoderQueue();
            engine.start();
        }

        // 2. VideoSource type
        DataSource.Factory dataSourceFactory = buildDataSourceFactory(myContext);
        Uri uri = ParseFilePath();
        MediaSource videoSource = buildMediaSource(myContext, uri, null, dataSourceFactory);
        Log.d(TAG, "Requested play of " + filePath + " uri: " + uri.toString());

        // 3. Exoplayer
        if (exoPlayer != null)
        {
            exoPlayer.release();
        }

        exoPlayer = new SimpleExoPlayer.Builder(myContext).build(); // Pasarle trackSelector
        AddPlayerListener();
        if(mySurface!=null)exoPlayer.setVideoSurface(mySurface);
        exoPlayer.setMediaSource(videoSource);
        exoPlayer.prepare();

        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setPlayWhenReady(false);
    }

    private class PlayerEventListener implements Player.Listener {
        @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason)
            {
                isPlaying = playWhenReady && (currentPlaybackState == Player.STATE_READY || currentPlaybackState == Player.STATE_BUFFERING);
                updatePlaybackState();

                if(m_ExoPlayerUnity.unityMessage!=null)m_ExoPlayerUnity.unityMessage.OnPlayWhenReadyChanged(playWhenReady, reason);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState)
            {
                //call on prepared from unity
                if (!readyToPlay && playbackState == Player.STATE_READY)
                {
                    readyToPlay = true;
                    //unityMessage.OnVideoPrepared();
                }

                currentPlaybackState = playbackState;
                updatePlaybackState();
                if(m_ExoPlayerUnity.unityMessage!=null)m_ExoPlayerUnity.unityMessage.OnPlaybackStateChanged(playbackState);
            }

            @Override
            public void onPlaybackParametersChanged(PlaybackParameters params)
            {
                updatePlaybackState();
            }

            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason)
            {
                updatePlaybackState();
            }

            @Override
            public void onPositionDiscontinuity(int reason)
            {
                updatePlaybackState();
            }


            @Override
    public void onMediaItemTransition(MediaItem availableCommands,int reason) {}
            @Override
    public void onAvailableCommandsChanged(Player.Commands availableCommands) {}
    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {}
    @Override
    public void onIsLoadingChanged(boolean isLoading) {}
    @Override
    public void onPlayerError(PlaybackException error) {}
    @Override
    public void onRepeatModeChanged(int repeatMode) 
    {
        isLooping = exoPlayer.getRepeatMode() == Player.REPEAT_MODE_ONE;   
    }
    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {}
    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        // 空实现即可
    }
    @Override
    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {}
    @Override
    public void onPlaylistMetadataChanged(MediaMetadata playlistMetadata) {}
    @Override
    public void onIsPlayingChanged(boolean isPlaying) {}
    @Override
    public void onDeviceInfoChanged(DeviceInfo deviceInfo) {}
    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {}
    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {}
    @Override
    public void onSurfaceSizeChanged(int w, int h) 
    {
       // width = w;
      //  height = h;
       // isDirtySurfaceSize = true;
       // Log.d(TAG, "onSurfaceSizeChanged " + width + "x" + height);
    }

    @Override
    public void onPlayerStateChanged(boolean b, int re)
    {
         isLooping = exoPlayer.getRepeatMode() == Player.REPEAT_MODE_ONE;   
    }

    @Override
    public void onEvents(Player player, Player.Events events) {
        // 空实现即可，必须存在
    }

        @Override
    public void onLoadingChanged(boolean b) {
        // 空实现即可，必须存在
    }

        @Override
    public void onVideoSizeChanged(int x, int y, int z, float r) {}
    
    @Override
    public void onRenderedFirstFrame(){}
    }


    private void AddPlayerListener()
    {
        exoPlayer.addListener(new PlayerEventListener());
    }

    public void updatePlaybackState()
    {
        duration = exoPlayer.getDuration();
        lastPlaybackPosition = exoPlayer.getCurrentPosition();
        lastPlaybackSpeed = isPlaying ? exoPlayer.getPlaybackParameters().speed : 0;
        lastPlaybackUpdateTime = System.currentTimeMillis();
        Format format = exoPlayer.getVideoFormat();
        if (format != null)
        {
            stereoMode = format.stereoMode;
            width = format.width;
            height = format.height;
           // if(mySurface == null)
            //{
              //  mySurface = m_ExoPlayerUnity.CreateExoSurface(width, height); 
             //   exoPlayer.setVideoSurface(mySurface);
           //}
        }
        else
        {
            stereoMode = -1;
            width = 0;
            height = 0;
        }

         Log.d(TAG, "updatePlaybackState " + width + "x" + height);
    }

    public void AttackSurface(Surface surface)
    {
        if (exoPlayer != null)
        {
            mySurface = surface;
            exoPlayer.setVideoSurface(surface);

            Log.d(TAG, "AttackSurface " + width + "x" + height);
        }
    }

    public void Play()
    {
        if (exoPlayer != null)
        {
            exoPlayer.setPlayWhenReady(true);
        }
    }

    public void Pause()
    {
        if (exoPlayer != null)
        {
            exoPlayer.setPlayWhenReady(false);
        }
    }

    public void Stop()
    {
        if (exoPlayer != null)
        {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }

        if (engine != null)
        {
            engine.destroySpatDecoderQueue(spat);
            engine.delete();
            spat = null;
            engine = null;
        }
    }

    public int GetWidth()
    {
        return width;
    }

    public int GetHeight()
    {
        return height;
    }

    public int GetCurrentPlaybackState()
    {
        return currentPlaybackState;
    }
    public boolean GetIsPlaying()
    {
        return isPlaying;
    }

    public boolean IsPaused()
    {
        if(exoPlayer!=null)
        {
            return !isPlaying && readyToPlay;
        }
        return false;
    }

    public long GetLength()
    {
        return duration;
    }

    public double GetPlaybackPosition()
    {
        long currPosition = Math.max(0, Math.min(duration, lastPlaybackPosition + (long) ((System.currentTimeMillis() - lastPlaybackUpdateTime) * lastPlaybackSpeed)));
        double percent = (double)currPosition / duration;
        return percent;
    }

    // SETTERS
    public void SetLooping(final boolean looping)
    {

        if (exoPlayer != null)
        {
            if (looping)
            {
                exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
            }
            else
            {
                exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);
            }
        }
    }

    public boolean IsLooping()
    {
        return isLooping;
    }

    public boolean IsFinished()
    {
        if(exoPlayer!=null)
        {
            return GetCurrentPlaybackState() == Player.STATE_ENDED;
        }
        return false;
    }

    public boolean IsBuffering()
    {
        if(exoPlayer!=null)
        {
            return GetCurrentPlaybackState() == Player.STATE_BUFFERING;
        }
        return false;
    }

    public void SetPlaybackPosition(final double percent)
    {
        if (exoPlayer != null)
        {
            Timeline timeline = exoPlayer.getCurrentTimeline();
            if (timeline != null)
            {

                long timeInMilliseconds = (long)(duration * percent);

                int windowIndex = timeline.getFirstWindowIndex(false);
                long windowPositionUs = timeInMilliseconds * 1000L;
                Timeline.Window tmpWindow = new Timeline.Window();
                for (int i = timeline.getFirstWindowIndex(false);
                     i < timeline.getLastWindowIndex(false); i++)
                {
                    timeline.getWindow(i, tmpWindow);

                    if (tmpWindow.durationUs > windowPositionUs)
                    {
                        break;
                    }

                    windowIndex++;
                    windowPositionUs -= tmpWindow.durationUs;
                }

                exoPlayer.seekTo(windowIndex, windowPositionUs / 1000L);
            }
        }
    }
    public  void SetPlaybackSpeed(final float speed)
    {
        if (exoPlayer != null)
        {
            PlaybackParameters param = new PlaybackParameters(speed);
            exoPlayer.setPlaybackParameters(param);
        }
    }

    public void SetVolume(final float volume)
    {
        if(exoPlayer == null) return;
      //  exoPlayer.SetVolume(volume);
    }

    /**
     * Returns a {@link DataSource.Factory}.
     */
    public DataSource.Factory buildDataSourceFactory(Context context)
    {
        DefaultDataSourceFactory upstreamFactory = new DefaultDataSourceFactory(context, null, buildHttpDataSourceFactory(context));
        return buildReadOnlyCacheDataSource(upstreamFactory, getDownloadCache(context));
    }

    /**
     * Returns a {@link HttpDataSource.Factory}.
     */
    public static HttpDataSource.Factory buildHttpDataSourceFactory(Context context)
    {
        return new DefaultHttpDataSource.Factory().setUserAgent(Util.getUserAgent(context, "VideoPlayer"));
    }

    private CacheDataSource.Factory buildReadOnlyCacheDataSource(DefaultDataSourceFactory upstreamFactory, Cache cache)
    {
        return new CacheDataSource.Factory().
                setCache(cache).
                setUpstreamDataSourceFactory(upstreamFactory).
                setCacheReadDataSourceFactory(new FileDataSource.Factory()).
                setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private synchronized Cache getDownloadCache(Context context)
    {
        if (m_ExoPlayerUnity.downloadCache == null)
        {
            File downloadContentDirectory = new File(getDownloadDirectory(context), "downloads");
            m_ExoPlayerUnity.downloadCache = new SimpleCache(downloadContentDirectory, new NoOpCacheEvictor(), new ExoDatabaseProvider(context));
        }
        return m_ExoPlayerUnity.downloadCache;
    }

    private File getDownloadDirectory(Context context)
    {
        if (m_ExoPlayerUnity.downloadDirectory == null)
        {
            m_ExoPlayerUnity.downloadDirectory = context.getExternalFilesDir(null);
            if (m_ExoPlayerUnity.downloadDirectory == null)
            {
                m_ExoPlayerUnity.downloadDirectory = context.getFilesDir();
            }
        }
        return m_ExoPlayerUnity.downloadDirectory;
    }

    @SuppressWarnings("unchecked")
    private  MediaSource buildMediaSource(Context context, Uri uri, /*@Nullable*/ String overrideExtension, DataSource.Factory dataSourceFactory)
    {
        @C.ContentType int type = Util.inferContentType(uri, overrideExtension);
        switch (type)
        {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(dataSourceFactory), dataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_SS:
                return new SsMediaSource.Factory(new DefaultSsChunkSource.Factory(dataSourceFactory), dataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(uri));
            default:
            {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }
    private Uri ParseFilePath()
    {
        Uri uri = Uri.parse(filePath);

        if (filePath.startsWith("jar:file:"))
        {
            if (filePath.contains(".apk"))
            { // APK
                uri = new Uri.Builder().scheme("asset").path(filePath.substring(filePath.indexOf("/assets/") + "/assets/".length())).build();
            }
            else if (filePath.contains(".obb"))
            { // OBB
                String obbPath = filePath.substring(11, filePath.indexOf(".obb") + 4);

                StorageManager sm = (StorageManager) myContext.getSystemService(Context.STORAGE_SERVICE);
                if (!sm.isObbMounted(obbPath))
                {
                    sm.mountObb(obbPath, null, new OnObbStateChangeListener() {
                        @Override
                        public void onObbStateChange(String path, int state) {
                            super.onObbStateChange(path, state);
                        }
                    });
                }

                uri = new Uri.Builder().scheme("file").path(sm.getMountedObbPath(obbPath) + filePath.substring(filePath.indexOf(".obb") + 5)).build();
            }
        }

        return uri;
    }
}