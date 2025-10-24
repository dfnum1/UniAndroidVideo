/********************************************************************
生成日期:	1:11:2020 13:16
类    名: 	UnityMediaPlayer
作    者:	HappLI
描    述:	unity 自带视频播放组件,用在出window、android 系统外
*********************************************************************/
using UnityEngine;
using System.Runtime.InteropServices;
using System.Collections;
using UnityEngine.Video;
using System;

namespace GameApp.Media
{
    public class UnityMediaPlayer : MonoBehaviour, IMediaPlayer
    {
        public string m_VideoPath;

        public bool m_Loop = false;

        [SerializeField]
        private MediaPlayerEvent m_events;

        public MediaPlayerEvent Events
        {
            get
            {
                if (m_events == null)
                {
                    m_events = new MediaPlayerEvent();
                }
                return m_events;
            }
        }

        private RenderTexture m_Texture;

        private float m_DurationMs = 0.0f;
        private int m_Width = 0;
        private int m_Height = 0;
        private bool m_bReadyStarted = false;
        private bool m_bPrepared = false;

        protected int m_iPlayerIndex = -1;

        // State
        private bool m_VideoOpened = false;
        private bool m_AutoStartTriggered = false;

        // Event state
        private bool m_EventFired_ReadyToPlay = false;
        private bool m_EventFired_Started = false;
        private bool m_EventFired_FirstFrameReady = false;
        private bool m_EventFired_FinishedPlaying = false;
        private bool m_EventFired_MetaDataReady = false;

        protected string _playerDescription = string.Empty;
        protected ErrorCode _lastError = ErrorCode.None;
        protected FilterMode _defaultTextureFilterMode = FilterMode.Bilinear;
        protected TextureWrapMode _defaultTextureWrapMode = TextureWrapMode.Clamp;
        protected int _defaultTextureAnisoLevel = 1;

        private VideoPlayer m_VideoPlayer = null;
        AnimationCurve m_AlphaCurve;

        //-------------------------------------------------
        void OnPlayerError(VideoPlayer source, string message)
        {
            _lastError = ErrorCode.LoadFailed;
            UpdateErrors();
            Debug.LogError(message);
            m_EventFired_FinishedPlaying = FireEventIfPossible(MediaPlayerEvent.EventType.FinishedPlaying, m_EventFired_FinishedPlaying);
            CloseVideo();
        }
        //-------------------------------------------------
        void OnPlayerPrepareCompleted(VideoPlayer source)
        {
            m_bPrepared = true;
            m_DurationMs = (float)source.length*1000;
            m_Width = (int)source.width;
            m_Height = (int)source.height;

            if (m_Width > 0 && m_Height > 0)
            {
                m_Texture = RenderTexture.GetTemporary(m_Width, m_Height, 0, RenderTextureFormat.ARGB32);
                if (m_VideoPlayer.targetTexture == null)
                    m_VideoPlayer.targetTexture = m_Texture;
            }
            m_EventFired_MetaDataReady = FireEventIfPossible(MediaPlayerEvent.EventType.MetaDataReady, m_EventFired_MetaDataReady);
            m_EventFired_ReadyToPlay = FireEventIfPossible(MediaPlayerEvent.EventType.ReadyToPlay, m_EventFired_ReadyToPlay);
        }
        //-------------------------------------------------
        void OnPlayerStarted(VideoPlayer source)
        {
            m_bReadyStarted = true;

            m_DurationMs = (float)source.length*1000;
            if (m_Width <0 || m_Height<0)
            {
                m_Width = (int)source.width;
                m_Height = (int)source.height;

                if (m_Width > 0 && m_Height > 0)
                {
                    m_Texture = RenderTexture.GetTemporary(m_Width, m_Height, 0, RenderTextureFormat.ARGB32);
                    if (m_VideoPlayer.targetTexture == null)
                        m_VideoPlayer.targetTexture = m_Texture;
                }
            }
            m_EventFired_Started = FireEventIfPossible(MediaPlayerEvent.EventType.Started, m_EventFired_Started);
            m_EventFired_FirstFrameReady = FireEventIfPossible(MediaPlayerEvent.EventType.FirstFrameReady, m_EventFired_FirstFrameReady);
        }
        //-------------------------------------------------
        void OnPlayerEnded(VideoPlayer source)
        {
            if(!source.isLooping)
            {
                m_bPrepared = false;
                m_bReadyStarted = false;
                m_EventFired_FinishedPlaying = FireEventIfPossible(MediaPlayerEvent.EventType.FinishedPlaying, m_EventFired_FinishedPlaying);
                CloseVideo();
            }
            else
            {
                source.frame = 1;
            }
        }
        //------------------------------------------------------
        public void SetCurveAlpha(AnimationCurve alpha)
        {
            m_AlphaCurve = alpha;
        }
        //------------------------------------------------------
        public float GetAlhpa(bool normalTime)
        {
            float alhpa = 1;
            if (m_VideoPlayer && m_AlphaCurve != null && IsPlaying())
            {
                if(normalTime)
                {
                    if(GetDurationMs()>0)
                        alhpa = m_AlphaCurve.Evaluate(GetCurrentTimeMs()/GetDurationMs());
                }
                else
                    alhpa = m_AlphaCurve.Evaluate(GetCurrentTimeMs() * 0.001f);
            }
            return alhpa;
        }
        //------------------------------------------------------
        public void Enable(bool bEnable)
        {
            this.enabled = bEnable;
        }
        //------------------------------------------------------
        public bool IsEnable()
        {
            return this.enabled;
        }
        //------------------------------------------------------
        public string GetVideoPath()
        {
            return m_VideoPath;
        }
        //------------------------------------------------------
        private void OnDestroy()
        {
            Dispose();
        }
        //------------------------------------------------------
        public bool OpenVideo(VideoClip clip)
        {
            CloseVideo();
            if (m_VideoPlayer == null)
            {
                m_VideoPlayer = GetComponent<VideoPlayer>();
                if (m_VideoPlayer == null)
                    m_VideoPlayer = gameObject.AddComponent<VideoPlayer>();
                m_VideoPlayer.source = VideoSource.Url;
                m_VideoPlayer.errorReceived += OnPlayerError;
                m_VideoPlayer.prepareCompleted += OnPlayerPrepareCompleted;
                m_VideoPlayer.loopPointReached += OnPlayerEnded;
                m_VideoPlayer.started += OnPlayerStarted;
            }
            if (m_VideoPlayer == null) return false;

            m_VideoPath = clip.name;

            m_bPrepared = false;
            m_bReadyStarted = false;
            m_VideoOpened = true;
            m_AutoStartTriggered = false;
            m_EventFired_MetaDataReady = false;
            m_EventFired_ReadyToPlay = false;
            m_EventFired_Started = false;
            m_EventFired_FirstFrameReady = false;

            m_VideoPlayer.audioOutputMode = VideoAudioOutputMode.Direct;
            m_VideoPlayer.source = VideoSource.VideoClip;
            m_VideoPlayer.clip = clip;
            SetLooping(m_Loop);
            Play();

            return true;
        }
        //------------------------------------------------------
        public bool OpenVideoFromFile(string path, bool bPersistentRoot = false, bool bAutoPlay = true, long offset = 0, string httpHeaderJson = null)
        {
            CloseVideo();
            if (m_VideoPlayer == null)
            {
                m_VideoPlayer = GetComponent<VideoPlayer>();
                if (m_VideoPlayer == null)
                    m_VideoPlayer = gameObject.AddComponent<VideoPlayer>();
                m_VideoPlayer.source = VideoSource.Url;
                m_VideoPlayer.errorReceived += OnPlayerError;
                m_VideoPlayer.prepareCompleted += OnPlayerPrepareCompleted;
                m_VideoPlayer.loopPointReached += OnPlayerEnded;
                m_VideoPlayer.started += OnPlayerStarted;
                m_VideoPlayer.playOnAwake = false;
            }
            if (m_VideoPlayer == null) return false;

            m_VideoPath = path;

            m_bPrepared = false;
            m_bReadyStarted = false;
            m_VideoOpened = true;
            m_AutoStartTriggered = false;
            m_EventFired_MetaDataReady = false;
            m_EventFired_ReadyToPlay = false;
            m_EventFired_Started = false;
            m_EventFired_FirstFrameReady = false;


            if (bPersistentRoot)
                path = Application.persistentDataPath + "/" + path;
            else
                path = Application.streamingAssetsPath + "/" + path;
#if UNITY_EDITOR
            path = "file:///" + path;
#elif UNITY_EDITOR_OSX
             path = "file://" + path;
#endif
            m_VideoPlayer.audioOutputMode = VideoAudioOutputMode.Direct;
            m_VideoPlayer.source = VideoSource.Url;
            m_VideoPlayer.url = path;
            SetLooping(m_Loop);
            if(bAutoPlay) Play();

            return true;
        }
        //-------------------------------------------------
        public void CloseVideo()
        {
            if(m_VideoOpened)
            {
                if (m_events != null)
                {
                    m_events.Invoke(this, MediaPlayerEvent.EventType.Closing, ErrorCode.None);
                }
            }
            m_VideoOpened = false;
            m_bReadyStarted = false;
            m_bPrepared = false;

            m_DurationMs = 0.0f;
            m_Width = 0;
            m_Height = 0;

            _lastError = ErrorCode.None;

            if (m_Texture)
                RenderTexture.ReleaseTemporary(m_Texture);
            m_Texture = null;
            if (m_VideoPlayer)
            {
                m_VideoPlayer.Stop();
                m_VideoPlayer.targetTexture = null;
                GameObject.Destroy(m_VideoPlayer);
                m_VideoPlayer = null;
            }
            m_AlphaCurve = null;
        }
        //-------------------------------------------------
        public void SetLooping(bool bLooping)
        {
            if (m_VideoPlayer)
            {
                m_VideoPlayer.isLooping = bLooping;
            }
        }
        //-------------------------------------------------
        public bool IsLooping()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                result = m_VideoPlayer.isLooping;
            }
            return result;
        }
        //-------------------------------------------------
        public bool HasVideo()
        {
            bool result = false;
            if (m_VideoPlayer && !string.IsNullOrEmpty(m_VideoPlayer.url) )
            {
                result = true;
            }
            return result;
        }
        //-------------------------------------------------
        public bool HasAudio()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                result = m_VideoPlayer.GetTargetAudioSource(0)!=null;
            }
            return result;
        }
        //-------------------------------------------------
        public bool HasMetaData()
        {
            bool result = false;
            if (m_DurationMs > 0.0f)
            {
                result = true;

                if (HasVideo())
                {
                    result = (m_Width > 0 && m_Height > 0);
                }
            }
            return result;
        }
        //-------------------------------------------------
        public bool CanPlay()
        {
            bool result = false;

            if (m_VideoPlayer && !string.IsNullOrEmpty(m_VideoPlayer.url) )
            {
                result = true;
            }
            return result;
        }
        //-------------------------------------------------
        public void Play()
        {
            if(m_VideoPlayer)
                m_VideoPlayer.Play();
        }
        //-------------------------------------------------
        public void Pause()
        {
            if (m_VideoPlayer)
                m_VideoPlayer.Pause();
        }
        //-------------------------------------------------
        public void Stop()
        {
            if (m_VideoPlayer)
                m_VideoPlayer.Stop();

        }
        //-------------------------------------------------
        public void Rewind()
        {
            Seek(0.0f);
        }
        //-------------------------------------------------
        public void Seek(float timeMs)
        {
            if (m_VideoPlayer)
                m_VideoPlayer.time = timeMs;

        }
        //-------------------------------------------------
        public void SeekFast(float timeMs)
        {
            Seek(timeMs);
        }
        //-------------------------------------------------
        public float GetCurrentTimeMs()
        {
            float result = 0.0f;
            if (m_VideoPlayer)
            {
                result = (float)(m_VideoPlayer.time * 1000);
            }
            return result;
        }
        //-------------------------------------------------
        public float GetCurrentFrameTimeMs()
        {
            float result = 0.0f;
            if (m_VideoPlayer)
            {
                result = GetTextureFrameCount();
            }
            return result;
        }
        //-------------------------------------------------
        public float GetDurationMs()
        {
            return m_DurationMs;
        }
        //-------------------------------------------------
        public int GetVideoWidth()
        {
            return m_Width;
        }
        //-------------------------------------------------
        public int GetVideoHeight()
        {
            return m_Height;
        }
        //-------------------------------------------------
        public void SetPlaybackRate(float rate)
        {

        }
        //-------------------------------------------------
        public float GetPlaybackRate()
        {
            float result = 0.0f;
            if (m_VideoPlayer)
            {
                result = m_VideoPlayer.frameRate;
                if (result <= 0) result = 30;

            }
            return result;
        }
        //-------------------------------------------------
        public bool IsPlaying()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                result = m_VideoPlayer.isPlaying || m_VideoPlayer.isPrepared;
            }
            return result;
        }
        //-------------------------------------------------
        public bool IsPrepared()
        {
            return m_bPrepared;
        }
        //-------------------------------------------------
        public bool IsPaused()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                result = m_VideoPlayer.isPaused;
            }
            return result;
        }
        //-------------------------------------------------
        public bool IsFinished()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                result = m_bReadyStarted && m_VideoPlayer.time >= m_VideoPlayer.length;
            }
            return result;
        }
        //-------------------------------------------------
        public Texture GetTexture(int index = 0)
        {
            Texture result = null;
            if (m_VideoPlayer && GetTextureFrameCount() >= 0 && IsPlaying())
            {
                result = m_VideoPlayer.texture;
            }
            return result;
        }
        //-------------------------------------------------
        public int GetTextureFrameCount()
        {
            int result = 0;
            if (m_VideoPlayer)
            {
                result = (int)m_VideoPlayer.frame;
            }
            return result;
        }
        //-------------------------------------------------
        public bool RequiresVerticalFlip()
        {
            return false;
        }
        //-------------------------------------------------
        public void SetVolume(float volume)
        {
            if (m_VideoPlayer)
            {
                m_VideoPlayer.SetDirectAudioVolume(0, volume);
            }
        }
        //-------------------------------------------------
        public float GetVolume()
        {
            float result = 0.0f;
            if (m_VideoPlayer)
            {
                result = m_VideoPlayer.GetDirectAudioVolume(0);
            }
            return result;
        }
        //-------------------------------------------------
        public void Update()
        {
            if (m_VideoPlayer != null)
            {
                m_DurationMs = (float)m_VideoPlayer.length*1000;
                if (m_VideoOpened && !m_AutoStartTriggered && CanPlay())
                {
                    m_AutoStartTriggered = true;
                }
            }
        }
        //-------------------------------------------------
        private void UpdateErrors()
        {
            if (ErrorCode.None != _lastError)
            {
                if (m_events != null)
                {
                    m_events.Invoke(this, MediaPlayerEvent.EventType.Error, _lastError);
                }
            }
        }
        //-------------------------------------------------
        private bool FireEventIfPossible(MediaPlayerEvent.EventType eventType, bool hasFired)
        {
            if (CanFireEvent(eventType, hasFired))
            {
                hasFired = true;
                m_events.Invoke(this, eventType, ErrorCode.None);
            }
            return hasFired;
        }
        //-------------------------------------------------
        private bool CanFireEvent(MediaPlayerEvent.EventType et, bool hasFired)
        {
            bool result = false;
            if (m_events != null && m_VideoPlayer && !hasFired)
            {
                switch (et)
                {
                    case MediaPlayerEvent.EventType.FinishedPlaying:
                        //Debug.Log(m_Control.GetCurrentTimeMs() + " " + m_Info.GetDurationMs());
                        result = (!IsLooping() && CanPlay() && IsFinished())
#if UNITY_EDITOR_WIN || UNITY_STANDALONE_WIN || UNITY_WSA
                            || (GetCurrentTimeMs() > GetDurationMs() && !IsLooping())
#endif
                            ;
                        break;
                    case MediaPlayerEvent.EventType.MetaDataReady:
                        result = (HasMetaData());
                        break;
                    case MediaPlayerEvent.EventType.FirstFrameReady:
                        result = (m_Texture != null && CanPlay() && GetTextureFrameCount() > 0);
                        break;
                    case MediaPlayerEvent.EventType.ReadyToPlay:
                        result = (!IsPlaying() && CanPlay());
                        break;
                    case MediaPlayerEvent.EventType.Started:
                        result = (IsPlaying());
                        break;
                }
            }
            return result;
        }
        //-------------------------------------------------
        public bool PlayerSupportsLinearColorSpace()
        {
            return false;
        }
        //-------------------------------------------------
        public void Dispose()
        {
            if (m_VideoPlayer)
            {
                GameObject.Destroy(m_VideoPlayer);
            }
            m_VideoPlayer = null;
            if (m_Texture != null)
            {
                RenderTexture.ReleaseTemporary(m_Texture);
                m_Texture = null;
            }
        }
        //-------------------------------------------------
        public void RemoveListener(UnityEngine.Events.UnityAction<IMediaPlayer, MediaPlayerEvent.EventType, ErrorCode> pEvent)
        {
            Events.RemoveListener(pEvent);
        }
        //-------------------------------------------------
        public void AddListener(UnityEngine.Events.UnityAction<IMediaPlayer, MediaPlayerEvent.EventType, ErrorCode> pEvent)
        {
            Events.AddListener(pEvent);
        }
        //-------------------------------------------------
        public void SetFlipY(bool bFlipY)
        {
          
        }
        //-------------------------------------------------
        public bool IsFlipY()
        {
            return false;
        }
    }
}