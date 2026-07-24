//
// YUV_420_888 → RGBA_8888 和 RGB_565 → RGBA_8888 的 ARM NEON 加速实现
//
// 编译要求：Android NDK, ARMv7a NEON 或 ARM64 (AArch64)
// 在 CMakeLists.txt 中添加此文件即可
//

#include <jni.h>
#include <android/log.h>

// NEON intrinsics are only available on ARM targets (armeabi-v7a with NEON, arm64-v8a)
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#define LOG_TAG "YuvToRgbaNeon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// NEON-accelerated implementations — only available on ARM targets
// ============================================================
#if defined(__ARM_NEON) || defined(__ARM_NEON__)

// ============================================================
// YUV_420_888 (NV21 布局) → RGBA_8888
// 使用 ARM NEON SIMD 指令，一次处理 8 像素
//
// BT.601 整数公式（与 Java 侧 LUT 一致）：
//   R = Y + ((V-128)*1436 + 512) >> 10
//   G = Y - ((U-128)*352  + 512) >> 10 - ((V-128)*731  + 512) >> 10
//   B = Y + ((U-128)*1815 + 512) >> 10
// ============================================================
extern "C"
JNIEXPORT void JNICALL
Java_com_unity3d_exovideo_ExoPlayerUnity_nativeYuvToRgba(
        JNIEnv *env, jclass clazz,
        jobject yBufObj, jobject uBufObj, jobject vBufObj,
        jbyteArray outDataArr,
        jint width, jint height,
        jint yRowStride, jint uvRowStride, jint uvPixelStride) {

    // 获取 DirectByteBuffer 指针
    auto *yBuf = (uint8_t *) env->GetDirectBufferAddress(yBufObj);
    auto *uBuf = (uint8_t *) env->GetDirectBufferAddress(uBufObj);
    auto *vBuf = (uint8_t *) env->GetDirectBufferAddress(vBufObj);
    if (!yBuf || !uBuf || !vBuf) {
        LOGE("GetDirectBufferAddress failed");
        return;
    }

    jbyte *outData = env->GetByteArrayElements(outDataArr, nullptr);
    if (!outData) {
        LOGE("GetByteArrayElements failed");
        return;
    }

    // BT.601 系数（Q10 定点数，与 Java 侧一致）
    const int16x8_t vC1436 = vdupq_n_s16(1436);
    const int16x8_t vC352  = vdupq_n_s16(352);
    const int16x8_t vC731  = vdupq_n_s16(731);
    const int16x8_t vC1815 = vdupq_n_s16(1815);
    const int16x8_t vC128  = vdupq_n_s16(128);
    const int32x4_t vC512_32 = vdupq_n_s32(512);  // 32-bit 版本，用于 32-bit 累加
    const uint8x8_t vAlpha = vdup_n_u8(255);

    for (int i = 0; i < height; i++) {
        int destRowBase = ((height - 1 - i) * width) << 2;
        int uvRowBase = (i >> 1) * uvRowStride;
        int yRowBase = i * yRowStride;

        // 逐 8 像素处理
        int j = 0;
        for (; j + 7 < width; j += 8) {
            // 加载 8 个 Y 值
            uint8x8_t y8 = vld1_u8(yBuf + yRowBase + j);

            // 计算 UV 索引：每个 UV 对应 2x2 像素块
            // YUV420 中 UV 分辨率减半，8 个 Y 像素只需要 4 组 UV
            int uvBase = uvRowBase + (j >> 1) * uvPixelStride;

            // 加载 U, V 值（uvPixelStride 通常为 1 或 2）
            // 关键：只加载 4 个 UV 值，然后每个复制一份给相邻的两个 Y 像素
            uint8x8_t u8, v8;
            if (uvPixelStride == 1) {
                // 紧凑布局：U 和 V 平面各自连续
                // 加载 8 个 UV 值，但只使用前 4 个（对应 8 个 Y 像素）
                // 需要将 4 个 UV 值每个复制一份：U[0],U[0],U[1],U[1],U[2],U[2],U[3],U[3]
                uint8x8_t u4 = vld1_u8(uBuf + uvBase);
                uint8x8_t v4 = vld1_u8(vBuf + uvBase);
                // vzip_u8(a, b) 返回 {a[0],b[0],a[1],b[1],...}
                // 用 vzip_u8(u4, u4) 得到 {u4[0],u4[0],u4[1],u4[1],...}
                uint8x8x2_t u_zipped = vzip_u8(u4, u4);
                uint8x8x2_t v_zipped = vzip_u8(v4, v4);
                u8 = u_zipped.val[0];
                v8 = v_zipped.val[0];
            } else {
                // 隔行布局（uvPixelStride >= 2）
                // 手动加载 4 个 UV 值，每个复制一份
                uint8_t utmp[8], vtmp[8];
                for (int k = 0; k < 4; k++) {
                    int idx = k * uvPixelStride;
                    uint8_t uVal = uBuf[uvBase + idx];
                    uint8_t vVal = vBuf[uvBase + idx];
                    utmp[k * 2]     = uVal;
                    utmp[k * 2 + 1] = uVal;
                    vtmp[k * 2]     = vVal;
                    vtmp[k * 2 + 1] = vVal;
                }
                u8 = vld1_u8(utmp);
                v8 = vld1_u8(vtmp);
            }

            // 扩展到 16-bit
            int16x8_t y16 = vreinterpretq_s16_u16(vmovl_u8(y8));
            int16x8_t u16 = vreinterpretq_s16_u16(vmovl_u8(u8));
            int16x8_t v16 = vreinterpretq_s16_u16(vmovl_u8(v8));

            // U -= 128, V -= 128
            int16x8_t us = vsubq_s16(u16, vC128);
            int16x8_t vs = vsubq_s16(v16, vC128);

            // R = Y + ((V * 1436 + 512) >> 10)
            int32x4_t vr_lo = vmull_s16(vget_low_s16(vs), vget_low_s16(vC1436));
            int32x4_t vr_hi = vmull_s16(vget_high_s16(vs), vget_high_s16(vC1436));
            vr_lo = vaddq_s32(vr_lo, vC512_32);
            vr_hi = vaddq_s32(vr_hi, vC512_32);
            int16x8_t vr = vcombine_s16(vshrn_n_s32(vr_lo, 10), vshrn_n_s32(vr_hi, 10));
            int16x8_t r16 = vaddq_s16(y16, vr);

            // G = Y - ((U * 352 + 512) >> 10) - ((V * 731 + 512) >> 10)
            int32x4_t ug_lo = vmull_s16(vget_low_s16(us), vget_low_s16(vC352));
            int32x4_t ug_hi = vmull_s16(vget_high_s16(us), vget_high_s16(vC352));
            ug_lo = vaddq_s32(ug_lo, vC512_32);
            ug_hi = vaddq_s32(ug_hi, vC512_32);
            int16x8_t ug = vcombine_s16(vshrn_n_s32(ug_lo, 10), vshrn_n_s32(ug_hi, 10));

            int32x4_t vg_lo = vmull_s16(vget_low_s16(vs), vget_low_s16(vC731));
            int32x4_t vg_hi = vmull_s16(vget_high_s16(vs), vget_high_s16(vC731));
            vg_lo = vaddq_s32(vg_lo, vC512_32);
            vg_hi = vaddq_s32(vg_hi, vC512_32);
            int16x8_t vg = vcombine_s16(vshrn_n_s32(vg_lo, 10), vshrn_n_s32(vg_hi, 10));

            int16x8_t g16 = vsubq_s16(vsubq_s16(y16, ug), vg);

            // B = Y + ((U * 1815 + 512) >> 10)
            int32x4_t ub_lo = vmull_s16(vget_low_s16(us), vget_low_s16(vC1815));
            int32x4_t ub_hi = vmull_s16(vget_high_s16(us), vget_high_s16(vC1815));
            ub_lo = vaddq_s32(ub_lo, vC512_32);
            ub_hi = vaddq_s32(ub_hi, vC512_32);
            int16x8_t ub = vcombine_s16(vshrn_n_s32(ub_lo, 10), vshrn_n_s32(ub_hi, 10));
            int16x8_t b16 = vaddq_s16(y16, ub);

            // 钳位到 [0, 255]
            uint8x8_t r8 = vqmovun_s16(r16);
            uint8x8_t g8 = vqmovun_s16(g16);
            uint8x8_t b8 = vqmovun_s16(b16);

            // 交织为 RGBA 顺序：R0,G0,B0,A0, R1,G1,B1,A1, ...
            uint8x8x4_t rgba;
            rgba.val[0] = r8;  // R
            rgba.val[1] = g8;  // G
            rgba.val[2] = b8;  // B
            rgba.val[3] = vAlpha; // A = 255

            // 存储到输出
            vst4_u8((uint8_t *)(outData + destRowBase + (j << 2)), rgba);
        }

        // 尾部剩余像素（< 8 个），用纯 C 回退
        for (; j < width; j++) {
            int y = yBuf[yRowBase + j] & 0xFF;
            int uvIdx = (j >> 1) * uvPixelStride;
            int u = uBuf[uvRowBase + uvIdx] & 0xFF;
            int v = vBuf[uvRowBase + uvIdx] & 0xFF;

            int r = y + (((v - 128) * 1436 + 512) >> 10);
            int g = y - (((u - 128) * 352 + 512) >> 10) - (((v - 128) * 731 + 512) >> 10);
            int b = y + (((u - 128) * 1815 + 512) >> 10);

            r = (r < 0) ? 0 : ((r > 255) ? 255 : r);
            g = (g < 0) ? 0 : ((g > 255) ? 255 : g);
            b = (b < 0) ? 0 : ((b > 255) ? 255 : b);

            int base = destRowBase + (j << 2);
            outData[base]     = (uint8_t) r;
            outData[base + 1] = (uint8_t) g;
            outData[base + 2] = (uint8_t) b;
            outData[base + 3] = (uint8_t) 255;
        }
    }

    env->ReleaseByteArrayElements(outDataArr, outData, 0);
}


// ============================================================
// RGB_565 → RGBA_8888 (ARM NEON)
// 一次处理 8 像素
// ============================================================
extern "C"
JNIEXPORT void JNICALL
Java_com_unity3d_exovideo_ExoPlayerUnity_nativeRgb565ToRgba(
        JNIEnv *env, jclass clazz,
        jobject inBufObj, jbyteArray outDataArr,
        jint width, jint height, jint rowStride) {

    auto *inBuf = (uint8_t *) env->GetDirectBufferAddress(inBufObj);
    if (!inBuf) {
        LOGE("GetDirectBufferAddress failed for RGB565 input");
        return;
    }

    jbyte *outData = env->GetByteArrayElements(outDataArr, nullptr);
    if (!outData) {
        LOGE("GetByteArrayElements failed for RGB565 output");
        return;
    }

    const uint8x8_t vAlpha = vdup_n_u8(255);

    for (int i = 0; i < height; i++) {
        int destRowBase = ((height - 1 - i) * width) << 2;
        int rowBase = i * rowStride;

        int j = 0;
        for (; j + 7 < width; j += 8) {
            // 加载 8 个 RGB_565 像素（16 字节）
            uint8x16_t pixel16 = vld1q_u8(inBuf + rowBase + (j << 1));

            // 将 8 个 16-bit 像素拆分为高低 8 字节
            uint16x8_t pixels = vreinterpretq_u16_u8(pixel16);

            // 提取 R (bits 15-11), G (bits 10-5), B (bits 4-0)
            // R: 左移 3 位扩展为 8-bit, 高位复制到低位
            uint16x8_t r5 = vshrq_n_u16(pixels, 11);          // 0000 0000 000R RRRR
            uint16x8_t r8 = vshlq_n_u16(r5, 3);               // 000R RRRR R000
            uint16x8_t r_extra = vshrq_n_u16(r5, 2);          // 0000 0000 0000 0RRR
            r8 = vorrq_u16(r8, r_extra);                      // 000R RRRR RRRR

            // G: 右移 5 提取, 左移 2, 高位复制
            // 注意：>>5 后高位混入了 R 分量，需先 &0x3F 隔离 G 的 6 位
            uint16x8_t g6 = vandq_u16(vshrq_n_u16(pixels, 5), vdupq_n_u16(0x3F));
            uint16x8_t g8 = vshlq_n_u16(g6, 2);               // GGGGGG00
            uint16x8_t g_extra = vshrq_n_u16(g6, 4);          // 000000GG
            g8 = vorrq_u16(g8, g_extra);                      // GGGGGGGG

            // B: 低 5 位, 左移 3, 高位复制
            uint16x8_t b5 = vandq_u16(pixels, vdupq_n_u16(0x1F));
            uint16x8_t b8 = vshlq_n_u16(b5, 3);
            uint16x8_t b_extra = vshrq_n_u16(b5, 2);
            b8 = vorrq_u16(b8, b_extra);

            // 压缩到 8-bit
            uint8x8_t r_lo = vmovn_u16(r8);
            uint8x8_t g_lo = vmovn_u16(g8);
            uint8x8_t b_lo = vmovn_u16(b8);

            // 交织为 RGBA
            uint8x8x4_t rgba;
            rgba.val[0] = r_lo;
            rgba.val[1] = g_lo;
            rgba.val[2] = b_lo;
            rgba.val[3] = vAlpha;

            vst4_u8((uint8_t *)(outData + destRowBase + (j << 2)), rgba);
        }

        // 尾部像素
        for (; j < width; j++) {
            int pixelOffset = (j << 1);
            int pixel = (inBuf[rowBase + pixelOffset] & 0xFF)
                      | ((inBuf[rowBase + pixelOffset + 1] & 0xFF) << 8);
            int r5 = (pixel >> 11) & 0x1F;
            int g6 = (pixel >> 5) & 0x3F;
            int b5 = pixel & 0x1F;
            int r = (r5 << 3) | (r5 >> 2);
            int g = (g6 << 2) | (g6 >> 4);
            int b = (b5 << 3) | (b5 >> 2);
            int base = destRowBase + (j << 2);
            outData[base]     = (uint8_t) r;
            outData[base + 1] = (uint8_t) g;
            outData[base + 2] = (uint8_t) b;
            outData[base + 3] = (uint8_t) 255;
        }
    }

    env->ReleaseByteArrayElements(outDataArr, outData, 0);
}

// ============================================================
// Pure C fallback — for x86/x86_64 or any non-ARM target
// Uses the same BT.601 formula, optimized with direct pointer
// arithmetic and local variables for the compiler to auto-vectorize
// ============================================================
#else

extern "C"
JNIEXPORT void JNICALL
Java_com_unity3d_exovideo_ExoPlayerUnity_nativeYuvToRgba(
        JNIEnv *env, jclass clazz,
        jobject yBufObj, jobject uBufObj, jobject vBufObj,
        jbyteArray outDataArr,
        jint width, jint height,
        jint yRowStride, jint uvRowStride, jint uvPixelStride) {

    auto *yBuf = (uint8_t *) env->GetDirectBufferAddress(yBufObj);
    auto *uBuf = (uint8_t *) env->GetDirectBufferAddress(uBufObj);
    auto *vBuf = (uint8_t *) env->GetDirectBufferAddress(vBufObj);
    if (!yBuf || !uBuf || !vBuf) {
        LOGE("GetDirectBufferAddress failed");
        return;
    }

    jbyte *outData = env->GetByteArrayElements(outDataArr, nullptr);
    if (!outData) {
        LOGE("GetByteArrayElements failed");
        return;
    }

    // BT.601 系数常量
    const int C1436 = 1436;
    const int C352  = 352;
    const int C731  = 731;
    const int C1815 = 1815;

    for (int i = 0; i < height; i++) {
        int destRowBase = ((height - 1 - i) * width) << 2;
        int uvRowBase = (i >> 1) * uvRowStride;
        int yRowBase = i * yRowStride;

        for (int j = 0; j < width; j++) {
            int y = yBuf[yRowBase + j] & 0xFF;
            int uvIdx = (j >> 1) * uvPixelStride;
            int u = uBuf[uvRowBase + uvIdx] & 0xFF;
            int v = vBuf[uvRowBase + uvIdx] & 0xFF;

            int r = y + (((v - 128) * C1436 + 512) >> 10);
            int g = y - (((u - 128) * C352  + 512) >> 10) - (((v - 128) * C731 + 512) >> 10);
            int b = y + (((u - 128) * C1815 + 512) >> 10);

            // 钳位
            if (r < 0) r = 0; else if (r > 255) r = 255;
            if (g < 0) g = 0; else if (g > 255) g = 255;
            if (b < 0) b = 0; else if (b > 255) b = 255;

            int base = destRowBase + (j << 2);
            outData[base]     = (uint8_t) r;
            outData[base + 1] = (uint8_t) g;
            outData[base + 2] = (uint8_t) b;
            outData[base + 3] = (uint8_t) 255;
        }
    }

    env->ReleaseByteArrayElements(outDataArr, outData, 0);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_unity3d_exovideo_ExoPlayerUnity_nativeRgb565ToRgba(
        JNIEnv *env, jclass clazz,
        jobject inBufObj, jbyteArray outDataArr,
        jint width, jint height, jint rowStride) {

    auto *inBuf = (uint8_t *) env->GetDirectBufferAddress(inBufObj);
    if (!inBuf) {
        LOGE("GetDirectBufferAddress failed for RGB565 input");
        return;
    }

    jbyte *outData = env->GetByteArrayElements(outDataArr, nullptr);
    if (!outData) {
        LOGE("GetByteArrayElements failed for RGB565 output");
        return;
    }

    for (int i = 0; i < height; i++) {
        int destRowBase = ((height - 1 - i) * width) << 2;
        int rowBase = i * rowStride;

        for (int j = 0; j < width; j++) {
            int pixelOffset = (j << 1);
            int pixel = (inBuf[rowBase + pixelOffset] & 0xFF)
                      | ((inBuf[rowBase + pixelOffset + 1] & 0xFF) << 8);
            int r5 = (pixel >> 11) & 0x1F;
            int g6 = (pixel >> 5) & 0x3F;
            int b5 = pixel & 0x1F;
            int r = (r5 << 3) | (r5 >> 2);
            int g = (g6 << 2) | (g6 >> 4);
            int b = (b5 << 3) | (b5 >> 2);
            int base = destRowBase + (j << 2);
            outData[base]     = (uint8_t) r;
            outData[base + 1] = (uint8_t) g;
            outData[base + 2] = (uint8_t) b;
            outData[base + 3] = (uint8_t) 255;
        }
    }

    env->ReleaseByteArrayElements(outDataArr, outData, 0);
}

#endif
