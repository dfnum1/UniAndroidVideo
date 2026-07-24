package com.unity3d;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLES30;

import java.nio.ByteBuffer;

public class Texture2DExtYUV extends Texture2DExt {

        private static final String TAG = Texture2DExtYUV.class.getSimpleName();

        // Y / U / V 三个平面各自的纹理，YUV->RGB 转换放到 GPU（片元着色器）里完成，
        // 避免在 CPU 上逐像素做浮点转换。
        private int mTexY;
        private int mTexU;
        private int mTexV;

        // 采样时用于把纹理宽度（可能等于 rowStride）折算回有效画面宽度的比例。
        private float mYSampleScale = 1.0f;
        private float mUVSampleScale = 1.0f;

        // RGB 渲染路径（用于 MuMu 等模拟器上解码器直接输出 RGB_565 / RGBA_8888 的情况）。
        // 单独一个 program 与一张 RGB 纹理，与 YUV 路径互不影响。
        private int mRgbProgram = 0;
        private int mTexRGB = 0;
        // 把纹理宽度（可能等于 rowStride/pixelStride 折算出的像素数）折算回有效画面宽度。
        private float mRgbSampleScale = 1.0f;

        public Texture2DExtYUV(Context context, int width, int height, boolean canVAO) {
                super(context, width, height, canVAO);

                // 父类构造里创建了一个 OES 纹理并存入 mTextureID，本类用不到，先删除避免泄漏。
                if (mTextureID != 0) {
                        GLES20.glDeleteTextures(1, new int[] { mTextureID }, 0);
                        mTextureID = 0;
                }

                initVertex();
                initShaderRGBA();
                createProgram();

                mTexY = createLuminanceTexture();
                mTexU = createLuminanceTexture();
                mTexV = createLuminanceTexture();

                mWidth = width;
                mHeight = height;
        }

        private int createLuminanceTexture() {
                int[] temps = new int[1];
                GLES20.glGenTextures(1, temps, 0);
                int texId = temps[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                Utils.checkGlError("createLuminanceTexture");
                return texId;
        }

        // 惰性创建 RGB 渲染所需的 program 与纹理。
        private void ensureRgbResources() {
                if (mRgbProgram != 0)
                        return;

                String vertexCode = "attribute vec4 aPosition;\n"
                                + "attribute mediump vec2 aTextureCoord;\n"
                                + "varying mediump vec2 vTextureCoord;\n"
                                + "void main() {\n"
                                + "  gl_Position = vec4(aPosition.xy, 0.0, 1.0);\n"
                                + "  vTextureCoord = vec2(aTextureCoord.x, 1.0 - aTextureCoord.y);\n"
                                + "}\n";

                String fragmentCode = "precision mediump float;\n"
                                + "varying mediump vec2 vTextureCoord;\n"
                                + "uniform sampler2D sTextureRGB;\n"
                                + "uniform float uRgbScale;\n"
                                + "void main() {\n"
                                + "  vec2 coord = vec2(vTextureCoord.x * uRgbScale, vTextureCoord.y);\n"
                                + "  gl_FragColor = vec4(texture2D(sTextureRGB, coord).rgb, 1.0);\n"
                                + "}\n";

                mRgbProgram = GLES20.glCreateProgram();
                int vs = Utils.loadShader(GLES20.GL_VERTEX_SHADER, vertexCode);
                int fs = Utils.loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode);
                GLES20.glAttachShader(mRgbProgram, vs);
                GLES20.glAttachShader(mRgbProgram, fs);
                GLES20.glLinkProgram(mRgbProgram);

                int[] temps = new int[1];
                GLES20.glGenTextures(1, temps, 0);
                mTexRGB = temps[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexRGB);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                Utils.checkGlError("ensureRgbResources");
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

                // 在片元着色器里完成 YUV(BT.601) -> RGB 转换，充分利用 GPU 并行能力。
                mFragmentCode = "precision mediump float;\n";
                mFragmentCode += "varying mediump vec2 vTextureCoord;\n";
                mFragmentCode += "uniform sampler2D sTextureY;\n";
                mFragmentCode += "uniform sampler2D sTextureU;\n";
                mFragmentCode += "uniform sampler2D sTextureV;\n";
                mFragmentCode += "uniform float uYScale;\n";
                mFragmentCode += "uniform float uUVScale;\n";
                mFragmentCode += "void main() {\n";
                mFragmentCode += "  vec2 yCoord = vec2(vTextureCoord.x * uYScale, vTextureCoord.y);\n";
                mFragmentCode += "  vec2 uvCoord = vec2(vTextureCoord.x * uUVScale, vTextureCoord.y);\n";
                mFragmentCode += "  float y = texture2D(sTextureY, yCoord).r;\n";
                mFragmentCode += "  float u = texture2D(sTextureU, uvCoord).r - 0.5;\n";
                mFragmentCode += "  float v = texture2D(sTextureV, uvCoord).r - 0.5;\n";
                mFragmentCode += "  float r = y + 1.402 * v;\n";
                mFragmentCode += "  float g = y - 0.344 * u - 0.714 * v;\n";
                mFragmentCode += "  float b = y + 1.772 * u;\n";
                mFragmentCode += "  gl_FragColor = vec4(r, g, b, 1.0);\n";
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

                // 绑定 Y / U / V 三个纹理到不同的纹理单元
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexY);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(mProgram, "sTextureY"), 0);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexU);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(mProgram, "sTextureU"), 1);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexV);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(mProgram, "sTextureV"), 2);

                GLES20.glUniform1f(GLES20.glGetUniformLocation(mProgram, "uYScale"), mYSampleScale);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(mProgram, "uUVScale"), mUVSampleScale);

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

                GLES20.glDrawElements(
                                GLES20.GL_TRIANGLES, drawOrder.length,
                                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
                Utils.checkGlError("glDrawElements");

                GLES20.glDisableVertexAttribArray(positionHandle);
                GLES20.glDisableVertexAttribArray(maTextureHandle);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
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
                // EGL context 重建后，旧 context 的纹理名称可能已经失效。
                // 不要把失效的纹理 ID 再绑定回去，否则 MuMu 会返回 GL_INVALID_OPERATION。
                int textureToRestore = lastTexture[0];
                if (textureToRestore != 0 && !GLES20.glIsTexture(textureToRestore)) {
                        textureToRestore = 0;
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureToRestore);
                GLES20.glUseProgram(lastProgram[0]);
        }

        /**
         * 直接上传 YUV_420_888 的三个平面，YUV->RGB 转换交由片元着色器完成。
         *
         * @param width        画面宽度
         * @param height       画面高度
         * @param yPlane       Y 平面数据（长度为 yRowStride * height）
         * @param uPlane       U 平面数据
         * @param vPlane       V 平面数据
         * @param yRowStride   Y 平面行跨度
         * @param uvRowStride  U/V 平面行跨度
         */
        public void updateYUV(int width, int height,
                        ByteBuffer yPlane, ByteBuffer uPlane, ByteBuffer vPlane,
                        int yRowStride, int uvRowStride, int uvPixelStride) {
                int chromaWidth = (width + 1) / 2;
                int chromaHeight = (height + 1) / 2;

                // 将纹理宽度设为 rowStride（以字节为单位），避免在 CPU 上做行压缩拷贝；
                // 采样时通过 uYScale / uUVScale 把有效宽度折算回来。
                uploadPlane(mTexY, yRowStride, height, yPlane);
                uploadPlane(mTexU, uvRowStride, chromaHeight, uPlane);
                uploadPlane(mTexV, uvRowStride, chromaHeight, vPlane);

                // Y: 画面 x∈[0,1] -> 字节列 x*width，纹理宽度为 yRowStride
                mYSampleScale = yRowStride > 0 ? (float) width / (float) yRowStride : 1.0f;
                // U/V: 画面 x∈[0,1] -> chroma 列 x*chromaWidth -> 字节列 x*chromaWidth*pixelStride
                // 兼容 planar(pixelStride=1) 与 semi-planar(pixelStride=2) 两种排布
                mUVSampleScale = uvRowStride > 0
                                ? (float) (chromaWidth * uvPixelStride) / (float) uvRowStride
                                : 1.0f;

                mWidth = width;
                mHeight = height;
        }

        private void uploadPlane(int texId, int texWidth, int texHeight, ByteBuffer buffer) {
                buffer.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                                texWidth, texHeight, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
                                buffer);
                Utils.checkGlError("uploadPlane");
        }

        /**
         * 上传 RGB_565 单平面数据（模拟器上解码器可能直接输出该格式）。
         *
         * @param width      画面宽度
         * @param height     画面高度
         * @param rgbPlane   RGB_565 数据
         * @param rowStride  行跨度（字节）
         */
        public void updateRGB565(int width, int height, ByteBuffer rgbPlane, int rowStride) {
                ensureRgbResources();

                // 每像素 2 字节；把纹理宽度设为 rowStride/2（像素数），避免 CPU 行压缩拷贝。
                int texWidth = rowStride > 0 ? rowStride / 2 : width;
                rgbPlane.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexRGB);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                                texWidth, height, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5,
                                rgbPlane);
                Utils.checkGlError("updateRGB565");

                mRgbSampleScale = texWidth > 0 ? (float) width / (float) texWidth : 1.0f;
                mWidth = width;
                mHeight = height;
        }

        /**
         * 上传 RGBA_8888 单平面数据。
         *
         * @param width      画面宽度
         * @param height     画面高度
         * @param rgbaPlane  RGBA_8888 数据
         * @param rowStride  行跨度（字节）
         */
        public void updateRGBA8888(int width, int height, ByteBuffer rgbaPlane, int rowStride) {
                ensureRgbResources();

                // 每像素 4 字节；把纹理宽度设为 rowStride/4（像素数）。
                int texWidth = rowStride > 0 ? rowStride / 4 : width;
                rgbaPlane.position(0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexRGB);
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                                texWidth, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                                rgbaPlane);
                Utils.checkGlError("updateRGBA8888");

                mRgbSampleScale = texWidth > 0 ? (float) width / (float) texWidth : 1.0f;
                mWidth = width;
                mHeight = height;
        }

        /** 使用 RGB 路径绘制（配合 updateRGB565 / updateRGBA8888 使用）。 */
        public void drawRGB() {
                if (mRgbProgram == 0)
                        return;

                GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, lastProgram, 0);
                GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, lastTexture, 0);
                GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, lastActiveTexture, 0);

                boolean isDepthTest = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST);
                boolean isCullFace = GLES20.glIsEnabled(GLES20.GL_CULL_FACE);
                boolean isScissorTest = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);

                GLES20.glDisable(GLES20.GL_DEPTH_TEST);
                GLES20.glDisable(GLES20.GL_CULL_FACE);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

                GLES20.glUseProgram(mRgbProgram);

                int lastBindeVAO = 0;
                if (!m_CanUseGLBindVertexArray) {
                        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, mlastVAO, 0);
                        lastBindeVAO = mlastVAO[0];
                        GLES30.glBindVertexArray(0);
                }

                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexRGB);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(mRgbProgram, "sTextureRGB"), 0);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(mRgbProgram, "uRgbScale"), mRgbSampleScale);

                int positionHandle = GLES20.glGetAttribLocation(mRgbProgram, "aPosition");
                GLES20.glEnableVertexAttribArray(positionHandle);
                GLES20.glVertexAttribPointer(
                                positionHandle, COORDS_PER_VERTEX,
                                GLES20.GL_FLOAT, false,
                                vertexStride, vertexBuffer);

                int maTextureHandle = GLES20.glGetAttribLocation(mRgbProgram, "aTextureCoord");
                GLES20.glVertexAttribPointer(
                                maTextureHandle, 2,
                                GLES20.GL_FLOAT, false,
                                0, uvBuffer);
                GLES20.glEnableVertexAttribArray(maTextureHandle);

                GLES20.glDrawElements(
                                GLES20.GL_TRIANGLES, drawOrder.length,
                                GLES20.GL_UNSIGNED_SHORT, drawListBuffer);
                Utils.checkGlError("drawRGB glDrawElements");

                GLES20.glDisableVertexAttribArray(positionHandle);
                GLES20.glDisableVertexAttribArray(maTextureHandle);

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
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

                GLES20.glActiveTexture(lastActiveTexture[0]);
                int textureToRestore = lastTexture[0];
                if (textureToRestore != 0 && !GLES20.glIsTexture(textureToRestore)) {
                        textureToRestore = 0;
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureToRestore);
                GLES20.glUseProgram(lastProgram[0]);
        }

        @Override
        public void destory() {
                int[] texs = { mTexY, mTexU, mTexV };
                GLES20.glDeleteTextures(3, texs, 0);
                mTexY = 0;
                mTexU = 0;
                mTexV = 0;
                if (mTexRGB != 0) {
                        GLES20.glDeleteTextures(1, new int[] { mTexRGB }, 0);
                        mTexRGB = 0;
                }
                if (mRgbProgram != 0) {
                        GLES20.glDeleteProgram(mRgbProgram);
                        mRgbProgram = 0;
                }
                super.destory();
        }
}
