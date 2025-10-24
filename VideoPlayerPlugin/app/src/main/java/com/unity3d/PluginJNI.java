package com.unity3d;

import com.unity3d.exovideo.ExoPlayerUnity;
import com.unity3d.video.MoblieVideo;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import android.util.Log;

public class PluginJNI 
{
  public static void OnRendererEvent(int eventId, int classId)
  {
    if(classId == 2)
      ExoPlayerUnity.OnRendererEvent(eventId);
    else if(classId == 1)
      MoblieVideo.OnRendererEvent(eventId);
  }

  public static void CreateSurface(Surface surface, int classId, int playerIndex, int texture)
  {
  }
}
