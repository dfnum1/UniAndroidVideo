using GameApp.UIComponent;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;

public class NewBehaviourScript : MonoBehaviour
{
    public Slider seekProcess;
    public UIVideo seekVideo;


    public Text videoText1;
    public Text videoText2;

    public UIVideo video1;
    public UIVideo video2;
    string[] videos =
    {
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
        "CH01_0031_QiYi.mp4",
        "CH01_0032_AnYingTuanBoss.mp4",
        "CH01_0050_QiYi.mp4",
        "CH01_0051_LiLa.mp4",
        "CH01_0052_LiLa.mp4",
        "CH01_0053_Niya.mp4",
        "CH01_0054_Niya.mp4",
        "Fg_Dremio_Idle.mp4",
        "CH01_0051_LiLa.mp4"
    };
    int m_nPlayerIndex = 0;
    // Start is called before the first frame update
    void Start()
    {
        
    }

    // Update is called once per frame
    void Update()
    {

        if (video1 != null && videoText1)
        {
            videoText1.text = video1.GetCurTime() + "/" + video1.GetDuration();
        }

        if (video2 != null && videoText2)
        {
            videoText2.text = video2.GetCurTime() + "/" + video2.GetDuration();
        }

        if(seekVideo && seekProcess)
        {
            seekVideo.SeekNormalTime(seekProcess.value);
        }
    }

    public void Stop()
    {
        if (video1 != null) video1.Stop();
        if (video2 != null) video2.Stop();
    }
    public void RePlay()
    {
        if(m_nPlayerIndex%2==0)
        {
            if (video2 != null) video2.Stop();
            video1.Play(videos[m_nPlayerIndex % videos.Length], false, true);
        }
        else
        {
            if (video1 != null) video1.Stop();
            video2.Play(videos[m_nPlayerIndex % videos.Length], false, true);
        }
        m_nPlayerIndex++;
    }
}
