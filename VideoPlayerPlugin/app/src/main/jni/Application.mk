# Build both ARMv5TE and ARMv7-A machine code.
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
#APP_STL := stlport_static
#APP_STL := gnustl_static
#APP_STL := c++_shared
APP_STL := c++_static
APP_CPPFLAGS += -fexceptions
APP_PLATFORM := android-16