/********************************************************************
生成日期:	04:24:2026
类    名: 	KeylightEffectEditor
作    者:	HappLI
描    述:	支持: 色度抠像、遮罩处理、溢色抑制、多种视图模式
            模拟 AE Keylight 插件的折叠式 UI 布局
*********************************************************************/
#if UNITY_EDITOR
using UnityEditor;
using UnityEngine;

namespace GameApp.UIComponent
{
    public class KeylightEffectEditor
    {
        // ---- 折叠状态 ----
        private bool _foldScreen = true;
        private bool _foldMatte = true;
        private bool _foldSpill = true;
        private bool _foldView = true;
        private bool _foldIO = false;

        // ---- 序列化属性 ----
        // Screen Settings
        private SerializedProperty _screenColor;
        private SerializedProperty _screenGain;
        private SerializedProperty _screenBalance;
        private SerializedProperty _screenPreBlur;
        private SerializedProperty _drawSampleMode;

        // Screen Matte
        private SerializedProperty _clipBlack;
        private SerializedProperty _clipWhite;
        private SerializedProperty _screenShrinkGrow;
        private SerializedProperty _screenSoftness;
        private SerializedProperty _screenDespotBlack;
        private SerializedProperty _screenDespotWhite;

        // Spill Suppression
        private SerializedProperty _spillSuppression;
        private SerializedProperty _spillTolerance;
        private SerializedProperty _spillDesaturate;
        private SerializedProperty _spillRange;
        private SerializedProperty _spillColorCorrection;
        private SerializedProperty _lumaCorrection;

        // View
        private SerializedProperty _viewMode;

        //// IO
        //private SerializedProperty _sourceTexture;
        //private SerializedProperty _outputTexture;
        //private SerializedProperty _autoApplyToRenderer;
        //private SerializedProperty _autoApplyToRawImage;

        // ---- 样式 ----
        private GUIStyle _headerStyle;
        private GUIStyle _foldoutHeaderStyle;
        private bool _stylesInitialized = false;

        KeylightEffect m_pEffect;
        UnityEngine.Object m_pTarget;
        internal KeylightEffect Effect
        {
            get { return m_pEffect; }
        }
        public void OnEnable(UnityEngine.Object pObj, SerializedObject serializedObject, string keyLightField = "m_KeyLight")
        {
            m_pEffect = null;
            m_pTarget = pObj;
            var fieldObj = pObj.GetType().GetField(keyLightField, System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic);
            if (fieldObj != null)
            {
                var obj = fieldObj.GetValue(pObj);
                if(obj!=null && obj is KeylightEffect)
                {
                    m_pEffect = obj as KeylightEffect;
                }
            }

            keyLightField += ".";
            // Screen Settings
            _screenColor = serializedObject.FindProperty(keyLightField + "screenColor");
            _screenGain = serializedObject.FindProperty(keyLightField + "screenGain");
            _screenBalance = serializedObject.FindProperty(keyLightField + "screenBalance");
            _screenPreBlur = serializedObject.FindProperty(keyLightField + "screenPreBlur");
            _drawSampleMode = serializedObject.FindProperty(keyLightField + "drawSampleMode");

            // Screen Matte
            _clipBlack = serializedObject.FindProperty(keyLightField + "clipBlack");
            _clipWhite = serializedObject.FindProperty(keyLightField + "clipWhite");
            _screenShrinkGrow = serializedObject.FindProperty(keyLightField + "screenShrinkGrow");
            _screenSoftness = serializedObject.FindProperty(keyLightField + "screenSoftness");
            _screenDespotBlack = serializedObject.FindProperty(keyLightField + "screenDespotBlack");
            _screenDespotWhite = serializedObject.FindProperty(keyLightField + "screenDespotWhite");

            // Spill Suppression
            _spillSuppression = serializedObject.FindProperty(keyLightField + "spillSuppression");
            _spillTolerance = serializedObject.FindProperty(keyLightField + "spillTolerance");
            _spillDesaturate = serializedObject.FindProperty(keyLightField + "spillDesaturate");
            _spillRange = serializedObject.FindProperty(keyLightField + "spillRange");
            _spillColorCorrection = serializedObject.FindProperty(keyLightField + "spillColorCorrection");
            _lumaCorrection = serializedObject.FindProperty(keyLightField + "lumaCorrection");

            // View
            _viewMode = serializedObject.FindProperty(keyLightField + "viewMode");

            //// IO
            //_sourceTexture = serializedObject.FindProperty(keyLightField + "sourceTexture");
            //_outputTexture = serializedObject.FindProperty(keyLightField + "outputTexture");
            //_autoApplyToRenderer = serializedObject.FindProperty(keyLightField + "autoApplyToRenderer");
            //_autoApplyToRawImage = serializedObject.FindProperty(keyLightField + "autoApplyToRawImage");
        }

        private void InitStyles()
        {
            if (_stylesInitialized) return;

            _headerStyle = new GUIStyle(EditorStyles.boldLabel)
            {
                fontSize = 14,
                alignment = TextAnchor.MiddleCenter
            };

            _foldoutHeaderStyle = new GUIStyle(EditorStyles.foldout)
            {
                fontStyle = FontStyle.Bold,
                fontSize = 12
            };

            _stylesInitialized = true;
        }

        public void OnInspectorGUI()
        {
            InitStyles();
            // ---- 标题 ----
            EditorGUILayout.Space(5);
            DrawHeader();
            EditorGUILayout.Space(5);

            // ---- 视图模式（置顶，方便切换） ----
            DrawViewSection();
            EditorGUILayout.Space(3);

            // ---- 屏幕设置 ----
            DrawScreenSection();
            EditorGUILayout.Space(3);

            // ---- 遮罩处理 ----
            DrawMatteSection();
            EditorGUILayout.Space(3);

            // ---- 溢色抑制 ----
            DrawSpillSection();
            EditorGUILayout.Space(3);

            // ---- 输入输出 ----
            DrawIOSection();
            EditorGUILayout.Space(3);

            // ---- 操作按钮 ----
            DrawButtons();
        }

        // =================================================================
        // 绘制各个区域
        // =================================================================

        private void DrawHeader()
        {
            EditorGUILayout.BeginHorizontal();
            GUILayout.FlexibleSpace();

            var rect = GUILayoutUtility.GetRect(new GUIContent("⚡ KEYLIGHT Chroma Key"), _headerStyle, GUILayout.Height(28));
            var bgColor = new Color(0.15f, 0.15f, 0.15f, 1f);
            EditorGUI.DrawRect(new Rect(rect.x - 50, rect.y, rect.width + 100, rect.height), bgColor);

            var titleColor = new Color(0.9f, 0.75f, 0.1f, 1f); // 金色标题
            var prevColor = GUI.color;
            GUI.color = titleColor;
            GUI.Label(rect, "⚡ KEYLIGHT Chroma Key", _headerStyle);
            GUI.color = prevColor;

            GUILayout.FlexibleSpace();
            EditorGUILayout.EndHorizontal();
        }

        private void DrawViewSection()
        {
            _foldView = EditorGUILayout.Foldout(_foldView, "View 视图", true, _foldoutHeaderStyle);
            if (_foldView)
            {
                EditorGUI.indentLevel++;
                EditorGUILayout.PropertyField(_viewMode, new GUIContent("View Mode 视图模式"));
                EditorGUI.indentLevel--;
            }
        }

        private void DrawScreenSection()
        {
            _foldScreen = EditorGUILayout.Foldout(_foldScreen, "抠图设置", true, _foldoutHeaderStyle);
            if (_foldScreen)
            {
                EditorGUI.indentLevel++;

                EditorGUILayout.PropertyField(_screenColor, new GUIContent("抠图颜色"));

                // 提供一个色域提示
                Color sc = _screenColor.colorValue;
                float maxChannel = Mathf.Max(sc.r, Mathf.Max(sc.g, sc.b));
                string channelHint = "";
                if (maxChannel < 0.01f)
                    channelHint = "⚠ 屏幕颜色过暗，请重新选择";
                else if (sc.g >= sc.r && sc.g >= sc.b)
                    channelHint = "🟢 绿幕模式";
                else if (sc.b >= sc.r && sc.b >= sc.g)
                    channelHint = "🔵 蓝幕模式";
                else
                    channelHint = "🔴 红幕模式";

                EditorGUILayout.HelpBox(channelHint, MessageType.None);

                EditorGUILayout.Slider(_screenGain, 0f, 200f, new GUIContent("Screen Gain 增益"));
                EditorGUILayout.Slider(_screenBalance, 0f, 100f, new GUIContent("Screen Balance 平衡"));
                EditorGUILayout.Slider(_screenPreBlur, 0f, 20f, new GUIContent("Screen Pre-blur 预模糊"));
                if (_screenPreBlur.floatValue > 0.01f)
                {
                    EditorGUILayout.HelpBox("预模糊会在抠图前对输入图像进行模糊处理，可以帮助减少噪点和锯齿，但过高可能导致边缘过于模糊\r\n有性能损耗", MessageType.Warning);
                }
                EditorGUILayout.PropertyField(_drawSampleMode, new GUIContent("降屏采样模式"));
                string sampleModeHint = "";
                if (_drawSampleMode.enumValueIndex == 0)
                    sampleModeHint = "😢 无降采样（性能较差）";
                else if (_drawSampleMode.enumValueIndex == 1)
                    sampleModeHint = "🙂 x2降采样（性能较好）";
                else if (_drawSampleMode.enumValueIndex == 2)
                    sampleModeHint = "😄 x4降采样（性能好）";
                else if(_screenDespotWhite.enumValueIndex == 3)
                    sampleModeHint = "😎 x8降采样（性能最佳）";

                EditorGUILayout.HelpBox(sampleModeHint, MessageType.None);
                EditorGUI.indentLevel--;
            }
        }

        private void DrawMatteSection()
        {
            _foldMatte = EditorGUILayout.Foldout(_foldMatte, "遮罩处理(Matte)", true, _foldoutHeaderStyle);
            if (_foldMatte)
            {
                EditorGUI.indentLevel++;

                EditorGUILayout.Slider(_clipBlack, 0f, 100f, new GUIContent("Clip Black 裁切黑"));
                EditorGUILayout.Slider(_clipWhite, 0f, 100f, new GUIContent("Clip White 裁切白"));

                // 确保 clipBlack < clipWhite
                if (_clipBlack.floatValue >= _clipWhite.floatValue - 1f)
                {
                    EditorGUILayout.HelpBox("⚠ 裁切黑应小于裁切白", MessageType.Warning);
                }

                EditorGUILayout.Space(2);
                EditorGUILayout.Slider(_screenShrinkGrow, -10f, 10f, new GUIContent("Shrink/Grow 收缩/扩展"));
                if (_screenShrinkGrow.floatValue > 0)
                {
                    EditorGUILayout.HelpBox("有性能损耗 请慎用!!!", MessageType.Warning);
                }
                EditorGUILayout.Slider(_screenSoftness, 0f, 20f, new GUIContent("Screen Softness 柔化"));
                if(_screenSoftness.floatValue>0)
                {
                    EditorGUILayout.HelpBox("柔化会增加边缘羽化效果，但过高可能导致边缘过于模糊\r\n有性能损耗", MessageType.Warning);
                }

                EditorGUILayout.Space(2);
                EditorGUILayout.Slider(_screenDespotBlack, 0f, 100f, new GUIContent("Despot Black 去黑斑"));
                if(_screenDespotBlack.floatValue>0)
                {
                    EditorGUILayout.HelpBox("去黑斑会尝试修复黑色区域的斑点，但过高可能导致边缘细节丢失\r\n有性能损耗", MessageType.Warning);
                }
                EditorGUILayout.Slider(_screenDespotWhite, 0f, 100f, new GUIContent("Despot White 去白斑"));
                if(_screenDespotWhite.floatValue>0)
                {
                    EditorGUILayout.HelpBox("去白斑会尝试修复白色区域的斑点，但过高可能导致边缘细节丢失\r\n有性能损耗", MessageType.Warning);
                }

                EditorGUI.indentLevel--;
            }
        }

        private void DrawSpillSection()
        {
            _foldSpill = EditorGUILayout.Foldout(_foldSpill, "溢色抑制(Spill Suppression)", true, _foldoutHeaderStyle);
            if (_foldSpill)
            {
                EditorGUI.indentLevel++;

                EditorGUILayout.Slider(_spillSuppression, 0f, 100f, new GUIContent("Suppression 抑制强度"));
                EditorGUILayout.Slider(_spillTolerance, 0f, 100f, new GUIContent("Tolerance 容差"));
                EditorGUILayout.Slider(_spillDesaturate, 0f, 100f, new GUIContent("Desaturate 去饱和"));
                EditorGUILayout.Slider(_spillRange, 0f, 100f, new GUIContent("Spill Range 溢色范围"));
                EditorGUILayout.Slider(_spillColorCorrection, 0f, 100f, new GUIContent("Color Correction 颜色校正"));
                EditorGUILayout.Slider(_lumaCorrection, -100f, 100f, new GUIContent("Luma Correction 亮度校正"));

                EditorGUI.indentLevel--;
            }
        }

        private void DrawIOSection()
        {
            //_foldIO = EditorGUILayout.Foldout(_foldIO, "Input/Output 输入输出", true, _foldoutHeaderStyle);
            //if (_foldIO)
            //{
            //    EditorGUI.indentLevel++;

            //    EditorGUILayout.PropertyField(_sourceTexture, new GUIContent("Source 源纹理"));
            //    EditorGUILayout.PropertyField(_outputTexture, new GUIContent("Output 输出纹理"));
            //    EditorGUI.indentLevel--;
            //}
        }

        private void DrawButtons()
        {
            EditorGUILayout.BeginHorizontal();

            if (m_pEffect!=null && GUILayout.Button("Reset 重置参数", GUILayout.Height(25)))
            {
                Undo.RecordObject(m_pTarget, "Reset Keylight Parameters");
                m_pEffect.ResetToDefaults();
            }

            //// 快速切换视图模式按钮
            //Color prevBgColor;

            //prevBgColor = GUI.backgroundColor;
            //GUI.backgroundColor = _viewMode.enumValueIndex == 1 ? Color.yellow : Color.white;
            //if (GUILayout.Button("Matte", GUILayout.Height(25)))
            //{
            //    _viewMode.enumValueIndex = _viewMode.enumValueIndex == 1 ? 0 : 1;
            //}
            //GUI.backgroundColor = prevBgColor;

            //prevBgColor = GUI.backgroundColor;
            //GUI.backgroundColor = _viewMode.enumValueIndex == 2 ? Color.cyan : Color.white;
            //if (GUILayout.Button("Intermediate", GUILayout.Height(25)))
            //{
            //    _viewMode.enumValueIndex = _viewMode.enumValueIndex == 2 ? 0 : 2;
            //}
            //GUI.backgroundColor = prevBgColor;

            EditorGUILayout.EndHorizontal();
        }
    }
}
#endif