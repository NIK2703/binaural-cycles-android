cmake_minimum_required(VERSION 3.22.1)
project("binaural-engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Используем lld для кросс-компиляции (gold не поддерживает ARM64 на x86_64)
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -fuse-ld=lld")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -fuse-ld=lld")

# Оптимизации для release
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -funroll-loops -fomit-frame-pointer -fvisibility=hidden -ffp-contract=fast")

# Debug флаги - включаем AUDIO_DEBUG для логирования
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -Wall -Wextra -DAUDIO_DEBUG")

# LTO отключено на уровне CMake, т.к. AGP добавляет -fuse-ld=gold который не работает с ARM64
# Все остальные оптимизации остаются: -O3, -ffast-math, -funroll-loops, NEON/SSE
# LTO можно включить после обновления AGP или исправления окружения

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

# Оптимизация размера и скорости (LTO отключено - см. комментарий выше)
# set_target_properties(binaural-engine PROPERTIES
#     INTERPROCEDURAL_OPTIMIZATION TRUE
# )

# Принудительно используем lld через -B (перекрывает все остальные настройки линкера)
# Получаем HOME из environment
if(DEFINED ENV{HOME})
    set(NDK_BIN_PATH "$ENV{HOME}/tools/android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64/bin")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -B${NDK_BIN_PATH}")
endif()

# Дополнительная страховка - явный флаг линкера
target_link_options(binaural-engine PRIVATE "-fuse-ld=lld")
