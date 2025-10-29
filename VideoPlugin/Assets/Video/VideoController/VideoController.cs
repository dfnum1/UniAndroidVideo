/********************************************************************
生成日期:	1:11:2020 13:16
类    名: 	VideoController
作    者:	HappLI
描    述:	视频播放控制器模块
*********************************************************************/
#if UNITY_ANDROID && !UNITY_EDITOR
#else
#define USE_AVPRO
#endif

using UnityEngine;
using System.Collections.Generic;
using System.IO;

namespace GameApp.Media
{
    public class VideoController
    {
        GameObject m_pVideoRoot = null;

        List<IMediaPlayer> m_vVideos = null;
        static VideoController ms_pInstance = null;
        public static VideoController Instance
        {
            get
            {
                if (ms_pInstance == null)
                {
                    ms_pInstance = new VideoController();
                    ms_pInstance.Init();
                }
                return ms_pInstance;
            }
        }
        //------------------------------------------------------
        public void Init()
        {
            if(m_pVideoRoot != null) return;
#if UNITY_EDITOR
                m_pVideoRoot = new GameObject("VideoSystem");
            if (Application.isPlaying)
                GameObject.DontDestroyOnLoad(m_pVideoRoot);
#else
            m_pVideoRoot = new GameObject("VideoSystem");
            GameObject.DontDestroyOnLoad(m_pVideoRoot);
#endif
            m_pVideoRoot.hideFlags |= HideFlags.HideAndDontSave;
            m_vVideos = new List<IMediaPlayer>(2);
        }
        //------------------------------------------------------
        public static void PreLoadVideo(string strPath, bool isPersistentPath)
        {
            PlayVideo(strPath, isPersistentPath, false);
        }
        //------------------------------------------------------
        public static IMediaPlayer PlayVideo(string strPath, bool isPersistentPath,  bool bAutoPlay = true)
        {
#if UNITY_EDITOR
            if (ms_pInstance == null && Application.isPlaying) Instance.Init();
#else
            if(ms_pInstance == null)Instance.Init();
#endif
            if (ms_pInstance == null)
                return null;
            if (ms_pInstance.m_vVideos == null) ms_pInstance.m_vVideos = new List<IMediaPlayer>(2);
            for (int i = 0; i < ms_pInstance.m_vVideos.Count; ++i)
            {
                if(strPath.CompareTo(ms_pInstance.m_vVideos[i].GetVideoPath()) == 0)
                {
                    if (bAutoPlay && !ms_pInstance.m_vVideos[i].IsPlaying()) ms_pInstance.m_vVideos[i].Play();
                    return ms_pInstance.m_vVideos[i];
                }
            }
            IMediaPlayer media = ms_pInstance.NewMediaPlayer();
            if (media == null) return null;
            media.AddListener(ms_pInstance.OnMediaListener);
            if (media.OpenVideoFromFile(strPath, isPersistentPath))
            {
                ms_pInstance.m_vVideos.Add(media);
                return media;
            }
            return null;
        }
        //------------------------------------------------------
        public static IMediaPlayer PlayVideo(UnityEngine.Video.VideoClip clip, bool bAutoPlay = true)
        {
#if UNITY_EDITOR
            if (ms_pInstance == null && Application.isPlaying) Instance.Init();
#else
            if(ms_pInstance == null)Instance.Init();
#endif
            if (ms_pInstance == null || clip == null) return null;
            if (ms_pInstance.m_vVideos == null) ms_pInstance.m_vVideos = new List<IMediaPlayer>(2);
            string clipName = clip.name;
            for (int i = 0; i < ms_pInstance.m_vVideos.Count; ++i)
            {
                if (clipName.CompareTo(ms_pInstance.m_vVideos[i].GetVideoPath()) == 0)
                {
                    if (bAutoPlay && !ms_pInstance.m_vVideos[i].IsPlaying()) ms_pInstance.m_vVideos[i].Play();
                    return ms_pInstance.m_vVideos[i];
                }
            }

            IMediaPlayer media = ms_pInstance.NewMediaPlayer();
            if (media == null) return null;
            media.AddListener(ms_pInstance.OnMediaListener);
            if (media.OpenVideo(clip))
            {
                ms_pInstance.m_vVideos.Add(media);
                return media;
            }
            return null;
        }
        //------------------------------------------------------
        public static void StopVideo(string strPath)
        {
            if (ms_pInstance == null || ms_pInstance.m_vVideos == null) return;
            for (int i = 0; i < ms_pInstance.m_vVideos.Count; ++i)
            {
                if (strPath.CompareTo(ms_pInstance.m_vVideos[i].GetVideoPath()) == 0)
                {
                    ms_pInstance.FreeMediaPlayer(ms_pInstance.m_vVideos[i]);
                    ms_pInstance.m_vVideos.RemoveAt(i);
                    break;
                }
            }
        }
        //------------------------------------------------------
        public static void StopVideo(IMediaPlayer mediaPlayer)
        {
            if (ms_pInstance == null || mediaPlayer == null) return;
            mediaPlayer.Stop();
            ms_pInstance.FreeMediaPlayer(mediaPlayer);
            ms_pInstance.m_vVideos.Remove(mediaPlayer);
        }
        //------------------------------------------------------
        public static void ClearVideo()
        {
            if (ms_pInstance == null || ms_pInstance.m_vVideos == null) return;
            for (int i = 0; i < ms_pInstance.m_vVideos.Count; ++i)
            {
                ms_pInstance.FreeMediaPlayer(ms_pInstance.m_vVideos[i]);
            }
            ms_pInstance.m_vVideos.Clear();
        }
        //------------------------------------------------------
        public void Destroy()
        {
#if UNITY_EDITOR
            if (Application.isPlaying)
                GameObject.Destroy(m_pVideoRoot);
            else
                GameObject.DestroyImmediate(m_pVideoRoot);
#else
            if (m_pVideoRoot) GameObject.Destroy(m_pVideoRoot);
#endif
                m_pVideoRoot = null;
        }
        //------------------------------------------------------
        void OnMediaListener(IMediaPlayer player, MediaPlayerEvent.EventType type, ErrorCode code)
        {
            Debug.Log(player.GetVideoPath() + ":" + type.ToString());
            if(type == MediaPlayerEvent.EventType.Closing ||
                type == MediaPlayerEvent.EventType.Error || 
                type == MediaPlayerEvent.EventType.FinishedPlaying)
            {
                FreeMediaPlayer(player);
                if (m_vVideos != null) m_vVideos.Remove(player);
            }
        }
        //------------------------------------------------------
        void FreeMediaPlayer(IMediaPlayer player)
        {
            MonoBehaviour behaviour = player as MonoBehaviour;
            if(behaviour)
            {
#if UNITY_EDITOR
                if (Application.isPlaying)
                    GameObject.Destroy(behaviour.gameObject);
                else
                {
                    GameObject.DestroyImmediate(behaviour.gameObject);
                    if(m_pVideoRoot!=null)
                    {
                        if(m_pVideoRoot.transform.childCount<=0)
                            GameObject.DestroyImmediate(m_pVideoRoot);
                        m_pVideoRoot = null;
                    }
                }
#else
                GameObject.Destroy(behaviour.gameObject);
#endif
            }
        }
        //------------------------------------------------------
        public static string GetMaterialDefines()
        {
#if USE_AVPRO
            return "USE_AVPRO";
#else
            return null;
#endif
        }
        //------------------------------------------------------
        IMediaPlayer NewMediaPlayer()
        {
            if (m_pVideoRoot == null) return null;
            GameObject video = new GameObject("video");
        //    video.hideFlags |= HideFlags.HideAndDontSave;
            video.transform.SetParent(m_pVideoRoot.transform);

#if USE_AVPRO
            return video.AddComponent<AVProVideoPlayer>();
#endif

#if (UNITY_STANDALONE_WIN || UNITY_EDITOR) && !UNITY_EDITOR_OSX
            return video.AddComponent<UnityMediaPlayer>();
            //return video.AddComponent<WindowsMediaPlayer>();
#elif !UNITY_EDITOR && UNITY_ANDROID
             return video.AddComponent<ExoMediaPlayer>();
             //return video.AddComponent<UnityMediaPlayer>();
             return video.AddComponent<AndroidMediaPlayer>();
#else
            return video.AddComponent<UnityMediaPlayer>();
#endif
        }

        public const string avproDirName = "avpro";
        public static string PrepareForPlayWithName(string assetName)
        {/*
            //目前播视频都统一用不压缩的ab包，从persistentpath下改后缀转存一遍,返回persistentpath路径去播
            var assetHelper = IoCRepository.Instance.Get<IAssetLocalHelper>();
            var assetFileModule = IoCRepository.Instance.Get<AssetsFileModule>();
            var playUrl = assetName;
            if (assetHelper.CheckAssetRes(assetName, out AssetDataInfo data))
            {
                string abName = data.assetbundle;
                string hash = assetFileModule.GetAssetbundleHash(abName);
                Debug.LogFormat("[TestUIVideo] PlayWithVideoName:{0}, bunble:{1}, hash:{2}", assetName, abName, hash);
                string avproDir = GetAVProPath();
                if (!Directory.Exists(avproDir))
                {
                    Directory.CreateDirectory(avproDir);
                }
                var loader = ResourceLoader.Create();
                string avproAssetPath = GetAVProAssetSavePath(assetName, hash);
                if (File.Exists(avproAssetPath))
                {
                    playUrl = GetAVProAssetRelastePath(assetName, hash);
                }
                else
                {
                    loader.LoadAsset(assetName, (UnityEngine.Object asset) =>
                    {
                        TextAsset byteVideo = asset as TextAsset;
                        File.WriteAllBytes(avproAssetPath, byteVideo.bytes);
                        playUrl = GetAVProAssetRelastePath(assetName, hash);
                        Debug.LogFormat("[TestUIVideo] Name:{0}, SaveAt:{1}, Url:{2}", assetName, avproAssetPath, playUrl);
                    }, ResourceLoadMethod.Sync);
                }
                //确认avpro目录下无过期文件
                DeleteExpiredAVProFile(assetName, hash);
            }
            Debug.LogFormat("[TestUIVideo] PrepareForPlayWithName, Url:{0}", playUrl);
            return playUrl;*/
            return null;
        }

        public static string GetAVProPath()
        {
            string avproDir = System.IO.Path.Combine(Application.persistentDataPath,avproDirName);
            return avproDir;
        }
        public static string GetAVProAssetSavePath(string assetName, string hash)
        {
            string avproDir = GetAVProPath();
#if UNITY_EDITOR
            string mp4Path = string.Format("{0}/{1}.mp4", avproDir, assetName);
#else
            string mp4Path = string.Format("{0}/{1}_{2}.mp4", avproDir, assetName, hash);
#endif
            return mp4Path;
        }
        public static string GetAVProAssetRelastePath(string assetName, string hash)
        {
#if UNITY_EDITOR
            string mp4Path = string.Format("{0}/{1}.mp4", avproDirName, assetName);
#else
            string mp4Path = string.Format("{0}/{1}_{2}.mp4", avproDirName, assetName, hash);
#endif
            return mp4Path;
        }
        public static List<string> deleteFiles = new List<string>(2);
        private static void DeleteExpiredAVProFile(string assetName, string hash)
        {
#if !UNITY_EDITOR
            string targetDir = GetAVProPath();
            // 获取所有以指定前缀开头的文件
            string[] files = Directory.GetFiles(targetDir, $"{assetName}*");
            string exceptFileName = string.Format("{0}_{1}.mp4", assetName, hash);
            if (files.Length <= 1)
            {
                return;
            }
            deleteFiles.Clear();
            // 批量删除文件
            foreach (string file in files)
            {
                if (!file.Equals(exceptFileName))
                {
                    deleteFiles.Add(file);
                }
            }
            foreach (string file in deleteFiles)
            {
                File.Delete(file);
            }
            deleteFiles.Clear();
#endif
        }
    }


}