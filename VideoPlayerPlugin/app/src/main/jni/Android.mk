LOCAL_PATH := $(call my-dir)

# Game
include $(CLEAR_VARS)
LOCAL_MODULE := CorePlus
					
#-D_USE_DEBUG_LOG
LOCAL_CFLAGS    :=-D_ANDROID_PLATFORM -D_ANDROID -DLUA_USE_LINUX \

define finder_cpp
$(wildcard $(1)) $(foreach e, $(wildcard $(1)/*), $(call finder_cpp, $(e)))
endef

ALLFILES = $(call finder_cpp, $(LOCAL_PATH)/../cpp)

FILE_LIST = $(filter %.cpp, $(ALLFILES))
	
LOCAL_SRC_FILES := $(FILE_LIST:$(LOCAL_PATH)/%=%)	
	
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../cpp \
					$(LOCAL_PATH)/../cpp/Unity \
					
LOCAL_LDLIBS := -llog -landroid -lOpenSLES -lGLESv2

LOCAL_CPPFLAGS += -fexceptions
LOCAL_CPPFLAGS += -fpermissive
LOCAL_CPPFLAGS += -Wattributes
LOCAL_CPPFLAGS += -std=c++11
LOCAL_CPPFLAGS += -frtti
# 关键：16 KB 对齐
LOCAL_LDFLAGS  += -Wl,-z,max-page-size=0x4000

include $(BUILD_SHARED_LIBRARY)