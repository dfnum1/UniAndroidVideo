package com.unity3d.exovideo;
import java.nio.ByteBuffer;
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

    // ========== JNI Native 方法（NEON 汇编级加速） ==========
    // YUV_420_888 → RGBA_8888 转换（ARM NEON SIMD）
    private static native void nativeYuvToRgba(ByteBuffer yBuf, ByteBuffer uBuf, ByteBuffer vBuf,
            byte[] outData, int width, int height,
            int yRowStride, int uvRowStride, int uvPixelStride);
    // RGB_565 → RGBA_8888 转换（ARM NEON SIMD）
    private static native void nativeRgb565ToRgba(ByteBuffer inBuf, byte[] outData,
            int width, int height, int rowStride);

    static {
        try {
            System.loadLibrary("RenderingPlugin");
            Log.d(TAG, "Loaded native RenderingPlugin library for NEON accelerated YUV→RGBA");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load RenderingPlugin library, will use Java fallback: " + e.getMessage());
        }
    }

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

    private byte[] mCachedBodyData = null;
    // 可复用的行缓存，避免每帧 GC 分配
    private byte[] m_RowCacheY = null;
    private byte[] m_RowCacheU = null;
    private byte[] m_RowCacheV = null;
    private short[] m_RowCacheShort = null;

    boolean m_UseImageVulkan = false;
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
    // EGL context 重建后，即使 Render 已经消费了 m_GlResourcesInvalid，
    // Resume 仍需要重建解码输出 surface；普通 Activity 返回不需要重建。
    private volatile boolean m_NeedsSurfaceRecreationAfterContextReset = false;

    VideoPlayer videoPlayer;
    int m_nPlayIndex;

    int m_iNumberFramesAvailable = 0;
    int m_nFrameCount = 0;

    boolean mNewFrameAvailable = false;
    int m_iOpenGLVersion = 1;
    boolean m_bCanUseGLBindVertexArray = false;

    // ========== YUV→RGB 查找表（BT.601 整数近似） ==========
    // 预计算 (channel-128)*coeff 的 clamp 结果，避免每像素做乘加和移位
    // 索引范围 0~255（对应 channel 的原始值 0~255）
    private static final int[] s_LutVr = new int[256]; // (v-128)*1436 → R 偏移
    private static final int[] s_LutUg = new int[256]; // (u-128)*352  → G 减
    private static final int[] s_LutVg = new int[256]; // (v-128)*731  → G 减
    private static final int[] s_LutUb = new int[256]; // (u-128)*1815 → B 偏移
    static {
        for (int i = 0; i < 256; i++) {
            int v = i - 128;
            int u = i - 128;
            s_LutVr[i] = ((v * 1436 + 512) >> 10);
            s_LutUg[i] = ((u * 352  + 512) >> 10);
            s_LutVg[i] = ((v * 731  + 512) >> 10);
            s_LutUb[i] = ((u * 1815 + 512) >> 10);
        }
    }

    // 带 clamp 的加法：result = base + offset，结果钳位到 [0, 255]
    private static int clamp255(int v) {
        return (v < 0) ? 0 : ((v > 255) ? 255 : v);
    }

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

    /**
     * 判断当前 MuMu 模拟器是否使用 Vulkan 作为 Unity 渲染后端。
     *
     * 判断策略（按优先级）：
     * 1. 读取系统属性 ro.kernel.qemu.gles：
     *    - 值为 "0" 或不存在 → 当前使用 Vulkan 渲染
     *    - 值为 "1"           → 当前使用 OpenGL ES 渲染
     * 2. 读取系统属性 ro.kernel.qemu.gl：
     *    - 包含 "vulkan"（不区分大小写）→ Vulkan 模式
     * 3. 读取系统属性 debug.renderengine（MuMu 特定）：
     *    - 包含 "vulkan"（不区分大小写）→ Vulkan 模式
     * 4. 通过 GLES20.glGetString 获取 GL_RENDERER/VENDOR 辅助判断：
     *    - MuMu 在 Vulkan 模式下，GLES 通过 Zink/MoltenVK 等方式转译，
     *      renderer 字符串通常包含 "Mesa" 或 "llvmpipe" 等特征。
     *
     * @return true 如果检测到 MuMu 使用 Vulkan 渲染后端
     */
    public static boolean isMuMuVulkan() {
        try {
            // 方法1: 检查 ro.kernel.qemu.gles —— MuMu 在 Vulkan 模式下该属性为 "0" 或不存在
            String glesProp = getSystemProperty("ro.kernel.qemu.gles");
            if (glesProp != null) {
                if ("0".equals(glesProp)) {
                    Log.d(TAG, "isMuMuVulkan: ro.kernel.qemu.gles=0 → Vulkan mode");
                    return true;
                } else if ("1".equals(glesProp)) {
                    Log.d(TAG, "isMuMuVulkan: ro.kernel.qemu.gles=1 → OpenGL ES mode");
                    return false;
                }
            }

            // 方法2: 检查 ro.kernel.qemu.gl 是否包含 vulkan
            String glProp = getSystemProperty("ro.kernel.qemu.gl");
            if (glProp != null && glProp.toLowerCase().contains("vulkan")) {
                Log.d(TAG, "isMuMuVulkan: ro.kernel.qemu.gl contains 'vulkan' → Vulkan mode");
                return true;
            }

            // 方法3: 检查 MuMu 特定属性 debug.renderengine
            String renderEngine = getSystemProperty("debug.renderengine");
            if (renderEngine != null && renderEngine.toLowerCase().contains("vulkan")) {
                Log.d(TAG, "isMuMuVulkan: debug.renderengine contains 'vulkan' → Vulkan mode");
                return true;
            }

            // 方法4: 通过 GL_RENDERER 辅助判断
            // 在 Unity 渲染线程上执行时，EGL context 已就绪，可以安全调用 GLES20
            try {
                String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
                String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
                if (renderer != null) {
                    String lower = renderer.toLowerCase();
                    // MuMu Vulkan 模式下 GLES 通常通过 Mesa/Zink 转译，renderer 含 "mesa" 或 "llvmpipe"
                    if (lower.contains("mesa") || lower.contains("llvmpipe")) {
                        Log.d(TAG, "isMuMuVulkan: GL_RENDERER=" + renderer + " → Vulkan mode (Mesa/Zink)");
                        return true;
                    }
                }
                if (renderer != null || vendor != null) {
                    Log.d(TAG, "isMuMuVulkan: GL_RENDERER=" + renderer + ", GL_VENDOR=" + vendor + " → OpenGL ES mode");
                }
            } catch (Exception e) {
                // GLES20 调用可能因 context 未就绪而失败，忽略
                Log.w(TAG, "isMuMuVulkan: GLES20 query failed: " + e.getMessage());
            }
        } catch (Exception ex) {
            Log.e(TAG, "isMuMuVulkan Exception: " + ex.getMessage());
        }

        // 默认：无法确定时返回 false（走 OpenGL ES 模式）
        return false;
    }

    /**
     * 通过反射读取 Android 系统属性（SystemProperties）。
     * 不需要任何特殊权限。
     */
    private static String getSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = systemProperties.getMethod("get", String.class);
            String value = (String) get.invoke(null, key);
            return value;
        } catch (Exception e) {
            return null;
        }
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
                theClass.m_UseImageVulkan = true;
            }

            theClass.m_UseImageVulkan = false;
            if (isEmulator()) {
                Log.d(TAG, "Detected Emulator, Force Enable ImageReader mode");
                theClass.m_UseImageReader = true;
                // MuMu 等模拟器上解码器直接输出 RGB_565(0x4)，若仍用 YUV_420_888(0x23)
                // 会在 Android 15 上触发格式校验崩溃/无画面。这里直接以 RGB_565 建 ImageReader。
                if (Build.VERSION.SDK_INT >= 15) {
                    theClass.m_ImageReaderFormat = PixelFormat.RGB_565;
                }

                // 判断 MuMu 是否使用 Vulkan 渲染后端
                // Vulkan 模式下无法使用 GLES 纹理操作，必须走 CPU 侧的 YUV→RGBA 转换路径
                theClass.m_UseImageVulkan = true;//isMuMuVulkan();
          //      Log.d(TAG, "Detected Emulator, m_UseImageVulkan=" + theClass.m_UseImageVulkan);
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
                    player.m_NeedsSurfaceRecreationAfterContextReset = true;
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

        // Activity 返回并不一定意味着 EGL context 已经丢失。若 context 仍然有效，
        // 保留 ImageReader/SurfaceTexture 和 Unity 外部纹理，只恢复播放，避免产生一帧黑屏。
        final boolean recreateSurface = m_NeedsSurfaceRecreationAfterContextReset;
        m_NeedsSurfaceRecreationAfterContextReset = false;
        if (!recreateSurface) {
            getHandler().post(new Runnable() {
                @Override
                public void run() {
                    if (videoPlayer != null) {
                        videoPlayer.Play();
                    }
                }
            });
            Log.d(TAG, "Resume preserved existing surface and GL resources");
            return;
        }

        Log.d(TAG, "Resume recreating surface after EGL context reset");
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

                        if(m_UseImageVulkan) RenderImageVulkan(image);
                        else RenderImageNoGL(image);
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

    void RenderImageVulkan(Image image) {
        try {
            if (mUnityTexture == null) {
                mUnityTexture = new Texture2D(myContext, GetWidth(), GetHeight(), m_bCanUseGLBindVertexArray);
            }
            if (this.unityMessage != null)
                this.unityMessage.OnVideoRenderBegin(this.m_nPlayIndex);
            Image.Plane[] planes = image.getPlanes();
            int width = image.getWidth();
            int height = image.getHeight();
            int format = image.getFormat();

            // Check and reallocate cache if necessary
            if (mCachedBodyData == null || mCachedBodyData.length != width * height * 4) {
                mCachedBodyData = new byte[width * height * 4];
            }
            byte[] data = mCachedBodyData;

            boolean converted = false;

            if (format == PixelFormat.RGB_565 && planes.length >= 1) {
                ByteBuffer buf = planes[0].getBuffer();
                int rowStride = planes[0].getRowStride();

                // 优先使用 JNI NEON 加速
                try {
                    nativeRgb565ToRgba(buf, data, width, height, rowStride);
                    converted = true;
                } catch (UnsatisfiedLinkError e) {
                    // JNI 未加载，走 Java 回退
                }
                if (!converted) {
                    // Java 回退：ShortBuffer 批量读取（复用行缓存）
                    java.nio.ShortBuffer shortBuf = buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    int rowShorts = rowStride >> 1;
                    int cacheSize = Math.max(rowShorts, width);
                    if (m_RowCacheShort == null || m_RowCacheShort.length < cacheSize) {
                        m_RowCacheShort = new short[cacheSize];
                    }
                    short[] sRow = m_RowCacheShort;
                    for (int i = 0; i < height; i++) {
                        int invertedRowIndex = (height - 1 - i) * width;
                        int destRowBase = invertedRowIndex << 2;
                        shortBuf.position(i * rowShorts);
                        shortBuf.get(sRow, 0, Math.min(rowShorts, shortBuf.remaining()));
                        for (int j = 0; j < width; j++) {
                            int pixel = sRow[j] & 0xFFFF;
                            int r = ((pixel >> 8) & 0xF8) | ((pixel >> 13) & 0x07);
                            int g = ((pixel >> 3) & 0xFC) | ((pixel >> 9) & 0x03);
                            int b = ((pixel << 3) & 0xF8) | ((pixel >> 2) & 0x07);
                            int base = destRowBase + (j << 2);
                            data[base]     = (byte) r;
                            data[base + 1] = (byte) g;
                            data[base + 2] = (byte) b;
                            data[base + 3] = (byte) 255;
                        }
                    }
                }
                buf.position(0);
                converted = true;
            } else if (planes.length >= 3) {
                ByteBuffer yBuffer = planes[0].getBuffer();
                ByteBuffer uBuffer = planes[1].getBuffer();
                ByteBuffer vBuffer = planes[2].getBuffer();
                int yRowStride = planes[0].getRowStride();
                int uvRowStride = planes[1].getRowStride();
                int uvPixelStride = planes[1].getPixelStride();

                // 优先使用 JNI NEON 加速
                try {
                    nativeYuvToRgba(yBuffer, uBuffer, vBuffer, data,
                            width, height, yRowStride, uvRowStride, uvPixelStride);
                    converted = true;
                } catch (UnsatisfiedLinkError e) {
                    // JNI 未加载，走 Java 回退
                }
                if (!converted) {
                    // Java 回退：LUT + 循环展开（单线程，复用行缓存）
                    int uvCacheSize = (width >> 1) + 1;
                    if (m_RowCacheY == null || m_RowCacheY.length < width) {
                        m_RowCacheY = new byte[width];
                        m_RowCacheU = new byte[uvCacheSize];
                        m_RowCacheV = new byte[uvCacheSize];
                    }
                    byte[] yRow = m_RowCacheY;
                    byte[] uRow = m_RowCacheU;
                    byte[] vRow = m_RowCacheV;
                    final int[] lutVr = s_LutVr, lutUg = s_LutUg, lutVg = s_LutVg, lutUb = s_LutUb;

                    for (int i = 0; i < height; i++) {
                        int invertedRowIndex = (height - 1 - i) * width;
                        int uvRowBase = (i >> 1) * uvRowStride;
                        int yRowBase = i * yRowStride;
                        int destRowBase = invertedRowIndex << 2;

                        yBuffer.position(yRowBase);
                        yBuffer.get(yRow, 0, width);
                        if ((i & 1) == 0) {
                            int uvHalfWidth = (width >> 1) + 1;
                            uBuffer.position(uvRowBase);
                            uBuffer.get(uRow, 0, Math.min(uvHalfWidth, uBuffer.remaining()));
                            vBuffer.position(uvRowBase);
                            vBuffer.get(vRow, 0, Math.min(uvHalfWidth, vBuffer.remaining()));
                        }

                        int j = 0, w4 = width & ~3;
                        for (; j < w4; j += 4) {
                            int uvIdx0 = (j >> 1) * uvPixelStride;
                            int uvIdx2 = ((j + 2) >> 1) * uvPixelStride;
                            int u0 = uRow[uvIdx0] & 0xFF, v0 = vRow[uvIdx0] & 0xFF;
                            int u2 = uRow[uvIdx2] & 0xFF, v2 = vRow[uvIdx2] & 0xFF;
                            int vr0 = lutVr[v0], ug0 = lutUg[u0], vg0 = lutVg[v0], ub0 = lutUb[u0];
                            int vr2 = lutVr[v2], ug2 = lutUg[u2], vg2 = lutVg[v2], ub2 = lutUb[u2];

                            int base = destRowBase + (j << 2);
                            int y0 = yRow[j] & 0xFF, y1 = yRow[j + 1] & 0xFF;
                            int y2 = yRow[j + 2] & 0xFF, y3 = yRow[j + 3] & 0xFF;

                            data[base]     = (byte) clamp255(y0 + vr0);
                            data[base + 1] = (byte) clamp255(y0 - ug0 - vg0);
                            data[base + 2] = (byte) clamp255(y0 + ub0);
                            data[base + 3] = (byte) 255;
                            int base1 = base + 4;
                            data[base1]     = (byte) clamp255(y1 + vr0);
                            data[base1 + 1] = (byte) clamp255(y1 - ug0 - vg0);
                            data[base1 + 2] = (byte) clamp255(y1 + ub0);
                            data[base1 + 3] = (byte) 255;
                            int base2 = base + 8;
                            data[base2]     = (byte) clamp255(y2 + vr2);
                            data[base2 + 1] = (byte) clamp255(y2 - ug2 - vg2);
                            data[base2 + 2] = (byte) clamp255(y2 + ub2);
                            data[base2 + 3] = (byte) 255;
                            int base3 = base + 12;
                            data[base3]     = (byte) clamp255(y3 + vr2);
                            data[base3 + 1] = (byte) clamp255(y3 - ug2 - vg2);
                            data[base3 + 2] = (byte) clamp255(y3 + ub2);
                            data[base3 + 3] = (byte) 255;
                        }
                        for (; j < width; j++) {
                            int y = yRow[j] & 0xFF;
                            int uvIdx = (j >> 1) * uvPixelStride;
                            int u = uRow[uvIdx] & 0xFF, v = vRow[uvIdx] & 0xFF;
                            int base = destRowBase + (j << 2);
                            data[base]     = (byte) clamp255(y + lutVr[v]);
                            data[base + 1] = (byte) clamp255(y - lutUg[u] - lutVg[v]);
                            data[base + 2] = (byte) clamp255(y + lutUb[u]);
                            data[base + 3] = (byte) 255;
                        }
                    }
                    yBuffer.position(0);
                    uBuffer.position(0);
                    vBuffer.position(0);
                }
                converted = true;
            }

            if (!converted) {
                Log.e(TAG, "RenderImageVulkan: unsupported format 0x" + Integer.toHexString(format)
                        + " with " + planes.length + " planes");
                return;
            }

            // 更新纹理
            mUnityTexture.updateTexture(width, height, data);

            if (this.unityMessage != null)
                this.unityMessage.OnVideoRenderEnd(this.m_nPlayIndex);
        } catch (Exception e) {
            Log.e(TAG, "RenderImageNoGL Exception: " + e.getMessage());
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
                    player.m_NeedsSurfaceRecreationAfterContextReset = true;
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
