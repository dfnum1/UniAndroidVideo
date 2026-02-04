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
import android.media.Image;
import android.media.ImageReader;
import android.graphics.ImageFormat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import com.unity3d.video.MoblieVideo_GlRender;
import com.unity3d.Texture2DExt;
import com.unity3d.Texture2DExtYUV;
import com.unity3d.Texture2D;
import com.unity3d.FBO;
import android.os.Build;

import com.google.android.exoplayer2.upstream.cache.Cache;

import java.io.File;

public class ExoPlayerUnity implements SurfaceTexture.OnFrameAvailableListener {
    private static HashMap<Integer, ExoPlayerUnity> s_AllPlayers = null;
    // Unity Class Defaults
    private static final String TAG = "ExoPlayerUnity";

    private Context myContext;
    public Handler handler;
    public File downloadDirectory;
    public Cache downloadCache;
    public IUnityMessage unityMessage;

    private Texture2DExt mTexture2DExt;
    private Texture2DExtYUV mTexture2DExtRGBA;
    private Texture2D mUnityTexture;
    private FBO mFBO;
    SurfaceTexture surfaceTexture;
    Surface mySurface;

    boolean m_UseImageReader = false;
    ImageReader mVideImageReader;

    float[] mSurfaceTextureMat = new float[16];
    private byte[] mCachedBodyData = null;

    int m_TextureHandle = 0;

    VideoPlayer videoPlayer;
    int m_nPlayIndex;

    int m_iNumberFramesAvailable = 0;
    int m_nFrameCount = 0;

    boolean mNewFrameAvailable = false;
    int m_iOpenGLVersion = 1;
    boolean m_bCanUseGLBindVertexArray = false;

    public static void OnRendererEvent(int eventID) {
        // Log.d(TAG, "OnRendererEventJava: " + eventID);
        int eventType = (eventID >> 16) & 0xFFFF;
        int playerIndex = (eventID >> 8) & 0xFF;
        int gfxType = eventID & 0xFF;
        if (eventType == 2)
            RendererSetupPlayer(playerIndex, gfxType);
        else if (eventType == 3)
            RenderPlayer(playerIndex);
        else if (eventType == 4)
            RenderResume(playerIndex);
        else if (eventType == 5)
            RenderDestroy(playerIndex);
    }

    // 创建简单的着色器程序 - 已移除，使用Texture2DExtRGBA类

    private static ExoPlayerUnity GetClassForPlayerIndex(int playerIndex) {
        ExoPlayerUnity returnPlayerClass = null;
        if (s_AllPlayers != null) {
            if (s_AllPlayers.containsKey(Integer.valueOf(playerIndex)))
                returnPlayerClass = (ExoPlayerUnity) s_AllPlayers.get(Integer.valueOf(playerIndex));
        }
        return returnPlayerClass;
    }

    public static void RenderPlayer(int playerIndex) {
        // Log.d(TAG, "RenderPlayer" + playerIndex);
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
            theClass.Render();
        }
    }

    public static String getPrimaryAbi() {
        // API 21+ (Android 5.0+) 推荐方式
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String[] supportedAbis = Build.SUPPORTED_ABIS;
            if (supportedAbis != null && supportedAbis.length > 0) {
                return supportedAbis[0]; // 主 ABI（性能最优）
            }
        }

        return Build.CPU_ABI;
    }

    public static boolean isX8664() {
        String abi = getPrimaryAbi();
        return "x86_64".equals(abi);
    }

    public static boolean isEmulator() {
        try {
            String[] mumuFiles = {
                    "/system/etc/mumu-configs"
            };
            for (String file : mumuFiles) {
                if (new File(file).exists()) {
                    Log.d(TAG, "Detected MuMu Emulator due to presence of file: " + file);
                    return isX8664();
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "isEmulator Exception: " + ex.getMessage());
        }

        return false;
    }

    public static void RendererSetupPlayer(int playerIndex, int iDeviceIndex) {
        Log.d(TAG, "RendererSetupPlayer" + playerIndex + " DeviceIndex:" + iDeviceIndex);

        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
            boolean bOverride = false;
            if (iDeviceIndex == 8) {
                theClass.m_iOpenGLVersion = 2; // opengles2.0
                bOverride = true;
            } else if (iDeviceIndex == 11)// opengles3.0
            {
                theClass.m_iOpenGLVersion = 3;
                bOverride = true;
            } else if (iDeviceIndex >= 255) {
                // ! 使用ImageReader模式
                // ! 目前在mumu模拟器上开启了这个模式，其余的还是默认
                Log.d(TAG, "RendererSetupPlayer Use ImageReader mode");
                theClass.m_UseImageReader = true;
            }

            if (isEmulator()) {
                Log.d(TAG, "Detected Emulator, Force Enable ImageReader mode");
                theClass.m_UseImageReader = true;
            }

            if (bOverride) {
                theClass.m_bCanUseGLBindVertexArray = false;// ((theClass.m_iOpenGLVersion > 2) &&
                                                            // (Build.VERSION.SDK_INT >= 18));
            }

            {
                theClass.Prepare();
            }
        }
    }

    public static void RenderResume(int playerIndex) {
        // Log.d(TAG, "RenderResume" + playerIndex);
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
            theClass.Resume();
        }
    }

    public static void RenderDestroy(int playerIndex) {
        // Log.d(TAG, "RenderDestroy" + playerIndex);
        ExoPlayerUnity theClass;
        if ((theClass = GetClassForPlayerIndex(playerIndex)) != null) {
            theClass.Destroy();
        }
    }

    public void SetUnityCallback(IUnityMessage _unityMessage) {
        this.unityMessage = _unityMessage;
    }

    public void Initialise(Context context, int index) {
        m_iNumberFramesAvailable = 0;
        m_nFrameCount = 0;
        if (videoPlayer == null) {
            m_nPlayIndex = index;
            myContext = context;
            unityMessage = null;
            // Log.d(TAG, "Added video player");
            if (s_AllPlayers == null)
                s_AllPlayers = new HashMap<Integer, ExoPlayerUnity>();
            s_AllPlayers.put(Integer.valueOf(m_nPlayIndex), this);
        }
    }

    public int GetPlayIndex() {
        return m_nPlayIndex;
    }

    public boolean OpenVideoFromFile(String filePath, long offset, String httpJson) {
        if (myContext == null)
            return false;
        if (videoPlayer != null)
            return false;
        m_nFrameCount = 0;
        m_iNumberFramesAvailable = 0;
        videoPlayer = new VideoPlayer(this, myContext, filePath);
        return true;
    }

    public static String GetPluginVersion() {
        return "1.0.0";
    }

    public int GetTextureHandle() {
        if (mUnityTexture != null)
            return mUnityTexture.getTextureID();
        return m_TextureHandle;
    }

    private Handler getHandler() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }

        return handler;
    }

    public void Log(String message) {
        Log.d(TAG, message);
    }

    public void Prepare() {
        m_nFrameCount = 0;
        m_iNumberFramesAvailable = 0;
        if (videoPlayer == null)
            return;

        // 对于SurfaceTexture模式，先创建表面
        // 对于ImageReader模式，等到视频尺寸确定后再创建
        if (!m_UseImageReader && surfaceTexture == null) {
            CreateExoSurface(GetWidth(), GetHeight());
        }

        // set up exoplayer on main thread
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (mySurface != null)
                    mySurface.release();

                if (!m_UseImageReader && surfaceTexture != null) {
                    mySurface = new Surface(surfaceTexture);
                } else {
                    // ImageReader模式暂时不创建Surface，等到视频尺寸确定后再创建
                    mySurface = null;
                }

                videoPlayer.Prepare(mySurface);
            }
        });
    }

    public void AttackSurface() {
        if (videoPlayer == null)
            return;
        // set up exoplayer on main thread
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.AttackSurface(mySurface);
            }
        });
    }

    public void Resume() {
        if (videoPlayer == null)
            return;
        DestroySurface();
        DestroyGl();
        mNewFrameAvailable = false;
        m_iNumberFramesAvailable = 0;
        CreateExoSurface(GetWidth(), GetHeight());
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                // 对于ImageReader模式，mySurface已经在CreateExoSurface中设置
                // 对于SurfaceTexture模式，重新创建Surface
                if (!m_UseImageReader && surfaceTexture != null) {
                    if (mySurface != null)
                        mySurface.release();
                    mySurface = new Surface(surfaceTexture);
                }
                // 绑定表面到播放器
                if (mySurface != null) {
                    videoPlayer.AttackSurface(mySurface);
                }
            }
        });
        Log.d(TAG, "Resume initiated " + GetWidth() + "x" + GetHeight() + "   textid:" + m_TextureHandle);
    }

    public void Render() {
        synchronized (this) {
            // Log.d(TAG, "Render: Starting");

            // 确保使用有效的尺寸
            int width = GetWidth();
            int height = GetHeight();

            if (m_UseImageReader) {
                if (mVideImageReader == null) {
                    // Log.d(TAG, "Render: mVideImageReader is null, recreating surface");
                    CreateExoSurface(width, height);
                    return;
                }
                // Log.d(TAG, "Render: Using ImageReader mode");
                UpdateImageReaderFrame();
            } else {
                if (surfaceTexture == null) {
                    Log.d(TAG, "Render: surfaceTexture is null, recreating surface");
                    CreateExoSurface(width, height);
                    return;
                }
                // Log.d(TAG, "Render: Using SurfaceTexture mode, textureHandle=" +
                // m_TextureHandle);
                UpdateSurfaceTexture();
            }

            // Log.d(TAG, "Render: Completed");
        }
    }

    public void UpdateSurfaceTexture() {
        if (videoPlayer == null) {
            Log.d(TAG, "UpdateSurfaceTexture: videoPlayer is null");
            return;
        }

        // Log.d(TAG, "UpdateSurfaceTexture: Available frames=" +
        // m_iNumberFramesAvailable + ", New frame="
        // + mNewFrameAvailable);

        if (m_iNumberFramesAvailable > 0 && mNewFrameAvailable) {
            int iNumFramesAvailable = this.m_iNumberFramesAvailable;
            mNewFrameAvailable = false;
            m_iNumberFramesAvailable = 0;
            if (surfaceTexture != null) {
                try {
                    // Log.d(TAG, "UpdateSurfaceTexture: Updating texture image");
                    surfaceTexture.updateTexImage();

                    surfaceTexture.getTransformMatrix(mSurfaceTextureMat);

                    // Log.d(TAG, "UpdateSurfaceTexture: Rendering scene");
                    RenderScene(mSurfaceTextureMat, this.m_TextureHandle, iNumFramesAvailable);
                    m_nFrameCount++;
                    if (m_nFrameCount >= 1000000)
                        m_nFrameCount = 1;

                    // Log.d(TAG, "UpdateSurfaceTexture: Completed, frame count=" + m_nFrameCount);
                } catch (Exception e) {
                    Log.e(TAG, "UpdateSurfaceTexture Exception: " + e.getMessage());
                    e.printStackTrace();

                    // 如果发生OpenGL错误，尝试重新创建表面
                    if (e.getMessage().contains("GL_INVALID") || e.getMessage().contains("OpenGL")) {
                        Log.d(TAG, "UpdateSurfaceTexture: OpenGL error, recreating surface");
                        int width = GetWidth();
                        int height = GetHeight();
                        CreateExoSurface(width, height);
                    }
                }
            } else {
                Log.d(TAG, "UpdateSurfaceTexture: surfaceTexture is null");
            }
        }
    }

    public void UpdateImageReaderFrame() {
        if (videoPlayer == null) {
            return;
        }

        if (m_iNumberFramesAvailable > 0 && mNewFrameAvailable) {
            int iNumFramesAvailable = this.m_iNumberFramesAvailable;
            mNewFrameAvailable = false;
            m_iNumberFramesAvailable = 0;
            if (mVideImageReader != null) {
                Image image = mVideImageReader.acquireLatestImage();

                if (image != null) {
                    try {
                        // 处理Image数据并渲染
                        // RenderImageReaderFrame(image);
                        RenderImageNoGL(image);
                        m_nFrameCount++;
                        if (m_nFrameCount >= 1000000)
                            m_nFrameCount = 1;
                    } finally {
                        image.close();
                    }
                } else {
                    Log.d(TAG, "UpdateImageReaderFrame: No new image available");
                }
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

    void RenderScene(float[] stMatrix, int textureId, int numFrame) {
        try {
            // Log.d(TAG, "RenderScene: Starting");

            // 确保使用有效的尺寸
            int width = GetWidth();
            int height = GetHeight();
            if (width <= 0 || height <= 0) {
                Log.d(TAG, "RenderScene: Using default size " + width + "x" + height);
                return;
            }

            // 确保OpenGL资源正确初始化
            if (mUnityTexture == null) {
                // Log.d(TAG, "RenderScene: Creating mUnityTexture");
                mUnityTexture = new Texture2D(myContext, width, height, m_bCanUseGLBindVertexArray);
                mFBO = new FBO(mUnityTexture);
                // Log.d(TAG, "RenderScene: Created mUnityTexture and mFBO");
            }

            // 确保mTexture2DExt正确初始化
            if (mTexture2DExt == null && !m_UseImageReader) {
                // Log.d(TAG, "RenderScene: Creating mTexture2DExt");
                mTexture2DExt = new Texture2DExt(myContext, width, height, m_bCanUseGLBindVertexArray);
                // Log.d(TAG, "RenderScene: Created mTexture2DExt with textureId=" +
                // mTexture2DExt.getTextureID());
            }

            if (this.unityMessage != null)
                this.unityMessage.OnVideoRenderBegin(this.m_nPlayIndex);

            // 确保FBO正确初始化
            if (mFBO != null) {
                // Log.d(TAG, "RenderScene: Starting FBO render");
                mFBO.FBOBegin();
                GLES20.glViewport(0, 0, width, height);

                // 清除颜色缓冲区，避免白色画面
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                // 确保mTexture2DExt正确初始化
                if (mTexture2DExt != null) {
                    // Log.d(TAG, "RenderScene: Drawing with mTexture2DExt");
                    mTexture2DExt.draw(stMatrix, false);
                } else {
                    // Log.e(TAG, "RenderScene: mTexture2DExt is null");
                }

                mFBO.FBOEnd();
                // Log.d(TAG, "RenderScene: FBO render completed");
            } else {
                // Log.e(TAG, "RenderScene: mFBO is null");
            }

            if (this.unityMessage != null)
                this.unityMessage.OnVideoRenderEnd(this.m_nPlayIndex);

            // Log.d(TAG, "RenderScene: Completed");
        } catch (Exception e) {
            Log.e(TAG, "RenderScene Exception: " + e.getMessage());
            e.printStackTrace();

            // 如果发生OpenGL错误，尝试重新初始化资源
            if (e.getMessage().contains("GL_INVALID") || e.getMessage().contains("OpenGL")) {
                // Log.d(TAG, "RenderScene: OpenGL error, resetting resources");
                if (mUnityTexture != null) {
                    mUnityTexture.destory();
                    mUnityTexture = null;
                }
                if (mFBO != null) {
                    mFBO.destory();
                    mFBO = null;
                }
            }
        }

    }

    void RenderImageNoGL(Image image) {
        try {
            if (mUnityTexture == null) {
                mUnityTexture = new Texture2D(myContext, GetWidth(), GetHeight(), m_bCanUseGLBindVertexArray);
            }
            if (this.unityMessage != null)
                this.unityMessage.OnVideoRenderBegin(this.m_nPlayIndex);
            Image.Plane[] planes = image.getPlanes();
            if (planes.length >= 3) {
                int width = image.getWidth();
                int height = image.getHeight();
                // 获取YUV数据
                ByteBuffer yBuffer = planes[0].getBuffer();
                ByteBuffer uBuffer = planes[1].getBuffer();
                ByteBuffer vBuffer = planes[2].getBuffer();

                int yRowStride = planes[0].getRowStride();
                int uvRowStride = planes[1].getRowStride();
                int uvPixelStride = planes[1].getPixelStride();

                // Check and reallocate cache if necessary
                if (mCachedBodyData == null || mCachedBodyData.length != width * height * 4) {
                    mCachedBodyData = new byte[width * height * 4];
                }

                byte[] data = mCachedBodyData;

                // YUV转RGBA
                for (int i = 0; i < height; i++) {
                    int invertedRowIndex = (height - 1 - i) * width;
                    for (int j = 0; j < width; j++) {
                        int y = yBuffer.get(i * yRowStride + j) & 0xFF;
                        int u = uBuffer.get((i / 2) * uvRowStride + (j / 2) * uvPixelStride) & 0xFF;
                        int v = vBuffer.get((i / 2) * uvRowStride + (j / 2) * uvPixelStride) & 0xFF;

                        // YUV转RGB
                        int r = (int) (y + 1.402f * (v - 128));
                        int g = (int) (y - 0.344f * (u - 128) - 0.714f * (v - 128));
                        int b = (int) (y + 1.772f * (u - 128));

                        // clamp to [0, 255]
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));

                        // Flip vertically and write directly to byte array (RGBA)
                        int pixelIndex = (invertedRowIndex + j) * 4;
                        data[pixelIndex] = (byte) r; // R
                        data[pixelIndex + 1] = (byte) g; // G
                        data[pixelIndex + 2] = (byte) b; // B
                        data[pixelIndex + 3] = (byte) 255; // A
                    }
                }

                // 使用Texture2DExtRGBA的updateTexture方法更新纹理数据
                mUnityTexture.updateTexture(width, height, data);
            }

            if (this.unityMessage != null)
                this.unityMessage.OnVideoRenderEnd(this.m_nPlayIndex);
        } catch (Exception e) {
            Log.e(TAG, "RenderImageNoGL Exception: " + e.getMessage());
        }
    }

    void RenderImageReaderFrame(Image image) {
        try {
            // 获取Image的像素数据
            Image.Plane[] planes = image.getPlanes();
            if (planes.length >= 3) {
                int width = image.getWidth();
                int height = image.getHeight();

                if (mUnityTexture == null) {
                    mUnityTexture = new Texture2D(myContext, GetWidth(), GetHeight(), m_bCanUseGLBindVertexArray);
                    mFBO = new FBO(mUnityTexture);
                }

                // 确保mTexture2DExtRGBA不为null且尺寸正确
                if (mTexture2DExtRGBA == null || mTexture2DExtRGBA.getWidth() != width
                        || mTexture2DExtRGBA.getHeight() != height) {
                    mTexture2DExtRGBA = new Texture2DExtYUV(myContext, width, height, m_bCanUseGLBindVertexArray);
                }

                // 确保mTexture2DExtRGBA和mFBO不为null
                if (mTexture2DExtRGBA != null && mFBO != null) {
                    if (this.unityMessage != null)
                        this.unityMessage.OnVideoRenderBegin(this.m_nPlayIndex);

                    // 获取YUV数据
                    ByteBuffer yBuffer = planes[0].getBuffer();
                    ByteBuffer uBuffer = planes[1].getBuffer();
                    ByteBuffer vBuffer = planes[2].getBuffer();

                    int yRowStride = planes[0].getRowStride();
                    int uvRowStride = planes[1].getRowStride();
                    int uvPixelStride = planes[1].getPixelStride();

                    // 直接创建像素数组，避免使用Bitmap
                    int[] pixels = new int[width * height];

                    // YUV转RGBA
                    for (int i = 0; i < height; i++) {
                        for (int j = 0; j < width; j++) {
                            int y = yBuffer.get(i * yRowStride + j) & 0xFF;
                            int u = uBuffer.get((i / 2) * uvRowStride + (j / 2) * uvPixelStride) & 0xFF;
                            int v = vBuffer.get((i / 2) * uvRowStride + (j / 2) * uvPixelStride) & 0xFF;

                            // YUV转RGB
                            int r = (int) (y + 1.402f * (v - 128));
                            int g = (int) (y - 0.344f * (u - 128) - 0.714f * (v - 128));
                            int b = (int) (y + 1.772f * (u - 128));

                            // clamp to [0, 255]
                            r = Math.max(0, Math.min(255, r));
                            g = Math.max(0, Math.min(255, g));
                            b = Math.max(0, Math.min(255, b));

                            pixels[i * width + j] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                        }
                    }

                    // 将像素数组转换为字节数组
                    byte[] data = new byte[width * height * 4];
                    for (int i = 0; i < pixels.length; i++) {
                        int pixel = pixels[i];
                        data[i * 4] = (byte) ((pixel >> 16) & 0xFF); // R
                        data[i * 4 + 1] = (byte) ((pixel >> 8) & 0xFF); // G
                        data[i * 4 + 2] = (byte) (pixel & 0xFF); // B
                        data[i * 4 + 3] = (byte) ((pixel >> 24) & 0xFF); // A
                    }

                    // 使用Texture2DExtRGBA的updateTexture方法更新纹理数据
                    mTexture2DExtRGBA.updateTexture(width, height, data);

                    // 绘制纹理到FBO
                    mFBO.FBOBegin();
                    GLES20.glViewport(0, 0, width, height);
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                    // 使用Texture2DExtRGBA绘制
                    Matrix.setIdentityM(mSurfaceTextureMat, 0);
                    mTexture2DExtRGBA.draw(mSurfaceTextureMat, false);

                    mFBO.FBOEnd();

                    if (this.unityMessage != null)
                        this.unityMessage.OnVideoRenderEnd(this.m_nPlayIndex);
                } else {
                    Log.e(TAG, "mTexture2DExtRGBA or mFBO is null");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "RenderImageReaderFrame Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void CreateExoSurface(int width, int height) {
        if (videoPlayer == null)
            return;
        try {
            // Log.d(TAG, "CreateExoSurface: Starting with " + width + "x" + height);

            // 先销毁旧的表面和GL资源
            DestroySurface();
            DestroyGl();

            if (m_UseImageReader) {
                // 使用ImageReader模式
                // Log.d(TAG, "CreateExoSurface: Using ImageReader mode");
                mVideImageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2);
                mVideImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {
                        synchronized (ExoPlayerUnity.this) {
                            mNewFrameAvailable = true;
                            m_iNumberFramesAvailable += 1;
                            // Log.d(TAG, "onImageAvailable: New frame available, count=" +
                            // m_iNumberFramesAvailable);
                        }
                    }
                }, getHandler());
                // 设置mySurface变量，以便AttackSurface方法使用
                mySurface = mVideImageReader.getSurface();
                Log.d(TAG, "CreateExoSurface with ImageReader " + width + "x" + height + ", surface=" + mySurface);
            } else {
                // 使用SurfaceTexture模式
                // Log.d(TAG, "CreateExoSurface: Using SurfaceTexture mode");
                mTexture2DExt = new Texture2DExt(myContext, width, height, m_bCanUseGLBindVertexArray);
                int textureId = mTexture2DExt.getTextureID();
                // Log.d(TAG, "CreateExoSurface: Created Texture2DExt with textureId=" +
                // textureId);

                if (textureId > 0) {
                    surfaceTexture = new SurfaceTexture(textureId);
                    m_TextureHandle = textureId;
                    surfaceTexture.setDefaultBufferSize(width, height);
                    surfaceTexture.setOnFrameAvailableListener(this);
                    Log.d(TAG, "CreateExoSurface with SurfaceTexture " + width + "x" + height + "   textid:"
                            + m_TextureHandle + ", surfaceTexture=" + surfaceTexture);
                } else {
                    Log.e(TAG, "CreateExoSurface: Failed to create texture");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "CreateExoSurface Exception: " + e.getMessage());
            e.printStackTrace();
        }

        // Log.d(TAG, "CreateExoSurface: Completed");
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (this) {
            if (st == this.surfaceTexture) {
                // Log.d(TAG, "onFrameAvailable " + m_nPlayIndex);
                mNewFrameAvailable = true;
                m_iNumberFramesAvailable += 1;

                // Log.d(TAG, "onFrameAvailable----------");
            } else {
                Log.d(TAG, "onFrameAvailable !=");
            }
            return;
        }

    }

    public void Play() {
        if (videoPlayer == null)
            return;

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.Play();
            }
        });
    }

    public void Pause() {
        if (videoPlayer == null)
            return;

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.Pause();
            }
        });
    }

    public void Stop() {
        if (videoPlayer == null)
            return;

        s_AllPlayers.remove(Integer.valueOf(this.m_nPlayIndex));
        if ((s_AllPlayers != null) && (s_AllPlayers.isEmpty())) {
            s_AllPlayers.clear();
            s_AllPlayers = null;
        }

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.Stop();
                videoPlayer = null;
            }
        });
    }

    public void Destroy() {
        Stop();
        DestroySurface();
        DestroyGl();

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                // Release the cache when destroying the player
                if (downloadCache != null) {
                    try {
                        downloadCache.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing cache: " + e.getMessage());
                    }
                    downloadCache = null;
                }
            }
        });
    }

    private void DestroyGl() {
        if (mFBO != null)
            mFBO.destory();
        mFBO = null;
        if (mUnityTexture != null)
            mUnityTexture.destory();
        mUnityTexture = null;
        if (mTexture2DExt != null)
            mTexture2DExt.destory();
        mTexture2DExt = null;
        if (mTexture2DExtRGBA != null)
            mTexture2DExtRGBA.destory();
        mTexture2DExtRGBA = null;

        m_TextureHandle = 0;
    }

    private void DestroySurface() {
        try {
         //   Log.d(TAG, "DestroySurface: Starting");

            final Surface oldSurface = mySurface;
            final SurfaceTexture oldSurfaceTexture = surfaceTexture;
            final ImageReader oldImageReader = mVideImageReader;
            final VideoPlayer player = videoPlayer;

            // Set fields to null immediately
            mySurface = null;
            surfaceTexture = null;
            mVideImageReader = null;

            // Reset frame counters
            m_iNumberFramesAvailable = 0;
            mNewFrameAvailable = false;

            getHandler().post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 1. Detach from player first to prevent use of released surface
                        if (player != null) {
                            try {
                                player.AttackSurface(null);
                            } catch (Exception e) {
                                Log.e(TAG, "DestroySurface: Error detaching surface: " + e.getMessage());
                            }
                        }

                        // 2. Release Surface
                        if (oldSurface != null) {
                           // Log.d(TAG, "DestroySurface: Releasing mySurface");
                            try {
                                oldSurface.release();
                            } catch (Exception e) {
                                Log.e(TAG, "DestroySurface: Error releasing mySurface: " + e.getMessage());
                            }
                        }

                        // 3. Release ImageReader
                        if (oldImageReader != null) {
                           // Log.d(TAG, "DestroySurface: Closing mVideImageReader");
                            try {
                                oldImageReader.close();
                            } catch (Exception e) {
                                Log.e(TAG, "DestroySurface: Error closing mVideImageReader: " + e.getMessage());
                            }
                        }

                        // 4. Release SurfaceTexture
                        if (oldSurfaceTexture != null) {
                        //    Log.d(TAG, "DestroySurface: Releasing surfaceTexture");
                            try {
                                oldSurfaceTexture.setOnFrameAvailableListener(null);
                                oldSurfaceTexture.release();
                            } catch (Exception e) {
                                Log.e(TAG, "DestroySurface: Error releasing surfaceTexture: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "DestroySurface Runnable Exception: " + e.getMessage());
                    }
                }
            });

          //  Log.d(TAG, "DestroySurface: Cleanup scheduled");
        } catch (Exception ex) {
            Log.e(TAG, "DestroySurface Exception: " + ex.getMessage());
            ex.printStackTrace();
        }

    }

    ///// SETTERS //////
    public void SetLooping(final boolean looping) {
        if (videoPlayer == null)
            return;

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.SetLooping(looping);
            }
        });
    }

    public boolean IsLooping() {
        if (videoPlayer == null) {
            return false;
        }
        return videoPlayer.IsLooping();
    }

    public boolean CanPlay() {
        return videoPlayer != null;
    }

    public void SetPlaybackPosition(final double percent) {
        if (videoPlayer == null)
            return;

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.SetPlaybackPosition(percent);
            }
        });
    }

    public void SetPlaybackSpeed(final float speed) {
        if (videoPlayer == null)
            return;

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.SetPlaybackSpeed(speed);
            }
        });
    }

    public int GetWidth() {
        if (videoPlayer == null) {
            return -1;
        }

        return videoPlayer.GetWidth();
    }

    public int GetHeight() {
        if (videoPlayer == null) {
            return -1;
        }

        return videoPlayer.GetHeight();
    }

    public boolean GetIsPlaying() {
        if (videoPlayer == null) {
            return false;
        }

        return videoPlayer.GetIsPlaying();
    }

    public boolean IsPaused() {
        if (videoPlayer == null) {
            return false;
        }

        return videoPlayer.IsPaused();
    }

    public boolean IsFinished() {
        if (videoPlayer == null) {
            return false;
        }

        return videoPlayer.IsFinished();
    }

    public boolean IsBuffering() {
        if (videoPlayer == null) {
            return false;
        }

        return videoPlayer.IsBuffering();
    }

    public int GetCurrentPlaybackState() {
        if (videoPlayer == null) {
            return 0;
        }

        return videoPlayer.GetCurrentPlaybackState();
    }

    public long GetLength() {
        if (videoPlayer == null) {
            return 0;
        }

        return videoPlayer.GetLength();
    }

    public double GetPlaybackPosition() {
        if (videoPlayer == null) {
            return 0;
        }

        return videoPlayer.GetPlaybackPosition();
    }

    public int GetFrameCount() {
        if (videoPlayer == null || !GetIsPlaying())
            return 0;
        return m_nFrameCount;
    }

    public void SetVolume(float volume) {
        if (videoPlayer == null)
            return;

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                videoPlayer.SetVolume(volume);
            }
        });
    }

    public boolean GetUseImageReader() {
        return m_UseImageReader;
    }

    // 视频尺寸变化时的回调
    public void onVideoSizeChanged(final int width, final int height) {
        // Log.d(TAG, "onVideoSizeChanged: " + width + "x" + height);

        getHandler().post(new Runnable() {
            @Override
            public void run() {
                // 对于ImageReader模式，重新创建表面
                if (m_UseImageReader) {
                    CreateExoSurface(width, height);
                    // 重新绑定表面到播放器
                    AttackSurface();
                } else if (surfaceTexture != null) {
                    // 对于SurfaceTexture模式，更新缓冲区大小
                    surfaceTexture.setDefaultBufferSize(width, height);
                }
            }
        });
    }
}
