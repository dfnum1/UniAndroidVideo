using GameApp.UIComponent;
using UnityEngine;

public class TestUIVideo : MonoBehaviour
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

    public void TestAVProFromAB(int id)
    {
        Debug.LogFormat("[TestUIVideo] ab begin");
        uiVideo.PlayWithVideoName("ByteVideo"+id, true);
        Debug.LogFormat("[TestUIVideo] ab end");
    }
    public void TestAVProFromStream(int id)
    {
        Debug.LogFormat("[TestUIVideo] stream begin");
        if (id == 1)
        {
            uiVideo.Play("Video/pptpart01.mp4", false, true);
        }
        else
        {
            uiVideo.Play("Video/output.mp4", false, true);
        } 
        Debug.LogFormat("[TestUIVideo] stream end");
    }
}
