# Unity Android 视频组件与 SDK 初始化问题处理总结

## 1. 文档目的

本文记录 Unity Android 视频组件在 MuMu 模拟器（重点是 Vulkan 渲染模式）、接入 SDK 初始化流程后出现黑屏和黑帧闪烁问题的定位过程、技术原因、代码调整和验证方法。

本文同时记录已验证的 MuMu Vulkan 处理方式：不在模拟器检测分支中强制启用 `ImageReader`，让视频走 `SurfaceTexture` 路径。该方式已在当前项目中验证可以正常播放，视频不再依赖持续点击来刷新画面。

本文只覆盖以下核心代码范围：

- `VideoPlugin/Assets/Video`：Unity C# 视频组件层
- `VideoPlugin/Assets/SimulatedSdk`：模拟 SDK 初始化流程
- `VideoPlugin/Assets/Plugins/Android`：模拟 SDK Java 层和 Android Manifest
- `VideoPlayerPlugin/app/src/main/java`：视频 Java 层
- `VideoPlayerPlugin/app/src/main/cpp`：视频 C++ 渲染插件层
- 根目录 `log.txt`：问题日志

## 2. 问题现象

### 未接入 SDK

- MuMu Android 15 模拟器中视频可以正常显示。
- Unity 退到后台再回到前台后，视频仍可以正常显示。

### 接入 SDK 初始化流程后

- 游戏启动时视频正常。
- SDK 初始化、登录或 Activity 切换后，MuMu 模拟器中的视频可能变黑。
- MuMu Vulkan 渲染模式下，如果强制走 `ImageReader`，视频区域可能黑屏；持续点击时画面才会间歇性刷新。
- 注释掉 MuMu 检测分支中强制启用 `ImageReader` 的代码后，改走 `SurfaceTexture`，当前项目验证通过。
- Android 真机上基本正常，问题主要出现在 MuMu 模拟器。
- 使用 Activity 模式模拟登录时，登录完成返回 Unity 后曾出现一次短暂黑帧，随后视频恢复正常。

这说明问题不是单纯的解码失败，而是 MuMu 的 Activity/EGL 生命周期、视频输出 Surface 和 Unity 外部纹理之间的时序差异。

## 3. 视频渲染链路

```mermaid
flowchart LR
    CSharp["Unity C#\nExoMediaPlayer / UIVideo"] --> Java["Java\nExoPlayerUnity"]
    Java --> Exo["ExoPlayer / VideoPlayer / decoder output"]
    Java --> Reader["ImageReader / optional compatibility path"]
    Java --> SurfaceTexture["SurfaceTexture / MuMu Vulkan verified path"]
    Reader --> Native["C++ RenderingPlugin / Unity Render Event"]
    SurfaceTexture --> Native
    Native --> UnityTexture["Unity external texture / FBO"]
    UnityTexture --> CSharp

    Init["SimulatedSdkInitializer / 8 second delay"] --> Activity["SimulatedSdkActivity / real Activity"]
    Init --> Overlay["SimulatedSdkOverlay / Unity content layer"]
    Activity -. "may trigger pause, focus, or EGL changes" .-> CSharp
    Overlay -. "does not switch Activity" .-> CSharp
```

核心流程是：

1. Unity C# 创建和控制 `ExoPlayerUnity`。
2. Java 使用 ExoPlayer 解码，并将视频输出到 `ImageReader` 或 `SurfaceTexture`。
3. C++ `RenderingPlugin` 通过 Unity Render Event 在 Unity 渲染线程中处理 GL 资源。
4. Unity C# 将 Java 层生成的纹理包装为 Unity 外部纹理并显示。

因此，Activity 生命周期变化会同时影响：

- Unity 的 `OnApplicationPause`。
- EGL/GL context 是否被重建。
- Java 视频输出 Surface 是否仍然有效。
- Unity 外部纹理和 FBO 是否仍然有效。

## 4. MuMu 的 ImageReader 兼容分支（按需启用）

原代码会在识别到 MuMu 后强制设置：

```java
theClass.m_UseImageReader = true;
```

这条分支会进入 `UpdateImageReaderFrame()`，再通过 `GLES20`、FBO 和 YUV/RGB 纹理转换把 `ImageReader` 的图像上传给 Unity。当前工程的视频插件本身是以 GLES 纹理和 Unity 外部纹理为主，在 MuMu Vulkan 渲染模式下强制走这条路径会出现“黑屏、点击才刷新”的现象。

本次已验证的处理流程是：

1. 仍然保留 MuMu 检测逻辑，例如检测 `/system/etc/mumu-configs`。
2. 注释掉 MuMu 检测分支中强制设置 `m_UseImageReader = true` 的代码。
3. MuMu Vulkan 下不再强制使用 `ImageReader`，视频输出改走 `SurfaceTexture`。
4. `SurfaceTexture` 在 Unity Render Event 中由 `UpdateSurfaceTexture()` 调用 `updateTexImage()`，再更新 Unity 外部纹理。
5. 验证结果：视频可以持续正常显示，停止点击后也不会再次黑屏。

因此，`ImageReader` 不是 MuMu Vulkan 的默认路径，而是保留给特定设备或实际格式不兼容时使用的兼容分支。不要因为检测到模拟器就无条件强制启用它。

如果确实需要启用 `ImageReader`，当前代码仍保留以下格式兼容逻辑：

1. 优先尝试 `PixelFormat.RGB_565`。
2. 如果实际解码器输出格式和当前 `ImageReader` 不匹配，再按候选格式回退：

```text
RGB_565 -> YUV_420_888 -> RGBA_8888
```

日志中可能出现类似：

```text
Producer output buffer format ... does not match ImageReader configured format ...
RecreateImageReaderWithFormat: switched to format ...
```

这表示格式回退流程正在工作。该逻辑仍可作为 `ImageReader` 兼容分支使用，但不应再作为 MuMu Vulkan 下的强制默认路径。

注意：代码中的 `Build.VERSION.SDK_INT >= 15` 表示 Android API 15，并不是 Android 15（API 35）。如果未来需要只针对 Android 15 系统启用某项逻辑，应使用 API 35 的判断条件，并结合实际设备验证。

## 5. 模拟 SDK 流程

### 5.1 延迟入口

`SimulatedSdkInitializer` 在 Android 真机包中等待：

```text
Login Page Delay = 8 秒
```

延迟位于 `SimulatedSdkInitializer` 层，而不是 Activity 内部。这样可以模拟真实 SDK 初始化完成一段时间后才弹出登录界面的流程。

### 5.2 Activity 模式

`SimulatedSdkActivity`：

- 是真实 Android Activity。
- 通过 Manifest 注册。
- 使用非全屏、居中的 Dialog 风格窗口。
- 模拟初始化、协议页、登录页和登录完成回调。
- 通过 `UnityPlayer.UnitySendMessage` 回调 Unity。
- 用于测试真实 Activity 切换对 Unity pause/focus、EGL 和视频组件的影响。

### 5.3 Overlay 模式

`SimulatedSdkOverlay`：

- 不启动第二个 Activity。
- 直接添加到 Unity Activity 的 `android.R.id.content` 内容层。
- 登录卡片覆盖在 Unity 画面上方。
- Unity Activity、EGL 和 Unity 渲染循环保持不变。
- 用于验证“仅有 SDK UI 覆盖，但没有 Activity 生命周期切换”时视频是否正常。

### 5.4 开关配置

在 Unity Inspector 的 `SimulatedSdkInitializer` 上配置：

```text
Use Android Activity       = true
Use Non Blocking Overlay   = false / true
Login Page Delay           = 8
```

对照测试：

- `Use Non Blocking Overlay = false`：Activity 生命周期测试。
- `Use Non Blocking Overlay = true`：不切换 Activity 的 Overlay 测试。

如果 Overlay 正常而 Activity 模式异常，可以基本确认问题位于 Activity 生命周期、EGL 或 Surface 恢复链路，而不是 SDK 登录 UI 本身。

## 6. 黑屏问题的核心原因

### 6.1 SDK Activity 造成生命周期差异

真机和 MuMu 对 Activity 切换、窗口焦点和 EGL context 的处理不完全一致。MuMu 可能在 SDK Activity 出现或退出时触发与真机不同的 EGL/Surface 生命周期。

### 6.2 旧的恢复流程过于激进

Unity 回到前台时，`ExoMediaPlayer.OnApplicationPause(false)` 会设置恢复标记，并在下一次 Render 中发送 `Resume` 事件。

旧流程中，Java `ExoPlayerUnity.Resume()` 无条件执行：

```text
DestroySurface()
DestroyGl()
CreateExoSurface()
重新绑定视频输出 Surface
```

即使 EGL context 实际没有丢失，也会主动销毁现有视频输出和纹理。此时新 Surface 尚未产生首帧，Unity 就可能显示一帧黑画面。

### 6.3 C# 侧曾无条件清空纹理缓存

旧的 C# 恢复逻辑会将纹理句柄和纹理 revision 强制清空，进一步促使 Unity 重新创建外部纹理，从而扩大黑帧窗口。

### 6.4 MuMu Vulkan 下不应强制走 ImageReader

这次复现和验证将问题范围进一步缩小到了渲染路径：

```text
MuMu 检测
    -> 强制 ImageReader
    -> GLES20/FBO/图像上传
    -> Unity 外部纹理
    -> Vulkan 渲染画面黑屏或不主动刷新
```

持续点击能够显示画面，说明解码数据并非完全没有产生，更符合 ImageReader、GLES 纹理更新和 Unity Vulkan 渲染提交之间的同步/刷新不匹配。点击行为改变了 Unity 的输入和渲染活动，因而可能暂时触发画面更新，但这不是可靠的视频刷新机制。

注释掉强制 `ImageReader` 后，实际路径变为：

```text
MuMu 检测
    -> SurfaceTexture
    -> Unity Render Event / updateTexImage()
    -> Unity 外部纹理
    -> Vulkan 下持续显示
```

这里的“Vulkan 下可用”是当前项目在 MuMu 上的验证结论；视频插件内部仍存在 GLES 纹理接口，因此不能据此推导所有 Android 设备或所有 Vulkan 驱动都支持任意 ImageReader/GLES 组合。

## 7. 最终修复方案

### 7.1 只在真实 EGL 重建时重建视频资源

`ExoPlayerUnity` 增加了：

```java
m_NeedsSurfaceRecreationAfterContextReset
```

只有以下事件会设置该标记：

- `OnGraphicsDeviceInitialize()`。
- `OnGraphicsDeviceShutdown()`。

恢复时分两条路径：

```text
普通 Activity 返回
    -> 保留 ImageReader / SurfaceTexture / GL 资源
    -> 只恢复 ExoPlayer 播放
    -> 保留 Unity 当前纹理

真实 EGL context 重建
    -> 销毁旧资源
    -> 创建新的视频输出 Surface
    -> 重建 GL/FBO/外部纹理
    -> 重新绑定播放器
```

### 7.2 C# 不再无条件清空纹理

`ExoMediaPlayer.Render()` 在恢复时不再直接设置：

```csharp
m_TextureHandle = 0;
m_TextureRevision = -1;
```

纹理 revision 由 Java 层在真实 EGL context 重建时递增。这样：

- 没有 EGL 重建：继续使用当前 Unity 纹理，避免黑帧。
- 有 EGL 重建：revision 变化，Unity 自动销毁并重新包装外部纹理。

### 7.3 Activity 退出动画关闭

模拟 Activity 登录完成和返回时调用：

```java
overridePendingTransition(0, 0);
```

用于减少 Activity 窗口动画造成的视觉闪烁。

### 7.4 MuMu Vulkan 下取消强制 ImageReader

MuMu Vulkan 的最终视频渲染流程为：

```text
识别 MuMu
    -> 不强制设置 m_UseImageReader = true
    -> 创建 SurfaceTexture
    -> ExoPlayer 输出到 SurfaceTexture
    -> Unity Render Event 中更新 SurfaceTexture
    -> Unity 外部纹理持续显示
```

此处不是删除 `ImageReader` 的全部实现，而是取消“检测到 MuMu 就强制切换到 ImageReader”的策略。这样可以保留其他设备在确有格式兼容问题时的回退能力，同时避免 MuMu Vulkan 进入已验证会黑屏的路径。

## 8. 关键代码文件

| 文件 | 作用 |
|---|---|
| `VideoPlugin/Assets/Video/VideoController/MediaPlayer/ExoMediaPlayer.cs` | Unity C# 播放控制、pause/resume、Render Event 调度 |
| `VideoPlayerPlugin/app/src/main/java/com/unity3d/exovideo/ExoPlayerUnity.java` | Java 播放器、ImageReader、SurfaceTexture、GL 资源恢复 |
| `VideoPlayerPlugin/app/src/main/cpp/RenderingPlugin.cpp` | Unity Render Event、C++/JNI、SurfaceTexture 和渲染线程桥接 |
| `VideoPlayerPlugin/app/src/main/java/com/unity3d/Texture2DExtYUV.java` | YUV/RGB 图像处理和纹理转换相关逻辑 |
| `VideoPlugin/Assets/SimulatedSdk/SimulatedSdkInitializer.cs` | 8 秒延迟、Activity/Overlay 模式切换和模拟 SDK 状态机 |
| `VideoPlugin/Assets/Plugins/Android/com/unity3d/simulatedsdk/SimulatedSdkActivity.java` | 真实 Activity 模式的模拟登录界面 |
| `VideoPlugin/Assets/Plugins/Android/com/unity3d/simulatedsdk/SimulatedSdkOverlay.java` | 不切换 Activity 的 Overlay 模式 |
| `VideoPlugin/Assets/Plugins/Android/AndroidManifest.xml` | Unity Activity 和模拟 SDK Activity 注册 |
| `VideoPlugin/ProjectSettings/ProjectSettings.asset` | Unity Android 安装位置等构建配置 |

## 9. 验证方法

### 9.1 Activity 模式

1. 设置 `Use Non Blocking Overlay = false`。
2. 设置 `Login Page Delay = 8`。
3. 启动视频。
4. 等待模拟登录页面出现并完成登录。
5. 观察返回 Unity 后视频是否保持连续显示。
6. 检查是否出现以下日志：

```text
Resume preserved existing surface and GL resources
```

如果看到该日志，说明本次返回没有发生 EGL context 重建，视频应该直接复用原画面。

### 9.2 EGL 重建路径

如果日志出现：

```text
Resume recreating surface after EGL context reset
```

说明系统确实发生了 EGL context 重建，代码会执行完整资源恢复。这条路径重点观察：

- 是否能恢复画面。
- 是否出现持续黑屏。
- 是否出现 `GL_INVALID_OPERATION`。
- 是否能继续播放新帧。

### 9.3 Overlay 模式

1. 设置 `Use Non Blocking Overlay = true`。
2. 启动视频并等待 8 秒。
3. 在 Overlay 登录页面显示期间观察 Unity 视频。
4. 点击模拟登录并观察 Overlay 移除后的画面。

Overlay 模式主要用于验证视频本身是否稳定，不用于模拟真实 Activity 生命周期。

### 9.4 MuMu Vulkan 渲染模式

1. 在 `ExoPlayerUnity` 的 MuMu 检测分支中确认没有执行强制 `m_UseImageReader = true`。
2. 重新构建 Java 视频插件并重新打包、安装 APK，不能继续使用修改前的旧 APK。
3. 使用 MuMu 的 Vulkan 渲染模式启动视频。
4. 确认日志不再出现：

```text
Detected Emulator, Force Enable ImageReader mode
CreateExoSurface with ImageReader
```

5. 确认日志进入 `SurfaceTexture` 路径，例如出现：

```text
CreateExoSurface with SurfaceTexture
```

6. 启动视频后保持画面静止，不进行连续点击，观察视频是否仍能持续显示和播放。

当前项目的验证结果是：注释掉 MuMu 的强制 `ImageReader` 方式后，视频可以正常显示，停止点击后也不会恢复为黑屏。

## 10. 日志关键词

建议重点关注以下日志：

```text
Detected MuMu Emulator
Detected Emulator, Force Enable ImageReader mode
CreateExoSurface with ImageReader
Producer output buffer format ... does not match ...
RecreateImageReaderWithFormat
OnGraphicsDeviceInitialize
OnGraphicsDeviceShutdown
Resume preserved existing surface and GL resources
Resume recreating surface after EGL context reset
ReCreateTexture
glBindTexture error
```

判断原则：

- MuMu Vulkan 的修复路径中，`Detected MuMu Emulator` 可以保留，它只表示完成了设备识别。
- MuMu Vulkan 的修复路径不应出现 `Detected Emulator, Force Enable ImageReader mode` 或 `CreateExoSurface with ImageReader`。
- `CreateExoSurface with SurfaceTexture` 是当前已验证的 MuMu Vulkan 路径。
- `UpdateImageReaderFrame: No new image available` 不应在当前 MuMu Vulkan 播放流程中持续出现；如果出现，先确认是否仍安装了旧 APK 或代码仍然强制启用了 `ImageReader`。
- 格式 mismatch 后能够成功回退并继续播放，不一定是故障。
- `Resume preserved...` 表示普通 Activity 返回路径。
- `Resume recreating...` 表示真实 EGL 重建路径。
- 持续出现 `glBindTexture error` 或 `ReCreateTexture` 循环，才需要继续检查 GL 资源生命周期。

## 11. APK 构建注意事项

- 修改 Java、C# 或 Manifest 后必须重新打包，不能使用旧 APK 判断结果。
- 当前 Unity Android 安装位置配置为内部安装：`AndroidPreferredInstallLocation: 2`。
- Manifest 中启动 Activity 必须保留 `android:exported="true"`。
- 模拟 SDK Activity 不作为启动入口，使用 `android:exported="false"`。
- 修改 Manifest 后应检查最终生成 APK 中的合并 Manifest，确认实际包内配置和工程源码一致。

## 12. 当前结论

当前问题可以分成两类：

1. MuMu Vulkan 的渲染路径问题：不要在模拟器检测分支中强制启用 `ImageReader`，当前项目应让 MuMu Vulkan 走已验证的 `SurfaceTexture` 路径。
2. MuMu Android 15+ 的视频格式兼容问题：`ImageReader` 的 RGB565 优先逻辑和格式回退仍然保留，但只作为按需兼容分支，不作为 MuMu Vulkan 的默认路径。
3. SDK Activity 生命周期造成的视频资源恢复问题：普通返回不再无条件销毁和重建视频资源，只有真实 EGL context 重建时才执行完整恢复。

Overlay 模式提供了一个稳定的对照实验：如果 Overlay 模式始终正常，而 Activity 模式只在真实 EGL 重建路径出现问题，就可以继续把排查范围限定在 MuMu 的 Activity/EGL 生命周期，不需要改变 MuMu 的 RGB565 兼容方案。

当前 MuMu Vulkan 的已验证结论是：注释掉强制 `ImageReader` 后，视频走 `SurfaceTexture`，播放和持续刷新均正常；连续点击不再是必要条件。
