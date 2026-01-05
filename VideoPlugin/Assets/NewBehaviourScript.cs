using GameApp.UIComponent;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;

public class NewBehaviourScript : MonoBehaviour
{
    public UIVideo uiVideo;
    // Start is called before the first frame update
    void Start()
    {
        
    }

    // Update is called once per frame
    void Update()
    {
        
    }

    public void Stop()
    {
        uiVideo.Stop();
    }
    public void RePlay()
    {
        uiVideo.Play("Fg_Dremio_Wakeup.mp4", false,true);
    }
}
