cmake_minimum_required(VERSION 3.28...3.30)
project(TCStudioIOSProbe VERSION 0.1.0 LANGUAGES CXX OBJCXX)

if(NOT IOS)
    message(FATAL_ERROR "Configure this target with Qt's iOS kit and Xcode (iOS toolchain).")
endif()

find_package(Qt6 6.9 REQUIRED COMPONENTS Core Gui Widgets)
qt_standard_project_setup()

find_library(AVFOUNDATION_FRAMEWORK AVFoundation REQUIRED)
find_library(COREMEDIA_FRAMEWORK CoreMedia REQUIRED)
find_library(COREVIDEO_FRAMEWORK CoreVideo REQUIRED)
find_library(VIDEOTOOLBOX_FRAMEWORK VideoToolbox REQUIRED)

qt_add_executable(tcstudio-ios-probe
    main.cpp
    IOSMediaProbe.hpp
    IOSMediaProbe.mm
)

target_compile_features(tcstudio-ios-probe PRIVATE cxx_std_17)
target_include_directories(tcstudio-ios-probe PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../android/app/src/main/cpp"
)
target_link_libraries(tcstudio-ios-probe PRIVATE
    Qt6::Core
    Qt6::Gui
    Qt6::Widgets
    ${AVFOUNDATION_FRAMEWORK}
    ${COREMEDIA_FRAMEWORK}
    ${COREVIDEO_FRAMEWORK}
    ${VIDEOTOOLBOX_FRAMEWORK}
)
target_compile_options(tcstudio-ios-probe PRIVATE
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
)
set_target_properties(tcstudio-ios-probe PROPERTIES
    MACOSX_BUNDLE TRUE
    XCODE_ATTRIBUTE_PRODUCT_BUNDLE_IDENTIFIER "org.obsandroid.port.iosprobe"
    XCODE_ATTRIBUTE_INFOPLIST_KEY_NSCameraUsageDescription "TC Studio prueba la cámara para evaluar la captura nativa de iOS."
    XCODE_ATTRIBUTE_INFOPLIST_KEY_UIUserInterfaceStyle "Dark"
)
