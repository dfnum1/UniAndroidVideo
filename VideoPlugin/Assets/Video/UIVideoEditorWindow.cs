#if UNITY_EDITOR
using GameApp.Media;
using GameApp.UIComponent;
using System.IO;
using UnityEditor;
using UnityEngine;
using UnityEngine.UI;

namespace GameApp.UIComponent
{
    public class UIVideoEditorWindow : EditorWindow
    {
        // 当前操作的UIVideo（可能是场景中的，也可能是内部创建的）
        private UIVideo m_TargetVideo = null;
        private SerializedObject m_SerializedTarget = null;
        private KeylightEffectEditor m_pKeylightEditor = null;

        // 外部绑定的场景UIVideo（从Inspector打开时传入）
        private UIVideo m_ExternalTarget = null;

        // 内部预览用的UIVideo（无外部目标时使用）
        private UIVideo m_InternalVideo = null;
        private GameObject m_InternalRoot = null;

        // 缓存播放路径（bPersistentPath模式下使用，不写入url属性）
        private string m_PlaybackPath = null;

        // Layout
        private const float LEFT_PANEL_WIDTH = 320f;
        private Vector2 m_LeftScrollPos = Vector2.zero;

        // 视频缓存目录
        private static string VideoCacheDir => Application.temporaryCachePath + "/VideoEditorCache";

        // Erasure type cache
        private enum EErasureType { eNone = 0, eNormal, eKeylight }
        private static string[] ErasurePOP = new string[] { "不抠色", "常规抠色", "Keylight抠色" };
        private static int[] ErasurePOPIndex = new int[] { 0, 1, 2 };
        private EErasureType m_ErasureType = EErasureType.eNone;

        // 预览选项
        private bool m_bFlipVertical = false;
        private bool m_bFlipHorizontal = true; // 默认开启左右镜像（GL绘制默认是反的）

        // 预览背景
        private Color m_PreviewBgColor = new Color(0.2f, 0.2f, 0.2f, 1f);
        private Texture2D m_PreviewBgTexture = null;

        // Foldouts
        private bool m_bExpandPlayback = false;
        private bool m_bExpandErasure = false;
        private bool m_bExpandEdgeClip = false;
        private bool m_bExpandEvent = false;

        // EditorPrefs keys
        private const string PREF_LAST_VIDEO_DIR = "UIVideoEditor_LastVideoDir";
        private const string PREF_FLIP_VERTICAL = "UIVideoEditor_FlipVertical";
        private const string PREF_FLIP_HORIZONTAL = "UIVideoEditor_FlipHorizontal";
        private const string PREF_PREVIEW_BG_COLOR = "UIVideoEditor_PreviewBgColor";
        private const string PREF_PREVIEW_BG_TEXTURE_PATH = "UIVideoEditor_PreviewBgTexturePath";

        //------------------------------------------------------
        [MenuItem("Tools/Video/视频编辑器", false, 100)]
        public static void ShowWindow()
        {
            var window = GetWindow<UIVideoEditorWindow>("视频编辑器");
            window.minSize = new Vector2(750, 400);
            window.Show();
        }

        //------------------------------------------------------
        /// <summary>
        /// 从UIVideoEditor打开，绑定指定UIVideo
        /// </summary>
        public static void OpenWithUIVideo(UIVideo target)
        {
            var window = GetWindow<UIVideoEditorWindow>("视频编辑器");
            window.minSize = new Vector2(750, 400);
            window.SetTarget(target);
            window.Show();
            window.Focus();
        }

        //------------------------------------------------------
        /// <summary>
        /// 绑定外部场景UIVideo作为操作目标
        /// </summary>
        public void SetTarget(UIVideo target)
        {
            if (m_TargetVideo == target) return;
            // 切换目标前停止当前播放
            if (m_TargetVideo != null && m_TargetVideo.IsPlaying())
            {
                m_TargetVideo.Stop();
            }

            m_ExternalTarget = target;
            m_TargetVideo = target;
            RefreshTarget();
        }

        //------------------------------------------------------
        void RefreshTarget()
        {
            if (m_TargetVideo != null)
            {
                m_SerializedTarget = new SerializedObject(m_TargetVideo);
                m_pKeylightEditor = new KeylightEffectEditor();
                m_pKeylightEditor.OnEnable(m_TargetVideo, m_SerializedTarget, "m_KeyLight");
                SyncErasureType();
            }
            else
            {
                m_SerializedTarget = null;
                m_pKeylightEditor = null;
            }
        }

        //------------------------------------------------------
        void SyncErasureType()
        {
            if (m_TargetVideo == null) return;
            if (m_TargetVideo.bErasure && !m_TargetVideo.bKeylightErasure)
                m_ErasureType = EErasureType.eNormal;
            else if (!m_TargetVideo.bErasure && m_TargetVideo.bKeylightErasure)
                m_ErasureType = EErasureType.eKeylight;
            else
                m_ErasureType = EErasureType.eNone;
        }

        //------------------------------------------------------
        private void OnEnable()
        {
            EditorApplication.update += OnEditorUpdate;
            m_bFlipVertical = EditorPrefs.GetBool(PREF_FLIP_VERTICAL, false);
            m_bFlipHorizontal = EditorPrefs.GetBool(PREF_FLIP_HORIZONTAL, false);

            // 加载预览背景色
            string bgColorStr = EditorPrefs.GetString(PREF_PREVIEW_BG_COLOR, "");
            if (!string.IsNullOrEmpty(bgColorStr))
            {
                ColorUtility.TryParseHtmlString(bgColorStr, out m_PreviewBgColor);
            }

            // 加载预览背景图
            string bgTexPath = EditorPrefs.GetString(PREF_PREVIEW_BG_TEXTURE_PATH, "");
            if (!string.IsNullOrEmpty(bgTexPath))
            {
                m_PreviewBgTexture = AssetDatabase.LoadAssetAtPath<Texture2D>(bgTexPath);
            }

            EnsureTarget();
        }

        //------------------------------------------------------
        private void OnDisable()
        {
            EditorApplication.update -= OnEditorUpdate;
            if (!Application.isPlaying && m_TargetVideo != null)
            {
                if (m_TargetVideo.IsPlaying())
                    m_TargetVideo.Stop();
            }
            DestroyInternalVideo();
        }

        //------------------------------------------------------
        private void OnDestroy()
        {
            DestroyInternalVideo();
            if (!Application.isPlaying)
            {
                VideoController.Instance.Destroy();
            }
        }

        //------------------------------------------------------
        void CreateInternalVideo()
        {
            if (m_InternalVideo != null) return;

            m_InternalRoot = new GameObject("[UIVideoEditorPreview]");
            m_InternalRoot.hideFlags = HideFlags.HideInHierarchy;

            var canvas = m_InternalRoot.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.ScreenSpaceOverlay;
            canvas.enabled = false;

            m_InternalRoot.AddComponent<CanvasScaler>();

            var videoGO = new GameObject("PreviewVideo");
            videoGO.transform.SetParent(m_InternalRoot.transform);

            m_InternalVideo = videoGO.AddComponent<UIVideo>();
            var mat = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo_Erasure.mat");
            if (mat != null) m_InternalVideo.material = mat;

            var rt = videoGO.GetComponent<RectTransform>();
            rt.sizeDelta = new Vector2(400, 225);
        }

        //------------------------------------------------------
        void DrawRightPanel()
        {
            EditorGUILayout.BeginVertical("box", GUILayout.ExpandWidth(true), GUILayout.ExpandHeight(true));

            if (m_TargetVideo != null && m_TargetVideo.texture != null)
            {
                // 顶部工具栏
                EditorGUILayout.BeginHorizontal();

                // 左侧：背景设置
                EditorGUILayout.LabelField("背景:", GUILayout.Width(35));
                EditorGUI.BeginChangeCheck();
                m_PreviewBgColor = EditorGUILayout.ColorField(GUIContent.none, m_PreviewBgColor, false, false, false, GUILayout.Width(40));
                if (EditorGUI.EndChangeCheck())
                {
                    EditorPrefs.SetString(PREF_PREVIEW_BG_COLOR, "#" + ColorUtility.ToHtmlStringRGBA(m_PreviewBgColor));
                }

                EditorGUI.BeginChangeCheck();
                m_PreviewBgTexture = (Texture2D)EditorGUILayout.ObjectField(m_PreviewBgTexture, typeof(Texture2D), false, GUILayout.Width(150));
                if (EditorGUI.EndChangeCheck())
                {
                    string texPath = m_PreviewBgTexture != null ? AssetDatabase.GetAssetPath(m_PreviewBgTexture) : "";
                    EditorPrefs.SetString(PREF_PREVIEW_BG_TEXTURE_PATH, texPath);
                }
                if (m_PreviewBgTexture != null)
                {
                    if (GUILayout.Button("×", GUILayout.Width(20)))
                    {
                        m_PreviewBgTexture = null;
                        EditorPrefs.SetString(PREF_PREVIEW_BG_TEXTURE_PATH, "");
                    }
                }

                GUILayout.FlexibleSpace();

                // 右侧：翻转按钮
                EditorGUI.BeginChangeCheck();
                m_bFlipHorizontal = GUILayout.Toggle(m_bFlipHorizontal, "↔ 左右镜像", "Button", GUILayout.Width(80));
                if (EditorGUI.EndChangeCheck())
                {
                    EditorPrefs.SetBool(PREF_FLIP_HORIZONTAL, m_bFlipHorizontal);
                }
                EditorGUI.BeginChangeCheck();
                m_bFlipVertical = GUILayout.Toggle(m_bFlipVertical, "↕ 上下镜像", "Button", GUILayout.Width(80));
                if (EditorGUI.EndChangeCheck())
                {
                    EditorPrefs.SetBool(PREF_FLIP_VERTICAL, m_bFlipVertical);
                }
                EditorGUILayout.EndHorizontal();

                float panelWidth = position.width - LEFT_PANEL_WIDTH - 30;
                float panelHeight = position.height - 80;
                float aspectRatio = (float)m_TargetVideo.texture.width / m_TargetVideo.texture.height;

                float previewWidth = panelWidth;
                float previewHeight = previewWidth / aspectRatio;

                if (previewHeight > panelHeight - 50)
                {
                    previewHeight = panelHeight - 50;
                    previewWidth = previewHeight * aspectRatio;
                }

                // 居中显示
                EditorGUILayout.BeginHorizontal();
                GUILayout.FlexibleSpace();
                EditorGUILayout.BeginVertical();
                GUILayout.FlexibleSpace();

                Rect previewRect = GUILayoutUtility.GetRect(previewWidth, previewHeight);

                // UV坐标：根据翻转状态决定方向
                float uvTop = m_bFlipVertical ? 0f : 1f;
                float uvBottom = m_bFlipVertical ? 1f : 0f;
                float uvLeft = m_bFlipHorizontal ? 1f : 0f;
                float uvRight = m_bFlipHorizontal ? 0f : 1f;

                // 绘制预览背景和视频
                if (Event.current.type == EventType.Repaint)
                {
                    // 绘制背景：背景图优先，否则使用背景色
                    if (m_PreviewBgTexture != null)
                    {
                        GUI.DrawTexture(previewRect, m_PreviewBgTexture, ScaleMode.StretchToFill);
                    }
                    else
                    {
                        EditorGUI.DrawRect(previewRect, m_PreviewBgColor);
                    }

                    // 绘制视频内容
                    Material previewMat = m_TargetVideo.material;
                    if(previewMat && !string.IsNullOrEmpty(VideoController.GetMaterialDefines()))
                        previewMat.EnableKeyword(VideoController.GetMaterialDefines());

                    if (previewMat != null && m_ErasureType != EErasureType.eNone)
                    {
                        Color vtxColor = (m_ErasureType == EErasureType.eNormal) ? m_TargetVideo.erasureColor : Color.white;
                        vtxColor.a = 1f;

                        // 渲染到临时 RT，使用 sRGB 格式确保色彩正确
                        int rtW = m_TargetVideo.texture.width;
                        int rtH = m_TargetVideo.texture.height;
                        var desc = new RenderTextureDescriptor(rtW, rtH, RenderTextureFormat.ARGB32, 0);
                        desc.sRGB = true;
                        RenderTexture rt = RenderTexture.GetTemporary(desc);
                        RenderTexture prevRT = RenderTexture.active;
                        RenderTexture.active = rt;

                        GL.Clear(true, true, Color.clear);
                        previewMat.SetTexture("_MainTex", m_TargetVideo.texture);
                        previewMat.SetPass(0);

                        // 注意：RT 的 Y 轴与 GUI 显示相反，需要翻转 V 坐标
                        float rtUvTop = uvBottom;
                        float rtUvBottom = uvTop;

                        GL.PushMatrix();
                        GL.LoadOrtho();
                        GL.Begin(GL.QUADS);
                        GL.Color(vtxColor);
                        GL.TexCoord2(uvLeft, rtUvTop); GL.Vertex3(0, 0, 0);
                        GL.TexCoord2(uvRight, rtUvTop); GL.Vertex3(1, 0, 0);
                        GL.TexCoord2(uvRight, rtUvBottom); GL.Vertex3(1, 1, 0);
                        GL.TexCoord2(uvLeft, rtUvBottom); GL.Vertex3(0, 1, 0);
                        GL.End();
                        GL.PopMatrix();

                        RenderTexture.active = prevRT;
                        GUI.DrawTexture(previewRect, rt, ScaleMode.StretchToFill);
                        RenderTexture.ReleaseTemporary(rt);
                    }
                    else
                    {
                        // 无抠色时直接显示纹理（处理翻转通过 texCoords）
                        Rect uvRect = new Rect(uvLeft, 1f - uvTop, uvRight - uvLeft, uvTop - uvBottom);
                        GUI.DrawTextureWithTexCoords(previewRect, m_TargetVideo.texture, uvRect);
                    }
                }

                GUILayout.FlexibleSpace();
                EditorGUILayout.EndVertical();
                GUILayout.FlexibleSpace();
                EditorGUILayout.EndHorizontal();

                // 进度条
                var player = m_TargetVideo.GetVideoPlayer();
                if (player != null)
                {
                    float duration = player.GetDurationMs();
                    float current = player.GetCurrentTimeMs();
                    if (duration > 0)
                    {
                        EditorGUILayout.BeginHorizontal();
                        EditorGUILayout.LabelField($"{(current / 1000f):F2}s / {(duration / 1000f):F2}s", GUILayout.Width(130));
                        EditorGUI.BeginChangeCheck();
                        float progress = current / duration;
                        float newProgress = GUILayout.HorizontalSlider(progress, 0f, 1f);
                        if (EditorGUI.EndChangeCheck())
                        {
                            m_TargetVideo.SeekTime(newProgress * duration / 1000f);
                        }
                        EditorGUILayout.EndHorizontal();
                    }
                }
            }
            else
            {
                GUILayout.FlexibleSpace();
                EditorGUILayout.LabelField("无视频画面", EditorStyles.centeredGreyMiniLabel);
                EditorGUILayout.LabelField("请选择视频文件并点击播放", EditorStyles.centeredGreyMiniLabel);
                GUILayout.FlexibleSpace();
            }

            EditorGUILayout.EndVertical();
        }

        //------------------------------------------------------
        void DestroyInternalVideo()
        {
            if (m_InternalVideo != null)
            {
                m_InternalVideo.Stop();
            }
            if (m_InternalRoot != null)
            {
                DestroyImmediate(m_InternalRoot);
                m_InternalRoot = null;
            }
            m_InternalVideo = null;
        }

        //------------------------------------------------------
        /// <summary>
        /// 确保有一个可操作的目标（优先外部，否则用内部）
        /// </summary>
        void EnsureTarget()
        {
            // 如果有外部目标且有效，使用外部目标
            if (m_ExternalTarget != null && m_ExternalTarget.gameObject != null)
            {
                m_TargetVideo = m_ExternalTarget;
                RefreshTarget();
                return;
            }

            // 否则使用内部预览UIVideo
            m_ExternalTarget = null;
            if (m_InternalVideo == null)
            {
                CreateInternalVideo();
            }
            m_TargetVideo = m_InternalVideo;
            RefreshTarget();
        }

        //------------------------------------------------------
        void OnEditorUpdate()
        {
            if (m_TargetVideo != null && m_TargetVideo.IsPlaying())
            {
                Repaint();
            }
        }

        //------------------------------------------------------
        private void OnGUI()
        {
            // 确保有目标可操作
            if (m_TargetVideo == null || (m_TargetVideo != null && m_TargetVideo.gameObject == null))
            {
                EnsureTarget();
            }

            if (m_TargetVideo == null)
            {
                EditorGUILayout.HelpBox("无法创建预览组件", MessageType.Error);
                return;
            }

            if (m_SerializedTarget == null || m_SerializedTarget.targetObject == null)
            {
                RefreshTarget();
            }

            m_SerializedTarget.Update();

            EditorGUILayout.BeginHorizontal();

            // ===== 左侧面板：参数编辑 =====
            DrawLeftPanel();

            // ===== 右侧面板：视频预览 =====
            DrawRightPanel();

            EditorGUILayout.EndHorizontal();

            // 应用修改 - 同时同步到外部目标
            if (m_SerializedTarget.ApplyModifiedProperties())
            {
                EditorUtility.SetDirty(m_TargetVideo);
            }
        }

        //------------------------------------------------------
        void DrawLeftPanel()
        {
            EditorGUILayout.BeginVertical(GUILayout.Width(LEFT_PANEL_WIDTH), GUILayout.ExpandHeight(true));

            // 目标信息
            EditorGUILayout.BeginHorizontal("toolbar");
            EditorGUILayout.LabelField("目标: " + m_TargetVideo.gameObject.name, EditorStyles.boldLabel);
            if (GUILayout.Button("切换目标", GUILayout.Width(60)))
            {
                ShowTargetSelectionMenu();
            }

            // 预制体保存按钮
            if (m_ExternalTarget != null && PrefabUtility.IsPartOfAnyPrefab(m_ExternalTarget.gameObject))
            {
                var prefabStage = UnityEditor.SceneManagement.PrefabStageUtility.GetCurrentPrefabStage();
                if (prefabStage != null)
                {
                    // 在预制体编辑模式中
                    if (GUILayout.Button("保存预制体", GUILayout.Width(70)))
                    {
                        m_SerializedTarget.ApplyModifiedProperties();
                        EditorUtility.SetDirty(m_ExternalTarget);
                        EditorUtility.SetDirty(m_ExternalTarget.gameObject);
                        PrefabUtility.SaveAsPrefabAsset(prefabStage.prefabContentsRoot, prefabStage.assetPath);
                        ShowNotification(new GUIContent("已保存预制体"));
                    }
                }
                else
                {
                    // 场景中的预制体实例
                    if (GUILayout.Button("保存预制体", GUILayout.Width(70)))
                    {
                        m_SerializedTarget.ApplyModifiedProperties();
                        EditorUtility.SetDirty(m_ExternalTarget);
                        EditorUtility.SetDirty(m_ExternalTarget.gameObject);
                        PrefabUtility.ApplyPrefabInstance(PrefabUtility.GetNearestPrefabInstanceRoot(m_ExternalTarget.gameObject), InteractionMode.UserAction);
                        ShowNotification(new GUIContent("已保存预制体"));
                    }
                }
            }

            EditorGUILayout.EndHorizontal();

            m_LeftScrollPos = EditorGUILayout.BeginScrollView(m_LeftScrollPos);

            DrawPlaybackSection();
            EditorGUILayout.Space(3);
            DrawErasureSection();
            EditorGUILayout.Space(3);
            DrawEdgeClipSection();
            EditorGUILayout.Space(3);
            DrawEvents();
            EditorGUILayout.Space(3);
            DrawToolsSection();


            EditorGUILayout.EndScrollView();
            EditorGUILayout.EndVertical();
        }
        //------------------------------------------------------
        void DrawEvents()
        {
            m_bExpandEvent = EditorGUILayout.Foldout(m_bExpandEvent, "事件回调");
            if (m_bExpandEvent)
            {
                EditorGUILayout.BeginVertical("box");
                var triggerEvents = m_SerializedTarget.FindProperty("triggerEvents");
                if (triggerEvents != null) EditorGUILayout.PropertyField(triggerEvents, new GUIContent("触发事件"));
                EditorGUILayout.EndVertical();
            }
        //    EditorGUILayout.EndFoldoutHeaderGroup();
        }
        //------------------------------------------------------
        void DrawPlaybackSection()
        {
            m_bExpandPlayback = EditorGUILayout.BeginFoldoutHeaderGroup(m_bExpandPlayback, "播放设置");
            if (m_bExpandPlayback)
            {
                EditorGUILayout.BeginVertical("box");

                if(m_ErasureType == EErasureType.eNone)
                {
                    var mColor = m_SerializedTarget.FindProperty("erasureColor");
                    if (mColor != null) EditorGUILayout.PropertyField(mColor, new GUIContent("顶点色"));
                }

                var preUsePop = m_SerializedTarget.FindProperty("preUse");
                if (preUsePop != null)
                {
                    bool preVal = preUsePop.boolValue;
                    EditorGUILayout.PropertyField(preUsePop, new GUIContent("预使用"));
                    if(preVal != preUsePop.boolValue)
                    {
                        UpdateMaterial();
                    }
                }

                var autoNativeSize = m_SerializedTarget.FindProperty("autoNativeSize");
                if (autoNativeSize != null) EditorGUILayout.PropertyField(autoNativeSize, new GUIContent("自适应视频大小"));

                var disableDontStop = m_SerializedTarget.FindProperty("disableDontStop");
                if (disableDontStop != null) EditorGUILayout.PropertyField(disableDontStop, new GUIContent("隐藏时不停止播放"));

                var bPersistentPath = m_SerializedTarget.FindProperty("bPersistentPath");
                if (bPersistentPath != null) EditorGUILayout.PropertyField(bPersistentPath, new GUIContent("缓存模式"));

                var mMaterial = m_SerializedTarget.FindProperty("m_Material");
                if (mMaterial != null) EditorGUILayout.PropertyField(mMaterial, new GUIContent("使用材质"));


                // 视频路径
                EditorGUILayout.BeginHorizontal();
                var urlPath = m_SerializedTarget.FindProperty("url");
                if (bPersistentPath.boolValue)
                {
                    // 缓存模式：显示播放路径（只读），不写入url
                    GUI.enabled = false;
                    EditorGUILayout.TextField("播放路径", m_PlaybackPath ?? "(未选择)");
                    GUI.enabled = true;
                }
                else
                {
                    string tips = "StreamingAssets下的相对路径";
                    EditorGUILayout.PropertyField(urlPath, new GUIContent("视频路径", tips));
                }
                if (GUILayout.Button("...", GUILayout.Width(25)))
                {
                    string lastDir = EditorPrefs.GetString(PREF_LAST_VIDEO_DIR, Application.dataPath);
                    if (!System.IO.Directory.Exists(lastDir)) lastDir = Application.dataPath;
                    string path = EditorUtility.OpenFilePanel("选择视频", lastDir, "mp4,avi,mov,wmv,flv,mkv,webm,bytes");
                    if (!string.IsNullOrEmpty(path))
                    {
                        // 记录选择的目录
                        EditorPrefs.SetString(PREF_LAST_VIDEO_DIR, System.IO.Path.GetDirectoryName(path));
                        HandleVideoPathSelection(path, bPersistentPath, urlPath);
                    }
                }
                EditorGUILayout.EndHorizontal();

                var bLoop = m_SerializedTarget.FindProperty("bLoop");
                if (bLoop != null) EditorGUILayout.PropertyField(bLoop, new GUIContent("循环"));

                if (bLoop != null && !bLoop.boolValue)
                {
                    var keepEndFrame = m_SerializedTarget.FindProperty("keepEndFrame");
                    if (keepEndFrame != null) EditorGUILayout.PropertyField(keepEndFrame, new GUIContent("保留最后一帧画面"));
                }

                var delayPlay = m_SerializedTarget.FindProperty("delayPlay");
                if (delayPlay != null) EditorGUILayout.PropertyField(delayPlay, new GUIContent("延迟播放"));

                EditorGUILayout.Space(5);

                // 播放控制按钮
                EditorGUILayout.BeginHorizontal();

                if (GUILayout.Button(m_TargetVideo.IsPlaying() ? "■ 停止" : "▶ 播放", GUILayout.Height(28)))
                {
                    if(m_TargetVideo)
                    {
                        m_TargetVideo.gameObject.SetActive(true);
                    }
                    if (m_TargetVideo.IsPlaying())
                    {
                        m_TargetVideo.Stop();
                    }
                    else
                    {
                        if (!Application.isPlaying)
                            VideoController.Instance.Init();
                        m_TargetVideo.Stop();
                        // 缓存模式使用 m_PlaybackPath，否则使用 url
                        string playUrl = m_TargetVideo.bPersistentPath ? m_PlaybackPath : m_TargetVideo.url;
                        if (!string.IsNullOrEmpty(playUrl))
                        {
                            m_TargetVideo.Play(playUrl, m_TargetVideo.bPersistentPath, m_TargetVideo.bLoop);
                        }
                        else
                        {
                            ShowNotification(new GUIContent("请先选择视频文件"));
                        }
                    }
                }

                if (GUILayout.Button("暂停", GUILayout.Height(28), GUILayout.Width(35)))
                {
                    m_TargetVideo.Pause();
                }
                if (GUILayout.Button("继续", GUILayout.Height(28), GUILayout.Width(35)))
                {
                    m_TargetVideo.Resume();
                }
                EditorGUILayout.EndHorizontal();

                EditorGUILayout.EndVertical();
            }
            EditorGUILayout.EndFoldoutHeaderGroup();
        }
        //------------------------------------------------------
        void UpdateMaterial()
        {
            var bErasure = m_SerializedTarget.FindProperty("bErasure");
            var bKeylightErasure = m_SerializedTarget.FindProperty("bKeylightErasure");
            var materialProp = m_SerializedTarget.FindProperty("m_Material");
            var preUse = m_SerializedTarget.FindProperty("preUse");
            if (m_ErasureType == EErasureType.eNormal)
            {
                bKeylightErasure.boolValue = false;
                bErasure.boolValue = true;
                if (preUse.boolValue)
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/PreRes/Videos/PreUIVideo_Erasure.mat");
                else
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo_Erasure.mat");
                if (m_TargetVideo.material) m_TargetVideo.material.EnableKeyword("ERASURE_COLOR");
            }
            else if (m_ErasureType == EErasureType.eKeylight)
            {
                bKeylightErasure.boolValue = true;
                bErasure.boolValue = false;
                if (preUse.boolValue)
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/PreRes/Videos/PreUIVideo.mat");
                else
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo.mat");
                if (m_TargetVideo.material) m_TargetVideo.material.DisableKeyword("ERASURE_COLOR");
            }
            else
            {
                bKeylightErasure.boolValue = false;
                bErasure.boolValue = false;
                if (preUse.boolValue)
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/PreRes/Videos/PreUIVideo.mat");
                else
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo.mat");
                if (m_TargetVideo.material) m_TargetVideo.material.DisableKeyword("ERASURE_COLOR");
            }
            m_TargetVideo.DestroyNewMaterial();
            EditorUtility.SetDirty(m_TargetVideo);
        }
        //------------------------------------------------------
        void DrawErasureSection()
        {
            m_bExpandErasure = EditorGUILayout.BeginFoldoutHeaderGroup(m_bExpandErasure, "抠色设置");
            if (m_bExpandErasure)
            {
                EditorGUILayout.BeginVertical("box");

                var bErasure = m_SerializedTarget.FindProperty("bErasure");
                var bKeylightErasure = m_SerializedTarget.FindProperty("bKeylightErasure");
                var materialProp = m_SerializedTarget.FindProperty("m_Material");
                var preUse = m_SerializedTarget.FindProperty("preUse");

                EErasureType lastType = m_ErasureType;
                m_ErasureType = (EErasureType)EditorGUILayout.IntPopup("抠色方式", (int)m_ErasureType, ErasurePOP, ErasurePOPIndex);

                if (m_ErasureType != lastType)
                {
                    UpdateMaterial();
                }

                if (m_ErasureType == EErasureType.eKeylight)
                {
                    // Keylight 参数
                    if (m_pKeylightEditor != null)
                    {
                        m_pKeylightEditor.OnInspectorGUI();
                    }
                    if (m_TargetVideo.material) m_TargetVideo.material.DisableKeyword("ERASURE_COLOR");
                }
                else if (m_ErasureType == EErasureType.eNormal)
                {
                    if (m_TargetVideo.material) m_TargetVideo.material.EnableKeyword("ERASURE_COLOR");
                    EditorGUI.indentLevel++;

                    var erasureColor = m_SerializedTarget.FindProperty("erasureColor");
                    if (erasureColor != null) EditorGUILayout.PropertyField(erasureColor, new GUIContent("绿幕色"));

                    var useCustomParams = m_SerializedTarget.FindProperty("useCustomParams");
                    if (useCustomParams != null)
                    {
                        bool preBool = useCustomParams.boolValue;
                        EditorGUILayout.PropertyField(useCustomParams, new GUIContent("使用自定义参数"));
                        if (useCustomParams.boolValue)
                        {
                            EditorGUI.BeginChangeCheck();
                            var colorCutoff = m_SerializedTarget.FindProperty("colorCutoff");
                            if (colorCutoff != null) EditorGUILayout.PropertyField(colorCutoff, new GUIContent("图像阈值"));
                            var colorFeathering = m_SerializedTarget.FindProperty("colorFeathering");
                            if (colorFeathering != null) EditorGUILayout.PropertyField(colorFeathering, new GUIContent("图像羽化"));
                            var maskFeathering = m_SerializedTarget.FindProperty("maskFeathering");
                         //   if (maskFeathering != null) EditorGUILayout.PropertyField(maskFeathering, new GUIContent("抠色羽化"));
                            var despill = m_SerializedTarget.FindProperty("despill");
                        //    if (despill != null) EditorGUILayout.PropertyField(despill, new GUIContent("滤镜强度"));
                            var despillLuminanceAdd = m_SerializedTarget.FindProperty("despillLuminanceAdd");
                       //     if (despillLuminanceAdd != null) EditorGUILayout.PropertyField(despillLuminanceAdd, new GUIContent("滤镜亮度增强"));
                            if (EditorGUI.EndChangeCheck() || !preBool)
                            {
                                // 运行时实时更新参数
                                m_SerializedTarget.ApplyModifiedProperties();
                                if (colorCutoff != null) m_TargetVideo.SetColorCutoff(colorCutoff.floatValue);
                                if (colorFeathering != null) m_TargetVideo.SetColorFeathering(colorFeathering.floatValue);
                                if (maskFeathering != null) m_TargetVideo.SetMaskFeathering(maskFeathering.floatValue);
                                if (despillLuminanceAdd != null && despill != null)
                                    m_TargetVideo.SetDespill(despill.floatValue, despillLuminanceAdd.floatValue);
                            }
                        }
                        else
                        {
                            if (preBool)
                            {
                                m_TargetVideo.DestroyNewMaterial();
                                materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo_Erasure.mat");
                            }
                        }
                    }
                    EditorGUI.indentLevel--;
                }

                EditorGUILayout.EndVertical();
            }
            EditorGUILayout.EndFoldoutHeaderGroup();
        }

        //------------------------------------------------------
        void DrawEdgeClipSection()
        {
            m_bExpandEdgeClip = EditorGUILayout.BeginFoldoutHeaderGroup(m_bExpandEdgeClip, "边缘裁剪");
            if (m_bExpandEdgeClip)
            {
                EditorGUILayout.BeginVertical("box");

                var edgeCliper = m_SerializedTarget.FindProperty("edgeCliper");
                if (edgeCliper != null)
                {
                    EditorGUI.BeginChangeCheck();
                    EditorGUILayout.PropertyField(edgeCliper, new GUIContent("裁剪(上,下,左,右)"));
                    edgeCliper.vector4Value = Vector4.Max(Vector4.zero, Vector4.Min(edgeCliper.vector4Value, Vector4.one));
                    var edgeClipFade = m_SerializedTarget.FindProperty("edgeClipFade");
                    if (edgeClipFade != null)
                        EditorGUILayout.PropertyField(edgeClipFade, new GUIContent("裁剪柔化"));
                    if (EditorGUI.EndChangeCheck())
                    {
                        m_SerializedTarget.ApplyModifiedProperties();
                        if (m_TargetVideo.material)
                        {
                            m_TargetVideo.material.SetVector("_EdgeCliper", edgeCliper.vector4Value);
                            if (edgeClipFade != null)
                                m_TargetVideo.material.SetFloat("_EdgeFeatherFade", edgeClipFade.floatValue);
                        }
                    }
                }

                EditorGUILayout.EndVertical();
            }
            EditorGUILayout.EndFoldoutHeaderGroup();
        }

        //------------------------------------------------------
        void DrawToolsSection()
        {
            EditorGUILayout.BeginVertical("box");
            EditorGUILayout.LabelField("工具", EditorStyles.boldLabel);

            EditorGUILayout.BeginHorizontal();
            if (GUILayout.Button("复制表格配置参数"))
            {
                CopyConfigToClipboard();
            }
            if (GUILayout.Button("从剪贴板粘贴参数"))
            {
                PasteConfigFromClipboard();
            }
            EditorGUILayout.EndHorizontal();

            if (GUILayout.Button("说明文档"))
            {
                string assetPath  =  Framework.ED.EditorUtils.FindScriptFilePath(this.GetType());
                if(!string.IsNullOrEmpty(assetPath))
                {
                    string docPath = System.IO.Path.GetFullPath(
                        System.IO.Path.Combine(Path.GetDirectoryName(assetPath), "doc~/UIVideoEditor_Help.html"));
                    if (System.IO.File.Exists(docPath))
                    {
                        Application.OpenURL("file:///" + docPath.Replace('\\', '/'));
                    }
                    else
                    {
                        Application.OpenURL("https://docs.qq.com/doc/DTG56eEVmd0pqcnVN");
                    }
                }
                else
                {
                    Application.OpenURL("https://docs.qq.com/doc/DTG56eEVmd0pqcnVN");
                }
            }
            EditorGUILayout.EndVertical();
        }

        //------------------------------------------------------
        /// <summary>
        /// 处理视频路径选择逻辑：
        /// - StreamingAssets目录下 → 取消缓存模式，记录相对路径到url
        /// - 非StreamingAssets目录 → 自动勾选缓存模式，不写入url
        /// - .bytes文件 → 复制到缓存目录并改后缀为.mp4
        /// </summary>
        void HandleVideoPathSelection(string fullPath, SerializedProperty bPersistentPath, SerializedProperty urlPath)
        {
            // 切换视频前停止当前播放
            if (m_TargetVideo != null && m_TargetVideo.IsPlaying())
            {
                m_TargetVideo.Stop();
            }

            string streamingAssetsPath = Application.streamingAssetsPath.Replace('\\', '/');
            string normalizedPath = fullPath.Replace('\\', '/');

            if (normalizedPath.StartsWith(streamingAssetsPath))
            {
                // StreamingAssets 目录下的视频
                bPersistentPath.boolValue = false;
                string relativePath = normalizedPath.Substring(streamingAssetsPath.Length + 1);
                urlPath.stringValue = relativePath;
                m_PlaybackPath = null;
                ShowNotification(new GUIContent("StreamingAssets模式"));
            }
            else
            {
                // 非StreamingAssets目录 → 自动勾选缓存模式
                bPersistentPath.boolValue = true;

                string extension = System.IO.Path.GetExtension(normalizedPath).ToLower();

                if (extension == ".bytes")
                {
                    // .bytes文件：复制到缓存目录，改后缀为.mp4
                    string cacheDir = VideoCacheDir;
                    if (!System.IO.Directory.Exists(cacheDir))
                    {
                        System.IO.Directory.CreateDirectory(cacheDir);
                    }

                    string fileName = System.IO.Path.GetFileNameWithoutExtension(normalizedPath) + ".mp4";
                    string cachePath = System.IO.Path.Combine(cacheDir, fileName).Replace('\\', '/');

                    try
                    {
                        System.IO.File.Copy(normalizedPath, cachePath, true);
                        m_PlaybackPath = cachePath;
                        ShowNotification(new GUIContent($"已复制到缓存: {fileName}"));
                    }
                    catch (System.Exception e)
                    {
                        Debug.LogError($"复制视频到缓存失败: {e.Message}");
                        ShowNotification(new GUIContent("复制文件失败"));
                        return;
                    }
                }
                else
                {
                    // 普通视频文件，直接使用绝对路径播放
                    m_PlaybackPath = normalizedPath;
                    ShowNotification(new GUIContent("缓存模式（非StreamingAssets）"));
                }

                // 缓存模式下不写入url
                // urlPath.stringValue 保持不变
            }

            m_SerializedTarget.ApplyModifiedProperties();
        }

        //------------------------------------------------------
        void CopyConfigToClipboard()
        {
            if (m_TargetVideo == null) return;

            string config = "";
            config += (m_ErasureType == EErasureType.eKeylight) ? "Keylight" : "常规";

            if (m_ErasureType == EErasureType.eKeylight && m_pKeylightEditor != null && m_pKeylightEditor.Effect != null)
                config += "\t#" + ColorUtility.ToHtmlStringRGB(m_pKeylightEditor.Effect.screenColor);
            else
                config += "\t#" + ColorUtility.ToHtmlStringRGB(m_TargetVideo.erasureColor);

            var so = m_SerializedTarget;
            config += "\t" + FTS(so.FindProperty("colorCutoff"));
            config += "\t" + FTS(so.FindProperty("colorFeathering"));
            config += "\t" + FTS(so.FindProperty("maskFeathering"));

            if (m_pKeylightEditor != null && m_pKeylightEditor.Effect != null)
            {
                var e = m_pKeylightEditor.Effect;
                config += "\t" + FloatToPercentString(e.screenGain);
                config += "\t" + FloatToPercentString(e.screenBalance);
                config += "\t" + FloatToPercentString(e.screenPreBlur);
                config += "\t" + FloatToPercentString(e.clipBlack);
                config += "\t" + FloatToPercentString(e.clipWhite);
                config += "\t" + FloatToPercentString(e.screenShrinkGrow);
                config += "\t" + FloatToPercentString(e.screenSoftness);
                config += "\t" + FloatToPercentString(e.spillSuppression);
                config += "\t" + FloatToPercentString(e.spillTolerance);
                config += "\t" + FloatToPercentString(e.spillDesaturate);
                config += "\t" + FloatToPercentString(e.spillRange);
                config += "\t" + FloatToPercentString(e.spillColorCorrection);
                config += "\t" + FloatToPercentString(e.lumaCorrection);
            }
            else
            {
                for (int i = 0; i < 13; i++) config += "\t0";
            }

            var ec = so.FindProperty("edgeCliper");
            if (ec != null)
            {
                config += "\t" + FloatToPercentString(ec.vector4Value.x);
                config += "\t" + FloatToPercentString(ec.vector4Value.y);
                config += "\t" + FloatToPercentString(ec.vector4Value.z);
                config += "\t" + FloatToPercentString(ec.vector4Value.w);
            }
            else
            {
                config += "\t0\t0\t0\t0";
            }
            var ef = so.FindProperty("edgeClipFade");
            config += "\t" + (ef != null ? FloatToPercentString(ef.floatValue) : "0");

            EditorGUIUtility.systemCopyBuffer = config;
            ShowNotification(new GUIContent("已复制配置参数到剪贴板"));
        }

        //------------------------------------------------------
        /// <summary>
        /// 从剪贴板粘贴表格配置参数并应用
        /// 格式（Tab分隔）：
        /// [0]类型 [1]颜色 [2]colorCutoff [3]colorFeathering [4]maskFeathering
        /// [5-17]Keylight参数(13个) [18-21]edgeCliper(4个) [22]edgeClipFade
        /// 数值为 float * 10000 的整数
        /// </summary>
        void PasteConfigFromClipboard()
        {
            if (m_TargetVideo == null || m_SerializedTarget == null) return;

            string clipboard = EditorGUIUtility.systemCopyBuffer;
            if (string.IsNullOrEmpty(clipboard))
            {
                ShowNotification(new GUIContent("剪贴板为空"));
                return;
            }

            string[] parts = clipboard.Split('\t');
            if (parts.Length < 5)
            {
                ShowNotification(new GUIContent("剪贴板数据格式不正确"));
                return;
            }

            try
            {
                // [0] 抠色类型
                string typeStr = parts[0].Trim();
                var bErasure = m_SerializedTarget.FindProperty("bErasure");
                var bKeylightErasure = m_SerializedTarget.FindProperty("bKeylightErasure");
                var materialProp = m_SerializedTarget.FindProperty("m_Material");
                var preUse = m_SerializedTarget.FindProperty("preUse");

                if (typeStr == "Keylight")
                {
                    m_ErasureType = EErasureType.eKeylight;
                    bKeylightErasure.boolValue = true;
                    bErasure.boolValue = false;

                    if(preUse.boolValue)
                        materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/PreRes/Videos/PreUIVideo.mat");
                    else
                        materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo.mat");
                }
                else
                {
                    m_ErasureType = EErasureType.eNormal;
                    bKeylightErasure.boolValue = false;
                    bErasure.boolValue = true;
                    if (preUse.boolValue)
                        materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/PreRes/Videos/PreUIVideo_Erasure.mat");
                    else
                        materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo_Erasure.mat");
                }

                // [1] 颜色 (#RRGGBB)
                if (parts.Length > 1)
                {
                    string colorStr = parts[1].Trim();
                    if (!colorStr.StartsWith("#")) colorStr = "#" + colorStr;
                    Color parsedColor;
                    if (ColorUtility.TryParseHtmlString(colorStr, out parsedColor))
                    {
                        if (m_ErasureType == EErasureType.eKeylight)
                        {
                            if (m_pKeylightEditor != null && m_pKeylightEditor.Effect != null)
                                m_pKeylightEditor.Effect.screenColor = parsedColor;
                        }
                        else
                        {
                            var erasureColor = m_SerializedTarget.FindProperty("erasureColor");
                            if (erasureColor != null) erasureColor.colorValue = parsedColor;
                        }
                    }
                }

                // [2] colorCutoff
                if (parts.Length > 2)
                {
                    var prop = m_SerializedTarget.FindProperty("colorCutoff");
                    if (prop != null) prop.floatValue = PercentStringToFloat(parts[2]);
                }

                // [3] colorFeathering
                if (parts.Length > 3)
                {
                    var prop = m_SerializedTarget.FindProperty("colorFeathering");
                    if (prop != null) prop.floatValue = PercentStringToFloat(parts[3]);
                }

                // [4] maskFeathering
                if (parts.Length > 4)
                {
                    var prop = m_SerializedTarget.FindProperty("maskFeathering");
                    if (prop != null) prop.floatValue = PercentStringToFloat(parts[4]);
                }

                // [5-17] Keylight参数 (13个)
                if (m_ErasureType == EErasureType.eKeylight && m_pKeylightEditor != null && m_pKeylightEditor.Effect != null)
                {
                    var e = m_pKeylightEditor.Effect;
                    if (parts.Length > 5)  e.screenGain = PercentStringToFloat(parts[5]);
                    if (parts.Length > 6)  e.screenBalance = PercentStringToFloat(parts[6]);
                    if (parts.Length > 7)  e.screenPreBlur = PercentStringToFloat(parts[7]);
                    if (parts.Length > 8)  e.clipBlack = PercentStringToFloat(parts[8]);
                    if (parts.Length > 9)  e.clipWhite = PercentStringToFloat(parts[9]);
                    if (parts.Length > 10) e.screenShrinkGrow = PercentStringToFloat(parts[10]);
                    if (parts.Length > 11) e.screenSoftness = PercentStringToFloat(parts[11]);
                    if (parts.Length > 12) e.spillSuppression = PercentStringToFloat(parts[12]);
                    if (parts.Length > 13) e.spillTolerance = PercentStringToFloat(parts[13]);
                    if (parts.Length > 14) e.spillDesaturate = PercentStringToFloat(parts[14]);
                    if (parts.Length > 15) e.spillRange = PercentStringToFloat(parts[15]);
                    if (parts.Length > 16) e.spillColorCorrection = PercentStringToFloat(parts[16]);
                    if (parts.Length > 17) e.lumaCorrection = PercentStringToFloat(parts[17]);
                }

                // [18-21] edgeCliper
                var edgeCliper = m_SerializedTarget.FindProperty("edgeCliper");
                if (edgeCliper != null && parts.Length > 21)
                {
                    Vector4 ec = new Vector4(
                        PercentStringToFloat(parts[18]),
                        PercentStringToFloat(parts[19]),
                        PercentStringToFloat(parts[20]),
                        PercentStringToFloat(parts[21])
                    );
                    edgeCliper.vector4Value = ec;
                    if (m_TargetVideo.material)
                        m_TargetVideo.material.SetVector("_EdgeCliper", ec);
                }

                // [22] edgeClipFade
                if (parts.Length > 22)
                {
                    var edgeClipFade = m_SerializedTarget.FindProperty("edgeClipFade");
                    if (edgeClipFade != null)
                    {
                        edgeClipFade.floatValue = PercentStringToFloat(parts[22]);
                        if (m_TargetVideo.material)
                            m_TargetVideo.material.SetFloat("_EdgeFeatherFade", edgeClipFade.floatValue);
                    }
                }

                // 启用自定义参数
                var useCustomParams = m_SerializedTarget.FindProperty("useCustomParams");
                if (useCustomParams != null) useCustomParams.boolValue = true;

                // 应用修改
                m_SerializedTarget.ApplyModifiedProperties();

                // 应用材质和运行时参数
                m_TargetVideo.DestroyNewMaterial();
                if (m_ErasureType == EErasureType.eNormal)
                {
                    if (m_TargetVideo.material) m_TargetVideo.material.EnableKeyword("ERASURE_COLOR");
                    var colorCutoff = m_SerializedTarget.FindProperty("colorCutoff");
                    var colorFeathering = m_SerializedTarget.FindProperty("colorFeathering");
                    var maskFeathering = m_SerializedTarget.FindProperty("maskFeathering");
                    var despill = m_SerializedTarget.FindProperty("despill");
                    var despillLuminanceAdd = m_SerializedTarget.FindProperty("despillLuminanceAdd");
                    if (colorCutoff != null) m_TargetVideo.SetColorCutoff(colorCutoff.floatValue);
                    if (colorFeathering != null) m_TargetVideo.SetColorFeathering(colorFeathering.floatValue);
                    if (maskFeathering != null) m_TargetVideo.SetMaskFeathering(maskFeathering.floatValue);
                    if (despill != null && despillLuminanceAdd != null)
                        m_TargetVideo.SetDespill(despill.floatValue, despillLuminanceAdd.floatValue);
                }
                else if (m_ErasureType == EErasureType.eKeylight)
                {
                    if (m_TargetVideo.material) m_TargetVideo.material.DisableKeyword("ERASURE_COLOR");
                }

                EditorUtility.SetDirty(m_TargetVideo);
                ShowNotification(new GUIContent("已粘贴配置参数"));
            }
            catch (System.Exception ex)
            {
                Debug.LogError($"粘贴配置参数失败: {ex.Message}\n剪贴板内容: {clipboard}");
                ShowNotification(new GUIContent("粘贴失败，数据格式错误"));
            }
        }

        //------------------------------------------------------
        /// <summary>
        /// 弹出菜单显示场景中所有 UIVideo 组件，用节点路径标识
        /// </summary>
        void ShowTargetSelectionMenu()
        {
            var allVideos = Object.FindObjectsOfType<UIVideo>(true);
            if (allVideos == null || allVideos.Length == 0)
            {
                ShowNotification(new GUIContent("场景中没有 UIVideo 组件"));
                return;
            }

            GenericMenu menu = new GenericMenu();

            // 如果当前选中的 GameObject 有 UIVideo，优先放在第一位
            UIVideo selectedVideo = null;
            if (Selection.activeGameObject != null)
            {
                selectedVideo = Selection.activeGameObject.GetComponent<UIVideo>();
            }

            if (selectedVideo != null)
            {
                string selectedPath = GetNodePath(selectedVideo.transform);
                menu.AddItem(new GUIContent("★ " + selectedPath), m_TargetVideo == selectedVideo, () =>
                {
                    SetTarget(selectedVideo);
                    ShowNotification(new GUIContent("已切换目标"));
                });
                menu.AddSeparator("");
            }

            // 添加所有其他 UIVideo
            foreach (var video in allVideos)
            {
                if (video == selectedVideo) continue; // 已经在第一位了
                UIVideo v = video; // 闭包变量
                string path = GetNodePath(v.transform);
                menu.AddItem(new GUIContent(path), m_TargetVideo == v, () =>
                {
                    SetTarget(v);
                    ShowNotification(new GUIContent("已切换目标"));
                });
            }

            menu.ShowAsContext();
        }

        //------------------------------------------------------
        /// <summary>
        /// 获取节点的完整路径，使用 " > " 分隔（避免 "/" 被 GenericMenu 识别为多级菜单）
        /// </summary>
        string GetNodePath(Transform t)
        {
            string path = t.name;
            Transform parent = t.parent;
            while (parent != null)
            {
                path = parent.name + " > " + path;
                parent = parent.parent;
            }
            return path;
        }

        //------------------------------------------------------
        private float PercentStringToFloat(string str)
        {
            str = str.Trim();
            int intValue;
            if (int.TryParse(str, out intValue))
                return intValue / 10000f;
            return 0f;
        }

        //------------------------------------------------------
        private string FTS(SerializedProperty prop)
        {
            return prop != null ? FloatToPercentString(prop.floatValue) : "0";
        }

        //------------------------------------------------------
        private string FloatToPercentString(float value)
        {
            return ((int)(value * 10000)).ToString();
        }
    }
}
#endif
