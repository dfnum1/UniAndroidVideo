package com.unity3d;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLES30;

public class Texture2DExtYUV extends Texture2DExt {

        private static final String TAG = Texture2DExtYUV.class.getSimpleName();

        public Texture2DExtYUV(Context context, int width, int height, boolean canVAO) {
                super(context, width, height, canVAO);
                initVertex();
                initShaderRGBA();
                createProgram();

                int[] temps = new int[1];
                GLES20.glGenTextures(1, temps, 0);
                mTextureID = temps[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID);
                Utils.checkGlError("glBindTexture mTextureID");

                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                                GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                                GLES20.GL_LINEAR);

                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                                GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                                GLES20.GL_CLAMP_TO_EDGE);

                // 初始化纹理数据为黑色
                if (width > 0 && height > 0) {
                        byte[] blackData = new byte[width * height * 4];
                        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA,
                                        GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(blackData));
                        Utils.checkGlError("glTexImage2D initialization");
                }

                mWidth = width;
                mHeight = height;
        }

        @Override
        protected void initShader() {
                super.initShader();
        }

        private void initShaderRGBA() {
                mVertexCode = "attribute vec4 aPosition;\n";
                mVertexCode += "attribute mediump vec2 aTextureCoord;\n";
                mVertexCode += "varying mediump vec2 vTextureCoord;\n";
                mVertexCode += "void main() {\n";
                mVertexCode += "  gl_Position = vec4(aPosition.xy, 0.0, 1.0);\n";
                mVertexCode += "  vTextureCoord = vec2(aTextureCoord.x, 1.0 - aTextureCoord.y);\n";
                mVertexCode += "}\n";

                mFragmentCode = "precision mediump float;\n";
                mFragmentCode += "varying mediump vec2 vTextureCoord;\n";
                mFragmentCode += "uniform sampler2D sTexture;\n";
                mFragmentCode += "void main() {\n";
                mFragmentCode += "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n";
                mFragmentCode += "}\n";
        }

        @Override
        public void draw(float[] stMatrix, boolean bClear) {
                if (bClear) {
                        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                        Utils.checkGlError("glClearColor1");
                        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
                        Utils.checkGlError("glClearColor2");
                }

                // 保存当前OpenGL状态
                GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, lastProgram, 0);
                GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, lastTexture, 0);
                GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, lastActiveTexture, 0);

                boolean isDepthTest = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
                boolean isCullFace = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
                boolean isScissorTest = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);

                GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                GLES20.glDisable(GLES20.GL_CULL_FACE);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

                GLES20.glUseProgram(mProgram);

                int lastBindeVAO = 0;
                if (!m_CanUseGLBindVertexArray) {
                        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, mlastVAO, 0);
                        lastBindeVAO = mlastVAO[0];
                        GLES30.glBindVertexArray(0);
                }

                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID);

                int positionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
                Utils.checkGlError("glGetAttribLocation aPosition");
                GLES20.glEnableVertexAttribArray(positionHandle);
                GLES20.glVertexAttribPointer(
                                positionHandle, COORDS_PER_VERTEX,
                                GLES20.GL_FLOAT, false,
                                vertexStride, vertexBuffer);

                int maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
                GLES20.glVertexAttribPointer(
                                maTextureHandle, 2,
                                GLES20.GL_FLOAT, false,
                                0, uvBuffer);
                GLES20.glEnableVertexAttribArray(maTextureHandle);

                int mSamplerLoc = GLES20.glGetUniformLocation(mProgram, "sTexture");
                GLES20.glUniform1i(mSamplerLoc, 0);

                GLES20.glDrawElements(
                                GLES20.GL_TRIANGLES, drawOrder.length,
                                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
                Utils.checkGlError("glDrawElements");

                GLES20.glDisableVertexAttribArray(positionHandle);
                GLES20.glDisableVertexAttribArray(maTextureHandle);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

                if (!m_CanUseGLBindVertexArray && lastBindeVAO != 0) {
                        GLES30.glBindVertexArray(lastBindeVAO);
                }

                if (isDepthTest)
                        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
                if (isCullFace)
                        GLES20.glEnable(GLES20.GL_CULL_FACE);
                if (isScissorTest)
                        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);

                // 恢复之前的OpenGL状态
                GLES20.glActiveTexture(lastActiveTexture[0]);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lastTexture[0]);
                GLES20.glUseProgram(lastProgram[0]);
        }

        public void updateTexture(int width, int height, byte[] data) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureID);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                                java.nio.ByteBuffer.wrap(data));
                Utils.checkGlError("glTexImage2D");
                mWidth = width;
                mHeight = height;
        }
}
