cmake_minimum_required(VERSION 3.28...3.30)
project(obs_android_jni LANGUAGES CXX)
get_filename_component(PORT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
add_library(obs SHARED IMPORTED)
set_target_properties(obs PROPERTIES IMPORTED_LOCATION "${PORT_ROOT}/out/libobs-arm64/libobs/libobs.so")
add_library(obs-android-jni SHARED obs-jni.cpp obs-session.cpp android-sources.cpp android-encoders.cpp android-hw-video.cpp android-uvc.cpp android-browser.cpp android-browser-docks.cpp android-ndi.cpp android-multistream.cpp android-vtuber.cpp android-graphics.cpp android-srt.cpp android-move-transition.cpp android-frame-style-filter.cpp android-bg-removal-filter.cpp)
target_compile_features(obs-android-jni PRIVATE cxx_std_17)
target_include_directories(obs-android-jni PRIVATE
    "${PORT_ROOT}/out/src/obs-studio/libobs"
    "${PORT_ROOT}/out/libobs-arm64/config"
    "${PORT_ROOT}/out/deps/arm64-v8a/include")
target_link_options(obs-android-jni PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(obs-android-jni PRIVATE
    obs
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libsrt.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libavformat.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libavcodec.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libswresample.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libswscale.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libavutil.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libmbedtls.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libmbedx509.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libmbedcrypto.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libeverest.a"
    "${PORT_ROOT}/out/deps/arm64-v8a/lib/libp256m.a"
    z
    android
    EGL
    GLESv3
    log
    mediandk)
