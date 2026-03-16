cmake_minimum_required(VERSION 3.22.1)
project("binaural-engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Оптимизации для release
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -funroll-loops -fomit-frame-pointer -fvisibility=hidden -ffp-contract=fast")

# Debug флаги - включаем AUDIO_DEBUG для логирования
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -Wall -Wextra -DAUDIO_DEBUG")

# LTO (Link Time Optimization) для release - дополнительная оптимизация
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    include(CheckIPOSupported)
    check_ipo_supported(RESULT LTO_SUPPORTED OUTPUT LTO_ERROR)
    if(LTO_SUPPORTED)
        set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)
    endif()
endif()

# Исходные файлы (Interpolation.h - header-only)
add_library(
    binaural-engine
    SHARED
    src/Wavetable.cpp
    src/AudioGenerator.cpp
    src/BinauralEngine.cpp
    src/jni.cpp
)

# Заголовочные файлы
target_include_directories(
    binaural-engine
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Линковка с Android log и STL
find_library(log-lib log)
target_link_libraries(
    binaural-engine
    ${log-lib}
    android
)

# NEON оптимизация для ARM
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # ARMv7: базовый NEON без расширенных intrinsics (vrndmq_f32, vfmaq_f32 недоступны)
    target_compile_options(binaural-engine PRIVATE -mfpu=neon -ftree-vectorize)
    add_definitions(-DUSE_NEON)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARMv8: полный NEON с FMA и rounding intrinsics
    target_compile_options(binaural-engine PRIVATE -ftree-vectorize)
    add_definitions(-DUSE_NEON)
    add_definitions(-DUSE_NEON_ARMV8)
elseif(ANDROID_ABI STREQUAL "x86")
    # x86: SSE оптимизация
    target_compile_options(binaural-engine PRIVATE -mssse3 -ftree-vectorize)
    add_definitions(-DUSE_SSE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # x86_64: SSE3+ оптимизация
    target_compile_options(binaural-engine PRIVATE -mssse3 -ftree-vectorize)
    add_definitions(-DUSE_SSE)
endif()

# Определения для отладки
add_definitions(-DANDROID)

# Оптимизация размера и скорости
set_target_properties(binaural-engine PROPERTIES
    INTERPROCEDURAL_OPTIMIZATION TRUE
)
