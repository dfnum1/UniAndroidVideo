package com.unity3d.video;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;
import com.unity3d.zip.ZipResourceFile;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import org.json.JSONObject;
import android.util.Log;

public class MoblieVideo
    implements SurfaceTexture.OnFrameAvailableListener, MediaPlayer.OnBufferingUpdateListener,
    MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener,
    MediaPlayer.OnPreparedListener, MediaPlayer.OnVideoSizeChangedListener {
  public static final int kUnityGfxRendererOpenGLES20 = 8;
  public static final int kUnityGfxRendererOpenGLES30 = 11;
  private static Random s_Random = null;
  private static int s_RandomInstanceCount = 0;
  private static HashMap<Integer, MoblieVideo> s_AllPlayers = null;
  private static Integer s_PlayersIndex = Integer.valueOf(0);
  private static final boolean s_bWatermarked = false;
  private static boolean s_bCompressedWatermarkDataGood = false;
  private boolean m_bWatermarkDataGood = false;
  private Context m_Context;
  private MediaPlayer m_MediaPlayerAPI;
  private MediaExtractor m_MediaExtractor;
  private int m_iPlayerIndex = -1;
  private int m_iOpenGLVersion;
  private boolean m_bCanUseGLBindVertexArray;
  private boolean m_bUseFastOesPath;
  private boolean m_bShowPosterFrame;
  private int m_Width;
  private int m_Height;
  private long m_DurationMs;
  private boolean m_bLooping;
  private float m_fPlaybackRate;
  private int m_FrameCount;
  private boolean m_bIsStream;
  private boolean m_bIsBuffering;
  private float m_AudioVolume;
  private float m_AudioPan;
  private boolean m_AudioMuted;
  private float m_fBufferingProgressPercent;
  MoblieVideo_GlRender m_GlRender_Video;
  MoblieVideo_GlRender m_GlRender_Watermark;
  private SurfaceTexture m_SurfaceTexture;
  private int m_iNumberFramesAvailable;
  private long m_TextureTimeStamp;
  private static int VideoCommand_Play = 0;
  private static int VideoCommand_Pause = 1;
  private static int VideoCommand_Stop = 2;
  private static int VideoCommand_Seek = 3;
  private Queue<VideoCommand> m_CommandQueue;
  private static final int VideoState_Idle = 0;
  private static final int VideoState_Opening = 1;
  private static final int VideoState_Preparing = 2;
  private static final int VideoState_Prepared = 3;
  private static final int VideoState_Buffering = 4;
  private static final int VideoState_Playing = 5;
  private static final int VideoState_Stopped = 6;
  private static final int VideoState_Paused = 7;
  private static final int VideoState_Finished = 8;
  private int m_VideoState;
  private boolean m_bVideo_CreateRenderSurface;
  private boolean m_bVideo_DestroyRenderSurface;
  private boolean m_bVideo_RenderSurfaceCreated;
  private boolean m_bVideo_AcceptCommands;
  private MediaPlayer.TrackInfo[] m_aTrackInfo;
  private int m_iCurrentAudioTrackIndex;
  private int m_iCurrentAudioTrackIndexInInfoArray;
  private boolean m_bSourceHasVideo;
  private int m_iNumberAudioTracks;
  private boolean m_bSourceHasTimedText;
  private boolean m_bSourceHasSubtitles;
  private float m_fSourceVideoFrameRate;
  private Handler m_WatermarkSizeHandler;
  private Runnable m_WatermarkPositionRunnable;
  private Point m_WatermarkPosition;
  private float m_WatermarkScale;
  private long m_DisplayRate_LastSystemTimeMS;
  private long m_DisplayRate_NumberFrames;
  private float m_DisplayRate_FrameRate;
  private boolean m_bDeinitialiseFlagged;
  private boolean m_bDeinitialised;
  private static final int ErrorCode_None = 0;
  private static final int ErrorCode_LoadFailed = 100;
  private static final int ErrorCode_DecodeFailed = 200;

  private class VideoCommand {
    int _command = -1;
    int _intValue = 0;
    // float _floatValue = 0.0F;

    private VideoCommand() {
    }
  }

  private int m_iLastError = 0;
  private final String TAG = "MediaPlayer";

  public static String GetPluginVersion() {
    return "1.0.0";
  }

  private static MoblieVideo GetClassForPlayerIndex(int playerIndex) {
    MoblieVideo returnPlayerClass = null;
    if (s_AllPlayers != null) {
      if (s_AllPlayers.containsKey(Integer.valueOf(playerIndex)))
        returnPlayerClass = (MoblieVideo) s_AllPlayers.get(Integer.valueOf(playerIndex));
    }
    return returnPlayerClass;
  }

  public static void OnRendererEvent(int eventID)
  {
      Log.d("MediaPlayer", "OnRendererEventJava: " + eventID);
      int eventType = (eventID >> 16) & 0xFFFF;
      int playerIndex = (eventID >> 8) & 0xFF;
      int gfxType = eventID & 0xFF;
      if(eventType == 2)   
          RendererSetupPlayer(playerIndex, gfxType);
      else if(eventType == 3)
          RenderPlayer(playerIndex);
    } 

  public static void RenderPlayer(int playerIndex) {
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      theClass.Render();
    }
  }

  public static void RendererSetupPlayer(int playerIndex, int iDeviceIndex) {
    new StringBuilder("RendererSetupPlayer called with index: ").append(playerIndex).append(" | iDeviceIndex: ")
        .append(iDeviceIndex);
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      boolean bOverride = false;
      if (iDeviceIndex == 8) {
        theClass.m_iOpenGLVersion = 2;
        bOverride = true;
      } else if (iDeviceIndex == 11) {
        theClass.m_iOpenGLVersion = 3;
        bOverride = true;
      }
      if (bOverride) {
        theClass.m_bCanUseGLBindVertexArray = ((theClass.m_iOpenGLVersion > 2) && (Build.VERSION.SDK_INT >= 18));
        new StringBuilder("Overriding: OpenGL ES version: ").append(theClass.m_iOpenGLVersion);
        new StringBuilder("Overriding: OpenGL ES Can use glBindArray: ").append(theClass.m_bCanUseGLBindVertexArray);
      }
      theClass.RendererSetup();
    }
  }

  public static void RendererDestroyPlayer(int playerIndex) {
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      theClass.Deinitialise();
    }
  }

  public static int _GetWidth(int playerIndex) {
    int iReturn = 0;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      iReturn = theClass.GetWidth();
    }
    return iReturn;
  }

  public static int _GetHeight(int playerIndex) {
    int iReturn = 0;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      iReturn = theClass.GetHeight();
    }
    return iReturn;
  }

  public static int _GetTextureHandle(int playerIndex) {
    int iReturn = 0;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      iReturn = theClass.GetTextureHandle();
    }
    return iReturn;
  }

  public static long _GetDuration(int playerIndex) {
    long iReturn = 0L;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      iReturn = theClass.GetDurationMs();
    }
    return iReturn;
  }

  public static int _GetLastErrorCode(int playerIndex) {
    int iReturn = 0;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      iReturn = theClass.GetLastErrorCode();
    }
    return iReturn;
  }

  public static int _GetFrameCount(int playerIndex) {
    int iReturn = 0;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      iReturn = theClass.GetFrameCount();
    }
    return iReturn;
  }

  public static float _GetVideoDisplayRate(int playerIndex) {
    float fReturn = 0.0F;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      fReturn = theClass.GetDisplayRate();
    }
    return fReturn;
  }

  public static boolean _CanPlay(int playerIndex) {
    boolean bReturn = false;
    MoblieVideo theClass;
    if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
      bReturn = theClass.CanPlay();
    }
    return bReturn;
  }

  public MoblieVideo() {
    if (s_AllPlayers == null) {
      s_AllPlayers = new HashMap<Integer, MoblieVideo>();
    }
    this.m_bDeinitialiseFlagged = false;
    this.m_bDeinitialised = false;

    this.m_iOpenGLVersion = 1;
    this.m_bCanUseGLBindVertexArray = false;
    this.m_bUseFastOesPath = false;
    this.m_bShowPosterFrame = false;

    this.m_bVideo_CreateRenderSurface = false;
    this.m_bVideo_DestroyRenderSurface = false;
    this.m_bVideo_RenderSurfaceCreated = false;
    this.m_bVideo_AcceptCommands = false;

    this.m_aTrackInfo = null;
    this.m_iCurrentAudioTrackIndex = -1;
    this.m_iCurrentAudioTrackIndexInInfoArray = -1;

    this.m_bSourceHasVideo = false;
    this.m_iNumberAudioTracks = 0;
    this.m_bSourceHasTimedText = false;
    this.m_bSourceHasSubtitles = false;
    this.m_fSourceVideoFrameRate = 0.0F;

    this.m_VideoState = 0;

    this.m_AudioVolume = 1.0F;
    this.m_AudioPan = 0.5F;
    this.m_AudioMuted = false;

    this.m_Width = 0;
    this.m_Height = 0;
    this.m_DurationMs = 0L;
    this.m_bLooping = false;
    this.m_fPlaybackRate = 1.0F;

    this.m_iNumberFramesAvailable = 0;
    this.m_TextureTimeStamp = Long.MIN_VALUE;
    this.m_FrameCount = 0;

    this.m_bIsStream = false;
    this.m_bIsBuffering = false;

    this.m_fBufferingProgressPercent = 0.0F;

    this.m_DisplayRate_FrameRate = 0.0F;
    this.m_DisplayRate_NumberFrames = 0L;
    this.m_DisplayRate_LastSystemTimeMS = System.currentTimeMillis();

    this.m_iLastError = 0;
  }

  public void Initialise(Context context) {
    this.m_iPlayerIndex = s_PlayersIndex.intValue();

    s_AllPlayers.put(Integer.valueOf(this.m_iPlayerIndex), this);
    s_PlayersIndex = Integer.valueOf(s_PlayersIndex.intValue() + 1);

    this.m_Context = context;

    this.m_CommandQueue = new LinkedList<VideoCommand>();
    if (s_Random == null) {
      s_Random = new Random(System.currentTimeMillis());
    }
    s_RandomInstanceCount += 1;

    this.m_WatermarkPosition = new Point();

    int iPackageManagerOpenGLESVersion = getVersionFromPackageManager(this.m_Context);
    int iDeviceInfoOpenGLESVersion = getGlVersionFromDeviceConfig(this.m_Context);
    if ((iPackageManagerOpenGLESVersion >= 3) && (iDeviceInfoOpenGLESVersion >= 3)) {
      this.m_iOpenGLVersion = 3;
    } else if ((iPackageManagerOpenGLESVersion >= 2) && (iDeviceInfoOpenGLESVersion >= 2)) {
      this.m_iOpenGLVersion = 2;
    }
    new StringBuilder("OpenGL ES version: ").append(this.m_iOpenGLVersion);

    this.m_bCanUseGLBindVertexArray = ((this.m_iOpenGLVersion > 2) && (Build.VERSION.SDK_INT >= 18));
    new StringBuilder("OpenGL ES Can use glBindArray: ").append(this.m_bCanUseGLBindVertexArray);

    this.m_MediaPlayerAPI = new MediaPlayer();
  }

  public void SetDeinitialiseFlagged() {
    this.m_bDeinitialiseFlagged = true;
    this.m_bDeinitialised = false;
  }

  public boolean GetDeinitialised() {
    return this.m_bDeinitialised;
  }

  public void Deinitialise() {
    CloseVideo();
    if (this.m_MediaPlayerAPI != null) {
      this.m_MediaPlayerAPI.setSurface(null);
      this.m_MediaPlayerAPI.stop();
      this.m_MediaPlayerAPI.reset();
      this.m_MediaPlayerAPI.release();
      this.m_MediaPlayerAPI = null;
    }
    if (this.m_GlRender_Video != null) {
      this.m_GlRender_Video.Destroy();
      this.m_GlRender_Video = null;
    }
    if (this.m_GlRender_Watermark != null) {
      this.m_GlRender_Watermark.Destroy();
      this.m_GlRender_Watermark = null;
    }
    if (this.m_SurfaceTexture != null) {
      this.m_SurfaceTexture.setOnFrameAvailableListener(null);
      this.m_SurfaceTexture.release();
      this.m_SurfaceTexture = null;
    }
    if (this.m_CommandQueue != null) {
      this.m_CommandQueue.clear();
      this.m_CommandQueue = null;
    }
    this.m_WatermarkPosition = null;
    if ((this.m_WatermarkSizeHandler != null) && (this.m_WatermarkPositionRunnable != null)) {
      this.m_WatermarkSizeHandler.removeCallbacks(this.m_WatermarkPositionRunnable);
    }
    this.m_WatermarkSizeHandler = null;
    this.m_WatermarkPositionRunnable = null;
    if ((--s_RandomInstanceCount <= 0) && (s_Random != null)) {
      s_Random = null;
    }
    s_AllPlayers.remove(Integer.valueOf(this.m_iPlayerIndex));
    if ((s_AllPlayers != null) && (s_AllPlayers.isEmpty())) {
      s_AllPlayers.clear();
      s_AllPlayers = null;
    }
    this.m_bDeinitialised = true;
  }

  public void RendererSetup() {
    if (this.m_bDeinitialiseFlagged) {
      return;
    }
    if (this.m_GlRender_Video == null) {
      this.m_GlRender_Video = new MoblieVideo_GlRender();
      this.m_GlRender_Video.Setup(0, 0, null, true, this.m_bCanUseGLBindVertexArray, this.m_bUseFastOesPath);
      CreateAndBindSinkTexture(this.m_GlRender_Video.GetGlTextureHandle());
    }
  }

  public int GetPlayerIndex() {
    return this.m_iPlayerIndex;
  }

  public int GetTextureHandle() {
    if ((this.m_GlRender_Video == null) || (!this.m_bVideo_RenderSurfaceCreated)
        || (this.m_bVideo_DestroyRenderSurface)) {
      return 0;
    }
    return this.m_GlRender_Video.GetGlTextureHandle();
  }

  public int GetLastErrorCode() {
    int iReturnError = this.m_iLastError;
    this.m_iLastError = 0;
    return iReturnError;
  }

  public boolean IsPlaying() {
    return this.m_VideoState == 5;
  }

  public boolean IsPaused() {
    return this.m_VideoState == 7;
  }

  public boolean IsSeeking() {
    return (this.m_VideoState == 2) || (this.m_VideoState == 4);
  }

  public boolean IsFinished() {
    return this.m_VideoState == 8;
  }

  public boolean CanPlay() {
    return (this.m_VideoState == 6) || (this.m_VideoState == 7) || (this.m_VideoState == 5) || (this.m_VideoState == 8);
  }

  private void setMediaPlayerDataSourceFromZip(String zipFileName, String fileNameInZip)
      throws IOException, FileNotFoundException {
    if (this.m_MediaPlayerAPI == null) {
      return;
    }
    ZipResourceFile zip = new ZipResourceFile(zipFileName);
    FileInputStream fis = new FileInputStream(zipFileName);
    try {
      FileDescriptor zipfd = fis.getFD();

      ZipResourceFile.ZipEntryRO entry = zipFindFile(zip, fileNameInZip);
      this.m_MediaPlayerAPI.setDataSource(zipfd, entry.mOffset, entry.mUncompressedLength);
      if (Build.VERSION.SDK_INT > 15) {
        if (this.m_MediaExtractor != null) {
          try {
            this.m_MediaExtractor.setDataSource(zipfd, entry.mOffset, entry.mUncompressedLength);
          } catch (IOException localIOException) {
            this.m_MediaExtractor.release();
            this.m_MediaExtractor = null;
          }
        }
      }
    } finally {
      fis.close();
    }
  }

  private static ZipResourceFile.ZipEntryRO zipFindFile(ZipResourceFile zip, String fileNameInZip) {
    ZipResourceFile.ZipEntryRO[] arrayOfZipEntryRO;
    int i = (arrayOfZipEntryRO = zip.getAllEntries()).length;
    for (int j = 0; j < i; j++) {
      ZipResourceFile.ZipEntryRO entry;
      if ((entry = arrayOfZipEntryRO[j]).mFileName.equals(fileNameInZip)) {
        return entry;
      }
    }
    throw new RuntimeException(String.format("File \"%s\"not found in zip", new Object[] { fileNameInZip }));
  }

  public void SetPlayerOptions(boolean useFastOesPath, boolean showPosterFrame) {
    this.m_bUseFastOesPath = useFastOesPath;

    this.m_bShowPosterFrame = showPosterFrame;
  }

  private static Map<String, String> GetJsonAsMap(String json) {
    HashMap<String, String> result = new HashMap<String, String>();
    try {
      JSONObject jsonObj;
      Iterator<String> keyIt = (jsonObj = new JSONObject(json)).keys();
      while (keyIt.hasNext()) {
        String key = (String) keyIt.next();
        String val = jsonObj.getString(key);
        result.put(key, val);
      }
      return result;
    } catch (Exception e) {
      throw new RuntimeException("Couldn't parse json:" + json, e);
    }
  }

  public boolean OpenVideoFromFile(String filePath, long fileOffset, String httpHeaderJson) {
    boolean bReturn = false;

    CloseVideo();

    this.m_VideoState = 1;

    this.m_bVideo_CreateRenderSurface = false;
    this.m_bVideo_DestroyRenderSurface = false;
    this.m_bVideo_AcceptCommands = false;

    this.m_aTrackInfo = null;
    this.m_iCurrentAudioTrackIndex = -1;
    this.m_iCurrentAudioTrackIndexInInfoArray = -1;

    this.m_bSourceHasVideo = false;
    this.m_iNumberAudioTracks = 0;
    this.m_bSourceHasTimedText = false;
    this.m_bSourceHasSubtitles = false;
    this.m_fSourceVideoFrameRate = 0.0F;

    this.m_DurationMs = 0L;

    this.m_FrameCount = 0;

    this.m_bIsStream = false;
    this.m_bIsBuffering = false;

    this.m_fBufferingProgressPercent = 0.0F;
    if (this.m_MediaPlayerAPI != null) {
      boolean bFileGood = true;
      try {
        String lowloawerPaht = filePath.toLowerCase();
        if ((lowloawerPaht.startsWith("http://")) ||
            (lowloawerPaht.startsWith("https://")) ||
            (lowloawerPaht.startsWith("rtsp://"))) {
          Uri uri = Uri.parse(filePath);
          if ((httpHeaderJson != null) && (!httpHeaderJson.isEmpty())) {
            Map<String, String> httpHeaderMap = GetJsonAsMap(httpHeaderJson);
            this.m_MediaPlayerAPI.setDataSource(this.m_Context, uri, httpHeaderMap);
          } else {
            this.m_MediaPlayerAPI.setDataSource(this.m_Context, uri);
          }
          this.m_bIsStream = true;
        } else {
          try {
            String lookFor = ".obb!/";
            int iIndexIntoString;
            if ((iIndexIntoString = filePath.lastIndexOf(lookFor)) >= 0) {
              String zipPathName = filePath.substring(11, iIndexIntoString + lookFor.length() - 2);
              String zipFileName = filePath.substring(iIndexIntoString + lookFor.length());

              Log.d(TAG, "OpenVideoFromFile zipFileName:" + zipPathName + "   zipFileName:" + zipFileName);
              setMediaPlayerDataSourceFromZip(zipPathName, zipFileName);
            } else {
              throw new IOException("Not an obb file");
            }
          } catch (IOException localIOException1) {
            try {
              // IOException exc;
              String fileName = filePath.substring(filePath.lastIndexOf("/assets/") + 8);
              AssetFileDescriptor assetFileDesc;
              if ((assetFileDesc = this.m_Context.getAssets().openFd(fileName)) != null) {
                Log.d(TAG, "OpenVideoFromFile AssetFileDescriptor:" + fileName);
                this.m_MediaPlayerAPI.setDataSource(assetFileDesc.getFileDescriptor(), assetFileDesc.getStartOffset(),
                    assetFileDesc.getLength());
                //if (Build.VERSION.SDK_INT > 15) 
                {
                  this.m_MediaExtractor = new MediaExtractor();
                  if (this.m_MediaExtractor != null) {
                    try {
                      this.m_MediaExtractor.setDataSource(assetFileDesc.getFileDescriptor(),
                          assetFileDesc.getStartOffset(), assetFileDesc.getLength());
                    } catch (IOException localIOException2) {
                      // IOException mediaExtractorE;
                      this.m_MediaExtractor.release();
                      this.m_MediaExtractor = null;
                    }
                  }
                }
              }
            } catch (IOException localIOException3) {
              try {
                // IOException e;
                if (fileOffset == 0L) {
                  // FileInputStream inputStream;
                  FileDescriptor fileDescriptor = new FileInputStream(filePath).getFD();
                  this.m_MediaPlayerAPI.setDataSource(fileDescriptor);
                  if (Build.VERSION.SDK_INT > 15) {
                    Log.d(TAG, "OpenVideoFromFile FileDescriptor:" + filePath);
                    this.m_MediaExtractor = new MediaExtractor();
                    if (this.m_MediaExtractor != null) {
                      try {
                        this.m_MediaExtractor.setDataSource(fileDescriptor);
                      } catch (IOException localIOException4) {
                        this.m_MediaExtractor.release();
                        this.m_MediaExtractor = null;
                      }
                    }
                  }
                } else {
                  FileInputStream inputStream;
                  FileDescriptor fileDescriptor = (inputStream = new FileInputStream(filePath)).getFD();
                  this.m_MediaPlayerAPI.setDataSource(fileDescriptor, fileOffset,
                      inputStream.getChannel().size() - fileOffset);
                  if (Build.VERSION.SDK_INT > 15) {
                    Log.d(TAG, "OpenVideoFromFile FileInputStream:" + filePath);
                    this.m_MediaExtractor = new MediaExtractor();
                    if (this.m_MediaExtractor != null) {
                      try {
                        this.m_MediaExtractor.setDataSource(fileDescriptor, fileOffset,
                            inputStream.getChannel().size() - fileOffset);
                      } catch (IOException localIOException5) {
                        this.m_MediaExtractor.release();
                        this.m_MediaExtractor = null;
                      }
                    }
                  }
                }
              } catch (IOException localIOException6) {
                // IOException uri_e;
                Uri uri = Uri.parse("file://" + filePath);
                this.m_MediaPlayerAPI.setDataSource(this.m_Context, uri);
                if (Build.VERSION.SDK_INT > 15) {
                  Log.d(TAG, "OpenVideoFromFile MediaExtractor:" + filePath);
                  this.m_MediaExtractor = new MediaExtractor();
                  if (this.m_MediaExtractor != null) {
                    try {
                      this.m_MediaExtractor.setDataSource(this.m_Context, uri, null);
                    } catch (IOException localIOException7) {
                      this.m_MediaExtractor.release();
                      this.m_MediaExtractor = null;
                    }
                  }
                }
              }
            }
          }
        }
        if (!bFileGood) {
          // break label772;
          this.m_iLastError = 100;
        }
      } catch (IOException e) {
        new StringBuilder("Failed to open video file: ").append(e);
        bFileGood = false;
      }
      this.m_MediaPlayerAPI.setOnPreparedListener(this);
      this.m_MediaPlayerAPI.setOnVideoSizeChangedListener(this);
      this.m_MediaPlayerAPI.setOnErrorListener(this);
      this.m_MediaPlayerAPI.setOnCompletionListener(this);
      this.m_MediaPlayerAPI.setOnBufferingUpdateListener(this);
      this.m_MediaPlayerAPI.setOnInfoListener(this);

      this.m_VideoState = 2;
      this.m_MediaPlayerAPI.prepareAsync();

      this.m_MediaPlayerAPI.setLooping(this.m_bLooping);

      // break label778;
      bReturn = bFileGood;

      // label772:
      // this.m_iLastError = 100;
      // label778:
      // bReturn = bFileGood;
    }
    return bReturn;
  }

  public void CloseVideo() {
    if (this.m_VideoState >= 3) {
      _pause();

      _stop();
    }
    this.m_VideoState = 0;

    this.m_CommandQueue = new LinkedList<VideoCommand>();
    this.m_bVideo_AcceptCommands = false;
    this.m_Width = 0;
    this.m_Height = 0;
    this.m_DurationMs = 0L;

    this.m_aTrackInfo = null;
    this.m_iCurrentAudioTrackIndex = -1;
    this.m_iCurrentAudioTrackIndexInInfoArray = -1;

    this.m_bSourceHasVideo = false;
    this.m_iNumberAudioTracks = 0;
    this.m_bSourceHasTimedText = false;
    this.m_bSourceHasSubtitles = false;
    this.m_fSourceVideoFrameRate = 0.0F;

    this.m_TextureTimeStamp = Long.MIN_VALUE;
    this.m_FrameCount = 0;

    this.m_fBufferingProgressPercent = 0.0F;
    if (this.m_bVideo_RenderSurfaceCreated) {
      this.m_bVideo_DestroyRenderSurface = true;
    }
    this.m_bVideo_CreateRenderSurface = false;
    if (Build.VERSION.SDK_INT > 15) {
      if (this.m_MediaExtractor != null) {
        this.m_MediaExtractor.release();
        this.m_MediaExtractor = null;
      }
    }
    if (this.m_MediaPlayerAPI != null) {
      this.m_MediaPlayerAPI.reset();
    }
    this.m_iLastError = 0;
  }

  public void SetLooping(boolean bLooping) {
    this.m_bLooping = bLooping;
    if (this.m_MediaPlayerAPI != null) {
      this.m_MediaPlayerAPI.setLooping(this.m_bLooping);
    }
  }

  public boolean IsLooping() {
    return this.m_bLooping;
  }

  public int GetFrameCount() {
    return this.m_FrameCount;
  }

  public long GetDurationMs() {
    return this.m_DurationMs;
  }

  public int GetWidth() {
    return this.m_Width;
  }

  public int GetHeight() {
    return this.m_Height;
  }

  public float GetDisplayRate() {
    return this.m_DisplayRate_FrameRate;
  }

  public long GetCurrentTimeMs() {
    long result = 0L;
    if (this.m_MediaPlayerAPI != null) {
      if ((this.m_VideoState >= 3) && (this.m_VideoState <= 8)) {
        if (((result = this.m_MediaPlayerAPI.getCurrentPosition()) > this.m_DurationMs) && (this.m_DurationMs > 0L)) {
          result = this.m_DurationMs;
        }
      }
    }
    return result;
  }

  public float GetPlaybackRate() {
    return this.m_fPlaybackRate;
  }

  public void SetPlaybackRate(float fRate) {
    if (Build.VERSION.SDK_INT > 22) {
      if ((this.m_MediaPlayerAPI != null) && (this.m_VideoState >= 3)) {
        PlaybackParams playbackParams = new PlaybackParams();
        if (fRate < 0.01F) {
          fRate = 0.01F;
        }
        playbackParams.setSpeed(fRate);
        this.m_MediaPlayerAPI.setPlaybackParams(playbackParams);
        this.m_fPlaybackRate = fRate;
      }
    }
  }

  public boolean HasVideo() {
    return this.m_bSourceHasVideo;
  }

  public boolean HasAudio() {
    return this.m_iNumberAudioTracks > 0;
  }

  public boolean HasTimedText() {
    return this.m_bSourceHasTimedText;
  }

  public boolean HasSubtitles() {
    return this.m_bSourceHasSubtitles;
  }

  public void MuteAudio(boolean muted) {
    this.m_AudioMuted = muted;
    UpdateAudioVolumes();
  }

  public boolean IsMuted() {
    return this.m_AudioMuted;
  }

  public void SetVolume(float volume) {
    this.m_AudioVolume = volume;
    UpdateAudioVolumes();
  }

  public float GetVolume() {
    return this.m_AudioVolume;
  }

  public void SetAudioPan(float pan) {
    this.m_AudioPan = pan;
    UpdateAudioVolumes();
  }

  public float GetAudioPan() {
    return this.m_AudioPan;
  }

  public void Play() {
    AddVideoCommandInt(VideoCommand_Play, 0);
  }

  public void Pause() {
    AddVideoCommandInt(VideoCommand_Pause, 0);
  }

  public void Stop() {
    AddVideoCommandInt(VideoCommand_Stop, 0);
  }

  public void Seek(int timeMs) {
    AddVideoCommandInt(VideoCommand_Seek, timeMs);
  }

  public void SetAudioTrack(int iTrackIndex) {
    if (Build.VERSION.SDK_INT > 15) {
      if ((this.m_MediaPlayerAPI != null) && (iTrackIndex < this.m_iNumberAudioTracks)
          && (iTrackIndex != this.m_iCurrentAudioTrackIndex)) {
        int iAudioTrack = 0;
        int iTrack = 0;
        MediaPlayer.TrackInfo[] arrayOfTrackInfo;
        int i = (arrayOfTrackInfo = this.m_aTrackInfo).length;
        for (int j = 0; j < i; j++) {
          MediaPlayer.TrackInfo info;
          if (((info = arrayOfTrackInfo[j]) != null)
              && (info.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO)) {
            if (iAudioTrack == iTrackIndex) {
              this.m_MediaPlayerAPI.selectTrack(iTrack);

              this.m_iCurrentAudioTrackIndex = iTrackIndex;
              this.m_iCurrentAudioTrackIndexInInfoArray = iTrack;

              return;
            }
            iAudioTrack++;
          }
          iTrack++;
        }
      }
    }
  }

  public int GetCurrentAudioTrackIndex() {
    return this.m_iCurrentAudioTrackIndex;
  }

  public int GetNumberAudioTracks() {
    return this.m_iNumberAudioTracks;
  }

  public boolean IsBuffering() {
    return this.m_bIsBuffering;
  }

  public float GetBufferingProgressPercent() {
    return this.m_fBufferingProgressPercent;
  }

  public float GetSourceVideoFrameRate() {
    return this.m_fSourceVideoFrameRate;
  }

  public long GetTextureTimeStamp() {
    return this.m_TextureTimeStamp / 100L;
  }

  private void UpdateAudioVolumes() {
    float leftVolume = 0.0F;
    float rightVolume = 0.0F;
    if (!this.m_AudioMuted) {
      float pan = this.m_AudioPan * 2.0F;
      float leftPan = 2.0F - pan;

      leftVolume = this.m_AudioVolume * leftPan;
      rightVolume = this.m_AudioVolume * pan;
      if (leftVolume > 1.0F) {
        leftVolume = 1.0F;
      }
      if (rightVolume > 1.0F) {
        rightVolume = 1.0F;
      }
    }
    if (this.m_MediaPlayerAPI != null) {
      this.m_MediaPlayerAPI.setVolume(leftVolume, rightVolume);
    }
  }

  private void _play() {
    if (this.m_MediaPlayerAPI != null) {
      this.m_MediaPlayerAPI.start();
    }
    ResetPlaybackFrameRate();

    this.m_VideoState = 5;
  }

  private void _pause() {
    if ((this.m_VideoState > 4) && (this.m_VideoState != 6)) {
      if (this.m_MediaPlayerAPI != null) {
        this.m_MediaPlayerAPI.pause();
      }
      ResetPlaybackFrameRate();

      this.m_VideoState = 7;
    }
  }

  private void _stop() {
    if (this.m_VideoState > 4) {
      if (this.m_MediaPlayerAPI != null) {
        this.m_MediaPlayerAPI.stop();
      }
      ResetPlaybackFrameRate();

      this.m_VideoState = 6;
    }
  }

  private void _seek(int timeMs) {
    if (this.m_MediaPlayerAPI != null) {
      this.m_MediaPlayerAPI.seekTo(timeMs);
    }
  }

  private void AddVideoCommandInt(int command, int intData) {
    if (this.m_CommandQueue != null) {
      VideoCommand videoCommand = new VideoCommand();
      videoCommand._command = command;
      videoCommand._intValue = intData;

      this.m_CommandQueue.add(videoCommand);

      UpdateCommandQueue();
    }
  }

  public boolean Render() {
    boolean result = false;
    if (this.m_bDeinitialiseFlagged) {
      return false;
    }
    if (this.m_bVideo_DestroyRenderSurface) {
      if (this.m_GlRender_Video != null) {
        this.m_GlRender_Video.DestroyRenderTarget();
      }
      this.m_bVideo_DestroyRenderSurface = false;
      this.m_bVideo_RenderSurfaceCreated = false;
    }
    if (this.m_bVideo_CreateRenderSurface) {
      if (this.m_GlRender_Video != null) {
        this.m_GlRender_Video.DestroyRenderTarget();
        if (!this.m_bUseFastOesPath) {
          this.m_GlRender_Video.CreateRenderTarget(this.m_Width, this.m_Height);
        }
      }
      this.m_bVideo_DestroyRenderSurface = false;
      this.m_bVideo_CreateRenderSurface = false;
      this.m_bVideo_RenderSurfaceCreated = true;
      if ((!this.m_bIsStream) && (this.m_VideoState >= 3)) {
        this.m_bVideo_AcceptCommands = true;
        if ((this.m_VideoState != 5) && (this.m_VideoState != 4)) {
          this.m_VideoState = 6;
        }
      }
      SetVolume(this.m_AudioVolume);
    } else {
      UpdateCommandQueue();
    }
    synchronized (this) {
      if ((this.m_iNumberFramesAvailable > 0) && (this.m_bVideo_RenderSurfaceCreated)
          && (this.m_GlRender_Video != null)) {
        int iNumFramesAvailable = this.m_iNumberFramesAvailable;
        if (this.m_bUseFastOesPath) {
          while (this.m_iNumberFramesAvailable > 0) {
            this.m_SurfaceTexture.updateTexImage();

            this.m_TextureTimeStamp = this.m_SurfaceTexture.getTimestamp();

            this.m_iNumberFramesAvailable -= 1;
          }
          this.m_iNumberFramesAvailable = 0;
        } else {
          this.m_GlRender_Video.StartRender();

          this.m_TextureTimeStamp = this.m_GlRender_Video.Blit(this.m_SurfaceTexture, this.m_iNumberFramesAvailable,
              null);
          this.m_iNumberFramesAvailable = 0;

          this.m_GlRender_Video.EndRender();
        }
        if (this.m_Width > 0) {
          this.m_FrameCount += iNumFramesAvailable;
          UpdateDisplayFrameRate(iNumFramesAvailable);
        }
        result = true;
      }
    }
    return result;
  }

  private void UpdateGetDuration() {
    if (this.m_MediaPlayerAPI != null) {
      this.m_DurationMs = this.m_MediaPlayerAPI.getDuration();
    }
    new StringBuilder("Video duration is: ").append(this.m_DurationMs).append("ms");
  }

  private void UpdateCommandQueue() {
    if ((this.m_bVideo_AcceptCommands) && (this.m_CommandQueue != null)) {
      while (!this.m_CommandQueue.isEmpty()) {
        VideoCommand videoCommand;
        if ((videoCommand = (VideoCommand) this.m_CommandQueue.poll())._command == VideoCommand_Play) {
          _play();
        } else if (videoCommand._command == VideoCommand_Pause) {
          _pause();
        } else if (videoCommand._command == VideoCommand_Stop) {
          _stop();
        } else if (videoCommand._command == VideoCommand_Seek) {
          _seek(videoCommand._intValue);
        }
      }
    }
  }

  private void ChangeWatermarkPosition() {
    this.m_WatermarkPosition.x = ((int) (0.0F + 4.0F * s_Random.nextFloat()));
    this.m_WatermarkPosition.y = ((int) (1.0F + 4.0F * s_Random.nextFloat()));
    this.m_WatermarkScale = 5.0F;
  }

  private void CreateAndBindSinkTexture(int glTextureHandle) {
    this.m_SurfaceTexture = new SurfaceTexture(glTextureHandle);

    this.m_SurfaceTexture.setOnFrameAvailableListener(this);
    if ((this.m_MediaPlayerAPI != null) && (this.m_SurfaceTexture != null)) {
      Surface surface = new Surface(this.m_SurfaceTexture);
      this.m_MediaPlayerAPI.setSurface(surface);
      surface.release();
    }
  }

  private void ResetPlaybackFrameRate() {
    this.m_DisplayRate_FrameRate = 0.0F;
    this.m_DisplayRate_NumberFrames = 0L;

    this.m_DisplayRate_LastSystemTimeMS = System.nanoTime();
  }

  private void UpdateDisplayFrameRate(int iNumFrames) {
    long systemTimeMS;
    long elapsedTime = ((systemTimeMS = System.nanoTime()) - this.m_DisplayRate_LastSystemTimeMS) / 1000000L;

    this.m_DisplayRate_NumberFrames += iNumFrames;
    if (elapsedTime >= 500L) {
      this.m_DisplayRate_FrameRate = ((float) this.m_DisplayRate_NumberFrames / ((float) elapsedTime * 0.001F));

      this.m_DisplayRate_NumberFrames = 0L;
      this.m_DisplayRate_LastSystemTimeMS = systemTimeMS;
    }
  }

  private static int getVersionFromPackageManager(Context context) {
    if (context != null) {
      FeatureInfo[] featureInfos;
      if (((featureInfos = context.getPackageManager().getSystemAvailableFeatures()) != null)
          && (featureInfos.length > 0)) {
        FeatureInfo[] arrayOfFeatureInfo1;
        int i = (arrayOfFeatureInfo1 = featureInfos).length;
        for (int j = 0; j < i; j++) {
          FeatureInfo featureInfo;
          if ((featureInfo = arrayOfFeatureInfo1[j]).name == null) {
            if (featureInfo.reqGlEsVersion != 0) {
              return getMajorVersion(featureInfo.reqGlEsVersion);
            }
            return 1;
          }
        }
      }
    }
    return 1;
  }

  private static int getGlVersionFromDeviceConfig(Context context) {
    int iReturn = 1;
    if (context != null) {
      ActivityManager activityManager;
      if ((activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)) != null) {
        ConfigurationInfo configInfo;
        if ((configInfo = activityManager.getDeviceConfigurationInfo()) != null) {
          if (configInfo.reqGlEsVersion >= 196608) {
            iReturn = 3;
          } else if (configInfo.reqGlEsVersion >= 131072) {
            iReturn = 2;
          }
        }
      }
    }
    return iReturn;
  }

  private static int getMajorVersion(int glEsVersion) {
    return (glEsVersion & 0xFFFF0000) >> 16;
  }

  public void onRenderersError(Exception e) {
    new StringBuilder("ERROR - onRenderersError: ").append(e);
  }

  public void onFrameAvailable(SurfaceTexture surfaceTexture) {
    synchronized (this) {
      this.m_iNumberFramesAvailable += 1;
      return;
    }
  }

  public void onPrepared(MediaPlayer mp) {
    this.m_VideoState = 3;

    UpdateGetDuration();
    if (this.m_bIsStream) {
      this.m_iNumberAudioTracks = 1;
    }
    if (this.m_MediaPlayerAPI != null) {
      if (Build.VERSION.SDK_INT > 15) {
        try {
          this.m_aTrackInfo = this.m_MediaPlayerAPI.getTrackInfo();
          if (this.m_aTrackInfo != null) {
            new StringBuilder("Source has ").append(this.m_aTrackInfo.length).append(" tracks");
            if (this.m_aTrackInfo.length > 0) {
              this.m_iNumberAudioTracks = 0;
              int iTrack = 0;
              MediaPlayer.TrackInfo[] arrayOfTrackInfo;
              int i = (arrayOfTrackInfo = this.m_aTrackInfo).length;
              for (int j = 0; j < i; j++) {
                MediaPlayer.TrackInfo info;
                if ((info = arrayOfTrackInfo[j]) != null) {
                  switch (info.getTrackType()) {
                    case 1:
                      this.m_bSourceHasVideo = true;
                      if (this.m_fSourceVideoFrameRate == 0.0F) {
                        if (Build.VERSION.SDK_INT >= 19) {
                          MediaFormat mediaFormat;
                          if ((mediaFormat = info.getFormat()) != null) {
                            this.m_fSourceVideoFrameRate = mediaFormat.getInteger("frame-rate");
                          }
                        }
                      }
                      if (Build.VERSION.SDK_INT > 15) {
                        if (this.m_MediaExtractor != null) {
                          if (this.m_fSourceVideoFrameRate == 0.0F) {
                            MediaFormat mediaFormat = this.m_MediaExtractor.getTrackFormat(iTrack);

                            this.m_fSourceVideoFrameRate = mediaFormat.getInteger("frame-rate");
                            new StringBuilder("Source video frame rate: ").append(this.m_fSourceVideoFrameRate);
                          }
                          this.m_MediaExtractor.release();
                          this.m_MediaExtractor = null;
                        }
                      }
                      break;
                    case 3:
                      this.m_bSourceHasTimedText = true;

                      break;
                    case 4:
                      this.m_bSourceHasSubtitles = true;

                      break;
                    case 2:
                      this.m_iNumberAudioTracks += 1;
                  }
                }
                iTrack++;
              }
              if (this.m_iNumberAudioTracks > 0) {
                SetAudioTrack(0);
              }
              new StringBuilder("Number of audio tracks in source: ").append(this.m_iNumberAudioTracks);
            }
          }
        } catch (Exception localException) {
        }
      }
    }
    if ((this.m_bIsStream) || (this.m_iNumberAudioTracks > 0) || ((this.m_bVideo_RenderSurfaceCreated)
        && (!this.m_bVideo_DestroyRenderSurface) && (!this.m_bVideo_CreateRenderSurface))) {
      this.m_bVideo_AcceptCommands = true;
      if ((this.m_VideoState != 5) && (this.m_VideoState != 4)) {
        this.m_VideoState = 6;
      }
    }
    if ((!this.m_bIsStream) || (this.m_Width > 0)) {
      if (this.m_bShowPosterFrame) {
        _seek(0);
      }
    }
  }

  public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
    if ((this.m_Width != width) || (this.m_Height != height)) {
      new StringBuilder("onVideoSizeChanged : New size: ").append(width).append(" x ").append(height);

      this.m_Width = width;
      this.m_Height = height;

      this.m_bSourceHasVideo = true;

      this.m_bVideo_CreateRenderSurface = true;
      this.m_bVideo_DestroyRenderSurface = false;
    }
  }

  public boolean onError(MediaPlayer mp, int what, int extra) {
    new StringBuilder("onError what(").append(what).append("), extra(").append(extra).append(")");

    boolean result = false;
    switch (this.m_VideoState) {
      case 0:
        break;
      case 1:
      case 2:
      case 4:
        this.m_iLastError = 100;
        result = true;

        break;
      case 5:
        this.m_iLastError = 200;
        result = true;
    }
    return result;
  }

  public void onCompletion(MediaPlayer mp) {
    if (!this.m_bLooping) {
      if ((this.m_VideoState >= 3) && (this.m_VideoState < 8)) {
        this.m_VideoState = 8;
      }
    }
  }

  public void onBufferingUpdate(MediaPlayer mp, int percent) {
    this.m_fBufferingProgressPercent = percent;
  }

  public boolean onInfo(MediaPlayer mp, int what, int extra) {
    switch (what) {
      case 701:
        this.m_bIsBuffering = true;
        break;
      case 702:
        this.m_bIsBuffering = false;
    }
    return false;
  }
}
