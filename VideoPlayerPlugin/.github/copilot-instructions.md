# Copilot Instructions for AI Coding Agents

## Project Overview
- This is an Android library plugin for Unity, focused on advanced video playback using ExoPlayer.
- Main code is in `app/src/main/java/com/unity3d/exovideo/VideoPlayer.java` and related classes.
- The plugin integrates with Unity via JNI and is designed to be used as a Unity Android native plugin (AAR).

## Architecture & Key Components
- **VideoPlayer.java**: Central class for video playback, caching, and ExoPlayer integration. Handles playback speed, volume, looping, and data source setup.
- **APEZProvider.java**: Custom `ContentProvider` for accessing APK expansion (OBB) files, supporting resource queries and asset file access.
- **JNI/Unity Integration**: Native interface points are in `src/main/java` and `src/main/jni`.
- **External dependencies**: Uses ExoPlayer (`com.google.android.exoplayer:exoplayer:2.15.1`), and custom `.aar` libraries in `libs/`.

## Build & Developer Workflow
- Build with Gradle: `./gradlew assembleRelease` (or `assembleDebug`).
- The output AAR is renamed to `VideoPlayerPlugin.aar` and can be copied to a Unity project (see `copybuild` task in `app/build.gradle`).
- To copy the built AAR to Unity, run the `copybuild` Gradle task or manually copy from `app/build/outputs/aar/`.
- Native code (C++) is in `src/main/cpp/` (CMake is configured but may be commented out in `build.gradle`).

## Project Conventions & Patterns
- **Caching**: Video caching uses a timestamped directory per file hash (see `getDownloadCache` in `VideoPlayer.java`).
- **DataSource Factories**: Always use `buildDataSourceFactory` for ExoPlayer to ensure proper caching and HTTP setup.
- **Resource Access**: Use `APEZProvider` for accessing assets in OBB/expansion files.
- **ProGuard**: Custom rules in `proguard-rules.pro`.
- **Dependencies**: Place `.aar` and `.jar` files in `libs/` and reference with `compileOnly` or `implementation` as needed.

## Integration Points
- Unity expects the plugin AAR in `Assets/Plugins/Android/libs`.
- JNI/native code should be kept in sync with Java interfaces.
- ExoPlayer and audio360 libraries are required for full functionality.

## Examples
- To add a new playback feature, extend `VideoPlayer.java` and expose via JNI if needed.
- To add new asset types, update `APEZProvider` and ensure correct resource mapping.

## References
- Key files: `app/src/main/java/com/unity3d/exovideo/VideoPlayer.java`, `app/src/main/java/com/unity3d/zip/APEZProvider.java`, `app/build.gradle`, `libs/`.
- For Unity integration, see the `copybuild` task and Unity's plugin import workflow.

---
If any section is unclear or missing, please provide feedback for further refinement.