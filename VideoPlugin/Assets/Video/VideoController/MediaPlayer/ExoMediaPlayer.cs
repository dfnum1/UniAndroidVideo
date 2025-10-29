/********************************************************************
生成日期:	1:11:2020 13:16
类    名: 	AndroidMediaPlayer
作    者:	HappLI
描    述:	window 系统视频播放
*********************************************************************/
#if UNITY_ANDROID && !UNITY_EDITOR
using System.Collections;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.Video;

namespace GameApp.Media
{
    // TODO: seal this class
    public class ExoMediaPlayer : MonoBehaviour, IMediaPlayer
    {
        public string m_VideoPath;

        bool useFastOesPath = false;
        bool showPosterFrame = false;
        public bool m_AutoStart = false;


        protected static bool				s_bInitialised		= false;

		private static string				s_Version = "Plug-in not yet initialised";

        [SerializeField]
        private MediaPlayerEvent m_events;

        public class AndroidUnityMessage : AndroidJavaProxy
        {
            ExoMediaPlayer m_pPlayer = null;
            public AndroidUnityMessage() : base("com/unity3d/exovideo/IUnityMessage") { }

            public void SetPlayer(ExoMediaPlayer player)
            {
                m_pPlayer = player;
            }

            public void OnVideoRenderBegin(int playerIndex)
            {
                if (m_pPlayer != null) m_pPlayer.OnVideoRenderBegin(playerIndex);
            }

            public void OnVideoRenderEnd(int playerIndex)
            {
                if (m_pPlayer != null) m_pPlayer.OnVideoRenderEnd(playerIndex);
            }

            public void OnPlayWhenReadyChanged(bool playWhenReady, int reason)
            { 
                //Debug.LogWarning($"TUTAN EXOPLAYER EVENT OnPlayWhenReadyChanged({playWhenReady}, {(Native.ExoPlayer_PlayWhenReadyChangeReason)reason})");
            }
            public void OnPlaybackStateChanged(int playbackState)
            {
                //Debug.LogWarning($"TUTAN EXOPLAYER EVENT OnPlaybackStateChanged({(Native.ExoPlayer_PlaybackState)playbackState})");
            }
        }

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

        protected AndroidJavaObject			m_Video;
		private Texture2D					m_Texture;
        private int                         m_TextureHandle;
		private bool						m_UseFastOesPath;

		private float						m_DurationMs		= 0.0f;
		private int							m_Width				= 0;
		private int							m_Height			= 0;

		protected int 						m_iPlayerIndex		= -1;

        // State
        private bool m_VideoOpened = false;
        private bool m_AutoStartTriggered = false;
        private bool m_WasPlayingOnPause = false;
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


        protected bool m_bResumeCall = false;
        static int ms_VideoIndex = 0;
        AndroidJavaClass m_pSurfaceTextureClass;
        AndroidJavaClass m_pSurfaceViewClass;

        private static System.IntPtr _nativeFunction_RenderEvent;

        AnimationCurve m_AlphaCurve;

        private static void IssuePluginEvent(Native.ExoPlayerEvent type, int param)
        {
            int eventId =((int)type) << 16 | (param<<8|((int)SystemInfo.graphicsDeviceType));
            if(_nativeFunction_RenderEvent != System.IntPtr.Zero)
                GL.IssuePluginEvent(_nativeFunction_RenderEvent, eventId);
        }

        static void InitialisePlatform()
        {
#if UNITY_ANDROID
#if UNITY_EDITOR
#else
            CoreNavtive.Init();
            // Get the activity context
            if( MetalAPI.s_ActivityContext == null )
            {
                AndroidJavaClass activityClass = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                if (activityClass != null)
                {
                    MetalAPI.s_ActivityContext = activityClass.GetStatic<AndroidJavaObject>("currentActivity");
				}
			}

			if( !s_bInitialised )
			{
				s_bInitialised = true;

				AndroidJavaObject videoClass = new AndroidJavaObject("com.unity3d.exovideo.ExoPlayerUnity");
				if( videoClass != null )
				{
					s_Version = videoClass.CallStatic<string>("GetPluginVersion");
                    _nativeFunction_RenderEvent = CoreNavtive.GetRenderEventFunc();
                }
            }
#endif
#endif
        }
        //-------------------------------------------------
        private void Awake()
        {
            InitialisePlatform();
        }
        //-------------------------------------------------
        private void Start()
        {
           // Init(useFastOesPath, showPosterFrame);
        }
        //-------------------------------------------------
        public void SetCurveAlpha(AnimationCurve alpha)
        {
            m_AlphaCurve = alpha;
        }
        //-------------------------------------------------
        public float GetAlhpa(bool normalTime)
        {
            float alhpa = 1;
            if (m_Video != null && m_AlphaCurve != null && IsPlaying())
            {
                if (normalTime)
                {
                    if(GetDurationMs()>0)
                        alhpa = m_AlphaCurve.Evaluate(GetCurrentTimeMs() / GetDurationMs());
                }
                else
                    alhpa = m_AlphaCurve.Evaluate(GetCurrentTimeMs() * 0.001f);
            }
            return alhpa;
        }
        //-------------------------------------------------
        public void Enable(bool bEnable)
        {
            enabled = bEnable;
        }
        //-------------------------------------------------
        public bool IsEnable()
        {
            return enabled;
        }
        //-------------------------------------------------
        public void SetAutoStart(bool bAuto)
        {
            m_AutoStart = bAuto;
        }
        //-------------------------------------------------
        public bool IsPrepared()
        {
			return IsBuffering();

		}
        //-------------------------------------------------
        private void OnApplicationPause(bool pauseStatus)
        {
            if (pauseStatus)
            {
                // 应用进入后台
                if (IsPlaying())
                {
                    m_WasPlayingOnPause = true;
                    Pause();
                }
                else
                {
                    m_WasPlayingOnPause = false;
                }
            }
            else
            {
                // 应用回到前台
                if (m_WasPlayingOnPause)
                {
              //      if (m_Video != null) m_Video.Call("OnResume");
                    Play();
                    m_bResumeCall = true;
                    m_WasPlayingOnPause = false;
                }
            }
        }
        //-------------------------------------------------
        void Init(bool useFastOesPath, bool showPosterFrame)
		{
#if UNITY_ANDROID
#if UNITY_EDITOR
#else
            // Create a java-size video class up front
            m_Video = new AndroidJavaObject("com.unity3d.exovideo.ExoPlayerUnity");
            CoreNavtive.OnRenderEventCallback += OnRenderEvent;
            if (m_Video != null)
            {
                // Initialise
                m_iPlayerIndex = ms_VideoIndex++;
                m_Video.Call("Initialise", MetalAPI.s_ActivityContext, m_iPlayerIndex);
                CoreNavtive.SetVideoJavaClass(m_iPlayerIndex, 2);
                SetOptions(useFastOesPath, showPosterFrame);

            //    AndroidUnityMessage msg = new AndroidUnityMessage();
            //    msg.SetPlayer(this);
            //    m_Video.Call("SetUnityCallback", msg);

                //m_Video.Call("RendererSetup");
                IssuePluginEvent(Native.ExoPlayerEvent.Setup, m_iPlayerIndex);
            }
#endif
#endif
        }
        //-------------------------------------------------
        public void SetOptions(bool useFastOesPath, bool showPosterFrame)
		{
			m_UseFastOesPath = useFastOesPath;
			if (m_Video != null)
			{
				//m_Video.Call("SetPlayerOptions", m_UseFastOesPath, showPosterFrame);
			}
		}
        //-------------------------------------------------
        public string GetVersion()
		{
			return s_Version;
		}
        //-------------------------------------------------
        public string GetVideoPath()
        {
            return m_VideoPath;
        }
        //-------------------------------------------------
        public bool OpenVideo(VideoClip videoClip)
        {
            Debug.LogError("unsupport");
            return false;
        }
        //-------------------------------------------------
        public bool OpenVideoFromFile(string path, bool bPersistentRoot = false, bool bAutoPlay =true, long offset = 0, string httpHeaderJson = null)
		{
			bool bReturn = false;

            CloseVideo();

            if (m_Video == null)
                Init(useFastOesPath, showPosterFrame);

            m_VideoOpened = true;
            m_AutoStartTriggered = false;
            m_EventFired_MetaDataReady = false;
            m_EventFired_ReadyToPlay = false;
            m_EventFired_Started = false;
            m_EventFired_FirstFrameReady = false;


            if ( m_Video != null )
			{
                m_VideoPath = path;

#if UNITY_5 || UNITY_5_4_OR_NEWER
                Debug.Assert(m_Width == 0 && m_Height == 0 && m_DurationMs == 0.0f);
#endif
                if(bPersistentRoot)
                    path = Application.persistentDataPath + "/" + path;
                else
                    path = Application.streamingAssetsPath + "/" + path;

                bReturn = m_Video.Call<bool>("OpenVideoFromFile", path, offset, httpHeaderJson);
                if(bReturn)
                {
                    m_AutoStart = bAutoPlay;
                //    m_Video.Call("Prepare", true);
                    IssuePluginEvent(Native.ExoPlayerEvent.Prepare, m_iPlayerIndex);
                    if (m_AutoStart) Play();
                    StartRenderCoroutine();
                }
                Debug.Log(path + " -- " + bReturn);
			}

			return bReturn;
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


            if (m_Texture != null)
            {
                Texture2D.Destroy(m_Texture);
                m_Texture = null;
            }
            m_TextureHandle = 0;

            m_DurationMs = 0.0f;
            m_Width = 0;
            m_Height = 0;

            m_AutoStart = false;

			_lastError = ErrorCode.None;

            m_AlphaCurve = null;

            if (m_Video!=null) m_Video.Call("Stop");
            CoreNavtive.OnRenderEventCallback -= OnRenderEvent;
        }
        //-------------------------------------------------
        public void SetLooping( bool bLooping )
		{
			if( m_Video != null )
			{
				m_Video.Call("SetLooping", bLooping);
			}
		}
        //-------------------------------------------------
        public bool IsLooping()
		{
			bool result = false;
			if( m_Video != null )
			{
				result = m_Video.Call<bool>("IsLooping");
			}
			return result;
		}
        //-------------------------------------------------
        public bool HasVideo()
		{
            return true;
			bool result = false;
			if( m_Video != null )
			{
				result = m_Video.Call<bool>("HasVideo");
			}
			return result;
		}
        //-------------------------------------------------
        public bool HasAudio()
		{
            return true;
			bool result = false;
			if( m_Video != null )
			{
				result = m_Video.Call<bool>("HasAudio");
			}
			return result;
		}
        //-------------------------------------------------
        public bool HasMetaData()
		{
			bool result = false;
			if( m_DurationMs > 0.0f )
			{
				result = true;

				if( HasVideo() )
				{
					result = ( m_Width > 0 && m_Height > 0 );
				}
			}
			return result;
		}
        //-------------------------------------------------
        public bool CanPlay()
		{
			bool result = false;

			if (m_Video != null)
			{
				result = m_Video.Call<bool>("CanPlay");
			}
			return result;
		}
        //-------------------------------------------------
        public void Play()
		{
			if (m_Video != null)
			{
				m_Video.Call("Play");
            }
		}
        //-------------------------------------------------
        public void Pause()
		{
			if (m_Video != null)
			{
				m_Video.Call("Pause");
			}
		}
        //-------------------------------------------------
        public void Stop()
		{
			if (m_Video != null)
			{
				// On Android we never need to actually Stop the playback, pausing is fine
				m_Video.Call("Stop");
			}
		}
        //-------------------------------------------------
        public void Rewind()
		{
			Seek( 0.0f );
		}
        //-------------------------------------------------
        public void Seek(float timeMs)
		{
			if (m_Video != null)
			{
                float time = GetDurationMs();
                if (time <= 0) return;
				m_Video.Call("SetPlaybackPosition", Mathf.FloorToInt(timeMs)/ time);
			}
		}
        //-------------------------------------------------
        public void SeekFast(float timeMs)
		{
			Seek( timeMs );
		}
        //-------------------------------------------------
        public float GetCurrentTimeMs()
		{
			float result = 0.0f;
			if (m_Video != null)
			{
				result = (float)(m_Video.Call<double>("GetPlaybackPosition")*1000);
			}
			return result;
		}
        //-------------------------------------------------
        public float GetCurrentFrameTimeMs()
        {
            float result = 0.0f;
            if (m_Video != null)
            {
                result = GetTextureFrameCount();
            }
            return result;
        }
        //-------------------------------------------------
        public void SetPlaybackRate(float rate)
		{
            return;
			if (m_Video != null)
			{
				m_Video.Call("SetPlaybackRate", rate);
			}
		}
        //-------------------------------------------------
        public float GetPlaybackRate()
		{
            return 0;
			float result = 0.0f;
			if (m_Video != null)
			{
				result = m_Video.Call<float>("GetPlaybackRate");
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
        public float GetVideoFrameRate()
		{
            return 0;
			float result = 0.0f;
			if( m_Video != null )
			{
				result = m_Video.Call<float>("GetSourceVideoFrameRate");
                if (result <= 0) result = 30;

            }
			return result;
		}
        //-------------------------------------------------
        public float GetBufferingProgress()
		{
            return 0;
			float result = 0.0f;
			if( m_Video != null )
			{
				result = m_Video.Call<float>("GetBufferingProgressPercent") * 0.01f;
			}
			return result;
		}
        //-------------------------------------------------
        public float GetVideoDisplayRate()
		{
            return 0;
			float result = 0.0f;
			if (m_Video != null)
			{
				result = m_Video.Call<float>("GetDisplayRate");
			}
			return result;
		}
        //-------------------------------------------------
        public bool IsSeeking()
		{
            return false;
			bool result = false;
			if (m_Video != null)
			{
				result = m_Video.Call<bool>("IsSeeking");
			}
			return result;
		}
        //-------------------------------------------------
        public bool IsPlaying()
		{
			bool result = false;
			if (m_Video != null)
			{
				result = m_Video.Call<bool>("GetIsPlaying");
			}
			return result;
		}
        //-------------------------------------------------
        public bool IsPaused()
		{
			bool result = false;
			if (m_Video != null)
			{
				result = m_Video.Call<bool>("IsPaused");
			}
			return result;
		}
        //-------------------------------------------------
        public bool IsFinished()
        {
            bool result = false;
            if (m_Video != null)
            {
                result = m_Video.Call<bool>("IsFinished");
            }
            return result;
        }
        //-------------------------------------------------
        public bool IsBuffering()
		{
			bool result = false;
			if (m_Video != null)
			{
				result = m_Video.Call<bool>("IsBuffering");
			}
			return result;
		}
        //-------------------------------------------------
        public Texture GetTexture( int index = 0 )
		{
			Texture result = null;
			if (GetTextureFrameCount() > 0)
			{
				result = m_Texture;
			}
			return result;
		}
        //-------------------------------------------------
        public int GetTextureFrameCount()
		{
			int result = 0;
			if (m_Video != null)
			{
				result = m_Video.Call<int>("GetFrameCount");
			}
			return result;
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
        //-------------------------------------------------
        public void MuteAudio(bool bMuted)
		{
            return;
			if (m_Video != null)
			{
				m_Video.Call("MuteAudio", bMuted);
			}
		}
        //-------------------------------------------------
        public bool IsMuted()
		{
            return false;
            bool result = false;
			if( m_Video != null )
			{
				result = m_Video.Call<bool>("IsMuted");
			}
			return result;
		}
        //-------------------------------------------------
        public void SetVolume(float volume)
		{
			if (m_Video != null)
			{
				m_Video.Call("SetVolume", volume);
			}
		}
        //-------------------------------------------------
        public float GetVolume()
		{
			float result = 0.0f;
			if( m_Video != null )
			{
				result = m_Video.Call<float>("GetVolume");
			}
			return result;
		}
        //-------------------------------------------------
        public int GetAudioTrackCount()
		{
            return 0;
			int result = 0;
			if( m_Video != null )
			{
				result = m_Video.Call<int>("GetNumberAudioTracks");
			}
			return result;
		}
        //-------------------------------------------------
        public int GetCurrentAudioTrack()
		{
            return 0;
            int result = 0;
			if( m_Video != null )
			{
				result = m_Video.Call<int>("GetCurrentAudioTrackIndex");
			}
			return result;
		}
        //-------------------------------------------------
        public void SetAudioTrack( int index )
		{
            return;
            if ( m_Video != null )
			{
				m_Video.Call("SetAudioTrack", index);
			}
		}
        //-------------------------------------------------
        public string GetCurrentAudioTrackId()
		{
			string id = "";
            return id;
            if ( m_Video != null )
			{
				id = m_Video.Call<string>("GetCurrentAudioTrackId");
			}
			return id;
		}
        //-------------------------------------------------
        public int GetCurrentAudioTrackBitrate()
		{
			int result = 0;
            return result;
            if ( m_Video != null )
			{
				result = m_Video.Call<int>("GetCurrentAudioTrackIndex");
			}
			return result;
        }
        //-------------------------------------------------
        public int GetVideoTrackCount()
		{
			int result = 0;
            return result;
            if ( m_Video != null )
			{
				result = m_Video.Call<int>("GetNumberVideoTracks");
			}
			return result;
		}
        //-------------------------------------------------
        public int GetCurrentVideoTrack()
		{
			int result = 0;
            return result;
            if ( m_Video != null )
			{
				result = m_Video.Call<int>("GetCurrentVideoTrackIndex");
			}
			return result;
		}
        //-------------------------------------------------
        public void SetVideoTrack( int index )
		{
            return;
            if ( m_Video != null )
			{
				m_Video.Call("SetVideoTrack", index);
			}
		}
        //-------------------------------------------------
        public string GetCurrentVideoTrackId()
		{
			string id = "";
            return id;
            if ( m_Video != null )
			{
				id = m_Video.Call<string>("GetCurrentVideoTrackId");
			}
			return id;
		}
        //-------------------------------------------------
        public int GetCurrentVideoTrackBitrate()
		{
			int bitrate = 0;
            return bitrate;
            if ( m_Video != null )
			{
				bitrate = m_Video.Call<int>("GetCurrentVideoTrackBitrate");
			}
			return bitrate;
		}
        //-------------------------------------------------
        public long GetTextureTimeStamp()
        {
            return 0;
            long timeStamp = long.MinValue;
            if (m_Video != null)
            {
                timeStamp = m_Video.Call<long>("GetTextureTimeStamp");
            }
            return timeStamp;
        }
        //-------------------------------------------------
        private void StartRenderCoroutine()
        {
            if (_renderingCoroutine == null)
            {
                // Use the method instead of the method name string to prevent garbage
                _renderingCoroutine = StartCoroutine(FinalRenderCapture());
            }
        }
        //-------------------------------------------------
        private void StopRenderCoroutine()
        {
            if (_renderingCoroutine != null)
            {
                StopCoroutine(_renderingCoroutine);
                _renderingCoroutine = null;
            }
        }
        //-------------------------------------------------
        private IEnumerator FinalRenderCapture()
        {
            // Preallocate the YieldInstruction to prevent garbage
            YieldInstruction wait = new WaitForEndOfFrame();
            while (Application.isPlaying)
            {
                // NOTE: in editor, if the game view isn't visible then WaitForEndOfFrame will never complete
                yield return wait;

                if (this.enabled)
                {
                    Render();
                }
            }
        }
        //-------------------------------------------------
        public void Render()
		{
			if (m_Video != null)
			{
                if(m_bResumeCall)
                {
                    m_bResumeCall = false;
                    IssuePluginEvent(Native.ExoPlayerEvent.Resume, m_iPlayerIndex);
                    return;
                }
				if (m_UseFastOesPath)
				{
					// This is needed for at least Unity 5.5.0, otherwise it just renders black in OES mode
					GL.InvalidateState();
				}
               // m_Video.Call<bool>("Render");

                IssuePluginEvent(Native.ExoPlayerEvent.Render, m_iPlayerIndex);

                if(m_UseFastOesPath)
                {
                    GL.InvalidateState();
                }

				// Check if we can create the texture
                // Scan for a change in resolution
				int newWidth = -1;
				int newHeight = -1;
                if (m_Texture != null)
                {
                    newWidth = m_Video.Call<int>("GetWidth");
                    newHeight = m_Video.Call<int>("GetHeight");
                    if (newWidth != m_Width || newHeight != m_Height)
                    {
                        m_Texture = null;
                        m_TextureHandle = 0;
                    }
                }

                int textureHandle = m_Video.Call<int>("GetTextureHandle");
                if (textureHandle > 0 && textureHandle != m_TextureHandle )
				{
					// Already got? (from above)
					if( newWidth == -1 || newHeight == -1 )
                    {
						newWidth = m_Video.Call<int>("GetWidth");
						newHeight = m_Video.Call<int>("GetHeight");
					}
                    if (Mathf.Max(newWidth, newHeight) > SystemInfo.maxTextureSize)
					{
						m_Width = newWidth;
						m_Height = newHeight;
	                    m_TextureHandle = textureHandle;
					}
					else if( newWidth > 0 && newHeight > 0 )
					{
						m_Width = newWidth;
						m_Height = newHeight;
	                    m_TextureHandle = textureHandle;

						_playerDescription = "MediaPlayer";

                        if (m_Texture != null) UnityEngine.Object.Destroy(m_Texture);

						// NOTE: From Unity 5.4.m_fYaw when using OES textures, an error "OPENGL NATIVE PLUG-IN ERROR: GL_INVALID_OPERATION: Operation illegal in current state" will be logged.
						// We assume this is because we're passing in TextureFormat.RGBA32 which isn't the true texture format.  This error should be safe to ignore.
						m_Texture = Texture2D.CreateExternalTexture(m_Width, m_Height, TextureFormat.RGBA32, false, false, new System.IntPtr(textureHandle));
						if (m_Texture != null)
						{
							ApplyTextureProperties(m_Texture);
						}

                        Debug.Log("ReCreateTexture");

                    }
                }

              //  Debug.Log(m_Width + "x" + m_Height + " TexHandle: " + textureHandle + " TexPtr: " + (m_Texture != null ? m_Texture.GetNativeTexturePtr().ToString() : "null"));

                {
                    if (m_Texture != null && textureHandle > 0 && m_Texture.GetNativeTexturePtr() == System.IntPtr.Zero)
                    {
						//Debug.Log("RECREATING");
						m_Texture.UpdateExternalTexture(new System.IntPtr(textureHandle));

					}

	//				_textureQuality = QualitySettings.masterTextureLimit;
				}
                //#endif

                if ( m_DurationMs == 0.0f )
				{
					m_DurationMs = (float)(m_Video.Call<long>("GetLength")*1000);
//					if( m_DurationMs > 0.0f ) { Helper.LogInfo("Duration: " + m_DurationMs); }
				}
			}
		}
        //-------------------------------------------------
        public void OnVideoRenderBegin(int playerIndex)
        {
            //Debug.Log("OnVideoRenderBegin:" + playerIndex);
        }
        //-------------------------------------------------
        public void OnVideoRenderEnd(int playerIndex)
        {
           // Debug.Log("OnVideoRenderEnd:" + playerIndex);
        }
        //-------------------------------------------------
        public void OnRenderEvent(int eventId)
        {
            int eventType = (eventId >> 16) & 0xFFFF;
            int playerIndex = (eventId >> 8) & 0xFF;
            int gfxType = eventId & 0xFF;
        //    Debug.Log("OnRenderEvent: " + ((Native.ExoPlayerEvent)eventType).ToString() + " PlayerIndex: " + playerIndex + " GfxType: " + gfxType);
            if (playerIndex != m_iPlayerIndex)
                return;


            switch ((Native.ExoPlayerEvent)eventType)
            {
                case Native.ExoPlayerEvent.Prepare:
                    {
                  //      Debug.Log("PrepareSurface");
                        //m_Video.Call("AttackSurface");
                        if(m_AutoStart) Play();
                    }
                    break;
                case Native.ExoPlayerEvent.Render:
                    {
                     //       Debug.Log("UpdateSurfaceTexture");
                        //  m_Video.Call("UpdateSurfaceTexture");
                    }
                    break;
            }
        }
        //-------------------------------------------------
        protected void ApplyTextureProperties(Texture texture)
		{
			// NOTE: According to OES_EGL_image_external: For external textures, the default min filter is GL_LINEAR and the default S and T wrap modes are GL_CLAMP_TO_EDGE
			// See https://www.khronos.org/registry/gles/extensions/OES/OES_EGL_image_external_essl3.txt
			if (!m_UseFastOesPath)
			{
                if (texture != null)
                {
                    texture.filterMode = _defaultTextureFilterMode;
                    texture.wrapMode = _defaultTextureWrapMode;
                    texture.anisoLevel = _defaultTextureAnisoLevel;
                }
            }
		}
        //-------------------------------------------------
        public void OnEnable()
		{
            if (m_Video == null) return;
        //    int textureHandle = m_Video.Call<int>("GetTextureHandle");
		//	if (m_Texture != null && textureHandle > 0 && m_Texture.GetNativeTexturePtr() == System.IntPtr.Zero)
		//	{
		//		//Debug.Log("RECREATING");
		//		m_Texture.UpdateExternalTexture(new System.IntPtr(textureHandle));
		//	}
		}
        //-------------------------------------------------
        public void Update()
		{
			if (m_Video != null)
			{
                if (m_VideoOpened && m_AutoStart && !m_AutoStartTriggered && CanPlay())
                {
                    m_AutoStartTriggered = true;
                    Play();
                }
                if (_renderingCoroutine == null && CanPlay())
                {
                    StartRenderCoroutine();
                }
              //  _lastError = (ErrorCode)( m_Video.Call<int>("GetLastErrorCode") );
                //		_lastError = (ErrorCode)( Native._GetLastErrorCode( m_iPlayerIndex) );

                UpdateErrors();
                UpdateEvents();
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
        private void UpdateEvents()
        {
            if (m_events != null && m_Video != null)
            {
                m_EventFired_FinishedPlaying = FireEventIfPossible(MediaPlayerEvent.EventType.FinishedPlaying, m_EventFired_FinishedPlaying);

                // Reset some event states that can reset during playback
                {
                    // Keep track of whether the Playing state has changed
                    if (m_EventFired_Started &&  !IsPlaying())
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
            if (m_events != null && m_Video != null && !hasFired)
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
        //------------------------------------------------------
        private void OnDestroy()
        {
            Dispose();
        }
        //-------------------------------------------------
        public void Dispose()
		{
            //Debug.LogError("DISPOSE");

            // Deinitialise player (replaces call directly as GL textures are involved)
            IssuePluginEvent(Native.ExoPlayerEvent.Destroy, m_iPlayerIndex);

            if (m_Video != null)
			{
				m_Video.Call("SetDeinitialiseFlagged");

				m_Video.Dispose();
				m_Video = null;
			}

			if (m_Texture != null)
			{
				Texture2D.Destroy(m_Texture);
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
        private struct Native
        {
            public enum ExoPlayerEvent
            {
                Setup = 1,
                Prepare,
                Render,
                Resume,
                Destroy,
            }
            public enum ExoPlayer_PlaybackState
            {
                IDLE = 1,
                BUFFERING = 2,
                READY = 3,
                ENDED = 4
            }
            public enum ExoPlayer_PlayWhenReadyChangeReason
            {
                USER_REQUEST = 1,
                AUDIO_FOCUS_LOSS = 2,
                AUDIO_BECOMING_NOISY = 3,
                REMOTE = 4,
                END_OF_MEDIA_ITEM = 5
            }
        }
    }
}
#endif