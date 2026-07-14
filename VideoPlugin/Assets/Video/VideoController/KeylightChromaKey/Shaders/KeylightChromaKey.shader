// =============================================================================
// KeylightChromaKey.shader
// 参考 AE Keylight 插件实现的多 Pass 色度抠像 Shader
// 包含: 高斯模糊、遮罩生成、遮罩处理、溢色抑制、合成输出
// =============================================================================
Shader "Hidden/KeylightChromaKey"
{
    Properties
    {
        _MainTex ("Texture", 2D) = "white" {}
    }

    CGINCLUDE
    #include "UnityCG.cginc"

    struct appdata
    {
        float4 vertex : POSITION;
        float2 uv : TEXCOORD0;
    };

    struct v2f
    {
        float2 uv : TEXCOORD0;
        float4 vertex : SV_POSITION;
    };

    // ---- 公共纹理与参数 ----
    sampler2D _MainTex;
    float4 _MainTex_TexelSize; // (1/width, 1/height, width, height)

    sampler2D _MatteTex;     // 已处理的遮罩纹理
    sampler2D _OriginalTex;  // 原始源纹理（未经预模糊）

    // Screen Settings
    float4 _ScreenColor;     // 屏幕颜色（要抠除的颜色）
    float _ScreenGain;       // 增益 (0~2, UI上为0~200)
    float _ScreenBalance;    // 平衡 (0~1, UI上为0~100)

    // Matte Processing
    float _ClipBlack;        // 裁切黑 (0~1, UI上为0~100)
    float _ClipWhite;        // 裁切白 (0~1, UI上为0~100)
    float _ShrinkGrow;       // 收缩/扩展 (像素单位)
    float _DespotBlack;      // 去黑斑 (0~1)
    float _DespotWhite;      // 去白斑 (0~1)

    // Blur
    float _BlurSize;         // 模糊大小
    float2 _BlurDir;         // 模糊方向 (1,0)=水平, (0,1)=垂直

    // Spill Suppression
    float _SpillSuppression;     // 溢色抑制强度 (0~1)
    float _SpillTolerance;       // 溢色容差 (0~1)
    float _SpillDesaturate;      // 溢色去饱和 (0~1)
    float _SpillRange;           // 溢色范围 (0~1)
    float _SpillColorCorrection; // 溢色颜色校正 (0~1)
    float _LumaCorrection;      // 亮度校正 (-1~1)

    // View Mode
    int _ViewMode; // 0=FinalResult, 1=ScreenMatte, 2=IntermediateResult, 3=Source

    v2f vert(appdata v)
    {
        v2f o;
        o.vertex = UnityObjectToClipPos(v.vertex);
        o.uv = v.uv;
        return o;
    }

    // ---- 9-tap 高斯核权重 (sigma ≈ 1.5) ----
    // center, offset1, offset2, offset3, offset4
    static const float _GaussWeights[5] = { 0.2270270, 0.1945946, 0.1216216, 0.0540541, 0.0162162 };

    ENDCG

    SubShader
    {
        Tags { "RenderType" = "Opaque" }
        Cull Off ZWrite Off ZTest Always

        // =================================================================
        // Pass 0: 高斯模糊 (方向性，可用于预模糊和遮罩柔化)
        // 输入: _MainTex (任何格式)
        // 输出: 模糊后的纹理
        // =================================================================
        Pass
        {
            Name "GaussianBlur"
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag_blur

            float4 frag_blur(v2f i) : SV_Target
            {
                float2 texelSize = _MainTex_TexelSize.xy;
                float4 color = tex2D(_MainTex, i.uv) * _GaussWeights[0];

                [unroll]
                for (int j = 1; j < 5; j++)
                {
                    float2 offset = _BlurDir * _BlurSize * j * texelSize;
                    color += tex2D(_MainTex, i.uv + offset) * _GaussWeights[j];
                    color += tex2D(_MainTex, i.uv - offset) * _GaussWeights[j];
                }

                return color;
            }
            ENDCG
        }

        // =================================================================
        // Pass 1: 生成遮罩 (Color Difference Keying)
        // 输入: _MainTex (源纹理或预模糊后的纹理)
        // 输出: R通道 = 遮罩值 (0=透明/屏幕, 1=不透明/前景)
        // =================================================================
        Pass
        {
            Name "GenerateMatte"
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag_matte

            float4 frag_matte(v2f i) : SV_Target
            {
                float3 pixel = tex2D(_MainTex, i.uv).rgb;
                float3 screen = _ScreenColor.rgb;

                // 确定主色通道（屏幕颜色的最强通道）
                float screenMax = max(screen.r, max(screen.g, screen.b));

                // 如果屏幕颜色几乎为黑色，无法抠像
                if (screenMax < 0.001)
                    return float4(1, 1, 1, 1);

                // 将像素和屏幕颜色分离为 关键通道 和 非关键通道
                float keyPixel, nonKey1Pixel, nonKey2Pixel;
                float keyScreen, nonKey1Screen, nonKey2Screen;

                if (screen.g >= screen.r && screen.g >= screen.b)
                {
                    // 绿幕
                    keyPixel = pixel.g;     nonKey1Pixel = pixel.r;         nonKey2Pixel = pixel.b;
                    keyScreen = screen.g;   nonKey1Screen = screen.r;       nonKey2Screen = screen.b;
                }
                else if (screen.b >= screen.r && screen.b >= screen.g)
                {
                    // 蓝幕
                    keyPixel = pixel.b;     nonKey1Pixel = pixel.r;         nonKey2Pixel = pixel.g;
                    keyScreen = screen.b;   nonKey1Screen = screen.r;       nonKey2Screen = screen.g;
                }
                else
                {
                    // 红幕
                    keyPixel = pixel.r;     nonKey1Pixel = pixel.g;         nonKey2Pixel = pixel.b;
                    keyScreen = screen.r;   nonKey1Screen = screen.g;       nonKey2Screen = screen.b;
                }

                // 计算非关键通道的参考值
                // Screen Balance: 0 = 使用较大值(保守), 1 = 使用较小值(激进)
                float nonKeyMaxP = max(nonKey1Pixel, nonKey2Pixel);
                float nonKeyMinP = min(nonKey1Pixel, nonKey2Pixel);
                float refPixel = lerp(nonKeyMaxP, nonKeyMinP, _ScreenBalance);

                float nonKeyMaxS = max(nonKey1Screen, nonKey2Screen);
                float nonKeyMinS = min(nonKey1Screen, nonKey2Screen);
                float refScreen = lerp(nonKeyMaxS, nonKeyMinS, _ScreenBalance);

                // 屏幕颜色的关键通道超出量
                float screenExcess = keyScreen - refScreen;
                if (screenExcess < 0.001)
                    return float4(1, 1, 1, 1);

                // 当前像素的关键通道超出量
                float pixelExcess = keyPixel - refPixel;

                // 归一化得到屏幕存在度 (0=无屏幕色, 1=纯屏幕色)
                float screenPresence = pixelExcess / screenExcess;

                // 应用增益
                screenPresence *= _ScreenGain;

                // Alpha: 0=透明(屏幕), 1=不透明(前景)
                float alpha = 1.0 - saturate(screenPresence);

                return float4(alpha, alpha, alpha, 1.0);
            }
            ENDCG
        }

        // =================================================================
        // Pass 2: 遮罩裁切 (Clip Black / Clip White)
        // 输入: _MainTex (遮罩纹理, R通道)
        // 输出: 裁切后的遮罩
        // =================================================================
        Pass
        {
            Name "MatteClip"
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag_clip

            float4 frag_clip(v2f i) : SV_Target
            {
                float matte = tex2D(_MainTex, i.uv).r;

                // Clip Black: 低于此值变为0
                // Clip White: 高于此值变为1
                float clipB = _ClipBlack;
                float clipW = _ClipWhite;

                // 确保有效范围
                clipW = max(clipW, clipB + 0.001);

                // 重映射
                matte = (matte - clipB) / (clipW - clipB);
                matte = saturate(matte);
                if(matte < 0.09) matte =0;

                return float4(matte, matte, matte, 1.0);
            }
            ENDCG
        }

        // =================================================================
        // Pass 3: 收缩/扩展 (Shrink/Grow - 形态学操作)
        // 输入: _MainTex (遮罩纹理)
        // 输出: 处理后的遮罩
        // =================================================================
        Pass
        {
            Name "ShrinkGrow"
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag_shrinkGrow
            #pragma target 3.0

            float4 frag_shrinkGrow(v2f i) : SV_Target
            {
                float matte = tex2Dlod(_MainTex, float4(i.uv, 0, 0)).r;

                float absSize = abs(_ShrinkGrow);
                if (absSize < 0.01)
                    return float4(matte, matte, matte, 1.0);

                int samples = clamp((int)ceil(absSize), 1, 8);
                float2 texelSize = _MainTex_TexelSize.xy;
                float result = matte;

                if (_ShrinkGrow < 0)
                {
                    // 收缩 (腐蚀) - 取最小值
                    result = 1.0;
                    for (int x = -samples; x <= samples; x++)
                    {
                        for (int y = -samples; y <= samples; y++)
                        {
                            float2 offset = float2(x, y) * absSize / samples * texelSize;
                            float s = tex2Dlod(_MainTex, float4(i.uv + offset, 0, 0)).r;
                            result = min(result, s);
                        }
                    }
                }
                else
                {
                    // 扩展 (膨胀) - 取最大值
                    result = 0.0;
                    for (int x = -samples; x <= samples; x++)
                    {
                        for (int y = -samples; y <= samples; y++)
                        {
                            float2 offset = float2(x, y) * absSize / samples * texelSize;
                            float s = tex2Dlod(_MainTex, float4(i.uv + offset, 0, 0)).r;
                            result = max(result, s);
                        }
                    }
                }

                return float4(result, result, result, 1.0);
            }
            ENDCG
        }

        // =================================================================
        // Pass 4: 去斑点 (Despot Black / Despot White)
        // 输入: _MainTex (遮罩纹理)
        // 输出: 清理后的遮罩
        // =================================================================
        Pass
        {
            Name "Despot"
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag_despot
            #pragma target 3.0

            float4 frag_despot(v2f i) : SV_Target
            {
                float matte = tex2Dlod(_MainTex, float4(i.uv, 0, 0)).r;
                float2 texelSize = _MainTex_TexelSize.xy;

                // 采样 3x3 邻域
                float sum = 0;
                float minVal = 1.0;
                float maxVal = 0.0;
                int count = 0;

                for (int x = -1; x <= 1; x++)
                {
                    for (int y = -1; y <= 1; y++)
                    {
                        float2 offset = float2(x, y) * texelSize;
                        float s = tex2Dlod(_MainTex, float4(i.uv + offset, 0, 0)).r;
                        sum += s;
                        minVal = min(minVal, s);
                        maxVal = max(maxVal, s);
                        count++;
                    }
                }

                float avg = sum / count;
                // 中值估算: 去掉最大最小值后的平均
                float medianEst = (sum - minVal - maxVal) / (count - 2);

                // 去黑斑: 如果当前像素很暗但邻域较亮，则提亮
                if (_DespotBlack > 0.001)
                {
                    float diff = avg - matte;
                    if (diff > 0 && matte < 0.5)
                    {
                        float strength = saturate(diff * 4.0) * _DespotBlack;
                        matte = lerp(matte, medianEst, strength);
                    }
                }

                // 去白斑: 如果当前像素很亮但邻域较暗，则压暗
                if (_DespotWhite > 0.001)
                {
                    float diff = matte - avg;
                    if (diff > 0 && matte > 0.5)
                    {
                        float strength = saturate(diff * 4.0) * _DespotWhite;
                        matte = lerp(matte, medianEst, strength);
                    }
                }

                return float4(matte, matte, matte, 1.0);
            }
            ENDCG
        }

        // =================================================================
        // Pass 5: 最终合成 (溢色抑制 + 应用遮罩)
        // 输入: _MainTex = 原始源纹理, _MatteTex = 处理后的遮罩
        // 输出: RGBA (预乘Alpha 或 直通Alpha)
        // =================================================================
        Pass
        {
            Name "Composite"
            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag_composite

            float4 frag_composite(v2f i) : SV_Target
            {
                float3 pixel = tex2D(_MainTex, i.uv).rgb;
                float matte = tex2D(_MatteTex, i.uv).r;
                float3 screen = _ScreenColor.rgb;

                // ---- 溢色抑制 (Spill Suppression) ----
                float3 corrected = pixel;

                if (_SpillSuppression > 0.001)
                {
                    float screenMax = max(screen.r, max(screen.g, screen.b));
                    float spillAmount = 0;

                    if (screen.g >= screen.r && screen.g >= screen.b)
                    {
                        // 绿幕溢色抑制
                        // 计算绿色通道的"允许值" —— 基于其他两个通道
                        float nonKeyAvg = (pixel.r + pixel.b) * 0.5;
                        float nonKeyMax = max(pixel.r, pixel.b);
                        // tolerance 控制参考值的计算方式
                        float limit = lerp(nonKeyMax, nonKeyAvg, _SpillTolerance);

                        spillAmount = max(0, pixel.g - limit);
                        corrected.g = lerp(pixel.g, min(pixel.g, limit), _SpillSuppression);
                    }
                    else if (screen.b >= screen.r && screen.b >= screen.g)
                    {
                        // 蓝幕溢色抑制
                        float nonKeyAvg = (pixel.r + pixel.g) * 0.5;
                        float nonKeyMax = max(pixel.r, pixel.g);
                        float limit = lerp(nonKeyMax, nonKeyAvg, _SpillTolerance);

                        spillAmount = max(0, pixel.b - limit);
                        corrected.b = lerp(pixel.b, min(pixel.b, limit), _SpillSuppression);
                    }
                    else
                    {
                        // 红幕溢色抑制
                        float nonKeyAvg = (pixel.g + pixel.b) * 0.5;
                        float nonKeyMax = max(pixel.g, pixel.b);
                        float limit = lerp(nonKeyMax, nonKeyAvg, _SpillTolerance);

                        spillAmount = max(0, pixel.r - limit);
                        corrected.r = lerp(pixel.r, min(pixel.r, limit), _SpillSuppression);
                    }

                    // 溢色范围加权
                    float spillWeight = saturate(spillAmount * _SpillRange * 4.0);

                    // 亮度校正: 补偿因去溢色导致的亮度损失
                    if (abs(_LumaCorrection) > 0.001 && spillAmount > 0)
                    {
                        float lumaOriginal = dot(pixel, float3(0.299, 0.587, 0.114));
                        float lumaCorrected = dot(corrected, float3(0.299, 0.587, 0.114));
                        float lumaLoss = lumaOriginal - lumaCorrected;
                        float correction = lumaLoss * _LumaCorrection;

                        corrected += correction * spillWeight;
                    }

                    // 溢色区域去饱和
                    if (_SpillDesaturate > 0.001 && spillWeight > 0)
                    {
                        float luma = dot(corrected, float3(0.299, 0.587, 0.114));
                        float desatAmount = spillWeight * _SpillDesaturate;
                        corrected = lerp(corrected, float3(luma, luma, luma), desatAmount);
                    }

                    // 溢色颜色校正: 将溢色区域向互补色方向偏移
                    if (_SpillColorCorrection > 0.001 && spillWeight > 0)
                    {
                        float corrAmount = spillWeight * _SpillColorCorrection * 0.15;
                        if (screen.g >= screen.r && screen.g >= screen.b)
                        {
                            // 绿色互补方向: 增加品红(R+B)
                            corrected.r += corrAmount;
                            corrected.b += corrAmount * 0.5;
                        }
                        else if (screen.b >= screen.r && screen.b >= screen.g)
                        {
                            // 蓝色互补方向: 增加黄(R+G)
                            corrected.r += corrAmount * 0.5;
                            corrected.g += corrAmount;
                        }
                        else
                        {
                            // 红色互补方向: 增加青(G+B)
                            corrected.g += corrAmount;
                            corrected.b += corrAmount * 0.5;
                        }
                    }

                    corrected = saturate(corrected);
                }

                // ---- 视图模式 ----
                if (_ViewMode == 1)
                {
                    // Screen Matte 视图
                    return float4(matte, matte, matte, 1.0);
                }
                else if (_ViewMode == 2)
                {
                    // Intermediate Result 视图 (带溢色抑制，不带Alpha)
                    return float4(corrected, 1.0);
                }
                else if (_ViewMode == 3)
                {
                    // Source 视图
                    return float4(pixel, 1.0);
                }

                // Final Result: 直通Alpha (Straight Alpha)
                return float4(corrected, matte);
            }
            ENDCG
        }
    }

    FallBack Off
}
