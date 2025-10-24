package com.unity3d.exovideo;

public interface IUnityMessage
{
    default void CreateOESTexture(int textureID) {}
    default int CreateSizeOESTexture(int width, int height) { return 0; }
    default void OnPlayWhenReadyChanged(boolean playWhenReady, int reason){}
    default void OnPlaybackStateChanged(int playbackState){}
}
