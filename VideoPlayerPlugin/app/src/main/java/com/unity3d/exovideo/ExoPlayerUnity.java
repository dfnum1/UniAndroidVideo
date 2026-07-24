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
import android.graphics.PixelFormat;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
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
    // ImageReader 当前配置的格式。默认 YUV_420_888；若在 Android 15 上出现格式不匹配，
    // 会自动回退到 RGBA_8888（兼容性更好，多数模拟器解码器可输出/转换为该格式）。
    int m_ImageReaderFormat = ImageFormat.YUV_420_888;

    float[] mSurfaceTextureMat = new float[16];

    int m_TextureHandle = 0;
    // GL 资源重建代数，每次 DestroyGl 自增。
    // GL 会复用已删除的纹理 id，Unity 侧仅凭 id 数值无法发现纹理已被重建，
    // 需要靠 revision 变化强制重新 CreateExternalTexture，避免一直黑屏。
    int m_TextureRevision = 0;

    // GL 对象只能在 Unity 渲染线程上创建/销毁。ImageReader 的尺寸变化和
    // Surface 生命周期回调可能在 Android 主线程执行，因此这里只设置失效标记，
    // 由下一次 Render 在渲染线程中真正销毁旧的 FBO/纹理。
    private volatile boolean m_GlResourcesInvalid = false;

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
                // MuMu 等模拟器上解码器直接输出 RGB_565(0x4)，若仍用 YUV_420_888(0x23)
                // 会在 Android 15 上触发格式校验崩溃/无画面。这里直接以 RGB_565 建 ImageReader。
                if (Build.VERSION.SDK_INT >= 15) {
                    theClass.m_ImageReaderFormat = PixelFormat.RGB_565;
                }
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

    // Unity EGL/GL context 重建后，旧的 Java GL 名称不能继续使用。
    // 该回调只设置标记，不在 Android 主线程执行任何 GL 操作。
    public static void OnGraphicsDeviceInitialize() {
        if (s_AllPlayers == null)
            return;
        try {
            for (ExoPlayerUnity player : s_AllPlayers.values()) {
                if (player != null) {
                    player.m_GlResourcesInvalid = true;
                    player.m_TextureHandle = 0;
                    player.m_TextureRevision++;
                    player.mNewFrameAvailable = false;
                    player.m_iNumberFramesAvailable = 0;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "OnGraphicsDeviceInitialize Exception: " + e.getMessage());
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
        // 只返回最终渲染目标纹理（GL_TEXTURE_2D）。
        // 不能回退返回 m_TextureHandle：它是 SurfaceTexture 使用的
        // GL_TEXTURE_EXTERNAL_OES 纹理，Unity 会按 GL_TEXTURE_2D 绑定它，
        // 导致 glBindTexture GL_INVALID_OPERATION(1282) 黑屏。
        if (mUnityTexture != null)
            return mUnityTexture.getTextureID();
        return 0;
    }

    public int GetTextureRevision() {
        return m_TextureRevision;
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

        // 在投递到主线程之前捕获当前 Surface，避免执行时读到下一代 Surface。
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
        final Surface targetSurface;
        synchronized (this) {
            targetSurface = mySurface;
        }
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                // videoPlayer 可能在 post 之后、run 执行前被置空，这里需要再次判空
                if (videoPlayer != null) {
                    videoPlayer.AttackSurface(targetSurface);
                }
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
                Surface surfaceToAttach = null;
                if (!m_UseImageReader && surfaceTexture != null) {
                    if (mySurface != null)
                        mySurface.release();
                    mySurface = new Surface(surfaceTexture);
                    surfaceToAttach = mySurface;
                } else if (m_UseImageReader) {
                    surfaceToAttach = mySurface;
                }
                // 绑定表面到播放器
                if (surfaceToAttach != null) {
                    videoPlayer.AttackSurface(surfaceToAttach);
                }
            }
        });
        Log.d(TAG, "Resume initiated " + GetWidth() + "x" + GetHeight() + "   textid:" + m_TextureHandle);
    }

    public void Render() {
        synchronized (this) {
            // 必须在 Unity 渲染线程执行，不能从 Android 主线程的 Surface 回调中执行。
            if (m_GlResourcesInvalid) {
                DestroyGl();
                m_GlResourcesInvalid = false;
            }

            // Log.d(TAG, "Render: Starting");

            // 确保使用有效的尺寸
            int width = GetWidth();
            int height = GetHeight();
            if (width <= 0 || height <= 0)
                return;
            if (m_UseImageReader) {
                if (mVideImageReader == null) {
                    // Log.d(TAG, "Render: mVideImageReader is null, recreating surface");
                    RecreateSurfaceAndAttach(width, height);
                    return;
                }
                // Log.d(TAG, "Render: Using ImageReader mode");
                UpdateImageReaderFrame();
            } else {
                if (surfaceTexture == null) {
                    Log.d(TAG, "Render: surfaceTexture is null, recreating surface");
                    RecreateSurfaceAndAttach(width, height);
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
                        m_nFrameCount = 2;

                    // Log.d(TAG, "UpdateSurfaceTexture: Completed, frame count=" + m_nFrameCount);
                } catch (Exception e) {
                    Log.e(TAG, "UpdateSurfaceTexture Exception: " + e.getMessage());
                    e.printStackTrace();

                    // 如果发生OpenGL错误，尝试重新创建表面
                    if (e.getMessage().contains("GL_INVALID") || e.getMessage().contains("OpenGL")) {
                        Log.d(TAG, "UpdateSurfaceTexture: OpenGL error, recreating surface");
                        int width = GetWidth();
                        int height = GetHeight();
                        RecreateSurfaceAndAttach(width, height);
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
                Image image = null;
                try {
                    image = mVideImageReader.acquireLatestImage();
                } catch (UnsupportedOperationException e) {
                    Log.e(TAG, "UpdateImageReaderFrame: format mismatch, recreating ImageReader: " + e.getMessage());
                    RecreateImageReaderWithFormat();
                    return;
                } catch (IllegalStateException e) {
                    // 缓冲区已耗尽等临时状态，跳过本帧即可。
                    Log.e(TAG, "UpdateImageReaderFrame: acquire failed: " + e.getMessage());
                    return;
                }

                if (image != null) {
                    try {
                        // 处理Image数据并渲染
                        // RenderImageReaderFrame(image);
                        RenderImageNoGL(image);
                        m_nFrameCount++;
                        if (m_nFrameCount >= 1000000)
                            m_nFrameCount = 2;
                    } finally {
                        image.close();
                    }
                } else {
                    Log.d(TAG, "UpdateImageReaderFrame: No new image available");
                }
            }
        }
    }

    // 出现格式不匹配时，依次尝试生产者可能使用的格式并重建表面。
    // 候选顺序：RGB_565(模拟器解码器常见) -> YUV_420_888(真机常见) -> RGBA_8888(兜底)。

    private int m_FormatFallbackAttempt = 0;
    private static final int[] IMAGE_READER_FORMAT_CANDIDATES = {
            PixelFormat.RGB_565, ImageFormat.YUV_420_888, PixelFormat.RGBA_8888
    };
    private void RecreateImageReaderWithFormat() {
        // 找到一个与当前不同、且尚未用尽的候选格式
        int next = -1;
        while (m_FormatFallbackAttempt < IMAGE_READER_FORMAT_CANDIDATES.length) {
            int candidate = IMAGE_READER_FORMAT_CANDIDATES[m_FormatFallbackAttempt++];
            if (candidate != m_ImageReaderFormat) {
                next = candidate;
                break;
            }
        }
        if (next < 0) {
            // 所有候选格式都试过仍失败，直接跳过本帧，避免死循环重建。
            return;
        }
        m_ImageReaderFormat = next;
        int width = GetWidth();
        int height = GetHeight();
        if (width <= 0 || height <= 0)
            return;
        CreateExoSurface(width, height);
        AttackSurface();
        Log.d(TAG, "RecreateImageReaderWithFormat: switched to format 0x" + Integer.toHexString(next));
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
            int width = image.getWidth();
            int height = image.getHeight();
            int format = image.getFormat();
            Image.Plane[] planes = image.getPlanes();
            if (planes.length < 1)
                return;

            if (mUnityTexture == null) {
                mUnityTexture = new Texture2D(myContext, GetWidth(), GetHeight(), m_bCanUseGLBindVertexArray);
                mFBO = new FBO(mUnityTexture);
            }

            // 确保YUV/RGB纹理存在且尺寸正确
            if (mTexture2DExtRGBA == null || mTexture2DExtRGBA.getWidth() != width
                    || mTexture2DExtRGBA.getHeight() != height) {
                if (mTexture2DExtRGBA != null)
                    mTexture2DExtRGBA.destory();
                mTexture2DExtRGBA = new Texture2DExtYUV(myContext, width, height, m_bCanUseGLBindVertexArray);
            }

            if (this.unityMessage != null)
                this.unityMessage.OnVideoRenderBegin(this.m_nPlayIndex);

            boolean uploaded = false;
            if (format == ImageFormat.YUV_420_888) {
                if (planes.length >= 3) {
                    // 直接上传 Y/U/V 三个平面，YUV->RGB 转换交给 GPU（片元着色器）。
                    mTexture2DExtRGBA.updateYUV(width, height,
                            planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer(),
                            planes[0].getRowStride(), planes[1].getRowStride(), planes[1].getPixelStride());
                    uploaded = true;
                }
            } else if (format == PixelFormat.RGB_565) {
                mTexture2DExtRGBA.updateRGB565(width, height,
                        planes[0].getBuffer(), planes[0].getRowStride());
                uploaded = true;
            } else if (format == PixelFormat.RGBA_8888 || format == PixelFormat.RGBX_8888) {
                mTexture2DExtRGBA.updateRGBA8888(width, height,
                        planes[0].getBuffer(), planes[0].getRowStride());
                uploaded = true;
            } else {
                Log.e(TAG, "RenderImageNoGL: unsupported image format 0x" + Integer.toHexString(format));
            }

            if (uploaded) {
                // 通过 FBO 把结果渲染到 Unity 使用的纹理上
                mFBO.FBOBegin();
                GLES20.glViewport(0, 0, width, height);
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                Matrix.setIdentityM(mSurfaceTextureMat, 0);
                if (format == ImageFormat.YUV_420_888) {
                    mTexture2DExtRGBA.draw(mSurfaceTextureMat, false);
                } else {
                    mTexture2DExtRGBA.drawRGB();
                }
                mFBO.FBOEnd();
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

                    // 直接上传 Y/U/V 三个平面，YUV->RGB 转换交给 GPU（片元着色器），
                    // 省去 CPU 逐像素的浮点转换。
                    mTexture2DExtRGBA.updateYUV(width, height,
                            planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer(),
                            planes[0].getRowStride(), planes[1].getRowStride(), planes[1].getPixelStride());

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

            // Surface 可以在 Android 主线程重建，但 GL 资源不能在这里销毁。
            // DestroyGl 延迟到下一次 Unity Render（渲染线程）执行。
            DestroySurface();
            if (m_UseImageReader) {
                m_GlResourcesInvalid = true;
            } else {
                // SurfaceTexture 模式的 CreateExoSurface 只从 Unity 渲染路径调用，
                // 保持原有的同步 GL 资源重建行为。
                DestroyGl();
            }

            if (m_UseImageReader) {
                // 使用ImageReader模式
                // Log.d(TAG, "CreateExoSurface: Using ImageReader mode");
                final ImageReader newImageReader = ImageReader.newInstance(width, height, m_ImageReaderFormat, 2);
                newImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
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
                synchronized (this) {
                    mVideImageReader = newImageReader;
                    // 设置mySurface变量，以便AttackSurface方法使用
                    mySurface = newImageReader.getSurface();
                }
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

    // 重建解码输出表面并重新绑定到播放器（渲染线程调用）。
    // 旧表面的解绑/释放由 DestroySurface 投递到主线程 Handler 串行执行，
    // 先于这里投递的重新绑定完成，因此顺序是安全的。
    private void RecreateSurfaceAndAttach(final int width, final int height) {
        CreateExoSurface(width, height);
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (videoPlayer == null)
                    return;
                Surface surfaceToAttach = null;
                if (!m_UseImageReader && surfaceTexture != null) {
                    if (mySurface != null)
                        mySurface.release();
                    mySurface = new Surface(surfaceTexture);
                    surfaceToAttach = mySurface;
                } else if (m_UseImageReader) {
                    surfaceToAttach = mySurface;
                }
                if (surfaceToAttach != null) {
                    videoPlayer.AttackSurface(surfaceToAttach);
                }
            }
        });
    }

    // Unity 图形设备销毁（EGL context 失效）时的回调，由 native 在渲染线程调用。
    // 旧 context 中的纹理/FBO 句柄已随 context 失效，这里清空 GL 与解码表面资源，
    // 之后 Render 会在新 context 中自动重建；m_TextureRevision 自增会促使
    // Unity 侧重新 CreateExternalTexture，避免旧纹理 id 失效导致的黑屏。
    public static void OnGraphicsDeviceShutdown() {
        if (s_AllPlayers == null)
            return;
        try {
            for (ExoPlayerUnity player : s_AllPlayers.values()) {
                if (player != null) {
                    player.DestroySurface();
                    player.DestroyGl();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "OnGraphicsDeviceShutdown Exception: " + e.getMessage());
        }
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
        m_TextureRevision++;
        m_GlResourcesInvalid = false;
    }

    // 在调用方已经拿到锁时使用；普通调用仍由 Render 的 synchronized 保护。
    private void CaptureCurrentSurfaceAndAttach() {
        final Surface targetSurface;
        synchronized (this) {
            targetSurface = mySurface;
        }
        getHandler().post(new Runnable() {
            @Override
            public void run() {
                if (videoPlayer != null) {
                    // 捕获调用时的 Surface，避免 Handler 执行时读取到下一代 Surface。
                    videoPlayer.AttackSurface(targetSurface);
                }
            }
        });
    }

    private void DestroySurface() {
        try {
         //   Log.d(TAG, "DestroySurface: Starting");

            final Surface oldSurface;
            final SurfaceTexture oldSurfaceTexture;
            final ImageReader oldImageReader;
            final VideoPlayer player = videoPlayer;

            synchronized (this) {
                oldSurface = mySurface;
                oldSurfaceTexture = surfaceTexture;
                oldImageReader = mVideImageReader;

                // Set fields to null immediately
                mySurface = null;
                surfaceTexture = null;
                mVideImageReader = null;

                // Reset frame counters
                m_iNumberFramesAvailable = 0;
                mNewFrameAvailable = false;
            }

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
                    CaptureCurrentSurfaceAndAttach();
                } else if (surfaceTexture != null) {
                    // 对于SurfaceTexture模式，更新缓冲区大小
                    surfaceTexture.setDefaultBufferSize(width, height);
                }
            }
        });
    }
}
