package com.unity3d.exovideo;

public interface IUnityMessage
{
    default void OnPlayWhenReadyChanged(boolean playWhenReady, int reason){}
    default void OnPlaybackStateChanged(int playbackState){}
    default void OnVideoRenderBegin(int index){}
    default void OnVideoRenderEnd(int index){}
}
