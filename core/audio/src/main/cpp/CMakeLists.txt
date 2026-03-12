cmake_minimum_required(VERSION 3.22.1)
project("binaural-engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Оптимизации для release
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -g -Wall -Wextra")

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
    target_compile_options(binaural-engine PRIVATE -mfpu=neon -ftree-vectorize)
    add_definitions(-DUSE_NEON)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(binaural-engine PRIVATE -ftree-vectorize)
    add_definitions(-DUSE_NEON)
endif()

# Определения для отладки
add_definitions(-DANDROID)