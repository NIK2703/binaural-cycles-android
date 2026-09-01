cmake_minimum_required(VERSION 3.22.1)
project("binaural-engine")

# CMake 3.22 для Android подставляет в LTO-линковку "-fuse-ld=gold", если не может
# определить версию NDK (см. Modules/Compiler/Clang.cmake: ANDROID AND NOT
# CMAKE_ANDROID_NDK_VERSION VERSION_GREATER_EQUAL "22"). NDK 29 не объявляет
# CMAKE_ANDROID_NDK_VERSION, поэтому CMake считает NDK младше 22 и требует gold —
# а gold и LLVMgold.so удалены из NDK ещё в r23, и clang подхватывает системный
# /usr/bin/ld.gold, который не умеет aarch64. Явно используем lld из NDK.
# NDK r29 removed the gold linker. CMake 3.22's Compiler/Clang.cmake hardcodes
# CMAKE_<LANG>_LINK_OPTIONS_IPO = "-fuse-ld=gold" for IPO/LTO builds, and that flag
# is appended AFTER any target_link_options, so it always wins and breaks the link.
# Force lld (the NDK default) for both C and C++.
set(CMAKE_C_LINK_OPTIONS_IPO "-fuse-ld=lld")
set(CMAKE_CXX_LINK_OPTIONS_IPO "-fuse-ld=lld")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Оптимизации для release
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -funroll-loops -fomit-frame-pointer -fvisibility=hidden -ffp-contract=fast")

# Debug флаги - включаем AUDIO_DEBUG для логирования и ENABLE_DEBUG_TIME_CONTROL для виртуального времени
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -Wall -Wextra -DAUDIO_DEBUG -DENABLE_DEBUG_TIME_CONTROL=1")

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
    ${CMAKE_CURRENT_SOURCE_DIR}
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
