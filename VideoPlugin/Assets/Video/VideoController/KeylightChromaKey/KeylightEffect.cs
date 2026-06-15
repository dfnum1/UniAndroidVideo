/********************************************************************
生成日期:	04:24:2026
类    名: 	KeylightEffect
作    者:	HappLI
描    述:	支持: 色度抠像、遮罩处理、溢色抑制、多种视图模式
            参考 AE Keylight 插件的 Unity 绿幕/蓝幕抠像效果组件
*********************************************************************/
using UnityEngine;

namespace GameApp.UIComponent
{
    /// <summary>
    /// 视图模式 - 对应 AE Keylight 的 View 选项
    /// </summary>
    public enum ViewMode
    {
        /// <summary>最终合成结果（带Alpha通道）</summary>
        FinalResult = 0,
        /// <summary>仅显示遮罩（黑白图）</summary>
        ScreenMatte = 1,
        /// <summary>中间结果（带溢色抑制，不带Alpha）</summary>
        IntermediateResult = 2,
        /// <summary>原始源画面</summary>
        Source = 3
    }
    public enum DownSampleMode
    {
        None = 0,
        x2 = 1,
        x4 = 2,
        x8 = 3
    }
    [System.Serializable]
    public class KeylightEffect
    {
        static int _MatteTexID = Shader.PropertyToID("_MatteTex");
        static int _ScreenColorID = Shader.PropertyToID("_ScreenColor");
        static int _ScreenGainID = Shader.PropertyToID("_ScreenGain");
        static int _ScreenBalanceID = Shader.PropertyToID("_ScreenBalance");
        static int _ClipBlackID = Shader.PropertyToID("_ClipBlack");
        static int _ClipWhiteID = Shader.PropertyToID("_ClipWhite");
        static int _ShrinkGrowID = Shader.PropertyToID("_ShrinkGrow");
        static int _DespotBlackID = Shader.PropertyToID("_DespotBlack");
        static int _DespotWhiteID = Shader.PropertyToID("_DespotWhite");
        static int _SpillSuppressionID = Shader.PropertyToID("_SpillSuppression");
        static int _SpillToleranceID = Shader.PropertyToID("_SpillTolerance");
        static int _SpillDesaturateID = Shader.PropertyToID("_SpillDesaturate");
        static int _SpillRangeID = Shader.PropertyToID("_SpillRange");
        static int _SpillColorCorrectionID = Shader.PropertyToID("_SpillColorCorrection");
        static int _LumaCorrectionID = Shader.PropertyToID("_LumaCorrection");
        static int _ViewModeID = Shader.PropertyToID("_ViewMode");

        static int _BlurSizeID = Shader.PropertyToID("_BlurSize");
        static int _BlurDirID = Shader.PropertyToID("_BlurDir");

        // =================================================================
        // 屏幕设置 (Screen Settings)
        // =================================================================
        [Header("Screen Settings 屏幕设置")]

        [Tooltip("要抠除的屏幕颜色（用吸管工具从绿幕上取色）")]
        public Color screenColor = new Color(0f, 1f, 0f, 1f);

        [Tooltip("抠像增益 - 越高抠像越激进 (默认100)")]
        [Range(0f, 200f)]
        public float screenGain = 100f;

        [Tooltip("非关键通道平衡 - 调节两个非关键通道的权重 (默认50)")]
        [Range(0f, 100f)]
        public float screenBalance = 50f;

        [Tooltip("预模糊 - 对源画面模糊后再生成遮罩，可减少噪点 (默认0)")]
        [Range(0f, 20f)]
        public float screenPreBlur = 0f;

        [Tooltip("Down Sample")]
        public DownSampleMode drawSampleMode = DownSampleMode.None;


        // =================================================================
        // 遮罩处理 (Screen Matte)
        // =================================================================
        [Header("Screen Matte 遮罩处理")]

        [Tooltip("裁切黑 - 低于此值的遮罩区域变为全透明 (默认10)")]
        [Range(0f, 100f)]
        public float clipBlack = 10f;

        [Tooltip("裁切白 - 高于此值的遮罩区域变为全不透明 (默认90)")]
        [Range(0f, 100f)]
        public float clipWhite = 90f;

        [Tooltip("收缩/扩展 - 负值收缩遮罩边缘，正值扩展 (默认0)")]
        [Range(-10f, 10f)]
        public float screenShrinkGrow = 0f;

        [Tooltip("遮罩柔化 - 对最终遮罩进行模糊 (默认0)")]
        [Range(0f, 20f)]
        public float screenSoftness = 0f;

        [Tooltip("去黑斑 - 移除遮罩中孤立的黑色斑点 (默认0)")]
        [Range(0f, 100f)]
        public float screenDespotBlack = 0f;

        [Tooltip("去白斑 - 移除遮罩中孤立的白色斑点 (默认0)")]
        [Range(0f, 100f)]
        public float screenDespotWhite = 0f;

        // =================================================================
        // 溢色抑制 (Spill Suppression)
        // =================================================================
        [Header("Spill Suppression 溢色抑制")]

        [Tooltip("溢色抑制强度 - 移除前景上的屏幕色反光 (默认100)")]
        [Range(0f, 100f)]
        public float spillSuppression = 100f;

        [Tooltip("溢色容差 - 控制判定为溢色的阈值 (默认50)")]
        [Range(0f, 100f)]
        public float spillTolerance = 50f;

        [Tooltip("溢色区域去饱和 - 降低溢色区域的色彩饱和度 (默认50)")]
        [Range(0f, 100f)]
        public float spillDesaturate = 50f;

        [Tooltip("溢色影响范围 (默认50)")]
        [Range(0f, 100f)]
        public float spillRange = 50f;

        [Tooltip("溢色颜色校正 - 向互补色方向偏移溢色区域 (默认50)")]
        [Range(0f, 100f)]
        public float spillColorCorrection = 50f;

        [Tooltip("亮度校正 - 补偿去溢色导致的亮度变化 (默认0)")]
        [Range(-100f, 100f)]
        public float lumaCorrection = 0f;

        // =================================================================
        // 视图模式 (View)
        // =================================================================
        [Header("View 视图设置")]

        [Tooltip("当前视图模式")]
        public ViewMode viewMode = ViewMode.FinalResult;

        // =================================================================
        // 内部变量
        // =================================================================
        RenderTexture               m_outputTexture;
        private Material            m_material;
        private RenderTexture       m_autoOutputRT;
        private bool                m_initialized = false;
        static private Shader       ms_shader;

        // Shader Pass 索引
        private const int PASS_BLUR = 0;
        private const int PASS_GENERATE_MATTE = 1;
        private const int PASS_MATTE_CLIP = 2;
        private const int PASS_SHRINK_GROW = 3;
        private const int PASS_DESPOT = 4;
        private const int PASS_COMPOSITE = 5;
        //-----------------------------------------------------------
        public void Process(Texture source, UnityEngine.UI.RawImage rawImage)
        {
            if (source == null) return;

            RenderTexture dest = GetOutputTexture(source.width, source.height);
            if (dest == null) return;

            Process(source, dest);

            if (rawImage != null)
            {
                rawImage.texture = dest;
            }
        }
        //-----------------------------------------------------------
        public void Initialize()
        {
            if (m_initialized) return;

            if(ms_shader == null)
                ms_shader = Shader.Find("Hidden/KeylightChromaKey");
            if (ms_shader == null)
            {
                Debug.LogError("[KeylightEffect] 找不到 Shader: Hidden/KeylightChromaKey");
                return;
            }

            m_material = new Material(ms_shader);
            m_material.hideFlags = HideFlags.HideAndDontSave;

            m_initialized = true;
        }
        //-----------------------------------------------------------
        public void Cleanup()
        {
            if (m_material != null)
            {
                if (Application.isPlaying)
                    Object.Destroy(m_material);
                else
                    Object.DestroyImmediate(m_material);
                m_material = null;
            }

            if (m_autoOutputRT != null)
            {
                m_autoOutputRT.Release();
                if (Application.isPlaying)
                    Object.Destroy(m_autoOutputRT);
                else
                    Object.DestroyImmediate(m_autoOutputRT);
                m_autoOutputRT = null;
            }

            m_initialized = false;
        }
        //-----------------------------------------------------------
        private RenderTexture GetOutputTexture(int width, int height)
        {
            // 优先使用手动指定的输出纹理
            if (m_outputTexture != null)
                return m_outputTexture;

            // 自动创建输出 RenderTexture
            if (m_autoOutputRT == null || m_autoOutputRT.width != width || m_autoOutputRT.height != height)
            {
                if (m_autoOutputRT != null)
                {
                    m_autoOutputRT.Release();
                    if (Application.isPlaying)
                        Object.Destroy(m_autoOutputRT);
                    else
                        Object.DestroyImmediate(m_autoOutputRT);
                }

                m_autoOutputRT = new RenderTexture(width, height, 0, RenderTextureFormat.ARGB32);
                m_autoOutputRT.name = "KeylightOutput";
                m_autoOutputRT.filterMode = FilterMode.Bilinear;
                m_autoOutputRT.Create();
            }

            return m_autoOutputRT;
        }
        //-----------------------------------------------------------
        public RenderTexture GetCurrentOutput()
        {
            if (m_outputTexture != null) return m_outputTexture;
            return m_autoOutputRT;
        }
        //-----------------------------------------------------------
        public void Process(Texture source, RenderTexture destination)
        {
            if (!m_initialized) Initialize();
            if (m_material == null || source == null || destination == null) return;

            int width = source.width;
            int height = source.height;
            switch(drawSampleMode)
            {
                case DownSampleMode.x2:
                    width /= 2;
                    height /= 2;
                    break;
                case DownSampleMode.x4:
                    width /= 4;
                    height /= 4;
                    break;
                case DownSampleMode.x8:
                    width /= 8;
                    height /= 8;
                    break;
            }

            // 设置公共 Shader 参数
            SetShaderParams();

            // ---- Step 1: 预模糊 (Screen Pre-blur) ----
            RenderTexture preBlurred = null;
            if (screenPreBlur > 0.01f)
            {
                preBlurred = DoBlur(source, width, height, screenPreBlur);
            }

            Texture matteSource = preBlurred != null ? (Texture)preBlurred : source;

            // ---- Step 2: 生成遮罩 (Generate Matte) ----
            RenderTexture matteRT = RenderTexture.GetTemporary(width, height, 0, RenderTextureFormat.ARGB32);
            Graphics.Blit(matteSource, matteRT, m_material, PASS_GENERATE_MATTE);

            // 释放预模糊临时纹理
            if (preBlurred != null)
                RenderTexture.ReleaseTemporary(preBlurred);

            // ---- Step 3: 遮罩裁切 (Clip Black / Clip White) ----
            RenderTexture clippedRT = RenderTexture.GetTemporary(width, height, 0, RenderTextureFormat.ARGB32);
            Graphics.Blit(matteRT, clippedRT, m_material, PASS_MATTE_CLIP);
            RenderTexture.ReleaseTemporary(matteRT);
            matteRT = clippedRT;

            // ---- Step 4: 遮罩柔化 (Screen Softness) ----
            if (screenSoftness > 0.01f)
            {
                RenderTexture softened = DoBlur(matteRT, width, height, screenSoftness);
                RenderTexture.ReleaseTemporary(matteRT);
                matteRT = softened;
            }

            // ---- Step 5: 收缩/扩展 (Shrink/Grow) ----
            if (Mathf.Abs(screenShrinkGrow) > 0.01f)
            {
                RenderTexture shrunkRT = RenderTexture.GetTemporary(width, height, 0, RenderTextureFormat.ARGB32);
                Graphics.Blit(matteRT, shrunkRT, m_material, PASS_SHRINK_GROW);
                RenderTexture.ReleaseTemporary(matteRT);
                matteRT = shrunkRT;
            }

            // ---- Step 6: 去斑点 (Despot) ----
            if (screenDespotBlack > 0.01f || screenDespotWhite > 0.01f)
            {
                RenderTexture despottedRT = RenderTexture.GetTemporary(width, height, 0, RenderTextureFormat.ARGB32);
                Graphics.Blit(matteRT, despottedRT, m_material, PASS_DESPOT);
                RenderTexture.ReleaseTemporary(matteRT);
                matteRT = despottedRT;
            }

            // ---- Step 7: 最终合成 (Composite) ----
            m_material.SetTexture(_MatteTexID, matteRT);
            Graphics.Blit(source, destination, m_material, PASS_COMPOSITE);

            // 释放遮罩临时纹理
            RenderTexture.ReleaseTemporary(matteRT);
        }
        //-----------------------------------------------------------
        private void SetShaderParams()
        {
            // Screen Settings
            m_material.SetColor(_ScreenColorID, screenColor);
            m_material.SetFloat(_ScreenGainID, screenGain / 100f);
            m_material.SetFloat(_ScreenBalanceID, screenBalance / 100f);

            // Matte Processing
            m_material.SetFloat(_ClipBlackID, clipBlack / 100f);
            m_material.SetFloat(_ClipWhiteID, clipWhite / 100f);
            m_material.SetFloat(_ShrinkGrowID, screenShrinkGrow);
            m_material.SetFloat(_DespotBlackID, screenDespotBlack / 100f);
            m_material.SetFloat(_DespotWhiteID, screenDespotWhite / 100f);

            // Spill Suppression
            m_material.SetFloat(_SpillSuppressionID, spillSuppression / 100f);
            m_material.SetFloat(_SpillToleranceID, spillTolerance / 100f);
            m_material.SetFloat(_SpillDesaturateID, spillDesaturate / 100f);
            m_material.SetFloat(_SpillRangeID, spillRange / 100f);
            m_material.SetFloat(_SpillColorCorrectionID, spillColorCorrection / 100f);
            m_material.SetFloat(_LumaCorrectionID, lumaCorrection / 100f);

            // View Mode
            m_material.SetInt(_ViewModeID, (int)viewMode);
        }
        //-----------------------------------------------------------
        private RenderTexture DoBlur(Texture source, int width, int height, float blurSize)
        {
            // 计算迭代次数（每次迭代模糊半径约为3像素，多次迭代实现更大模糊）
            int iterations = Mathf.Max(1, Mathf.CeilToInt(blurSize / 3f));
            float perPassSize = blurSize / iterations;

            RenderTexture current = RenderTexture.GetTemporary(width, height, 0, RenderTextureFormat.ARGB32);
            RenderTexture temp = RenderTexture.GetTemporary(width, height, 0, RenderTextureFormat.ARGB32);

            // 首次迭代从 source 开始
            Texture blurSource = source;

            for (int i = 0; i < iterations; i++)
            {
                m_material.SetFloat(_BlurSizeID, perPassSize);

                // 水平模糊
                m_material.SetVector(_BlurDirID, new Vector4(1, 0, 0, 0));
                Graphics.Blit(blurSource, temp, m_material, PASS_BLUR);

                // 垂直模糊
                m_material.SetVector(_BlurDirID, new Vector4(0, 1, 0, 0));
                Graphics.Blit(temp, current, m_material, PASS_BLUR);

                // 后续迭代从 current 开始
                blurSource = current;
            }

            RenderTexture.ReleaseTemporary(temp);
            return current;
        }
        //-----------------------------------------------------------
        /// <summary>
        /// 从纹理上指定的UV坐标拾取屏幕颜色
        /// 可用于实现吸管工具
        /// </summary>
        /// <param name="texture">源纹理</param>
        /// <param name="uv">UV坐标 (0~1)</param>
        public void PickScreenColor(Texture2D texture, Vector2 uv)
        {
            if (texture == null) return;

            int x = Mathf.Clamp(Mathf.FloorToInt(uv.x * texture.width), 0, texture.width - 1);
            int y = Mathf.Clamp(Mathf.FloorToInt(uv.y * texture.height), 0, texture.height - 1);

            screenColor = texture.GetPixel(x, y);
            screenColor.a = 1f;
        }
        //-----------------------------------------------------------
        /// <summary>
        /// 从 RenderTexture 上指定的UV坐标拾取屏幕颜色
        /// </summary>
        /// <param name="rt">源 RenderTexture</param>
        /// <param name="uv">UV坐标 (0~1)</param>
        public void PickScreenColor(RenderTexture rt, Vector2 uv)
        {
            if (rt == null) return;

            RenderTexture prev = RenderTexture.active;
            RenderTexture.active = rt;

            Texture2D temp = new Texture2D(1, 1, TextureFormat.RGBA32, false);
            int x = Mathf.Clamp(Mathf.FloorToInt(uv.x * rt.width), 0, rt.width - 1);
            int y = Mathf.Clamp(Mathf.FloorToInt(uv.y * rt.height), 0, rt.height - 1);
            temp.ReadPixels(new Rect(x, y, 1, 1), 0, 0);
            temp.Apply();

            screenColor = temp.GetPixel(0, 0);
            screenColor.a = 1f;

            RenderTexture.active = prev;

            if (Application.isPlaying)
                Object.Destroy(temp);
            else
                Object.DestroyImmediate(temp);
        }
        //-----------------------------------------------------------
        /// <summary>
        /// 重置所有参数到默认值
        /// </summary>
        public void ResetToDefaults()
        {
            screenColor = new Color(0f, 1f, 0f, 1f);
            screenGain = 100f;
            screenBalance = 50f;
            screenPreBlur = 0f;

            clipBlack = 10f;
            clipWhite = 90f;
            screenShrinkGrow = 0f;
            screenSoftness = 0f;
            screenDespotBlack = 0f;
            screenDespotWhite = 0f;

            spillSuppression = 100f;
            spillTolerance = 50f;
            spillDesaturate = 50f;
            spillRange = 50f;
            spillColorCorrection = 50f;
            lumaCorrection = 0f;

            viewMode = ViewMode.FinalResult;
        }
    }
}
