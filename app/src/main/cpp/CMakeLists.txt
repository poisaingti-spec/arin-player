cmake_minimum_required(VERSION 3.22.1)
project(arinwhisper C CXX)

set(CMAKE_CXX_STANDARD 17)
include(FetchContent)

# whisper.cpp + ggml as static libs, linked into one JNI library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16" CACHE STRING "" FORCE)
endif ()

FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(arinwhisper SHARED whisper_jni.cpp)
target_compile_options(arinwhisper PRIVATE -O3)
find_library(LOG_LIB log)
target_link_libraries(arinwhisper whisper ${LOG_LIB} android)
