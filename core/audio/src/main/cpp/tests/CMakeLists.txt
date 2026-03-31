cmake_minimum_required(VERSION 3.22.1)
project(binaural_engine_tests)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Debug режим с логированием
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -g -Wall -Wextra")

# Find Google Test
find_package(GTest REQUIRED)

# Исходные файлы движка (без jni.cpp)
add_library(binaural_engine_test_lib STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/../src/Wavetable.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../src/AudioGenerator.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/../src/BinauralEngine.cpp
)

target_include_directories(binaural_engine_test_lib
    PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/../include
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}  # Для android_stub.h
)

# Определения для тестов (заглушка ANDROID)
target_compile_definitions(binaural_engine_test_lib PRIVATE 
    ANDROID
    AUDIO_DEBUG
    AUDIO_TEST_BUILD
)

# Для тестов используем SSE на x86_64
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    target_compile_options(binaural_engine_test_lib PRIVATE -mssse3 -ftree-vectorize)
    target_compile_definitions(binaural_engine_test_lib PRIVATE USE_SSE)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "arm|aarch64")
    target_compile_options(binaural_engine_test_lib PRIVATE -ftree-vectorize)
    target_compile_definitions(binaural_engine_test_lib PRIVATE USE_NEON)
endif()

# Исполняемый файл тестов
add_executable(buffer_package_tests
    BufferPackageTest.cpp
)

target_include_directories(buffer_package_tests
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../include
    ${CMAKE_CURRENT_SOURCE_DIR}  # Для android_stub.h
)

target_link_libraries(buffer_package_tests
    binaural_engine_test_lib
    GTest::gtest
    GTest::gtest_main
    pthread
)

# Добавляем тесты в CTest (без discover из-за проблем с LD_LIBRARY_PATH)
# include(GoogleTest)
# gtest_discover_tests(buffer_package_tests)
add_test(NAME buffer_package_tests COMMAND buffer_package_tests)
