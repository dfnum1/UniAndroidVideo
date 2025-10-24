/********************************************************************
生成日期:	1:11:2020 13:16
类    名: 	AVProVideoPlayer
作    者:	HappLI
描    述:	基于AVPro 插件的视频播放组件
*********************************************************************/
//#define USE_AVPRO
#if USE_AVPRO
using UnityEngine;
using UnityEngine.Video;
using System.IO;

namespace GameApp.Media
{
    public class AVProVideoPlayer : MonoBehaviour, IMediaPlayer
    {
        public string m_VideoPath;

        public bool m_Loop = false;

        private bool m_bReadyStarted = false;
        private bool m_bPrepared = false;

        protected int m_iPlayerIndex = -1;

        // State
        private bool m_VideoOpened = false;

        private float m_DurationMs = 0;

        protected string _playerDescription = string.Empty;
        protected ErrorCode _lastError = ErrorCode.None;
        protected FilterMode _defaultTextureFilterMode = FilterMode.Bilinear;
        protected TextureWrapMode _defaultTextureWrapMode = TextureWrapMode.Clamp;
        protected int _defaultTextureAnisoLevel = 1;

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

        private RenderHeads.Media.AVProVideo.MediaPlayer m_VideoPlayer = null;
        AnimationCurve m_AlphaCurve;        
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
        void OnDestroy()
        {
            Dispose();
        }
        //------------------------------------------------------
        public bool OpenVideo(UnityEngine.Video.VideoClip videoClip)
        {
            CloseVideo();
            Debug.LogWarning("Unsupport");
            return false;
        }
        //------------------------------------------------------
        public bool OpenVideoFromFile(string path, bool bPersistentRoot = false, bool bAutoPlay = true, long offset = 0, string httpHeaderJson = null)
        {
            CloseVideo();
            if (m_VideoPlayer == null)
            {
                m_VideoPlayer = GetComponent<RenderHeads.Media.AVProVideo.MediaPlayer>();
                if (m_VideoPlayer == null)
                    m_VideoPlayer = gameObject.AddComponent<RenderHeads.Media.AVProVideo.MediaPlayer>();

                m_VideoPlayer.Events.AddListener(OnMediaPlayerCallback);
            }
            if (m_VideoPlayer == null) return false;

            m_VideoPath = path;

            m_bPrepared = false;
            m_bReadyStarted = false;
            m_VideoOpened = true;
            m_VideoPlayer.OpenMedia(bPersistentRoot ? MediaPathType.RelativeToPersistentDataFolder : MediaPathType.RelativeToStreamingAssetsFolder, path, bAutoPlay);
            SetLooping(m_Loop);

#if UNITY_EDITOR
            if (!Application.isPlaying)
            {
                UnityEditor.EditorApplication.update += OnEditorUpdate;
            }
#endif
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

            _lastError = ErrorCode.None;

            if (m_VideoPlayer)
            {
                m_VideoPlayer.Stop();
                GameObject.Destroy(m_VideoPlayer);
                m_VideoPlayer = null;
            }
            m_AlphaCurve = null;
        }
        //------------------------------------------------------
        void OnMediaPlayerCallback(RenderHeads.Media.AVProVideo.MediaPlayer player, RenderHeads.Media.AVProVideo.MediaPlayerEvent.EventType eventType, RenderHeads.Media.AVProVideo.ErrorCode code)
        {
            switch(eventType)
            {
                case RenderHeads.Media.AVProVideo.MediaPlayerEvent.EventType.MetaDataReady:
                    m_bPrepared = true;
                    m_DurationMs = (float)(player.Info.GetDuration() * 1000);
                    if (m_events != null) m_events.Invoke(this, MediaPlayerEvent.EventType.MetaDataReady, (ErrorCode)eventType);
                    break;
                case RenderHeads.Media.AVProVideo.MediaPlayerEvent.EventType.ReadyToPlay:
                    m_bReadyStarted = true;
                    if (m_events != null) m_events.Invoke(this, MediaPlayerEvent.EventType.ReadyToPlay, (ErrorCode)eventType);
                    break;
                case RenderHeads.Media.AVProVideo.MediaPlayerEvent.EventType.Started:
                    m_bReadyStarted = true;
                    m_DurationMs = (float)(player.Info.GetDuration() * 1000);
                    if (m_events != null) m_events.Invoke(this, MediaPlayerEvent.EventType.Started, (ErrorCode)eventType);
                    break;
                case RenderHeads.Media.AVProVideo.MediaPlayerEvent.EventType.FirstFrameReady:
                    if (m_events != null) m_events.Invoke(this, MediaPlayerEvent.EventType.FirstFrameReady, (ErrorCode)eventType);
                    break;
                case RenderHeads.Media.AVProVideo.MediaPlayerEvent.EventType.FinishedPlaying:
                    if (!player.Loop)
                    {
                        m_bPrepared = false;
                        m_bReadyStarted = false;
                        if (m_events != null) m_events.Invoke(this, MediaPlayerEvent.EventType.FinishedPlaying, (ErrorCode)eventType);
                        CloseVideo();
                    }
                    else
                    {
                    }
                    break;
                case RenderHeads.Media.AVProVideo.MediaPlayerEvent.EventType.Error:
                    _lastError = ErrorCode.LoadFailed;
                    Debug.LogError("play video error:" + eventType);
                    CloseVideo();
                    if (m_events != null) m_events.Invoke(this, MediaPlayerEvent.EventType.Error, (ErrorCode)eventType);
                    break;
            }
        }
        //-------------------------------------------------
        void OnPlayerEnded(VideoPlayer source)
        {

        }
        //-------------------------------------------------
        public void SetLooping(bool bLooping)
        {
            if (m_VideoPlayer)
            {
                m_VideoPlayer.Loop = bLooping;
            }
        }
        //-------------------------------------------------
        public bool IsLooping()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                result = m_VideoPlayer.Loop;
            }
            return result;
        }
        //-------------------------------------------------
        public bool HasVideo()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                return m_VideoPlayer.Info.HasVideo();
            }
            return result;
        }
        //-------------------------------------------------
        public bool HasAudio()
        {
            bool result = false;
            if (m_VideoPlayer)
            {
                return m_VideoPlayer.Info.HasAudio();
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
                    result = true;
                }
            }
            return result;
        }
        //-------------------------------------------------
        public bool CanPlay()
        {
            bool result = false;

            if (m_VideoPlayer && m_VideoPlayer.Control!=null)
            {
                result = m_VideoPlayer.Control.CanPlay();
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
            if (m_VideoPlayer && m_VideoPlayer.Control!=null)
            {
                m_VideoPlayer.Control.Seek((double)(timeMs * 0.001f));
            }
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
            if (m_VideoPlayer && m_VideoPlayer.Control!=null)
            {
                result = (float)(m_VideoPlayer.Control.GetCurrentTime()*1000);
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
            if (m_VideoPlayer == null) return 0;
            return m_VideoPlayer.Info.GetVideoWidth();
        }
        //-------------------------------------------------
        public int GetVideoHeight()
        {
            if (m_VideoPlayer == null) return 0;
            return m_VideoPlayer.Info.GetVideoHeight();
        }
        //-------------------------------------------------
        public void SetPlaybackRate(float rate)
        {
            if (m_VideoPlayer == null || m_VideoPlayer.Control == null) return;
            m_VideoPlayer.Control.SetPlaybackRate(rate);
        }
        //-------------------------------------------------
        public float GetPlaybackRate()
        {
            float result = 0.0f;
            if (m_VideoPlayer && m_VideoPlayer.Control!=null)
            {
                result = m_VideoPlayer.Control.GetPlaybackRate();
                if (result <= 0) result = 30;

            }
            return result;
        }
        //-------------------------------------------------
        public bool IsPlaying()
        {
            bool result = false;
            if (m_VideoPlayer && m_VideoPlayer.Control!=null)
            {
                result = m_VideoPlayer.Control.IsPlaying();
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
            if (m_VideoPlayer && m_VideoPlayer.Control != null)
            {
                result = m_VideoPlayer.Control.IsPaused();
            }
            return result;
        }
        //-------------------------------------------------
        public bool IsFinished()
        {
            bool result = false;
            if (m_VideoPlayer && m_VideoPlayer.Control != null)
            {
                result = m_VideoPlayer.Control.IsFinished();
            }
            return result;
        }
        //-------------------------------------------------
        public Texture GetTexture(int index = 0)
        {
            Texture result = null;
            if (m_VideoPlayer && IsPlaying() && m_VideoPlayer.TextureProducer!=null && m_VideoPlayer.TextureProducer.GetTexture()!=null)
            {
                var resamplerTex = m_VideoPlayer.FrameResampler == null || m_VideoPlayer.FrameResampler.OutputTexture == null ? null : m_VideoPlayer.FrameResampler.OutputTexture[index];
                result = m_VideoPlayer.UseResampler ? resamplerTex : m_VideoPlayer.TextureProducer.GetTexture();
            }
            return result;
        }
        //-------------------------------------------------
        public int GetTextureFrameCount()
        {
            int result = 0;
            if (m_VideoPlayer && m_VideoPlayer.Info!=null)
            {
                result = m_VideoPlayer.Info.GetDurationFrames();
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
                m_VideoPlayer.AudioVolume = volume;
            }
        }
        //-------------------------------------------------
        public float GetVolume()
        {
            float result = 0.0f;
            if (m_VideoPlayer)
            {
                return m_VideoPlayer.AudioVolume;
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
#if UNITY_EDITOR
            UnityEditor.EditorApplication.update -= OnEditorUpdate;
#endif
        }
        //-------------------------------------------------
#if UNITY_EDITOR
        void OnEditorUpdate()
        {
            if (m_VideoPlayer != null) m_VideoPlayer.EditorUpdate();
        }
#endif
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
#endif