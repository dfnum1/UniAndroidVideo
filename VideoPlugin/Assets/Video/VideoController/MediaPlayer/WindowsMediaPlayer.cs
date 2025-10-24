/********************************************************************
生成日期:	1:11:2020 13:16
类    名: 	WindowsMediaPlayer
作    者:	HappLI
描    述:	window 系统视频播放
*********************************************************************/
#if (UNITY_STANDALONE_WIN || UNITY_EDITOR) && !UNITY_EDITOR_OSX
using UnityEngine;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using System;
using UnityEngine.Events;
using System.Collections;

namespace GameApp.Media
{
    public class WindowsMediaPlayer : MonoBehaviour, IMediaPlayer
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

        protected System.IntPtr m_Video;
        private Texture2D m_Texture;

        private float m_DurationMs = 0.0f;
        private int m_Width = 0;
        private int m_Height = 0;

        protected int m_iPlayerIndex = -1;

        // State
        private bool m_VideoOpened = false;
        private bool m_AutoStartTriggered = false;
        private Coroutine _renderingCoroutine = null;

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

        private static System.IntPtr _nativeFunction_processDecode;

        private float m_fDecodeSleep = 0;

        private AnimationCurve m_AlphaCurve;

        private static void IssuePluginEvent(System.IntPtr handle, int mdeia)
        {
            GL.IssuePluginEvent(Native.GetVideoRenderEventFunc(), mdeia);
        }

        public void Enable(bool bEnable)
        {
            this.enabled = bEnable;
        }

        public bool IsEnable()
        {
            return this.enabled;
        }
        //------------------------------------------------------
        private void OnDestroy()
        {
            Dispose();
        }
        //------------------------------------------------------
        public string GetVideoPath()
        {
            return m_VideoPath;
        }
        //------------------------------------------------------
        public bool OpenVideo(UnityEngine.Video.VideoClip videoClip)
        {
            Debug.LogError("unsupport");
            return false;
        }
        //------------------------------------------------------
        public bool OpenVideoFromFile(string path, bool bPersistentRoot = false, bool bAutoPlay = true, long offset = 0, string httpHeaderJson = null)
        {
            bool bReturn = false;

            CloseVideo();

            if (m_Video == System.IntPtr.Zero)
                m_Video = Native.setupMedia();

            m_AutoStartTriggered = false;
            m_EventFired_MetaDataReady = false;
            m_EventFired_ReadyToPlay = false;
            m_EventFired_Started = false;
            m_EventFired_FirstFrameReady = false;

            if (m_Video != null)
            {
                m_VideoPath = path;
                if (bPersistentRoot)
                    path = Application.persistentDataPath + "/" + path;
                else
                    path = Application.streamingAssetsPath + "/" + path;

                bReturn = Native.openVideo(m_Video, path) == 0;
                if (bReturn)
                {
                    m_VideoOpened = true;
                    Debug.Log(path + " -- open");

                    m_Width = Native.getVideoWidth(m_Video);
                    m_Height = Native.getVideoHeight(m_Video);

                    if (m_Width > 0 && m_Height > 0)
                    {
                        m_Texture = new Texture2D(m_Width, m_Height, TextureFormat.RGBA32, false);
                        if (m_Texture != null)
                        {
                            ApplyTextureProperties(m_Texture);
                        }
                        Native.setTextureHandle(m_Video, m_Texture.GetNativeTexturePtr());
                    }

                    _nativeFunction_processDecode = Native.GetVideoRenderEventFunc();

                    m_AutoStartTriggered = true;
                    StartRenderCoroutine();
                    SetFlipY(true);
                    SetLooping(m_Loop);
                    if (bAutoPlay) Play();
                    else Pause();
                }
                else
                {
                    m_VideoOpened = false;
                    CloseVideo();
                    Debug.Log(path + " -- open failed");
                }
            }

            return bReturn;
        }

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

            if (m_Texture != null)
            {
                Texture2D.Destroy(m_Texture);
                m_Texture = null;
            }

            m_DurationMs = 0.0f;
            m_Width = 0;
            m_Height = 0;

            _lastError = ErrorCode.None;

            if (m_Video != System.IntPtr.Zero) Native.shutdownMedia(m_Video);
            m_Video = System.IntPtr.Zero;

            m_AlphaCurve = null;
        }

        public void SetCurveAlpha(AnimationCurve alpha)
        {
            m_AlphaCurve = alpha;
        }
        public float GetAlhpa(bool normalTime)
        {
            float alhpa = 1;
            if (m_Video != System.IntPtr.Zero && m_AlphaCurve!=null && IsPlaying())
            {
                if(normalTime)
                {
                    if(GetDurationMs()>0)
                        alhpa = m_AlphaCurve.Evaluate(GetCurrentTimeMs() / GetDurationMs());
                }
                else
                    alhpa = m_AlphaCurve.Evaluate(GetCurrentTimeMs() * 0.001f);
            }
            return alhpa;
        }


        public void SetFlipY(bool bFlipY)
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.setVideoFlipY(m_Video, bFlipY);
            }
        }

        public bool IsFlipY()
        {
            return false;
        }

        public void SetLooping(bool bLooping)
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.setVideoLoop(m_Video, bLooping ? -1 :0 );
            }
        }

        public bool IsLooping()
        {
            bool result = false;
            if (m_Video != System.IntPtr.Zero)
            {
                result = Native.getVideoLoop(m_Video)<0;
            }
            return result;
        }

        public bool HasVideo()
        {
            bool result = false;
            if (m_Video != System.IntPtr.Zero)
            {
                result = true;
            }
            return result;
        }

        public bool HasAudio()
        {
            bool result = false;
            if (m_Video != System.IntPtr.Zero)
            {
                result = true;
            }
            return result;
        }

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

        public bool CanPlay()
        {
            bool result = false;

            if (m_Video != System.IntPtr.Zero && m_VideoOpened)
            {
                result = true;
            }
            return result;
        }

        public void Play()
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.playVideo(m_Video, false);
            }
        }

        public void Pause()
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.pauseVideo(m_Video);
            }
        }

        public void Stop()
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.stopVideo(m_Video);
            }
        }

        public void Rewind()
        {
            Seek(0.0f);
        }

        public void Seek(float timeMs)
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.setVideoTime(m_Video, Mathf.FloorToInt(timeMs));
            }
        }

        public void SeekFast(float timeMs)
        {
            Seek(timeMs);
        }

        public float GetCurrentTimeMs()
        {
            float result = 0.0f;
            if (m_Video != System.IntPtr.Zero)
            {
                result = Native.getVideoDuration(m_Video)*1000;
            }
            return result;
        }

        public float GetCurrentFrameTimeMs()
        {
            float result = 0.0f;
            if (m_Video != System.IntPtr.Zero)
            {
                result = GetTextureFrameCount();
            }
            return result;
        }

        public void SetPlaybackRate(float rate)
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.setVideoFrameRate(m_Video, rate);
            }
        }

        public float GetPlaybackRate()
        {
            float result = 0.0f;
            if (m_Video != System.IntPtr.Zero)
            {
                result = Native.getVideoFps(m_Video);
            }
            return result;
        }

        public float GetDurationMs()
        {
            return m_DurationMs;
        }

        public int GetVideoWidth()
        {
            return m_Width;
        }

        public int GetVideoHeight()
        {
            return m_Height;
        }

        public float GetVideoFrameRate()
        {
            float result = 0.0f;
            if (m_Video != System.IntPtr.Zero)
            {
                result = Native.getVideoFps(m_Video);
                if (result <= 0) result = 30;

            }
            return result;
        }


        public bool IsPlaying()
        {
            bool result = false;
            if (m_Video != System.IntPtr.Zero)
            {
                result = (Native.Status)Native.getVideoStatus(m_Video) == Native.Status.PLAY;
            }
            return result;
        }

        public bool IsPrepared()
        {
            if (m_Video != System.IntPtr.Zero && GetTextureFrameCount() > 0 && IsPlaying())
                return true;
            return false;
        }

        public bool IsPaused()
        {
            bool result = false;
            if (m_Video != System.IntPtr.Zero)
            {
                result = (Native.Status)Native.getVideoStatus(m_Video) == Native.Status.PAUSE;
            }
            return result;
        }

        public bool IsFinished()
        {
            bool result = false;
            if (m_Video != System.IntPtr.Zero)
            {
                result = (Native.Status)Native.getVideoStatus(m_Video) == Native.Status.FINISHED;
            }
            return result;
        }

        public Texture GetTexture(int index = 0)
        {
            Texture result = null;
            if (m_Video != System.IntPtr.Zero && GetTextureFrameCount() > 0 && IsPlaying())
            {
                result = m_Texture;
            }
            return result;
        }

        public int GetTextureFrameCount()
        {
            int result = 0;
            if (m_Video != System.IntPtr.Zero)
            {
                result = Native.getVideoFrameCount(m_Video);
            }
            return result;
        }

        public bool RequiresVerticalFlip()
        {
            return false;
        }

        public void SetVolume(float volume)
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.setVideoVolume(m_Video, volume);
            }
        }

        public float GetVolume()
        {
            float result = 0.0f;
            if (m_Video != System.IntPtr.Zero)
            {
                result = Native.getVideoVolume(m_Video);
            }
            return result;
        }

        private void StartRenderCoroutine()
        {
            if (_renderingCoroutine == null)
            {
                // Use the method instead of the method name string to prevent garbage
                _renderingCoroutine = StartCoroutine(FinalRenderCapture());
            }
        }
        private void StopRenderCoroutine()
        {
            if (_renderingCoroutine != null)
            {
                StopCoroutine(_renderingCoroutine);
                _renderingCoroutine = null;
            }
        }

        private IEnumerator FinalRenderCapture()
        {
            // Preallocate the YieldInstruction to prevent garbage
            YieldInstruction wait = new WaitForEndOfFrame();
            while (Application.isPlaying)
            {
                // NOTE: in editor, if the game view isn't visible then WaitForEndOfFrame will never complete
                yield return wait;

                if (IsEnable())
                {
                    Render();
                }
            }
        }

        public void Render()
        {
            if (m_Video != System.IntPtr.Zero)
            {
                m_fDecodeSleep -= Time.deltaTime;
                if (m_fDecodeSleep > 0) return;

                IssuePluginEvent(_nativeFunction_processDecode, Native.getVideoMediaIndex(m_Video));
                if (m_DurationMs == 0.0f)
                {
                    m_DurationMs = Native.getVideoLength(m_Video)*1000;
                }
                m_fDecodeSleep = Native.getVideoInvFps(m_Video);
            }
        }

        void ApplyTextureProperties(Texture texture)
        {
            if (texture != null)
            {
                texture.filterMode = _defaultTextureFilterMode;
                texture.wrapMode = _defaultTextureWrapMode;
                texture.anisoLevel = _defaultTextureAnisoLevel;
            }
        }

        public void Update()
        {
            if (m_Video != null)
            {
                if (_renderingCoroutine == null && CanPlay())
                {
                    StartRenderCoroutine();
                }

                UpdateErrors();
                UpdateEvents();
            }
        }

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

        private void UpdateEvents()
        {
            if (m_events != null && m_Video != null)
            {
                m_EventFired_FinishedPlaying = FireEventIfPossible(MediaPlayerEvent.EventType.FinishedPlaying, m_EventFired_FinishedPlaying);

                // Reset some event states that can reset during playback
                {
                    // Keep track of whether the Playing state has changed
                    if (m_EventFired_Started && !IsPlaying())
                    {
                        // Playing has stopped
                        m_EventFired_Started = false;
                    }

                    // NOTE: We check m_Control isn't null in case the scene is unloaded in response to the FinishedPlaying event
                    if (m_EventFired_FinishedPlaying && IsPlaying() && !IsFinished())
                    {
                        bool reset = true;

#if UNITY_EDITOR_WIN || UNITY_STANDALONE_WIN || UNITY_WSA
                        reset = false;
                        // Don't reset if within a frame of the end of the video, important for time > duration workaround
                        float msPerFrame = 1000f / GetVideoFrameRate();
                        //Debug.Log(m_Info.GetDurationMs() - m_Control.GetCurrentTimeMs() + " " + msPerFrame);
                        if (GetDurationMs() - GetCurrentTimeMs() > msPerFrame)
                        {
                            reset = true;
                        }
#endif
                        if (reset)
                        {
                            //Debug.Log("Reset");
                            m_EventFired_FinishedPlaying = false;
                        }
                    }
                }

                m_EventFired_MetaDataReady = FireEventIfPossible(MediaPlayerEvent.EventType.MetaDataReady, m_EventFired_MetaDataReady);
                m_EventFired_ReadyToPlay = FireEventIfPossible(MediaPlayerEvent.EventType.ReadyToPlay, m_EventFired_ReadyToPlay);
                m_EventFired_Started = FireEventIfPossible(MediaPlayerEvent.EventType.Started, m_EventFired_Started);
                m_EventFired_FirstFrameReady = FireEventIfPossible(MediaPlayerEvent.EventType.FirstFrameReady, m_EventFired_FirstFrameReady);

            }
        }

        private bool FireEventIfPossible(MediaPlayerEvent.EventType eventType, bool hasFired)
        {
            if (CanFireEvent(eventType, hasFired))
            {
                hasFired = true;
                m_events.Invoke(this, eventType, ErrorCode.None);
            }
            return hasFired;
        }

        private bool CanFireEvent(MediaPlayerEvent.EventType et, bool hasFired)
        {
            bool result = false;
            if (m_events != null && m_Video != System.IntPtr.Zero && !hasFired)
            {
                switch (et)
                {
                    case MediaPlayerEvent.EventType.FinishedPlaying:
                        //Debug.Log(m_Control.GetCurrentTimeMs() + " " + m_Info.GetDurationMs());
                        result = (!IsLooping() && CanPlay() && IsFinished());
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

        public bool PlayerSupportsLinearColorSpace()
        {
            return false;
        }

        public void Dispose()
        {
            if (m_Video != System.IntPtr.Zero)
            {
                Native.shutdownMedia(m_Video);
            }
            m_Video = System.IntPtr.Zero;

            if (m_Texture != null)
            {
                Texture2D.Destroy(m_Texture);
                m_Texture = null;
            }
        }

        public void RemoveListener(UnityEngine.Events.UnityAction<IMediaPlayer, MediaPlayerEvent.EventType, ErrorCode> pEvent)
        {
            Events.RemoveListener(pEvent);
        }

        public void AddListener(UnityEngine.Events.UnityAction<IMediaPlayer, MediaPlayerEvent.EventType, ErrorCode> pEvent)
        {
            Events.AddListener(pEvent);
        }

        class Native
        {
            public enum Status
            {
                STOP = 0,
                PLAY,
                PAUSE,
                FINISHED,
            }

#if !UNITY_EDITOR && UNITY_IPHONE
        const string MediaPlayerDLL = "__Internal";
#else
            const string MediaPlayerDLL = "MediaPlayer";
#endif
            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern System.IntPtr setupMedia();

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int getVideoMediaIndex(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void shutdownMedia(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void clearMedia(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int openVideo(System.IntPtr handle, string video);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int setBufferData(System.IntPtr handle, ref byte buffer, int size);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void setVideoTime(System.IntPtr handle, float time);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern float getVideoLength(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern float getVideoDuration(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int getVideoWidth(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int getVideoHeight(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void playVideo(System.IntPtr handle, bool bThread);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void stopVideo(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void pauseVideo(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void setVideoLoop(System.IntPtr handle, int loop);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void setVideoFlipY(System.IntPtr handle, bool bFlipY);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int getVideoLoop(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void setVideoVolume(System.IntPtr handle, float volum);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern float getVideoVolume(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int getVideoStatus(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern float getVideoFps(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern float getVideoInvFps(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern int getVideoFrameCount(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void setVideoFrameRate(System.IntPtr handle, float frameRate);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern void setTextureHandle(System.IntPtr handle, System.IntPtr textureHandle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern System.IntPtr getTextureHandle(System.IntPtr handle);

            [DllImport(MediaPlayerDLL, CallingConvention = CallingConvention.Cdecl)]
            public static extern System.IntPtr GetVideoRenderEventFunc();
        }
    }
}
#endif