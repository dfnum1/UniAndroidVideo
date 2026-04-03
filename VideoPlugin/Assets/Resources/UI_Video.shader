Shader "UI/UI_Video"
{
    Properties
    {
        [HideInInspector]_MainTex ("Texture", 2D) = "white" {}
        _ColorCutoff("图像阈值", Range(0, 1)) = 0.05
        _ColorFeathering("图像羽化", Range(0, 1)) = 0.15
        _MaskFeathering("抠色羽化", Range(0, 1)) = 1
        _Sharpening("锐度", Range(0, 1)) = 0.5
		
		_Despill("滤镜强度", Range(0, 1)) = 0
        _DespillLuminanceAdd("滤镜亮度增强", Range(0, 1)) = 0
        _EdgeSoft("边缘", Range(0,1)) = 1

        _MirrorX ("Mirror X", Range(0, 1)) = 0
        _MirrorY ("Mirror Y", Range(0, 1)) = 0
		
		[HideInInspector]_StencilComp("Stencil Comparison", Float) = 8
		[HideInInspector]_Stencil("Stencil ID", Float) = 0
		[HideInInspector]_StencilOp("Stencil Operation", Float) = 0
		[HideInInspector]_StencilWriteMask("Stencil Write Mask", Float) = 255
		[HideInInspector]_StencilReadMask("Stencil Read Mask", Float) = 255

		[HideInInspector]_ColorMask("Color Mask", Float) = 15
		[Toggle(USE_GRAY)] _UseGray("Use Gray", Float) = 0
        [Toggle(ERASURE_COLOR)] _UseErasure("Use Erasure", Float) = 1
		[Toggle(UNITY_UI_ALPHACLIP)] _UseUIAlphaClip("Use Alpha Clip", Float) = 0     
	//	[Toggle(USE_AVPRO)] _UseAVPro("Use AVPro", Float) = 0
    }
    SubShader
    {
        Tags
        {
            "Queue" = "Transparent"
            "IgnoreProjector" = "True"
            "RenderType" = "Transparent"
            "PreviewType" = "Plane"
            "CanUseSpriteAtlas" = "True"
        }
        Stencil
        {
            Ref[_Stencil]
            Comp[_StencilComp]
            Pass[_StencilOp]
            ReadMask[_StencilReadMask]
            WriteMask[_StencilWriteMask]
        }

        Cull Off
        Lighting Off
        ZWrite Off
        ZTest[unity_GUIZTestMode]
        Blend SrcAlpha OneMinusSrcAlpha
        ColorMask[_ColorMask]
        Pass
        {
            HLSLPROGRAM
            #pragma vertex vert
            #pragma fragment frag

            #include "UnityCG.cginc"
            #include "UnityUI.cginc"
            #pragma multi_compile __ UNITY_UI_CLIP_RECT
            #pragma multi_compile __ UNITY_UI_ALPHACLIP
            #pragma shader_feature _ ERASURE_COLOR
			#pragma multi_compile _ USE_AVPRO
            struct appdata
            {
                float4 vertex : POSITION;
				float4 color  : COLOR;
                float2 uv : TEXCOORD0;
				UNITY_VERTEX_INPUT_INSTANCE_ID
            };

            struct v2f
            {
				float4 vertex : SV_POSITION;
				float4 color  : COLOR;
                float2 uv : TEXCOORD0;
				float4 worldPosition : TEXCOORD1;
				UNITY_VERTEX_OUTPUT_STEREO
            };

            sampler2D _MainTex;
			CBUFFER_START(UnityPerMaterial)
            float4 _MainTex_TexelSize;
            float4 _MainTex_ST;
            float _ColorCutoff;
            float _ColorFeathering;
            float _MaskFeathering;
            float _Sharpening;
			float _Despill;
            float _DespillLuminanceAdd;
            float _MirrorX;
            float _MirrorY;
			float4 _ClipRect;
            float _EdgeSoft;
			CBUFFER_END

            
            float rgb2y(float3 c) 
            {
                return (0.299*c.r + 0.587*c.g + 0.114*c.b);
            }

            float rgb2cb(float3 c) 
            {
                return (0.5 + -0.168736*c.r - 0.331264*c.g + 0.5*c.b);
            }

            float rgb2cr(float3 c) 
            {
                return (0.5 + 0.5*c.r - 0.418688*c.g - 0.081312*c.b);
            }

            float colorclose(float Cb_p, float Cr_p, float Cb_key, float Cr_key, float tola, float tolb)
            {
                float temp = (Cb_key-Cb_p)*(Cb_key-Cb_p)+(Cr_key-Cr_p)*(Cr_key-Cr_p);
                float tola2 = tola*tola;
                float tolb2 = tolb*tolb;
                if (temp < tola2) return (0);
                if (temp < tolb2) return (temp-tola2)/(tolb2-tola2);
                return (1);
            }

            float maskedTex2D(float4 color, float3 eraserColor, float2 uv)
            {
                // Chroma key to CYK conversion
                float key_cb = rgb2cb(eraserColor.rgb);
                float key_cr = rgb2cr(eraserColor.rgb);
                float pix_cb = rgb2cb(color.rgb);
                float pix_cr = rgb2cr(color.rgb);

                return colorclose(pix_cb, pix_cr, key_cb, key_cr, _ColorCutoff, _ColorFeathering);
            }

            v2f vert (appdata v)
            {
                v2f o;
				UNITY_SETUP_INSTANCE_ID(v);
                UNITY_INITIALIZE_VERTEX_OUTPUT_STEREO(o);
				o.worldPosition = v.vertex;
                o.vertex = UnityObjectToClipPos(v.vertex);
                o.uv = TRANSFORM_TEX(v.uv, _MainTex);
				o.color = v.color;
                return o;
            }

            fixed4 frag (v2f i) : SV_Target
            {
			#if USE_AVPRO
                #if defined(UNITY_UV_STARTS_AT_TOP)
                    i.uv.y = 1.0 - i.uv.y;
                #endif
				#if defined(SHADER_API_METAL)
					i.uv.y = 1.0 - i.uv.y;
				#endif
			#endif
                float2 mirroredUV = float2(lerp(i.uv.x, 1.0 - i.uv.x, _MirrorX), lerp(i.uv.y, 1.0 - i.uv.y, _MirrorY));
                fixed4 col = tex2D(_MainTex, mirroredUV);

             #if defined(APPLY_GAMMA)
                col.rgb = GammaToLinearSpace(col.rgb);
             #endif

   #if defined(SHADER_API_METAL)// || defined(USE_AVPRO)
                col.rgb = LinearToGammaSpace(col.rgb);
 #endif   
             #ifdef ERASURE_COLOR
                float2 pixelWidth = float2(1.0 / _MainTex_TexelSize.z, 0);
                float2 pixelHeight = float2(0, 1.0 / _MainTex_TexelSize.w);

                half3 eraseColor =LinearToGammaSpace(i.color.rgb);
                float factor = 1.0; // 斜对角采样权重
                float factor1 = 1.0 / 9.0; // 3x3均值模糊

                float c = maskedTex2D(col, eraseColor, i.uv);
                float r = maskedTex2D(tex2D(_MainTex, mirroredUV + pixelWidth), eraseColor, i.uv + pixelWidth);
                float l = maskedTex2D(tex2D(_MainTex, mirroredUV - pixelWidth), eraseColor, i.uv - pixelWidth);
                float d = maskedTex2D(tex2D(_MainTex, mirroredUV + pixelHeight), eraseColor, i.uv + pixelHeight);
                float u = maskedTex2D(tex2D(_MainTex, mirroredUV - pixelHeight), eraseColor, i.uv - pixelHeight);
                float rd = maskedTex2D(tex2D(_MainTex, mirroredUV + pixelWidth + pixelHeight), eraseColor, i.uv + pixelWidth + pixelHeight) * factor;
                float dl = maskedTex2D(tex2D(_MainTex, mirroredUV - pixelWidth + pixelHeight), eraseColor, i.uv - pixelWidth + pixelHeight) * factor;
                float lu = maskedTex2D(tex2D(_MainTex, mirroredUV - pixelHeight - pixelWidth), eraseColor, i.uv - pixelHeight - pixelWidth) * factor;
                float ur = maskedTex2D(tex2D(_MainTex, mirroredUV + pixelWidth - pixelHeight), eraseColor, i.uv + pixelWidth - pixelHeight) * factor;

                // 3x3均值模糊
                float blurAlpha = (c + r + l + d + u + rd + dl + lu + ur) * factor1;

                // 用_EdgeSoft控制插值强度
                float edgeSoft = lerp(c, blurAlpha, _EdgeSoft);

                // 你可以直接用edgeSoft作为alpha，或者再做一次smoothstep
                float alpha = smoothstep(_Sharpening, 1, lerp(edgeSoft, blurAlpha, _MaskFeathering));
                // 应用透明度
				fixed4 mainColor = col;
                col = mainColor*alpha;
				
				// Despill
                float v = (2*col.b+col.r)/4;
                if(col.g > v) col.g = lerp(col.g, v, _Despill);
                float4 dif = (mainColor - col);
                float desaturatedDif = rgb2y(dif.xyz);
                col += lerp(0, desaturatedDif, _DespillLuminanceAdd);
             #endif

#ifdef UNITY_UI_CLIP_RECT
                col.a *= UnityGet2DClipping(i.worldPosition.xy, _ClipRect);
#endif

#ifdef UNITY_UI_ALPHACLIP
                clip(col.a - 0.001);
#endif				
               col.rgb = GammaToLinearSpace(col.rgb);
				
                return col;
            }
            ENDHLSL
        }
    }
	FallBack Off
}    
