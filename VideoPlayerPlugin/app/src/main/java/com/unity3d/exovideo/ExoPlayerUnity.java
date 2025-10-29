package com.unity3d.exovideo;
import android.os.Build;
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

    int m_TextureHandle =0;

    VideoPlayer videoPlayer;
    int m_nPlayIndex;

    int m_iNumberFramesAvailable = 0;
    int m_nFrameCount = 0;

    boolean mNewFrameAvailable = false;
    int m_iOpenGLVersion = 1;
    boolean m_bCanUseGLBindVertexArray = false;

    public static void OnRendererEvent(int eventID)
    {
      //  Log.d(TAG, "OnRendererEventJava: " + eventID);
        int eventType = (eventID >> 16) & 0xFFFF;
        int playerIndex = (eventID >> 8) & 0xFF;
        int gfxType = eventID & 0xFF;
        if(eventType == 2)   
            RendererSetupPlayer(playerIndex, gfxType);
        else if(eventType == 3)
            RenderPlayer(playerIndex);
        else if(eventType == 4)
            RenderResume(playerIndex);    
        else if(eventType == 5)
            RenderDestroy(playerIndex);                        
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
     //   Log.d(TAG, "RenderPlayer" + playerIndex);
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
           theClass.Render();
        }
    }
    
    public static void RendererSetupPlayer(int playerIndex, int iDeviceIndex)
    {
        Log.d(TAG, "RendererSetupPlayer" + playerIndex + " DeviceIndex:" + iDeviceIndex);
               
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) 
        {
            boolean bOverride = false;
            if(iDeviceIndex == 8)
            {
                theClass.m_iOpenGLVersion = 2;  //opengles2.0
                bOverride = true;
            }
            else if(iDeviceIndex == 11)//opengles3.0
            {
                theClass.m_iOpenGLVersion =3;
                bOverride = true;
            }
            if(bOverride)
            {
                theClass.m_bCanUseGLBindVertexArray = false;//((theClass.m_iOpenGLVersion > 2) && (Build.VERSION.SDK_INT >= 18));
            }

            {
                theClass.Prepare();
            }
        }
    }

    public static void RenderResume(int playerIndex) 
    {
        Log.d(TAG, "RenderResume" + playerIndex);
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
           theClass.Resume();
        }
    }

    public static void RenderDestroy(int playerIndex) 
    {
        Log.d(TAG, "RenderDestroy" + playerIndex);
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
           theClass.Destroy();
        }
    }

    public void SetUnityCallback(IUnityMessage _unityMessage)
    {
        this.unityMessage = _unityMessage;
    }

    public void Initialise(Context context, int index)
    {
        m_iNumberFramesAvailable =0;
        m_nFrameCount = 0;
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
        m_nFrameCount = 0;
        m_iNumberFramesAvailable =0;
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
        m_nFrameCount = 0;
        m_iNumberFramesAvailable =0;
        if (videoPlayer == null) return;

        if(surfaceTexture == null)
         {
            CreateExoSurface(GetWidth(), GetHeight());
         }   
        // set up exoplayer on main thread
        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                if(mySurface!=null) mySurface.release();
                mySurface = new Surface(surfaceTexture);
                videoPlayer.Prepare(mySurface);
            }
        });
    }

    public void AttackSurface()
    {
        if (videoPlayer == null) return;
        // set up exoplayer on main thread
        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                videoPlayer.AttackSurface(mySurface);
            }
        });
    }

    public void Resume()
    {
        if(videoPlayer == null)
            return;
        CreateExoSurface(GetWidth(), GetHeight());
        if(GetWidth() >0 && GetHeight() >0)
        {
            mUnityTexture = new Texture2D(myContext, GetWidth(), GetHeight(), m_bCanUseGLBindVertexArray);
            mFBO = new FBO(mUnityTexture);
        }

        getHandler().post(new Runnable()
        {
            @Override
            public void run()
            {
                if(mySurface!=null) mySurface.release();
                mySurface = new Surface(surfaceTexture);
                videoPlayer.AttackSurface(mySurface);
            }
        });
        Log.d(TAG, "Resume " + GetWidth() + "x" + GetHeight() + "   textid:" + m_TextureHandle);
    }

    public void Render()
    {
        synchronized(this)
        {
            if(GetWidth() <=0 || GetHeight() <=0)
            return;

            if(surfaceTexture == null)
                return;
            
            UpdateSurfaceTexture();
        }
    }

    public void UpdateSurfaceTexture()
    {
        if (videoPlayer == null) return;

        if(m_iNumberFramesAvailable>0 && mNewFrameAvailable)
        {
            int iNumFramesAvailable = this.m_iNumberFramesAvailable;
            mNewFrameAvailable = false;
            m_iNumberFramesAvailable =0;
            if (surfaceTexture != null)
            {
                surfaceTexture.updateTexImage();

                surfaceTexture.getTransformMatrix(mSurfaceTextureMat);

                RenderScene(mSurfaceTextureMat, this.m_TextureHandle,m_iNumberFramesAvailable);
                m_nFrameCount++;
                if(m_nFrameCount >= 1000000) m_nFrameCount =1;

               // Log.d(TAG, "RenderScene " + m_nPlayIndex + "   textid:" + m_TextureHandle);
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

    void RenderScene(float[] stMatrix, int textureId, int numFrame)
    {
        try
        {
            if(mUnityTexture == null)
            {
                mUnityTexture = new Texture2D(myContext, GetWidth(), GetHeight(), m_bCanUseGLBindVertexArray);
                mFBO = new FBO(mUnityTexture);
            }

            if(this.unityMessage!=null)
                this.unityMessage.OnVideoRenderBegin(this.m_nPlayIndex);

            Matrix.setIdentityM(mSurfaceTextureMat, 0);

            mFBO.FBOBegin();
            GLES20.glViewport(0, 0, GetWidth(), GetHeight());
            mTexture2DExt.draw(mSurfaceTextureMat,false);
            mFBO.FBOEnd();
            if(this.unityMessage!=null)
                this.unityMessage.OnVideoRenderEnd(this.m_nPlayIndex);
        }
        catch (Exception e)
        {
            Log.e(TAG, "RenderScene Exception: " + e.getMessage());
        }

    }

    public void CreateExoSurface(int width, int height)
    {
        if (videoPlayer == null) return;

        DestroySurface();
        DestroyGl();
         mTexture2DExt = new Texture2DExt(myContext, 0,0,m_bCanUseGLBindVertexArray);
          surfaceTexture = new SurfaceTexture(mTexture2DExt.getTextureID());
          m_TextureHandle = mTexture2DExt.getTextureID();
        surfaceTexture.setDefaultBufferSize(width, height);
        surfaceTexture.setOnFrameAvailableListener(this);
        Log.d(TAG, "CreateExoSurface " + width + "x" + height + "   textid:" + m_TextureHandle);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (this) {
            if(st == this.surfaceTexture)
            {
             //   Log.d(TAG, "onFrameAvailable " + m_nPlayIndex);
                mNewFrameAvailable = true;
                m_iNumberFramesAvailable+=1;
            }
            else
            {
                Log.d(TAG, "onFrameAvailable !=");
            }
            return;
        }
       
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
            }
        });
    }

    public void Destroy()
    {
        Stop();
        DestroySurface();
        DestroyGl();
    }    

    private void DestroyGl()
    {
        if(mFBO!=null)mFBO.destory();
        mFBO = null;
        if(mUnityTexture!=null)mUnityTexture.destory();
        mUnityTexture = null;
        if(mTexture2DExt!=null) mTexture2DExt.destory();
        mTexture2DExt = null;
        m_TextureHandle = 0;
    }

    private void DestroySurface()
    {
        if(surfaceTexture!=null)
        {
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
        }
        surfaceTexture = null;

        if(mySurface!=null)mySurface.release();
        mySurface = null;

        m_iNumberFramesAvailable =0;
        mNewFrameAvailable = false;
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
        if(videoPlayer == null || !GetIsPlaying()) return 0;
        return m_nFrameCount;
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