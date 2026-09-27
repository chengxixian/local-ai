# ============================================================================
#  MNNKit 原生层
#
#  两个产物：
#
#   1) mnnkitbridge —— LLM 对话 JNI
#      复用官方 MnnLlmChat 的最小 7 文件集：
#        llm_session.cpp / llm_session.h   (裁剪：benchmark / TTS / Firebase)
#        mnn_llm_jni.cpp                   (裁剪：benchmark / TTS / diffusion /
#                                           sana / crash_util / processor / video)
#        llm_stream_buffer.hpp / utf8_stream_processor.hpp / mls_log.h  (原样)
#        third_party/nlohmann/json.hpp     (原样)
#
#   2) mnnkitconvert —— **设备端模型转换** JNI
#      MNN 的转换器（tools/converter）是纯 C++ 实现，不依赖 Python / PyTorch，
#      因此可以交叉编译进 APK，让用户在手机上把 ONNX / TFLite / Caffe /
#      TorchScript 直接转成 .mnn。这是"本地转换模型格式"的核心。
#
#  依赖关系：
#      libmnnkitbridge.so  -> libMNN.so
#      libmnnkitconvert.so -> libMNNConvertDeps.so -> libMNN.so
#
#  关键点（见 docs/research-official-jni.md §5 / §7.5）：
#   * MNN_SEP_BUILD=OFF，LLM 全部编译进 libMNN.so，所以只链接 MNN / android / log。
#   * libMNN.so 的 DT_NEEDED 含 libc++_shared.so → 必须 -DANDROID_STL=c++_shared，
#     且 jniLibs 里要同时放 libc++_shared.so。
#   * IMPORTED_NO_SONAME TRUE：防止链接器把绝对路径写进 DT_NEEDED。
#   * 16 KB page size（Android 15+ 必需）。
# ============================================================================
cmake_minimum_required(VERSION 3.22.1)

project(mnnkit-native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# —— MNN 源码根（提供头文件） ——
set(MNN_SOURCE_ROOT "D:/dsh work region/reference/x/MNN-master" CACHE PATH "MNN source root (ROOT)")
if (NOT EXISTS "${MNN_SOURCE_ROOT}/include/MNN/MNNForwardType.h")
    message(FATAL_ERROR
            "MNN_SOURCE_ROOT='${MNN_SOURCE_ROOT}' 看起来不是 MNN 源码根，"
            "缺少 include/MNN/MNNForwardType.h。请用 -DMNN_SOURCE_ROOT=<ROOT> 指定。")
endif ()

# —— 预编译原生库：直接用 jniLibs 里那一份，保证「打进 APK 的」与
#    「链接时用的」是同一个文件，不会版本漂移 ——
set(JNI_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

set(MNN_LIB_PATH "${JNI_LIB_DIR}/libMNN.so" CACHE FILEPATH "Prebuilt libMNN.so")
if (NOT EXISTS "${MNN_LIB_PATH}")
    message(FATAL_ERROR "找不到预编译 libMNN.so：${MNN_LIB_PATH}")
endif ()

# 转换器库是可选依赖：缺失时 LLM 功能仍然可用，只是转换功能关闭。
set(MNN_CONVERT_LIB_PATH "${JNI_LIB_DIR}/libMNNConvertDeps.so" CACHE FILEPATH "Prebuilt libMNNConvertDeps.so")
set(HAVE_CONVERTER FALSE)
if (EXISTS "${MNN_CONVERT_LIB_PATH}")
    set(HAVE_CONVERTER TRUE)
    message(STATUS "mnnkit: 转换器已启用 (${MNN_CONVERT_LIB_PATH})")
else ()
    message(WARNING "mnnkit: 未找到 libMNNConvertDeps.so，端侧模型转换功能将不可用")
endif ()

message(STATUS "mnnkit: MNN_SOURCE_ROOT = ${MNN_SOURCE_ROOT}")
message(STATUS "mnnkit: MNN_LIB_PATH    = ${MNN_LIB_PATH}")

# —— 预编译 MNN 运行时的 IMPORTED 声明 ——
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES
        IMPORTED_LOCATION "${MNN_LIB_PATH}"
        IMPORTED_NO_SONAME TRUE
)

# ============================================================================
#  1) mnnkitbridge —— LLM JNI
# ============================================================================
add_library(mnnkitbridge SHARED
        mnn_llm_jni.cpp
        llm_session.cpp
)

target_include_directories(mnnkitbridge PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${MNN_SOURCE_ROOT}/include"
        "${MNN_SOURCE_ROOT}/transformers/llm/engine/include"
        "${MNN_SOURCE_ROOT}/3rd_party"
        "${CMAKE_CURRENT_SOURCE_DIR}/third_party"
)

target_compile_options(mnnkitbridge PRIVATE
        -fexceptions -frtti -Wall -Wno-unused-parameter
)
target_link_options(mnnkitbridge PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(mnnkitbridge android log MNN)

# ============================================================================
#  2) mnnkitconvert —— 端侧模型转换 JNI
# ============================================================================
if (HAVE_CONVERTER)
    add_library(MNNConvertDeps SHARED IMPORTED)
    set_target_properties(MNNConvertDeps PROPERTIES
            IMPORTED_LOCATION "${MNN_CONVERT_LIB_PATH}"
            IMPORTED_NO_SONAME TRUE
    )

    add_library(mnnkitconvert SHARED mnn_convert_jni.cpp)

    target_include_directories(mnnkitconvert PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}"
            "${MNN_SOURCE_ROOT}/include"
            # cli.hpp / config.hpp 是转换器的公开 API
            "${MNN_SOURCE_ROOT}/tools/converter/include"
    )

    target_compile_options(mnnkitconvert PRIVATE
            -fexceptions -frtti -Wall -Wno-unused-parameter
    )
    target_link_options(mnnkitconvert PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_libraries(mnnkitconvert android log MNN MNNConvertDeps)
endif ()

message(STATUS "mnnkit: outputs = libmnnkitbridge.so$<$<BOOL:${HAVE_CONVERTER}>:, libmnnkitconvert.so>")
