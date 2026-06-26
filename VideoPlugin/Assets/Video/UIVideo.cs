using GameApp.Media;
#if UNITY_EDITOR
using UnityEditor;
using UnityEditor.SceneManagement;
#endif
using UnityEngine;
using UnityEngine.UI;

namespace GameApp.UIComponent
{
    public class UIVideo : RawImage
    {
        struct EraserParam
        {
            public byte eraserType;//0-none; 1:color erasure; 2:keylight erasure
            public Color erasureColor;
            public float colorCutoff;
            public float colorFeathering;
            public float maskFeathering;
            public float despill;
            public float despillLuminanceAdd;
            public void Clear()
            {
                eraserType = 0;
            }
        }
        [System.Serializable]
        public struct Event
        {
            public float time;
            public string strValue;
        }
        const float ALPHA_TIME = 0.25f;
        public string url;
        public bool bLoop = true;
        public bool bPersistentPath = false;
        public bool bErasure = true;
        public bool bKeylightErasure = false;
        public Color erasureColor = Color.green;
        public float delayPlay = 0.0f;
        public Graphic defaultShow = null;
        public bool disableDontStop = false;
        public bool autoNativeSize = false;
        [SerializeField] private bool preUse = false;
        [SerializeField] private bool useCustomParams = false;
        [SerializeField] private bool keepEndFrame = false;
        [SerializeField][Range(0, 1)] private float colorCutoff;
        [SerializeField][Range(0, 1)] private float colorFeathering;
        [SerializeField][Range(0, 1)] private float maskFeathering;
        [SerializeField][Range(0, 1)] private float despill;
        [SerializeField][Range(0, 1)] private float despillLuminanceAdd;
        [SerializeField] private Event[] triggerEvents;

        [SerializeField]private Vector4 edgeCliper = Vector4.zero;
        [SerializeField][Range(0, 1)] private float edgeClipFade = 0;

        [SerializeField] private  KeylightEffect m_KeyLight = new KeylightEffect();

        private bool m_bKeepEndRePlay = false;
        private bool m_bKeepEndFrameTrigger = false;
        private string m_strUrl = null;
        private float m_fDelayPlay = 0.0f;
        private bool m_bVideoLoop = true;
        private bool m_bPersistentPath = false;
        private float m_fVideoDuration = 0;
        private IMediaPlayer m_VideoPlayer;
        private IMediaPlayer m_LastVideoPlayer;
        private EraserParam m_LastEraserParam = new EraserParam();
        private System.Action<MediaPlayerEvent.EventType> m_pCallback = null;
        private System.Action<string> m_pEventCallback = null;

        private float m_fAlphaTime = 0.0f;
        private float m_fAlphaFade = 1;
        private float m_fFadeInAlpha = -1;

        private float m_fKeepLogicPlayTime = 0;
        private float m_fKeepEndErrorTime = 150;
        private bool m_bUseLogicTimeCheck = true;

        private uint m_TriggerEventFlags = 0;

        private Material m_pNewMaterail = null;
        public static int _ColorId = Shader.PropertyToID("_Color");
        public static int _ColorCutoff = Shader.PropertyToID("_ColorCutoff");
        public static int _ColorFeathering = Shader.PropertyToID("_ColorFeathering");
        public static int _MaskFeathering = Shader.PropertyToID("_MaskFeathering");
        public static int _Despill = Shader.PropertyToID("_Despill");
        public static int _DespillLuminanceAdd = Shader.PropertyToID("_DespillLuminanceAdd");
        public static int _EdgeCliper = Shader.PropertyToID("_EdgeCliper");
        public static int _EdgeFeather = Shader.PropertyToID("_EdgeFeatherFade");
        //------------------------------------------------------
        protected override void Awake()
        {
            m_fVideoDuration = 0;
            m_TriggerEventFlags = 0;
            m_bKeepEndFrameTrigger = false;
            m_fAlphaFade = 1;
            m_fFadeInAlpha = -1;
            erasureColor.a = 0.0f;
            this.color = erasureColor;
            base.Awake();
        }
        //------------------------------------------------------
        protected override void Start()
        {
            m_TriggerEventFlags = 0;
            m_bKeepEndFrameTrigger = false;
            m_fAlphaFade = 1;
            m_fFadeInAlpha = -1;
            erasureColor.a = 0.0f;
            this.color = erasureColor;
            StartVideo();
            base.Start();
        }
        //------------------------------------------------------
        protected override void OnDisable()
        {
            m_TriggerEventFlags = 0;
            m_bKeepEndFrameTrigger = false;
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
            m_TriggerEventFlags = 0;
            if (disableDontStop)
            {
                base.OnEnable();
                return;
            }
            m_bKeepEndFrameTrigger = false;
            StartVideo();
            base.OnEnable();
        }
        //------------------------------------------------------
        protected override void OnDestroy()
        {
            m_TriggerEventFlags = 0;
            base.OnDestroy();
            Stop();
            m_bKeepEndFrameTrigger = false;
            m_KeyLight.Cleanup();
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
        internal void DestroyNewMaterial()
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
            m_bKeepEndFrameTrigger = false;
            m_fAlphaTime = ALPHA_TIME;
            m_fAlphaFade = 1;
            m_fFadeInAlpha = -1;
            erasureColor.a = 0.0f;
            this.color = erasureColor;
            if (defaultShow) defaultShow.CrossFadeAlpha(1, ALPHA_TIME, true);
            if (!string.IsNullOrEmpty(url))
            {
#if UNITY_EDITOR
                Play(url, bPersistentPath, bLoop, delayPlay);
#else
                Play(url, bPersistentPath, bLoop, delayPlay);
#endif
            }
        }
        //------------------------------------------------------
        public void SetKeepEndErrorTime(float time)
        {
            m_fKeepEndErrorTime = time * 1000;
        }
        //------------------------------------------------------
        public void UseLogicTimeKeep(bool bUsed)
        {
            m_bUseLogicTimeCheck = bUsed;
        }
        //------------------------------------------------------
        public void Stop()
        {
            m_fVideoDuration = 0;
            m_TriggerEventFlags = 0;
            m_bKeepEndRePlay = false;
            m_bKeepEndFrameTrigger = false;
            VideoController.StopVideo(m_VideoPlayer);
            m_VideoPlayer = null;
            VideoController.StopVideo(m_LastVideoPlayer);
            m_LastVideoPlayer = null;
            m_LastEraserParam.Clear();
            m_pCallback = null;
            m_pEventCallback = null;
            m_fDelayPlay = 0.0f;
            m_fAlphaFade = 1;
            m_fFadeInAlpha = -1;
            m_strUrl = null;
            m_fAlphaTime = 0;
            m_fKeepLogicPlayTime = 0;
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
        void BackupEraserParam()
        {
            if (this.bKeylightErasure) m_LastEraserParam.eraserType = 2;
            else if (this.bErasure) m_LastEraserParam.eraserType = 1;
            else m_LastEraserParam.eraserType = 0;
            m_LastEraserParam.erasureColor = this.erasureColor;
            m_LastEraserParam.colorCutoff = this.colorCutoff;
            m_LastEraserParam.colorFeathering = this.colorFeathering;
            m_LastEraserParam.maskFeathering = this.maskFeathering;
            m_LastEraserParam.despill = this.despill;
            m_LastEraserParam.despillLuminanceAdd = this.despillLuminanceAdd;
        }
        //------------------------------------------------------
        public void DummyStop()
        {
            m_bKeepEndRePlay = false;
            if (m_bKeepEndFrameTrigger) m_bKeepEndRePlay = true;
            if (m_LastVideoPlayer!= m_VideoPlayer)
            {
                if(m_LastVideoPlayer!=null) VideoController.StopVideo(m_LastVideoPlayer);
                m_LastVideoPlayer = null;
            }

            BackupEraserParam();
            m_LastVideoPlayer = m_VideoPlayer;
            m_VideoPlayer = null;
            this.m_strUrl = null;
            m_TriggerEventFlags = 0;
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
        public void SetDuration(float duration)
        {
            m_fVideoDuration = duration* 1000;
            if (m_VideoPlayer == null) return;
            m_VideoPlayer.SetDuration(m_fVideoDuration);
        }
        //------------------------------------------------------
        public float GetDuration()
        {
            if (m_fVideoDuration > 0) return m_fVideoDuration / 1000.0f;
            if (m_VideoPlayer != null) return m_VideoPlayer.GetDurationMs() / 1000.0f;
            return 0;
        }
        //------------------------------------------------------
        public float GetCurTime()
        {
            if (m_VideoPlayer != null) return m_VideoPlayer.GetCurrentTimeMs() / 1000.0f;
            return 0;
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
        //------------------------------------------------------
        public void SetAlphaFade(float alpha)
        {
            m_fAlphaFade = Mathf.Clamp01(alpha);
        }
        //------------------------------------------------------
        public void FadeInAlpah(float alpha)
        {
            m_fFadeInAlpha = Mathf.Clamp01(alpha);
            if(m_VideoPlayer!=null)
            {
                erasureColor.a = alpha;
                var color = this.color;
                color.a = alpha;
                this.color = color;
            }
        }
        //------------------------------------------------------
        public bool Play(UnityEngine.Video.VideoClip videoClip, bool bLoop = false, System.Action<MediaPlayerEvent.EventType> onCallback = null, System.Action<string> onEvents = null)
        {
            if (videoClip == null)
            {
                onCallback?.Invoke(MediaPlayerEvent.EventType.Error);
                return false;
            }
            m_TriggerEventFlags = 0;
            m_bKeepEndFrameTrigger = false;
            delayPlay = 0.0f;
            m_bVideoLoop = bLoop;
            m_pCallback = onCallback;
            m_pEventCallback = onEvents;
            if (m_VideoPlayer != null)
            {
                if (m_VideoPlayer.GetVideoPath().CompareTo(videoClip.name) == 0)
                {
                    return true;
                }
                m_LastVideoPlayer = m_VideoPlayer;
                //   VideoController.StopVideo(m_VideoPlayer);
            }
            m_VideoPlayer = VideoController.PlayVideo(videoClip);
            if (m_VideoPlayer != null)
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
        public bool Play(string video, bool bPersistentPath, bool bLoop = false, float fDelayPlay = 0, System.Action<MediaPlayerEvent.EventType> pCallback = null, System.Action<string> onEvents = null)
        {
            m_bKeepEndFrameTrigger = false;
            if (string.IsNullOrEmpty(video))
            {
                if (pCallback != null) pCallback(MediaPlayerEvent.EventType.Error);
                return false;
            }
            if (string.IsNullOrEmpty(System.IO.Path.GetExtension(video)))
            {
                Debug.LogErrorFormat("视频路径格式错误:{0}", video);
                if (pCallback != null) pCallback(MediaPlayerEvent.EventType.Error);
                return false;
            }
            if (video.CompareTo(this.m_strUrl) == 0)
            {
                RefreshDirtyMaterial();
                return true;
            }
            m_TriggerEventFlags = 0;
            this.m_bVideoLoop = bLoop;
            this.m_bPersistentPath = bPersistentPath;
            this.m_strUrl = video;
            this.m_fDelayPlay = fDelayPlay;
            m_pCallback = pCallback;
            m_pEventCallback = onEvents;

            if (fDelayPlay <= 0.0f)
            {
                DelayPlay();
            }
            return true;
        }
        //------------------------------------------------------
        public void PlayWithVideoName(string assetName, bool bLoop = false, float fDelayPlay = 0, System.Action<MediaPlayerEvent.EventType> pCallback = null, System.Action<string> onEvents = null)
        {
            string assetPath = VideoController.PrepareForPlayWithName(assetName);
            Play(assetPath, true, bLoop, fDelayPlay, pCallback, onEvents);
        }
        //------------------------------------------------------
        public void SeekNormalTime(float process)
        {
            process = Mathf.Clamp01(process);
            SeekTime(process*GetDuration());
        }
        //------------------------------------------------------
        public void SeekTime(float time)
        {
            if (m_VideoPlayer == null) return;
            m_VideoPlayer.Seek(time * 1000.0f);
            m_fKeepLogicPlayTime = time;
            if (m_bKeepEndFrameTrigger &&
                keepEndFrame && !bLoop &&
                m_VideoPlayer.GetDurationMs() > 0 && time < (m_VideoPlayer.GetDurationMs() / 1000.0f - m_fKeepEndErrorTime*0.001f))
            {
                m_bKeepEndFrameTrigger = false;
            }

            if (this.triggerEvents!=null)
            {
                for(int i =0; i < this.triggerEvents.Length; ++i)
                {
                    if(this.triggerEvents[i].time >= time)
                    {
                        m_TriggerEventFlags &= ~(1u << i);
                    }
                }
            }
        }
        //------------------------------------------------------
        public void UseErasure(bool bKeylight)
        {
            if (bKeylight)
            {
                this.bKeylightErasure = true;
                this.bErasure = false;
            }
            else
            {
                this.bKeylightErasure = false;
                this.bErasure = true;
            }
        }
        //------------------------------------------------------
        public void DisableErasure()
        {
            this.bKeylightErasure = false;
            this.bErasure = false;
        }
        //------------------------------------------------------
        public void SetColorCutoff(float cutoff)
        {
            if(cutoff>=0) colorCutoff = cutoff;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        public void SetColorFeathering(float feathering)
        {
            if (feathering >= 0) colorFeathering = feathering;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        public void SetMaskFeathering(float feathering)
        {
            if (feathering >= 0) maskFeathering = feathering;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        public void SetDespill(float despill, float despillLumnianceAdd)
        {
            if(despill>=0) this.despill = despill;
            if(despillLumnianceAdd>=0) this.despillLuminanceAdd = despillLumnianceAdd;
            UpdateMaterialParams();
        }
        //------------------------------------------------------
        public void SetKeylightScreen(Color screenColor, float screenGain = -1.0f, float screenBalance = -1.0f, float screenPreBlur = -1.0f)
        {
            if (m_KeyLight == null) return;
            m_KeyLight.screenColor = screenColor;
            if(screenGain>=0) m_KeyLight.screenGain = screenGain;
            if (screenBalance >= 0) m_KeyLight.screenBalance = screenBalance;
            if (screenPreBlur >= 0) m_KeyLight.screenPreBlur = screenPreBlur;
        }
        //------------------------------------------------------
        public void SetKeylightMatte(float clipBlack = -1.0f, float clipWhite = -1.0f, float screenShrinkGrow = 0f, float screenSoftness = 0f, float screenDespotBlack = -1.0f, float screenDespotWhite = -1.0f)
        {
            if (m_KeyLight == null) return;
            if (clipBlack >= 0) m_KeyLight.clipBlack = clipBlack;
            if (clipWhite >= 0) m_KeyLight.clipWhite = clipWhite;
            m_KeyLight.screenShrinkGrow = screenShrinkGrow;
            m_KeyLight.screenSoftness = screenSoftness;
            if (screenDespotBlack >= 0) m_KeyLight.screenDespotBlack = screenDespotBlack;
            if (screenDespotWhite >= 0) m_KeyLight.screenDespotWhite = screenDespotWhite;
        }
        //------------------------------------------------------
        public void SetKeylightSpillSupperssion(float spillSuppression = -1.0f, float spillTolerance = -1.0f, float spillDesaturate = -1.0f, float spillRange = -1.0f, float spillColorCorrection = -1.0f, float lumaCorrection = -1.0f)
        {
            if (m_KeyLight == null) return;
            if (spillSuppression >= 0) m_KeyLight.spillSuppression = spillSuppression;
            if (spillTolerance >= 0) m_KeyLight.spillTolerance = spillTolerance;
            if (spillDesaturate >= 0) m_KeyLight.spillDesaturate = spillDesaturate;
            if (spillRange >= 0) m_KeyLight.spillRange = spillRange;
            if (spillColorCorrection >= 0) m_KeyLight.spillColorCorrection = spillColorCorrection;
            if (lumaCorrection >= 0) m_KeyLight.lumaCorrection = lumaCorrection;
        }
        //------------------------------------------------------
        public void ResetDefaultKeylight()
        {
            if (m_KeyLight == null) return;
            m_KeyLight.ResetToDefaults();
        }
        //------------------------------------------------------
        void UpdateMaterialParams()
        {
            if (material == null) return;
            CheckCloneNewMaterial();
            material.SetColor(_ColorId, erasureColor);
            material.SetFloat(_ColorCutoff, colorCutoff);
            material.SetFloat(_ColorFeathering, colorFeathering);
            material.SetFloat(_MaskFeathering, maskFeathering);
            material.SetFloat(_Despill, despill);
            material.SetFloat(_DespillLuminanceAdd, despillLuminanceAdd);
        }
        //------------------------------------------------------
        void UpdateLastMaterialParams()
        {
            if (material == null) return;
            CheckCloneNewMaterial();
            material.SetColor(_ColorId, m_LastEraserParam.erasureColor);
            material.SetFloat(_ColorCutoff, m_LastEraserParam.colorCutoff);
            material.SetFloat(_ColorFeathering, m_LastEraserParam.colorFeathering);
            material.SetFloat(_MaskFeathering, m_LastEraserParam.maskFeathering);
            material.SetFloat(_Despill, m_LastEraserParam.despill);
            material.SetFloat(_DespillLuminanceAdd, m_LastEraserParam.despillLuminanceAdd);
        }
        //------------------------------------------------------
        void BackupLastVideo()
        {
            m_bKeepEndRePlay = false;
            if (m_LastVideoPlayer != null)
            {
                if (m_LastVideoPlayer != m_VideoPlayer)
                    VideoController.StopVideo(m_LastVideoPlayer);
            }
            m_LastVideoPlayer = m_VideoPlayer;
            m_VideoPlayer = null;
        }
        //------------------------------------------------------
        public void SetEdgeCliper(float top, float bottom, float left, float right, float fade)
        {
            edgeCliper = new Vector4(left, top, right, bottom);
            edgeClipFade = fade;
            if (material == null) return;
            if(top !=0 || bottom!=0 || left!=0 || right!=0)
                CheckCloneNewMaterial();
            material.SetVector(_EdgeCliper, edgeCliper);
            material.SetFloat(_EdgeFeather, edgeClipFade);
        }
        //------------------------------------------------------
        private void RefreshDirtyMaterial()
        {
            if (bErasure && useCustomParams && !bKeylightErasure)
            {
                UpdateMaterialParams();
            }

            if (bErasure && !bKeylightErasure)
            {
                UpdateMaterialParams();
                this.color = erasureColor;
                if(material) material.EnableKeyword("ERASURE_COLOR");
            }
            else if (bKeylightErasure)
            {
                CheckCloneNewMaterial();
                var color = Color.white;
                color.a = erasureColor.a;
                this.color  = color;
                if(material) material.DisableKeyword("ERASURE_COLOR");
            }
            else
            {
                if(material) material.DisableKeyword("ERASURE_COLOR"); 
            }
            SetEdgeCliper(this.edgeCliper.x, this.edgeCliper.y, this.edgeCliper.z, this.edgeCliper.w, this.edgeClipFade);
        }
        //------------------------------------------------------
        bool DelayPlay()
        {
            m_bKeepEndFrameTrigger = false;
            RefreshDirtyMaterial();
            if (string.IsNullOrEmpty(this.m_strUrl)) return false;
            if (m_VideoPlayer != null)
            {
                if (m_LastVideoPlayer != null)
                {
                    if (m_LastVideoPlayer.GetVideoPath().CompareTo(this.m_strUrl) == 0 &&
                        m_LastVideoPlayer.GetVideoPath().CompareTo(m_VideoPlayer.GetVideoPath()) != 0)
                    {
                        string shaderMarc = VideoController.GetMaterialDefines();
                        if (material && !string.IsNullOrEmpty(shaderMarc))
                            material.EnableKeyword(shaderMarc);

                        var temp = m_VideoPlayer;
                        m_VideoPlayer = m_LastVideoPlayer;
                        m_LastVideoPlayer = temp;
                        this.SetVerticesDirty();

                        if (m_bKeepEndRePlay) m_VideoPlayer.Seek(0);
                        m_bKeepEndRePlay = false;
                        if (m_VideoPlayer.IsPaused()) m_VideoPlayer.Play();
                        return true;
                    }
                }
                if (m_VideoPlayer.GetVideoPath().CompareTo(this.m_strUrl) == 0)
                {
                    string shaderMarc = VideoController.GetMaterialDefines();
                    if (material && !string.IsNullOrEmpty(shaderMarc))
                        material.EnableKeyword(shaderMarc);
                    this.SetVerticesDirty();
                    if (m_bKeepEndRePlay) m_VideoPlayer.Seek(0);
                    if (m_VideoPlayer.IsPaused()) m_VideoPlayer.Play();
                    m_bKeepEndRePlay = false;
                    return true;
                }
                BackupLastVideo();
                //     VideoController.StopVideo(m_VideoPlayer);
            }
            else
            {
                if(m_LastVideoPlayer!=null)
                {
                    if (m_LastVideoPlayer.GetVideoPath().CompareTo(this.m_strUrl) == 0)
                    {
                        string shaderMarc = VideoController.GetMaterialDefines();
                        if (material && !string.IsNullOrEmpty(shaderMarc))
                            material.EnableKeyword(shaderMarc);

                        var temp = m_VideoPlayer;
                        m_VideoPlayer = m_LastVideoPlayer;
                        m_LastVideoPlayer = temp;
                        this.SetVerticesDirty();

                        if (m_bKeepEndRePlay) m_VideoPlayer.Seek(0);
                        m_bKeepEndRePlay = false;
                        if (m_VideoPlayer.IsPaused()) m_VideoPlayer.Play();
                        return true;
                    }
                }
            }
            m_bKeepEndRePlay = false;
            m_TriggerEventFlags = 0;
            m_fAlphaTime = ALPHA_TIME;
            m_fKeepLogicPlayTime = 0;
            m_fDelayPlay = -1.0f;

            if (m_VideoPlayer == null)
            {
#if UNITY_EDITOR
                if (Application.isPlaying)
                    erasureColor.a = 0.0f;
#else
                erasureColor.a = 0.0f;
#endif
            }

            //! 如果之前的视频有缓存，且有画面，则不淡入
            if(m_LastVideoPlayer!=null && m_LastVideoPlayer.GetTextureFrameCount()>2 && m_LastVideoPlayer.GetTexture() != null)
            {
                erasureColor.a = 1.0f;
            }

            if (m_fFadeInAlpha > 0)
                erasureColor.a = m_fFadeInAlpha;

            this.color = erasureColor;
            if (defaultShow)
            {
                var color = defaultShow.color;
                color.a = 1.0f*m_fAlphaFade;
                defaultShow.color = color;
            }
            this.SetAllDirty();
            string shaderDefines = VideoController.GetMaterialDefines();
            if (material && !string.IsNullOrEmpty(shaderDefines))
                material.EnableKeyword(shaderDefines);
            m_VideoPlayer = VideoController.PlayVideo(this.m_strUrl, this.m_bPersistentPath);
            if (m_VideoPlayer != null)
            {
                if(m_fVideoDuration>0) m_VideoPlayer.SetDuration(m_fVideoDuration);
                m_VideoPlayer.SetLooping(this.m_bVideoLoop);
                m_VideoPlayer.AddListener(OnMediaListener);
                return true;
            }
            else
            {
                if (m_pCallback != null) m_pCallback(MediaPlayerEvent.EventType.Error);
                m_pCallback = null;
                m_pEventCallback = null;
            }
            return false;
        }
        //------------------------------------------------------
        void OnMediaListener(IMediaPlayer player, MediaPlayerEvent.EventType type, ErrorCode code)
        {
            if (m_VideoPlayer != player) return;
            if (m_pCallback != null) m_pCallback(type);
            if (type == MediaPlayerEvent.EventType.Closing ||
                type == MediaPlayerEvent.EventType.FinishedPlaying ||
                type == MediaPlayerEvent.EventType.Error)
            {
                m_VideoPlayer = null;
                m_fKeepLogicPlayTime = 0;
                //! 视频解析错误，隐藏视频画面
                var col = this.color;
                col.a = 0;
                this.color = col;
                if (this.defaultShow != null)
                {
                    col = this.defaultShow.color;
                    col.a = 1;
                    this.defaultShow.color = col;
                }
            }
            else if (type == MediaPlayerEvent.EventType.Started)
                m_fKeepLogicPlayTime = 0;
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
            if (m_VideoPlayer != null && m_VideoPlayer.IsPlaying() && m_VideoPlayer.GetTextureFrameCount() > 2 && m_VideoPlayer.GetTexture() != null)
            {
                if (m_LastVideoPlayer != null)
                {
                    VideoController.StopVideo(m_LastVideoPlayer);
                    m_LastVideoPlayer = null;
                    m_LastEraserParam.Clear();
                    RefreshDirtyMaterial();
                }
            }
            if (m_LastVideoPlayer != null)
            {
                SyncTexture(m_LastVideoPlayer,1, true);
                return;
            }
            if (m_VideoPlayer == null) return;
            SyncTexture(m_VideoPlayer);

            //! 记录当前播放视频的抠色参数
            BackupEraserParam();
            //    if (m_VideoPlayer.IsFinished())
            //       Hide();
        }
        //------------------------------------------------------
        public void SyncTexture(IMediaPlayer player, float alphaFactor = 1, bool bLast  =false)
        {
            if (bLast)
            {
                if (m_LastEraserParam.eraserType == 2)
                {
                    //! 上一个视频使用keylight抠色，确保禁用颜色抠色keyword，避免shader对已处理的keylight输出再次抠色
                    if (material) material.DisableKeyword("ERASURE_COLOR");
                    this.texture = m_KeyLight.GetCurrentOutput();
                }
                else if (m_LastEraserParam.eraserType == 1)
                {
                    UpdateLastMaterialParams();
                    if (material) material.EnableKeyword("ERASURE_COLOR");
                    this.texture = player.GetTexture();
                }
                else
                {
                    //! 上一个视频无抠色，确保禁用颜色抠色keyword
                    if (material) material.DisableKeyword("ERASURE_COLOR");
                    this.texture = player.GetTexture();
                }
            }
            else
            {
                if (bKeylightErasure)
                {
                    m_KeyLight.Initialize();
                    m_KeyLight.Process(player.GetTexture(), this);
                }
                else
                {
                    this.texture = player.GetTexture();
                }
            }

            if (this.texture == null || player.GetTextureFrameCount() <= 2)
            {
                erasureColor.a = 0;
                this.color = erasureColor;
            }
            else
            {
                if (m_fAlphaTime > 0)
                {
                    m_fAlphaTime -= Time.unscaledDeltaTime;
                    float factor = Mathf.Clamp01((1 - m_fAlphaTime / ALPHA_TIME));
                    if (defaultShow)
                    {
                        var color = defaultShow.color;
                        if (color.a > 0)
                        {
                            color.a = Mathf.Lerp(color.a* m_fAlphaFade, 0, factor);
                            defaultShow.color = color;
                        }
                    }
                    erasureColor.a = Mathf.Lerp(erasureColor.a, player.GetAlhpa(true) * alphaFactor, factor);
                    //! 显示上一个视频时，使用上一个视频的抠色颜色作为顶点色，避免新旧视频抠色参数不同导致闪帧
                    Color colorTemp;
                    if (bLast)
                    {
                        if (m_LastEraserParam.eraserType == 1)
                            colorTemp = new Color(m_LastEraserParam.erasureColor.r, m_LastEraserParam.erasureColor.g, m_LastEraserParam.erasureColor.b, erasureColor.a);
                        else
                            colorTemp = new Color(1f, 1f, 1f, erasureColor.a);
                    }
                    else
                    {
                        colorTemp = erasureColor;
                    }
                    colorTemp.a *= m_fAlphaFade;
                    this.color = colorTemp;
                }
                else if (erasureColor.a <= 0)
                {
                    m_fAlphaTime = 0.1f;
                }
                else
                {
                    //! 显示上一个视频时，保持正确的顶点色RGB
                    if (bLast)
                    {
                        Color color;
                        if (m_LastEraserParam.eraserType == 1)
                            color = new Color(m_LastEraserParam.erasureColor.r, m_LastEraserParam.erasureColor.g, m_LastEraserParam.erasureColor.b, erasureColor.a * m_fAlphaFade);
                        else
                            color = new Color(1f, 1f, 1f, erasureColor.a * m_fAlphaFade);
                        this.color = color;
                    }
                    else
                    {
                        var color = this.color;
                        color.a = erasureColor.a* m_fAlphaFade;
                        this.color = color;
                    }
                }
            }

            if (this.autoNativeSize && this.texture)
            {
                this.SetNativeSize();
            }
           // Debug.Log("Video:" + m_VideoPlayer.GetCurrentTimeMs() + "    dur:" + m_VideoPlayer.GetDurationMs() + "   logic:" + m_fKeepLogicPlayTime);
            if(this.texture && this.m_VideoPlayer.IsPlaying())
                m_fKeepLogicPlayTime += Time.deltaTime;
            if (!m_bKeepEndFrameTrigger && this.keepEndFrame && !this.bLoop && m_LastVideoPlayer==null)
            {
                var duration = this.m_VideoPlayer.GetDurationMs();
                var curTime = m_bUseLogicTimeCheck?(m_fKeepLogicPlayTime * 1000):this.m_VideoPlayer.GetCurrentTimeMs();
                if (duration>0 && curTime >= (duration - m_fKeepEndErrorTime))
                {
                    m_bKeepEndFrameTrigger = true;
                    //! 保留最后一帧画面：暂停播放器，使纹理停留在最后一帧
                    this.m_VideoPlayer.Pause();
#if USE_DEBUG
                    Debug.LogFormat("{0} 视频停止到最后一帧", m_strUrl);
#endif
                    if (m_pCallback != null) m_pCallback(MediaPlayerEvent.EventType.KeepEndFrame);
                }
            }

            if (m_pEventCallback!=null && this.triggerEvents != null && this.triggerEvents.Length > 0)
            {
                var duration = this.m_VideoPlayer.GetDurationMs();
                if(duration>0)
                {
                    //float curTime = this.m_VideoPlayer.GetCurrentTimeMs();
					var curTime = m_bUseLogicTimeCheck?(m_fKeepLogicPlayTime * 1000):this.m_VideoPlayer.GetCurrentTimeMs();
                    if(curTime<= Time.fixedDeltaTime*1000)
                    {
                        m_TriggerEventFlags = 0;
                    }
                    for (int i = 0; i < this.triggerEvents.Length; i++)
                    {
                        bool bTrigged = (m_TriggerEventFlags & (1u << i)) != 0;
                        if (bTrigged) continue;
                        var eventData = this.triggerEvents[i];
                        var time = eventData.time * 1000.0f;
                        if(curTime >= time)
                        {
                            m_TriggerEventFlags |= (1u << i);
                            if (string.IsNullOrEmpty(eventData.strValue)) continue;
                            if (m_pEventCallback == null) break;
                            m_pEventCallback(eventData.strValue);
#if USE_DEBUG
                            Debug.LogFormat("视频事件:{0}", (eventData.strValue == null?"":eventData.strValue));
#endif
                        }
                    }
                }    
            }
        }
    }
#if UNITY_EDITOR
    [UnityEditor.CanEditMultipleObjects]
    [UnityEditor.CustomEditor(typeof(UIVideo))]
    public class UIVideoEditor : UnityEditor.Editor
    {
        public override void OnInspectorGUI()
        {
            serializedObject.Update();
            UIVideo video = target as UIVideo;

            // 显示基本信息
            EditorGUILayout.LabelField("视频路径", video.url ?? "(无)");
            string erasureInfo = "无";
            if (video.bErasure && !video.bKeylightErasure) erasureInfo = "常规抠色";
            else if (video.bKeylightErasure) erasureInfo = "Keylight抠色";
            EditorGUILayout.LabelField("抠色方式", erasureInfo);
            EditorGUILayout.LabelField("循环", video.bLoop ? "是" : "否");
            EditorGUILayout.ColorField("erasureColor", video.erasureColor);

            var preUsePop = this.serializedObject.FindProperty("preUse");
            if(preUsePop!=null)
            {
                EditorGUILayout.PropertyField(preUsePop, new GUIContent("预使用"));
            }

            if (GUILayout.Button("一键不抠色"))
            {
                video.erasureColor = Color.white;
                video.DisableErasure();
            }

            var mat = EditorGUILayout.ObjectField("材质", video.material, typeof(Material), false);
            if (mat) video.material = mat as Material;
            else video.material = null;

            EditorGUILayout.Space(5);

            // 打开编辑器按钮
            if (GUILayout.Button("打开视频编辑器", GUILayout.Height(30)))
            {
                UIVideoEditorWindow.OpenWithUIVideo(video);
            }

            serializedObject.ApplyModifiedProperties();
        }

        //-----------------------------------------------------
        [MenuItem("GameObject/UI/Video", false, 0)]
        static public void AddVideo(MenuCommand menuCommand)
        {
            GameObject panelRoot = new GameObject("Video");
            var videoComp = panelRoot.AddComponent<UIVideo>();
            if (videoComp.bErasure && !videoComp.bKeylightErasure)
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
            if (parent) panelRoot.transform.SetParent(parent.transform);
            RectTransform rectTransform = panelRoot.GetComponent<RectTransform>();
            if (rectTransform) rectTransform.sizeDelta = new Vector2(300, 300);

            Selection.activeGameObject = panelRoot;
        }
    }
#endif
}
