using UnityEngine;

namespace GameApp.Media
{
    public class MediaPlayerEvent : UnityEngine.Events.UnityEvent<IMediaPlayer, MediaPlayerEvent.EventType, ErrorCode>
    {
        public enum EventType
        {
            MetaDataReady,      // Called when meta data(width, duration etc) is available
            ReadyToPlay,        // Called when the video is loaded and ready to play
            Started,            // Called when the playback starts
            FirstFrameReady,    // Called when the first frame has been rendered
            FinishedPlaying,    // Called when a non-looping video has finished playing
            Closing,            // Called when the media is closed
            Error,              // Called when an error occurs

            LoadFailed = 100,
            DecodeFailed = 200,
            // TODO: 
            //FinishedSeeking,	// Called when seeking has finished
            //StartLoop,			// Called when the video starts and is in loop mode
            //EndLoop,			// Called when the video ends and is in loop mode
        }
    }

    public enum ErrorCode
    {
        None = 0,
        LoadFailed = 100,
        DecodeFailed = 200,
    }

    public interface IMediaPlayer
    {
        string      GetVideoPath();
		Texture		GetTexture(int index = 0);
		void		SetFlipY(bool bFlipY);
        bool        IsFlipY();

        bool IsEnable();
        void Enable(bool bEnable);

        void Pause();
        void Stop();
        void Play();
        void SetLooping(bool bLooping);
        bool IsLooping();

        bool IsPlaying();
        bool IsPaused();
        bool IsFinished();
        bool IsPrepared();

        float GetDurationMs();
        float GetCurrentTimeMs();

        void SetPlaybackRate(float rate);
        float GetPlaybackRate();

        void Seek(float timeMs);

        void SetCurveAlpha(AnimationCurve alpha);
        float GetAlhpa(bool normalTime);

        void Dispose();

        bool OpenVideoFromFile(string path, bool bPersistentRoot = false, bool bAutoPlay = true, long offset = 0, string httpHeaderJson = null);

        bool OpenVideo(UnityEngine.Video.VideoClip videoClip);

        void RemoveListener(UnityEngine.Events.UnityAction<IMediaPlayer, MediaPlayerEvent.EventType, ErrorCode> pEvent);
        void AddListener(UnityEngine.Events.UnityAction<IMediaPlayer, MediaPlayerEvent.EventType, ErrorCode>  pEvent);
    }
}