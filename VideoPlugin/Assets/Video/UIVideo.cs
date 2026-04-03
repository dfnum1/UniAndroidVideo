using GameApp.Media;
using System;

#if UNITY_EDITOR
using UnityEditor;
using UnityEditor.SceneManagement;
#endif
using UnityEngine;
using UnityEngine.UI;
using UnityEngine.Video;


namespace GameApp.UIComponent
{
    public class UIVideo : RawImage
    {
        const float                 ALPHA_TIME = 0.25f;
        public string               url;
        public bool                 bLoop = true;
        public bool                 bPersistentPath = false;
        public bool                 bErasure = true;
        public Color                erasureColor = Color.green;  
        public float                delayPlay = 0.0f;
        public Graphic              defaultShow = null;
        public bool                 disableDontStop = false;
        [SerializeField] private bool               useCustomParams = false;
        [SerializeField][Range(0,1)]private float   colorCutoff;
        [SerializeField][Range(0, 1)]private float  colorFeathering;
        [SerializeField][Range(0, 1)]private float  maskFeathering;
        [SerializeField][Range(0, 1)]private float  despill;
        [SerializeField][Range(0, 1)]private float  despillLuminanceAdd;

        private string                                      m_strUrl = null;
        private float                                       m_fDelayPlay = 0.0f;
        private bool                                        m_bVideoLoop = true;
        private bool                                        m_bPersistentPath = false;
        private IMediaPlayer                                 m_VideoPlayer;
        private IMediaPlayer                                 m_LastVideoPlayer;
        private System.Action<MediaPlayerEvent.EventType>   m_pCallback = null;

        private float               m_fAlphaTime = 0.0f;

        private Material            m_pNewMaterail = null;
        public static int _ColorCutoff = Shader.PropertyToID("_ColorCutoff");
        public static int _ColorFeathering = Shader.PropertyToID("_ColorFeathering");
        public static int _MaskFeathering = Shader.PropertyToID("_MaskFeathering");
        public static int _Despill = Shader.PropertyToID("_Despill");
        public static int _DespillLuminanceAdd = Shader.PropertyToID("_DespillLuminanceAdd");
        //------------------------------------------------------
        protected override void Awake()
        {
            erasureColor.a = 0.0f;
            this.color = erasureColor;
            base.Awake();
        }
        //------------------------------------------------------
        protected override void Start()
        {
            erasureColor.a = 0.0f;
            this.color = erasureColor;
            StartVideo();
            base.Start();
        }
        //------------------------------------------------------
        protected override void OnDisable()
        {
            if (disableDontStop)
            {
                base.OnDisable();
                return;
            }
            Stop();
            DestroyNewMaterial();
            base.OnDisable();
        }
        //------------------------------------------------------
        protected override void OnEnable()
        {
            if (disableDontStop)
            {
                base.OnEnable();
                return;
            }
            StartVideo();
            base.OnEnable();
        }
        //------------------------------------------------------
        protected override void OnDestroy()
        {
            base.OnDestroy();
            DestroyNewMaterial();
        }
        //------------------------------------------------------
        void CheckCloneNewMaterial()
        {
            if (material == null) return;
            if (material.name.EndsWith("(Instance)")) return;
            if (m_pNewMaterail == null)
            {
                m_pNewMaterail = new Material(material);
                m_pNewMaterail.name = material.name + "(Instance)";
            }
        }
        //------------------------------------------------------
        void DestroyNewMaterial()
        {
            if (m_pNewMaterail == null)
                return;
#if UNITY_EDITOR
            if (Application.isPlaying) UnityEngine.Object.Destroy(m_pNewMaterail);
            else UnityEngine.Object.DestroyImmediate(m_pNewMaterail);
#else
            UnityEngine.Object.Destroy(m_pNewMaterail);
#endif
            m_pNewMaterail = null;
        }
        //------------------------------------------------------
        public override Material material
        {
            get
            {
                if (m_pNewMaterail != null) return m_pNewMaterail;
                return base.material;
            }
            set
            {
                base.material = value;
                DestroyNewMaterial();
            }
        }
        //------------------------------------------------------
        public void StartVideo()
        {
            m_fAlphaTime = ALPHA_TIME;
            erasureColor.a = 0.0f;
            this.color = erasureColor;
            if (defaultShow) defaultShow.CrossFadeAlpha(1, ALPHA_TIME, true);
            if (!string.IsNullOrEmpty(url))
            {
#if UNITY_EDITOR
                if (Application.isPlaying) Play(url, bPersistentPath, bLoop, delayPlay);
#else
                Play(url, bPersistentPath, bLoop, delayPlay);
#endif
            }
        }
        //------------------------------------------------------
        public void Stop()
        {
            VideoController.StopVideo(m_VideoPlayer);
            m_VideoPlayer = null;
            VideoController.StopVideo(m_LastVideoPlayer);
            m_LastVideoPlayer = null;
            m_pCallback = null;
            m_fDelayPlay = 0.0f;
            m_strUrl = null;
            m_fAlphaTime = 0;
            if (defaultShow)
            {
                var color = defaultShow.color;
                color.a = 1.0f;
                defaultShow.color = color;
            }
            erasureColor.a = 0.0f;
            this.color = erasureColor;
        }
        //------------------------------------------------------
        public void Pause()
        {
            if (m_VideoPlayer != null)
                m_VideoPlayer.Pause();
        }
        //------------------------------------------------------
        public void Resume()
        {
            if (m_VideoPlayer != null)
                m_VideoPlayer.Play();
        }
        //------------------------------------------------------
        public bool IsPlaying()
        {
            if (m_VideoPlayer != null) return m_VideoPlayer.IsPlaying();
            return false;
        }
        //------------------------------------------------------
        public IMediaPlayer GetVideoPlayer()
        {
            return m_VideoPlayer;
        }
#if UNITY_EDITOR
        //------------------------------------------------------
        protected override void OnValidate()
        {
            base.OnValidate();
            string shaderDefines = VideoController.GetMaterialDefines();
            if (!string.IsNullOrEmpty(shaderDefines))
                material.EnableKeyword(shaderDefines);
        }
#endif
        //------------------------------------------------------
        public bool Play(UnityEngine.Video.VideoClip videoClip, bool bLoop = false, System.Action<MediaPlayerEvent.EventType> onCallback = null)
        {
            if(videoClip == null)
            {
                onCallback?.Invoke(MediaPlayerEvent.EventType.Error);
                return false;
            }
            delayPlay = 0.0f;
            m_bVideoLoop = bLoop;
            m_pCallback = onCallback;
            if(m_VideoPlayer!=null)
            {
                if (m_VideoPlayer.GetVideoPath().CompareTo(videoClip.name) == 0)
                {
                    return true;
                }
                m_LastVideoPlayer= m_VideoPlayer;
             //   VideoController.StopVideo(m_VideoPlayer);
            }
            m_VideoPlayer = VideoController.PlayVideo(videoClip);
            if(m_VideoPlayer!=null)
            {
                m_VideoPlayer.SetLooping(m_bVideoLoop);
                m_VideoPlayer.AddListener(OnMediaListener);
            }
            else
            {
                onCallback?.Invoke(MediaPlayerEvent.EventType.Error);
                m_pCallback = null;
                return false;
            }
            return true;
        }
        //------------------------------------------------------
        public bool Play(string video, bool bPersistentPath, bool bLoop = false, float fDelayPlay = 0, System.Action<MediaPlayerEvent.EventType> pCallback = null)
        {
            if (string.IsNullOrEmpty(video))
            {
                if (pCallback != null) pCallback(MediaPlayerEvent.EventType.Error);
                return false;
            }
            if (video.CompareTo(this.m_strUrl) == 0)
            {
                return true;
            }
            this.m_bVideoLoop = bLoop;
            this.m_bPersistentPath = bPersistentPath;
            this.m_strUrl = video;
            this.m_fDelayPlay = fDelayPlay;
            m_pCallback = pCallback;

            if(fDelayPlay<=0.0f)
            {
                DelayPlay();
            }
            return true;
        }
        //------------------------------------------------------
        public void PlayWithVideoName(string assetName, bool bLoop = false, float fDelayPlay = 0, System.Action<MediaPlayerEvent.EventType> pCallback = null)
        {
            string assetPath = VideoController.PrepareForPlayWithName(assetName);
            Play(assetPath, true, bLoop, fDelayPlay, pCallback);
        }
        //------------------------------------------------------
        public void SeekTime(float time)
        {
            if (m_VideoPlayer == null) return;
            m_VideoPlayer.Seek(time * 1000.0f);
        }
        //------------------------------------------------------
        public void SetColorCutoff(float cutoff)
        {
            CheckCloneNewMaterial();
            colorCutoff = cutoff;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        public void SetColorFeathering(float feathering)
        {
            CheckCloneNewMaterial();
            colorFeathering = feathering;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        public void SetMaskFeathering(float feathering)
        {
            CheckCloneNewMaterial();
            maskFeathering = feathering;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        public void SetDespill(float despill, float despillLumnianceAdd)
        {
            CheckCloneNewMaterial();
            this.despill = despill;
            this.despillLuminanceAdd = despillLumnianceAdd;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        void UpdateMaterialParams()
        {
            if (material == null) return;
            material.SetFloat(_ColorCutoff, colorCutoff);
            material.SetFloat(_ColorFeathering, colorFeathering);
            material.SetFloat(_MaskFeathering, maskFeathering);
            material.SetFloat(_Despill, despill);
            material.SetFloat(_DespillLuminanceAdd, despillLuminanceAdd);
        }
        //------------------------------------------------------
        void BackupLastVideo()
        {
            if (m_LastVideoPlayer != null)
            {
                if(m_LastVideoPlayer != m_VideoPlayer)
                    VideoController.StopVideo(m_LastVideoPlayer);
            }
            m_LastVideoPlayer = m_VideoPlayer;
        }
        //------------------------------------------------------
        bool DelayPlay()
        {
            if (bErasure && useCustomParams)
            {
                UpdateMaterialParams();
            }
            if (string.IsNullOrEmpty(this.m_strUrl)) return false;
            if (m_VideoPlayer != null)
            {
                if(m_LastVideoPlayer!=null )
                {
                    if (m_LastVideoPlayer.GetVideoPath().CompareTo(this.m_strUrl) == 0 &&
                        m_LastVideoPlayer.GetVideoPath().CompareTo(m_VideoPlayer.GetVideoPath())!=0)
                    {
                        string shaderMarc = VideoController.GetMaterialDefines();
                        if (material && !string.IsNullOrEmpty(shaderMarc))
                            material.EnableKeyword(shaderMarc);

                        var temp = m_VideoPlayer;
                        m_VideoPlayer = m_LastVideoPlayer;
                        m_LastVideoPlayer = temp;
                        return true;
                    }
                }
                if (m_VideoPlayer.GetVideoPath().CompareTo(this.m_strUrl) == 0)
                {
                    string shaderMarc = VideoController.GetMaterialDefines();
                    if (material && !string.IsNullOrEmpty(shaderMarc))
                        material.EnableKeyword(shaderMarc);
                    return true;
                }
                BackupLastVideo();
           //     VideoController.StopVideo(m_VideoPlayer);
            }

            m_fAlphaTime = ALPHA_TIME;
            m_fDelayPlay = -1.0f;
            if (m_LastVideoPlayer == null || m_LastVideoPlayer.GetTextureFrameCount()<=1)
            {
                //! TODO...
            }

            if (m_VideoPlayer == null)
            {
#if UNITY_EDITOR
                if (Application.isPlaying)
                    erasureColor.a = 0.0f;
#else
                erasureColor.a = 0.0f;
#endif
            }
            this.color = erasureColor;
            if (defaultShow)
            {
                var color = defaultShow.color;
                color.a = 1.0f;
                defaultShow.color = color;
            }
            this.SetAllDirty();
            string shaderDefines = VideoController.GetMaterialDefines();
            if (material && !string.IsNullOrEmpty(shaderDefines))
                material.EnableKeyword(shaderDefines);
            m_VideoPlayer = VideoController.PlayVideo(this.m_strUrl, this.m_bPersistentPath);
            if (m_VideoPlayer != null)
            {
                m_VideoPlayer.SetLooping(this.m_bVideoLoop);
                m_VideoPlayer.AddListener(OnMediaListener);
                return true;
            }
            else
            {
                if (m_pCallback != null) m_pCallback(MediaPlayerEvent.EventType.Error);
                m_pCallback = null;
            }
            return false;
        }
        //------------------------------------------------------
        void OnMediaListener(IMediaPlayer player, MediaPlayerEvent.EventType type, ErrorCode code)
        {
            if (m_VideoPlayer != player) return;
            if (m_pCallback != null) m_pCallback(type);
            if (type == MediaPlayerEvent.EventType.Closing ||
                type == MediaPlayerEvent.EventType.Error ||
                type == MediaPlayerEvent.EventType.FinishedPlaying)
            {
                m_VideoPlayer = null;
            }
        }
        //------------------------------------------------------
        void Update()
        {
            if (this.m_fDelayPlay >= 0.0f)
            {
                this.m_fDelayPlay -= Time.deltaTime;
                if (this.m_fDelayPlay <= 0)
                {
                    DelayPlay();
                }
            }
            if(m_VideoPlayer != null && m_VideoPlayer.IsPlaying() && m_VideoPlayer.GetTextureFrameCount()>2 && m_VideoPlayer.GetTexture()!=null)
            {
                if (m_LastVideoPlayer != null)
                {
                    VideoController.StopVideo(m_LastVideoPlayer);
                    m_LastVideoPlayer = null;
                }
            }
            if(m_LastVideoPlayer!=null)
            {
                SyncTexture(m_LastVideoPlayer);
                return;
            }
            if (m_VideoPlayer == null) return;
            SyncTexture(m_VideoPlayer);
            //    if (m_VideoPlayer.IsFinished())
            //       Hide();
        }
        //------------------------------------------------------
        public void SyncTexture(IMediaPlayer player, float alphaFactor = 1)
        {
            this.texture = player.GetTexture();
            if (this.texture == null || player.GetTextureFrameCount() <= 2)
            {
                erasureColor.a = 0;
                this.color = erasureColor;
            }
            else
            {
                if (m_fAlphaTime>0)
                {
                    m_fAlphaTime -= Time.unscaledDeltaTime;
                    float factor = Mathf.Clamp01((1 - m_fAlphaTime / ALPHA_TIME));
                    if (defaultShow)
                    {
                        var color = defaultShow.color;
                        if (color.a > 0)
                        {
                            color.a = Mathf.Lerp(color.a, 0, factor);
                            defaultShow.color = color;
                        }
                    }
                    erasureColor.a = Mathf.Lerp(erasureColor.a, player.GetAlhpa(true) * alphaFactor, factor);
                    this.color = erasureColor;
                }
                else if(erasureColor.a <=0)
                {
                    m_fAlphaTime = 0.1f;
                }
            }
        }
    }
#if UNITY_EDITOR
    [UnityEditor.CanEditMultipleObjects]
    [UnityEditor.CustomEditor(typeof(UIVideo))]
    public class UIVideoEditor : UnityEditor.Editor
    {
        public void OnDisable()
        {
            if (!Application.isPlaying)
                VideoController.Instance.Destroy();
        }
        public override void OnInspectorGUI()
        {
            serializedObject.Update();
            UIVideo video = target as UIVideo;
            EditorGUI.BeginChangeCheck();
            video.disableDontStop = UnityEditor.EditorGUILayout.Toggle("隐藏时不停止播放", video.disableDontStop);
            video.defaultShow = (Graphic)UnityEditor.EditorGUILayout.ObjectField(new GUIContent("缺省显示", "当视频播放失败，或者正在加载时，显示"), video.defaultShow, typeof(Graphic), true);
            video.bPersistentPath = UnityEditor.EditorGUILayout.Toggle("缓存模式", video.bPersistentPath);
            EditorGUILayout.BeginHorizontal();
            string tips = video.bPersistentPath ? "" : "视频放到StreamingAssets目录下,配置时不要包含StreamingAssets/";
            video.url = UnityEditor.EditorGUILayout.TextField(new GUIContent("视频路径", tips), video.url);
            if (GUILayout.Button("...", new GUILayoutOption[] { GUILayout.Width(20) }))
            {
                string path = UnityEditor.EditorUtility.OpenFilePanel("选择视频", Application.streamingAssetsPath, "mp4,avi,mov,wmv,flv,mkv,webm");
                if (!string.IsNullOrEmpty(path))
                {
                    if (path.StartsWith(Application.streamingAssetsPath))
                    {
                        path = path.Substring(Application.streamingAssetsPath.Length + 1);
                        video.url = path;
                    }
                    else
                    {
                        if (SceneView.lastActiveSceneView)
                            SceneView.lastActiveSceneView.ShowNotification(new GUIContent("视频放到StreamingAssets目录下,配置时不要包含StreamingAssets/"), 3.0f);
                    }
                }
            }
            if(!Application.isPlaying)
            {
                if (!string.IsNullOrEmpty(video.url) && GUILayout.Button(video.IsPlaying() ? "停止" : "预览", new GUILayoutOption[] { GUILayout.Width(50) }))
                {
                    if (video.IsPlaying())
                    {
                        video.Stop();
                    }
                    else
                    {
                        VideoController.Instance.Init();
                        video.Stop();
                        video.Play(video.url, video.bPersistentPath, video.bLoop);
                    }
                }
            }

            var materialProp = serializedObject.FindProperty("m_Material");

            EditorGUILayout.EndHorizontal();
            video.bLoop = UnityEditor.EditorGUILayout.Toggle("循环", video.bLoop);
            UnityEditor.EditorGUI.BeginChangeCheck();
            video.bErasure = UnityEditor.EditorGUILayout.Toggle("抠色", video.bErasure);
            if (video.bErasure)
            {
                EditorGUI.indentLevel++;
                video.erasureColor = UnityEditor.EditorGUILayout.ColorField("绿幕色", video.erasureColor);
                video.erasureColor.a = 0.0f;
                video.color = new Color(video.erasureColor.r, video.erasureColor.g, video.erasureColor.b, video.color.a);
                if (video.material)
                {
                    if (video.material.shader.name != "UI/UI_Video")
                    {
                        UnityEditor.EditorGUILayout.HelpBox("使用UI/UI_Video shader 可进行抠色哦！", MessageType.Warning, true);
                    }
                }
                EditorGUI.BeginChangeCheck();
                var useCustomParams = serializedObject.FindProperty("useCustomParams");
                if (useCustomParams != null)
                {
                    EditorGUILayout.PropertyField(useCustomParams, new GUIContent("使用自定义参数"));
                    if (useCustomParams.boolValue)
                    {
                        var colorCutoff = serializedObject.FindProperty("colorCutoff");
                        if (colorCutoff != null) EditorGUILayout.PropertyField(colorCutoff, new GUIContent("图像阈值"));
                        var colorFeathering = serializedObject.FindProperty("colorFeathering");
                        if (colorFeathering != null) EditorGUILayout.PropertyField(colorFeathering, new GUIContent("图像羽化"));
                        var maskFeathering = serializedObject.FindProperty("maskFeathering");
                        if (maskFeathering != null) EditorGUILayout.PropertyField(maskFeathering, new GUIContent("抠色羽化"));
                        var despill = serializedObject.FindProperty("despill");
                        if (despill != null) EditorGUILayout.PropertyField(despill, new GUIContent("滤镜强度"));
                        var despillLuminanceAdd = serializedObject.FindProperty("despillLuminanceAdd");
                        if (despillLuminanceAdd != null) EditorGUILayout.PropertyField(despillLuminanceAdd, new GUIContent("滤镜亮度增强"));
                        if (EditorGUI.EndChangeCheck())
                        {
                            if (colorCutoff != null) video.SetColorCutoff(colorCutoff.floatValue);
                            if (colorFeathering != null) video.SetColorFeathering(colorFeathering.floatValue);
                            if (maskFeathering != null) video.SetMaskFeathering(maskFeathering.floatValue);
                            if (despillLuminanceAdd != null && despill != null) video.SetDespill(despill.floatValue, despillLuminanceAdd.floatValue);
                        }
                    }
                }
                EditorGUI.indentLevel--;
            }

            if (UnityEditor.EditorGUI.EndChangeCheck())
            {
                if (video.bErasure)
                {
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo_Erasure.mat");
                }
                else
                {
                    materialProp.objectReferenceValue = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo.mat");
                }
            }

            if (materialProp != null) EditorGUILayout.PropertyField(materialProp, new GUIContent("默认材质"));

            EditorGUILayout.ObjectField("当前材质:", video.material, typeof(Material) ,false);
            video.delayPlay = UnityEditor.EditorGUILayout.FloatField("延迟播放", video.delayPlay);

            if (EditorGUI.EndChangeCheck())
            {
                video.SetVerticesDirty();
                video.SetMaterialDirty();
                EditorUtility.SetDirty(target);
            }
            serializedObject.ApplyModifiedProperties();
            if (GUILayout.Button("说明文档"))
            {
                Application.OpenURL("https://docs.qq.com/doc/DTG56eEVmd0pqcnVN");
            }
        }
        //-----------------------------------------------------
        [MenuItem("GameObject/UI/Video", false, 0)]
        static public void AddVideo(MenuCommand menuCommand)
        {
            GameObject panelRoot = new GameObject("Video");
            var videoComp = panelRoot.AddComponent<UIVideo>();
            if (videoComp.bErasure)
            {
                videoComp.material = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo_Erasure.mat");
            }
            else
            {
                videoComp.material = AssetDatabase.LoadAssetAtPath<Material>("Assets/Res/UI/Material/UIVideo.mat");
            }
            GameObject parent = menuCommand.context as GameObject;
            if (parent == null)
            {
                var canvasRoot = GameObject.FindAnyObjectByType<Canvas>();
                if (canvasRoot == null)
                {
                    var canvas = new GameObject("Canvas");
                    canvasRoot = canvas.AddComponent<Canvas>();
                    canvas.AddComponent<CanvasScaler>();
                    canvas.AddComponent<GraphicRaycaster>();
                    canvasRoot.gameObject.layer = LayerMask.NameToLayer("UI");
                    StageUtility.PlaceGameObjectInCurrentStage(canvas);
                    parent = canvas;
                }
                else
                    parent = canvasRoot.gameObject;
            }
            if(parent) panelRoot.transform.SetParent(parent.transform);
            RectTransform rectTransform = panelRoot.GetComponent<RectTransform>();
            if(rectTransform) rectTransform.sizeDelta = new Vector2(300,300);

            Selection.activeGameObject = panelRoot;
        }
    }
#endif
}
