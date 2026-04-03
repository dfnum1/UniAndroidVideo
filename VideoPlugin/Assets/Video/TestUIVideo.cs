using GameApp.UIComponent;
using System;
using UnityEngine;
using UnityEngine.UI;

public class TestUIVideo : MonoBehaviour
{
    int m_nTest = 0;
    public UIVideo uiVideo1;
    public UIVideo uiVideo2;
    public Text videoName1;
    public Text videoName2;
    static string[] ranoms_videos = new string[]
    {
        "DragonQuest_Video_Clip_01.mp4",
        "DragonQuest_Video_Clip_02.mp4"
    };
    static string[] videos = new string[] {
        "CH01_0001_ZhuJiao.mp4",
        "CH01_0002_ZhuJiao.mp4",
        "CH01_0004_SaiDi.mp4",
        "CH01_0004_ZhuJiao.mp4",
        "CH01_0006_ZhuJiao.mp4",
        "CH01_0007_SaiLinNa.mp4",
        "CH01_0009_SaiDi.mp4",
        "CH01_0010_SiDiEr.mp4",
        "CH01_0012_SaiDi.mp4",
        "CH01_0012_ZhuJiao.mp4",
        "CH01_0015_YiTaiLingMiao.mp4",
        "CH01_0018_SiDiEr.mp4",
        "CH01_0025_AnYingTuanMan.mp4",
        "CH01_0028_AnYingTuanMan.mp4",
        "CH01_0031_QiYi.mp4",
        "CH01_0032_AnYingTuanBoss.mp4",
        "CH01_0050_QiYi.mp4",
        "CH01_0051_LiLa.mp4",
        "CH01_0052_LiLa.mp4",
        "CH01_0053_Niya.mp4",
        "CH01_0054_Niya.mp4",
        "Fg_Dremio_Idle.mp4",
        "Fg_Dremio_Sleep.mp4",
        "Fg_Dremio_Wakeup.mp4",
        "micb4n2e14578oly.mp4",
        "pptpart01.mp4",
        "EmergencyUI_Demo.mp4"
        };
    // Start is called before the first frame update
    void Start()
    {
    }

    // Update is called once per frame
    void Update()
    {
        
    }

    public void TestAVProFromAB(int id)
    {
        Debug.LogFormat("[TestUIVideo] ab begin");
        uiVideo1.PlayWithVideoName("ByteVideo"+id, true);
        Debug.LogFormat("[TestUIVideo] ab end");
    }
    public void TestAVProFromStream()
    {
        Debug.LogFormat("[TestUIVideo] stream begin");

        int index = m_nTest % videos.Length;
        if (uiVideo1) uiVideo1.Stop();
        if (uiVideo2) uiVideo2.Stop();
        if (m_nTest %2==0)
        {
            videoName1.text = videos[index];
            uiVideo1.Play(videos[index], false, true);
        }
        else
        {
            uiVideo2.Play(videos[index], false, true);
            videoName2.text = videos[index];
        }
        m_nTest++;
    }

    public void Change()
    {
        videoName1.text = GetRandomVideo();
        uiVideo1.Play(videoName1.text, false, true);
        uiVideo1.SetColorCutoff(0.217f);
    }

    string GetRandomVideo()
    {
        int index = UnityEngine.Random.Range(0, ranoms_videos.Length);
        return ranoms_videos[index];
    }
}
