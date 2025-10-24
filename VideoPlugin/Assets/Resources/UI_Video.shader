Shader "UI/UI_Video"
{
    Properties
    {
        [HideInInspector]_MainTex ("Texture", 2D) = "white" {}
        _Tolerance ("Tolerance", Range(0, 1)) = 0.7
        _Smoothing ("Smoothing", Range(0, 1)) = 0.1
        _Desaturation ("Desaturation", Range(0, 1)) = 0

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
		[Toggle(USE_AVPRO)] _UseAVPro("Use AVPro", Float) = 0
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
            float4 _MainTex_ST;
            float _Tolerance;
            float _Smoothing;
            float _Desaturation;
            float _MirrorX;
            float _MirrorY;
			float4 _ClipRect;
			CBUFFER_END

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
			#endif
                float2 mirroredUV = float2(lerp(i.uv.x, 1.0 - i.uv.x, _MirrorX), lerp(i.uv.y, 1.0 - i.uv.y, _MirrorY));
                fixed4 col = tex2D(_MainTex, mirroredUV);

 #if defined(SHADER_API_METAL) || defined(USE_AVPRO)
                col.rgb = LinearToGammaSpace(col.rgb);
 #endif
     
                #ifdef ERASURE_COLOR
                    // 计算与绿幕颜色的差值
                    half3 eraseColor = LinearToGammaSpace(i.color.rgb);
                    half3 colorDiff = abs(col.rgb - eraseColor);
                
                    // 计算颜色匹配度
                    half match = length(colorDiff);
                
                    // 计算透明度
					half tolerance = _Tolerance;//LinearToGammaSpaceExact(_Tolerance);
					half smoothing = _Smoothing;//LinearToGammaSpaceExact(_Smoothing);
                    half alpha = smoothstep(tolerance, tolerance + smoothing, match);
                
                    // 应用去饱和度
                    half luminance = dot(col.rgb, LinearToGammaSpace(half3(0.299, 0.587, 0.114)));
                    col.rgb = lerp(col.rgb, half3(luminance, luminance, luminance), LinearToGammaSpaceExact(_Desaturation));
                
                    // 应用透明度
                    col.a = alpha*i.color.a;
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