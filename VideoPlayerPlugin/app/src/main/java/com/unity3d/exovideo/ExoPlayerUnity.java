package com.unity3d.exovideo;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES11Ext; 
import android.opengl.Matrix;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import com.unity3d.video.MoblieVideo_GlRender;
import com.unity3d.Texture2DExt;
import com.unity3d.Texture2D;
import com.unity3d.FBO;

import com.google.android.exoplayer2.upstream.cache.Cache;

import java.io.File;

public class ExoPlayerUnity implements SurfaceTexture.OnFrameAvailableListener
{
    private static HashMap<Integer, ExoPlayerUnity> s_AllPlayers = null;
    //Unity Class Defaults
    private static final String TAG = "ExoPlayerUnity";

    private Context myContext ;
    public Handler handler;
    public File downloadDirectory;
    public Cache downloadCache;
    public IUnityMessage unityMessage;

    private Texture2DExt mTexture2DExt;
    private Texture2D mUnityTexture;
    private FBO mFBO;
    SurfaceTexture surfaceTexture;
    Surface mySurface;

    float[] mSurfaceTextureMat = new float[16];

    MoblieVideo_GlRender m_GlRender_Video;

    int m_TextureHandle =0;

    VideoPlayer videoPlayer;
    int m_nPlayIndex;

    boolean mNewFrameAvailable = false;

    public static void OnRendererEvent(int eventID)
    {
        Log.d(TAG, "OnRendererEventJava: " + eventID);
        int eventType = (eventID >> 16) & 0xFFFF;
        int playerIndex = (eventID >> 8) & 0xFF;
        int gfxType = eventID & 0xFF;
        if(eventType == 2)   
            RendererSetupPlayer(playerIndex, gfxType);
        else if(eventType == 3)
            RenderPlayer(playerIndex);
    }

    private static ExoPlayerUnity GetClassForPlayerIndex(int playerIndex) 
    {
        ExoPlayerUnity returnPlayerClass = null;
        if (s_AllPlayers != null) 
        {
            if (s_AllPlayers.containsKey(Integer.valueOf(playerIndex)))
                returnPlayerClass = (ExoPlayerUnity) s_AllPlayers.get(Integer.valueOf(playerIndex));
        }
        return returnPlayerClass;
    }
    public static void RenderPlayer(int playerIndex) 
    {
        Log.d(TAG, "RenderPlayer" + playerIndex);
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
           theClass.Render();
        }
    }
    public static void RendererSetupPlayer(int playerIndex, int iDeviceIndex)
    {
        Log.d(TAG, "RendererSetupPlayer" + playerIndex + " DeviceIndex:" + iDeviceIndex);
               
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
        {
            theClass.Prepare();
        }
        }
    }

    public void Initialise(Context context, int index, IUnityMessage _unityMessage)
    {
        if (videoPlayer == null)
        {
            m_nPlayIndex = index;
            myContext = context;
            unityMessage = _unityMessage;
            Log.d(TAG, "Added video player :" + index);
            if (s_AllPlayers == null) s_AllPlayers = new HashMap<Integer, ExoPlayerUnity>();
            s_AllPlayers.put(Integer.valueOf(m_nPlayIndex), this);
            
        }
    }

    public void Initialise(Context context, int index)
    {
        if (videoPlayer == null)
        {
            m_nPlayIndex = index;
            myContext = context;
            unityMessage = null;
            Log.d(TAG, "Added video player");
            if (s_AllPlayers == null) s_AllPlayers = new HashMap<Integer, ExoPlayerUnity>();
            s_AllPlayers.put(Integer.valueOf(m_nPlayIndex), this);
        }
    }    

    public int GetPlayIndex()
    {
        return m_nPlayIndex;
    }

    public boolean OpenVideoFromFile(String filePath, long offset, String httpJson) 
    {
        if(myContext == null) return false;
        if(videoPlayer !=null) return false;
        videoPlayer = new VideoPlayer(this,myContext, filePath);
        return true;
    }

    public static String GetPluginVersion()
    {
        return "1.0.0";
    }

    public int GetTextureHandle()
    {
        if(mUnityTexture!=null)
            return mUnityTexture.getTextureID();
        return m_TextureHandle;
    }

    private Handler getHandler()
    {
        if (handler == null)
        {
            handler = new Handler(Looper.getMainLooper());
        }

        return handler;
    }

    public void Log(String message)
    {
        Log.d(TAG, message);
    }

    public void Prepare()
    {
        if (videoPlayer == null) return;
        // set up exoplayer on main thread
        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.Prepare(null);
            }
        });
    }

    public void Render()
    {
         Log.d(TAG, "Render " + GetWidth() + "x" + GetHeight());

        if(GetWidth() <=0 || GetHeight() <=0)
         return;

         if(surfaceTexture == null)
         {
            CreateExoSurface(GetWidth(), GetHeight());
            return;
         }
        UpdateSurfaceTexture();
    }

    public void UpdateSurfaceTexture()
    {
        if (videoPlayer == null) return;
        Log.d(TAG, "UpdateSurfaceTexture " + GetWidth() + "x" + GetHeight());

        if(mNewFrameAvailable)
        {
            Log.d(TAG, "UpdateSurfaceTexture NeFrame" + GetWidth() + "x" + GetHeight());
            mNewFrameAvailable = false;
                    if (surfaceTexture != null)
                    {
                        surfaceTexture.updateTexImage();

                        surfaceTexture.getTransformMatrix(mSurfaceTextureMat);

                        RenderScene(mSurfaceTextureMat, this.m_TextureHandle);
                       long ts =  surfaceTexture.getTimestamp();
                        Log.d("EXO", "updateTexImage ts=" + ts + " width=" + GetWidth() + " height=" + GetHeight());
                    }
                }
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e("Unity", op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }

    void RenderScene(float[] stMatrix, int textureId)
    {
        if(mUnityTexture == null)
        {
            mUnityTexture = new Texture2D(myContext, GetWidth(), GetHeight());
            mFBO = new FBO(mUnityTexture);
        }

        Matrix.setIdentityM(mSurfaceTextureMat, 0);
        mFBO.FBOBegin();
        GLES20.glViewport(0, 0, GetWidth(), GetHeight());
        mTexture2DExt.draw(mSurfaceTextureMat);
        mFBO.FBOEnd();
    }

    public void CreateExoSurface(int width, int height)
    {
        if (videoPlayer == null) return;

        DestroyGlTexture();
/* 
        int[] textures = new int[1];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGenTextures(1, textures, 0);
        m_TextureHandle = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, m_TextureHandle);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
       // GLES20.glBindTexture( GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0 );

        surfaceTexture = new SurfaceTexture(m_TextureHandle);
        surfaceTexture.setDefaultBufferSize(width, height);
        surfaceTexture.setOnFrameAvailableListener(this);

        mySurface = new Surface(surfaceTexture);
        */
         mTexture2DExt = new Texture2DExt(myContext, 0,0);
          surfaceTexture = new SurfaceTexture(mTexture2DExt.getTextureID());
          m_TextureHandle = mTexture2DExt.getTextureID();
        surfaceTexture.setDefaultBufferSize(width, height);
        surfaceTexture.setOnFrameAvailableListener(this);

        mySurface = new Surface(surfaceTexture);

        getHandler().post(new Runnable()
        {
                @Override
                public void run()
                {
                    videoPlayer.AttackSurface(mySurface);
                }
        }); 
        Log.d(TAG, "CreateExoSurface " + width + "x" + height + "   textid:" + m_TextureHandle);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mNewFrameAvailable = true;
    }


    public void Play()
    {
        if (videoPlayer == null) return;

        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.Play();
            }
        });
    }
    public void Pause()
    {
        if (videoPlayer == null) return;

        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.Pause();
            }
        });
    }
    public void Stop()
    {
        if (videoPlayer == null) return;

        s_AllPlayers.remove(Integer.valueOf(this.m_nPlayIndex));
        if ((s_AllPlayers != null) && (s_AllPlayers.isEmpty())) {
            s_AllPlayers.clear();
            s_AllPlayers = null;
        }

        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.Stop();
                videoPlayer = null;
                if(surfaceTexture!=null)surfaceTexture.release();
                surfaceTexture = null;

                if(mySurface!=null)mySurface.release();
                mySurface = null;

                DestroyGlTexture();
            }
        });
    }

    private void DestroyGlTexture()
    {
        if(mFBO!=null)mFBO.destory();
        mFBO = null;
        if(mUnityTexture!=null)mUnityTexture.destory();
        mUnityTexture = null;
        if(mTexture2DExt!=null) mTexture2DExt.destory();
        mTexture2DExt = null;
        m_TextureHandle = 0;
    }


    ///// SETTERS //////
    public void SetLooping(final boolean looping)
    {
        if (videoPlayer == null) return;

        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.SetLooping(looping);
            }
        });
    }

    public boolean IsLooping()
    {
        if (videoPlayer == null)
        {
            return false;
        }
        return videoPlayer.IsLooping();
    }

    public boolean CanPlay()
    {
        return videoPlayer !=null;
    }

    public void SetPlaybackPosition(final double percent)
    {
        if (videoPlayer == null) return;

        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.SetPlaybackPosition(percent);
            }
        });
    }
    public void SetPlaybackSpeed(final float speed)
    {
        if (videoPlayer == null) return;

        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.SetPlaybackSpeed(speed);
            }
        });
    }


    public int GetWidth()
    {
        if (videoPlayer == null)
        {
            return -1;
        }

        return videoPlayer.GetWidth();
    }
    public int GetHeight()
    {
        if (videoPlayer == null)
        {
            return -1;
        }

        return videoPlayer.GetHeight();
    }
    public boolean GetIsPlaying()
    {
        if (videoPlayer == null)
        {
            return false;
        }

        return videoPlayer.GetIsPlaying();
    }

    public boolean IsPaused()
    {
        if (videoPlayer == null)
        {
            return false;
        }

        return videoPlayer.IsPaused();
    }

    public boolean IsFinished()
    {
        if (videoPlayer == null)
        {
            return false;
        }

        return videoPlayer.IsFinished();
    }

    public boolean IsBuffering()
    {
        if (videoPlayer == null)
        {
            return false;
        }

        return videoPlayer.IsBuffering();
    }

    public int GetCurrentPlaybackState()
    {
        if (videoPlayer == null)
        {
            return 0;
        }

        return videoPlayer.GetCurrentPlaybackState();
    }
    public long GetLength()
    {
        if (videoPlayer == null)
        {
            return 0;
        }

        return videoPlayer.GetLength();
    }
    public double GetPlaybackPosition()
    {
        if (videoPlayer == null)
        {
            return 0;
        }

        return videoPlayer.GetPlaybackPosition();
    }

    public int GetFrameCount()
    {
        if(videoPlayer == null) return 0;
        double positionMs   = GetPlaybackPosition()*GetLength();
        double frameRate    = 30;
        int currentFrame = (int)(positionMs * frameRate);
        return currentFrame;
    }

    public void SetVolume(float volume)
    {
        if(videoPlayer == null) return;
        
        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.SetVolume(volume);
            }
        });
    }
}