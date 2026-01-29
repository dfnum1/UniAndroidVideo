package com.unity3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

//import junit.framework.Assert;

public class Texture2DExt extends com.unity3d.Texture2D {

        private static final String TAG = Texture2DExt.class.getSimpleName();
        private int[] lastProgram = new int[1];
        private int[] lastTexture = new int[1];
        private int[] lastTextureOES = new int[1]; // 保存 EXTERNAL_OES 纹理绑定
        private int[] lastActiveTexture = new int[1];

        public Texture2DExt(Context context, int width, int height, boolean canVAO) {
                super(context, width, height, canVAO);

                mContext = context;
                initVertex();
                initShader();
                createProgram();

                int[] temps = new int[1];
                GLES20.glGenTextures(1, temps, 0);
                mTextureID = temps[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
                Utils.checkGlError("glBindTexture mTextureID");

                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                                GLES20.GL_NEAREST);
                GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                                GLES20.GL_LINEAR);

                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                                GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                                GLES20.GL_CLAMP_TO_EDGE);

                // GLES20.glTexImage2D(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, GLES20.GL_RGBA,
                // width, height, 0,
                // GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                // GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

                mWidth = width;
                mHeight = height;

        }

        public Texture2DExt(Context context, Bitmap bitmap, boolean canVAO) {
                super(context, bitmap, canVAO);
        }

        @Override
        protected void initShader() {
                super.initShader();
                // 顶点着色器 - 使用单位矩阵作为MVP，纹理变换应用到纹理坐标
                mVertexCode = "attribute vec4 aPosition;\n";
                mVertexCode += "attribute mediump vec2 aTextureCoord;\n";
                mVertexCode += "varying mediump vec2 vTextureCoord;\n";
                mVertexCode += "uniform mat4 uSTMatrix;\n"; // 纹理变换矩阵
                mVertexCode += "void main() {\n";
                mVertexCode += "  gl_Position = vec4(aPosition.xy, 0.0, 1.0);\n"; // 使用原始顶点位置，不应用MVP变换
                mVertexCode += "  vTextureCoord = (uSTMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;\n"; // 应用纹理变换
                mVertexCode += "}\n";
        }

        @Override
        public void draw(float[] stMatrix, boolean bClear) {

                // Log.d(TAG, "draw");
                if (bClear) {
                        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                        Utils.checkGlError("glClearColor1");
                        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
                        Utils.checkGlError("glClearColor2");
                }

                // 保存当前OpenGL状态
                GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, lastProgram, 0);
                GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, lastTexture, 0);
                GLES20.glGetIntegerv(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES, lastTextureOES, 0); // 保存 OES 纹理
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
                        int[] lastVAO = new int[1];
                        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, lastVAO, 0);
                        lastBindeVAO = lastVAO[0];
                        GLES30.glBindVertexArray(0);
                }

                // 一定要加这两行，不然会出现OUF OF MEMORY错误
                // http://forum.unity3d.com/threads/mixing-unity-with-native-opengl-drawing-on-android.134621/
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

                // GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                //
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
                int positionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
                Utils.checkGlError("glGetAttribLocation aPosition");

                // Enable a handle to the triangle vertices
                GLES20.glEnableVertexAttribArray(positionHandle);

                // Prepare the triangle coordinate data
                GLES20.glVertexAttribPointer(
                                positionHandle, COORDS_PER_VERTEX,
                                GLES20.GL_FLOAT, false,
                                vertexStride, vertexBuffer);

                // Assert.assertNotNull(vertexBuffer);
                //
                int maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
                GLES20.glVertexAttribPointer(
                                maTextureHandle, 2,
                                GLES20.GL_FLOAT, false,
                                0, uvBuffer);

                // Assert.assertNotNull(uvBuffer);

                GLES20.glEnableVertexAttribArray(maTextureHandle);
                //
                int mSamplerLoc = GLES20.glGetUniformLocation(mProgram, "sTexture");
                GLES20.glUniform1i(mSamplerLoc, 0);
                //
                int stMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
                //
                // // Pass the projection and view transformation to the shader
                GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0);

                // // Draw the square
                //// GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDrawElements(
                                GLES20.GL_TRIANGLES, drawOrder.length,
                                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
                // Assert.assertNotNull(drawOrder);
                // Assert.assertNotNull(drawListBuffer);
                Utils.checkGlError("glDrawElements");
                //
                // Disable vertex array
                GLES20.glDisableVertexAttribArray(positionHandle);
                GLES20.glDisableVertexAttribArray(maTextureHandle);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

                if (!m_CanUseGLBindVertexArray && lastBindeVAO != 0) {
                        GLES30.glBindVertexArray(lastBindeVAO);
                }

                if (isDepthTest)
                        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
                if (isCullFace)
                        GLES20.glEnable(GLES20.GL_CULL_FACE);
                if (isScissorTest)
                        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);

                // 恢复之前的OpenGL状态（必须先恢复active texture，再恢复绑定）
                GLES20.glActiveTexture(lastActiveTexture[0]);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, lastTextureOES[0]); // 恢复 OES 纹理
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lastTexture[0]);
                GLES20.glUseProgram(lastProgram[0]);
        }
}