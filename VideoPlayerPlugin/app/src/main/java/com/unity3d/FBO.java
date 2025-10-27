package com.unity3d;

import android.opengl.GLES20;
import android.opengl.GLES30;

/**
 * Created by eleven on 16/9/7.
 */
public class FBO {
    private Texture2D mTexture2D;
    private int mDepthTextureID;
    private int mFBOID;
    private int mRBOID;

    private int mOldFBO;

    public FBO(Texture2D texture2D) {
        mTexture2D = texture2D;
        int[] temps = new int[1];
        // Render buffer
        GLES20.glGenTextures(1, temps, 0);
        mDepthTextureID = temps[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, temps[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_DEPTH_COMPONENT, texture2D.getWidth(), texture2D.getHeight(), 0, GLES20.GL_DEPTH_COMPONENT, GLES20.GL_UNSIGNED_SHORT, null);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glGenFramebuffers(1, temps, 0);
        mFBOID = temps[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFBOID);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, mTexture2D.getTextureID(), 0);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_TEXTURE_2D, mDepthTextureID, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);


    }

    public void FBOBegin() {

         int[] oldFBO = new int[1];
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, oldFBO, 0);
        mOldFBO = oldFBO[0]; // 成员变量

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFBOID);

      //  GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
     //   GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        Utils.checkGlError("FBOBegin");

    }

    public void FBOEnd() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
         Utils.checkGlError("FBOEnd");

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mOldFBO);
    }

    public void destory()
    {
        if(mFBOID!=0)
        {
            int[] fbo = {mFBOID};
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            mFBOID = 0;
        }
        if (mDepthTextureID != 0) {
            GLES20.glDeleteTextures(1, new int[]{mDepthTextureID}, 0);
            mDepthTextureID = 0;
        }
    }
}