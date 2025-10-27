#include <assert.h>
#include <math.h>
#include <vector>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include "Unity/IUnityGraphics.h"
#include <jni.h>
#include <string>
#include <list>
#include <map>
#ifdef _ANDROID
#include <android/api-level.h>
#include <android/log.h>
#endif

typedef void	(*ENGINE_PLUGIN_RENDER_EVENT)( int );
typedef void	(*ENGINE_PLUGIN_DEVICE_EVENT)( int );
struct SInvBridgeInterface
{
    ENGINE_PLUGIN_RENDER_EVENT pRenderEvent;
    ENGINE_PLUGIN_DEVICE_EVENT pDeviceEvent;
	SInvBridgeInterface()
	{
		Clear();
	}
	~SInvBridgeInterface()
	{
		Clear();
	}
	void  Clear()
	{
		memset(this,0,sizeof(*this));
	}
};

SInvBridgeInterface g_InvBridgeInterface;

static JavaVM* gJvm = nullptr;
static jobject gClassLoader;
static jmethodID gFindClassMethod;

struct SSurfaceTextureInfo
{
    unsigned int textureId;
    jobject surfaceTexture;
    jobject surface;
    int classId;
    SSurfaceTextureInfo()
    {
        textureId = 0;
        classId =0;
        surfaceTexture = nullptr;
        surface = nullptr;
    }
};

std::map<int, SSurfaceTextureInfo*> g_VideoJavaClassMap;

JNIEnv* getEnv()
{
    JNIEnv *env;
    int status = gJvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if(status < 0)
    {
        status = gJvm->AttachCurrentThread(&env, NULL);
        if(status < 0)
        {
            return nullptr;
        }
    }
    return env;
}

extern "C"
{
	JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved);
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *pjvm, void *reserved)
{
    gJvm = pjvm;  // cache the JavaVM pointer
    auto env = getEnv();
    jclass videoClass = env->FindClass("com/unity3d/PluginJNI");
    jclass videoObject = env->GetObjectClass(videoClass);
    jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getClassLoaderMethod = env->GetMethodID(videoObject, "getClassLoader","()Ljava/lang/ClassLoader;");
    gClassLoader = env->NewGlobalRef(env->CallObjectMethod(videoClass, getClassLoaderMethod));
    gFindClassMethod = env->GetMethodID(classLoaderClass, "findClass","(Ljava/lang/String;)Ljava/lang/Class;");
    return JNI_VERSION_1_6;
}

static jclass findClass(const char* name)
{
    return static_cast<jclass>(getEnv()->CallObjectMethod(gClassLoader, gFindClassMethod, getEnv()->NewStringUTF(name)));
}

static void UNITY_INTERFACE_API OnGraphicsDeviceEvent(UnityGfxDeviceEventType eventType);

static IUnityInterfaces* s_UnityInterfaces = NULL;
static IUnityGraphics* s_Graphics = NULL;

static UnityGfxRenderer s_DeviceType;

static void UNITY_INTERFACE_API OnGraphicsDeviceEvent(UnityGfxDeviceEventType eventType)
{
    if (eventType == kUnityGfxDeviceEventInitialize)
    {
        s_DeviceType = s_Graphics->GetRenderer();
    }
    if(g_InvBridgeInterface.pDeviceEvent !=NULL)
        g_InvBridgeInterface.pDeviceEvent((int)eventType);
}

static void Log(std::string msg)
{
#ifdef _ANDROID
    __android_log_print(ANDROID_LOG_INFO, "ExoPlayerUnity", "%s", msg.c_str());
#endif
}

static void CreateSurfaceTexture(int playerIndex)
{
    Log("CreateSurfaceTexture");
    std::map<int, SSurfaceTextureInfo*>::iterator it = g_VideoJavaClassMap.find(playerIndex);
    if(it == g_VideoJavaClassMap.end())
        return;
    SSurfaceTextureInfo* surfaceTextureInfo = it->second;

    uint textureID;
    glGenTextures(1, &textureID);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureID);
     auto env = getEnv();
     // 1. 释放旧 GlobalRef
    if (surfaceTextureInfo->surfaceTexture) {
        env->DeleteGlobalRef(surfaceTextureInfo->surfaceTexture);
        surfaceTextureInfo->surfaceTexture = nullptr;
    }
    if (surfaceTextureInfo->surface) {
        env->DeleteGlobalRef(surfaceTextureInfo->surface);
        surfaceTextureInfo->surface = nullptr;
    }

    surfaceTextureInfo->textureId = textureID;

    jint apiLevel = android_get_device_api_level();

    jobject jniSurfaceTexture = nullptr;

    if (apiLevel >= 26) 
    {
        // ≥ API 26：直接构造
        jclass surfTexCls = env->FindClass("android/graphics/SurfaceTexture");
        jmethodID ctor = env->GetMethodID(surfTexCls, "<init>", "(I)V");
        jobject local = env->NewObject(surfTexCls, ctor, (jint)textureID);
        jniSurfaceTexture = env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        env->DeleteLocalRef(surfTexCls);
    } 
    else 
    {
        // < API 26：无参构造 + attach
        jclass surfTexCls = env->FindClass("android/graphics/SurfaceTexture");
        jmethodID ctor = env->GetMethodID(surfTexCls, "<init>", "()V");
        jobject local = env->NewObject(surfTexCls, ctor);
        jniSurfaceTexture = env->NewGlobalRef(local);

        // attachToGLContext(int texName)
        jmethodID attach = env->GetMethodID(surfTexCls, "attachToGLContext", "(I)V");
        env->CallVoidMethod(local, attach, (jint)textureID);

        env->DeleteLocalRef(local);
        env->DeleteLocalRef(surfTexCls);
    }
    surfaceTextureInfo->surfaceTexture = jniSurfaceTexture;

    //Create a Surface from the SurfaceTexture using JNI
    const jclass surfaceClass = env->FindClass("android/view/Surface");
    const jmethodID surfaceConstructor = env->GetMethodID(surfaceClass, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
    jobject surfaceObject = env->NewObject(surfaceClass, surfaceConstructor, jniSurfaceTexture);
    surfaceTextureInfo->surface = env->NewGlobalRef(surfaceObject);

    // Now that we have a globalRef, we can free the localRef
    env->DeleteLocalRef(surfaceObject);
    env->DeleteLocalRef(surfaceClass);

    // Get the method to pass the Surface object to the videoPlayer
    jclass videoClass = findClass("com.unity3d.PluginJNI");
    int textureNum = (int)textureID;
    jmethodID playVideoMethodID = env->GetStaticMethodID(videoClass, "CreateSurface", "(Landroid/view/Surface;III)V");
    // Pass the JNI Surface object to the videoPlayer with video and texture ID
    env->CallStaticVoidMethod(videoClass, playVideoMethodID, surfaceTextureInfo->surface, surfaceTextureInfo->classId, playerIndex, textureNum);
    Log("CreateSurfaceTexture-Ok");
}

static void UpdateSurfaceTexture(int playerIndex)
{
    std::map<int, SSurfaceTextureInfo*>::iterator it = g_VideoJavaClassMap.find(playerIndex);
    if(it == g_VideoJavaClassMap.end())
        return;
    SSurfaceTextureInfo* surfaceTextureInfo = it->second;
    if(surfaceTextureInfo->surfaceTexture != nullptr)
    {
        auto env = getEnv();
        jclass surfaceTextureClass = env->FindClass("android/graphics/SurfaceTexture");
        jmethodID updateTexImageMethod = env->GetMethodID(surfaceTextureClass, "updateTexImage", "()V");
        env->CallVoidMethod(surfaceTextureInfo->surfaceTexture, updateTexImageMethod);
        env->DeleteLocalRef(surfaceTextureClass);
    }
}

static void UNITY_INTERFACE_API OnRenderEvent(int eventID)
{
    int eventType = (eventID >> 16) & 0xFFFF;
    int playerIndex = (eventID >> 8) & 0xFF;
    int gfxType = eventID & 0xFF;

    std::map<int, SSurfaceTextureInfo*>::iterator it = g_VideoJavaClassMap.find(playerIndex);
    if(it == g_VideoJavaClassMap.end())
    {
        if(g_InvBridgeInterface.pRenderEvent !=NULL)
            g_InvBridgeInterface.pRenderEvent(eventID);

        return;
    }

    jclass videoClass = findClass("com.unity3d.PluginJNI");
    auto env = getEnv();
    jmethodID playVideoMethodID = env->GetStaticMethodID(videoClass, "OnRendererEvent", "(II)V");
        env->CallStaticVoidMethod(videoClass, playVideoMethodID, eventID, it->second->classId);

    if(g_InvBridgeInterface.pRenderEvent !=NULL)
        g_InvBridgeInterface.pRenderEvent(eventID);
}

extern "C" void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API InitInvBridgeInterface(SInvBridgeInterface* pInteface)
{
    g_InvBridgeInterface = *pInteface;
}

extern "C" void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API SetVideoJavaClass(int index, int classIndex)
{
    if(g_VideoJavaClassMap.find(index) != g_VideoJavaClassMap.end())
        return;
    SSurfaceTextureInfo* surfaceTexture = new SSurfaceTextureInfo();
    surfaceTexture->classId = classIndex;
    g_VideoJavaClassMap[index] = surfaceTexture;
}

extern "C" void* UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API GetSurfaceTextureHandle(int index)
{
    std::map<int, SSurfaceTextureInfo*>::iterator it = g_VideoJavaClassMap.find(index);
    if(it == g_VideoJavaClassMap.end())
        return NULL;

    SSurfaceTextureInfo* surfaceTextureInfo = it->second;
    return &surfaceTextureInfo->textureId;
}

extern "C" void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API DestroyVideoSurface(int index)
{
    std::map<int, SSurfaceTextureInfo*>::iterator it = g_VideoJavaClassMap.find(index);
    if(it == g_VideoJavaClassMap.end())
        return;

    SSurfaceTextureInfo* surfaceTextureInfo = it->second;
    auto env = getEnv();
    if(surfaceTextureInfo->surfaceTexture != nullptr)
    {
        env->DeleteGlobalRef(surfaceTextureInfo->surfaceTexture);
        surfaceTextureInfo->surfaceTexture = nullptr;
    }
    if(surfaceTextureInfo->surface != nullptr)
    {
        env->DeleteGlobalRef(surfaceTextureInfo->surface);
        surfaceTextureInfo->surface = nullptr;
    }
    delete surfaceTextureInfo;
    g_VideoJavaClassMap.erase(it);
}

extern "C" UnityRenderingEvent UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API GetRenderEventFunc()
{
    return OnRenderEvent;
}