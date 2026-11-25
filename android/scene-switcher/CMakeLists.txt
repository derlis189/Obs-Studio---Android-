# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.30)
project(obs-studio LANGUAGES C CXX)
get_filename_component(PORT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(OBS_SRC "${PORT_ROOT}/out/src/obs-studio")
set(OBS_BUILD "${PORT_ROOT}/out/libobs-arm64")
add_library(OBS::libobs SHARED IMPORTED GLOBAL)
set_target_properties(OBS::libobs PROPERTIES
  IMPORTED_LOCATION "${OBS_BUILD}/libobs/libobs.so"
  INTERFACE_INCLUDE_DIRECTORIES "${OBS_SRC}/libobs;${OBS_BUILD}/config")
add_library(OBS::frontend-api SHARED IMPORTED GLOBAL)
set_target_properties(OBS::frontend-api PROPERTIES
  IMPORTED_LOCATION "${OBS_BUILD}/frontend/api/libobs-frontend-api.so"
  INTERFACE_INCLUDE_DIRECTORIES "${OBS_SRC}/frontend/api")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_link_options(-Wl,-z,max-page-size=16384 -Wl,--no-undefined)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/jniLibs/arm64-v8a")
find_package(CURL REQUIRED)
add_subdirectory("${PORT_ROOT}/out/scene-switcher-src" upstream)
foreach(target advanced-scene-switcher advanced-scene-switcher-lib advanced-scene-switcher-base)
  set_target_properties(${target} PROPERTIES PREFIX "lib")
endforeach()
target_link_libraries(advanced-scene-switcher-lib PRIVATE CURL::libcurl log)
target_sources(advanced-scene-switcher-lib PRIVATE "${CMAKE_CURRENT_LIST_DIR}/demo-android.cpp")
set_source_files_properties("${CMAKE_CURRENT_LIST_DIR}/demo-android.cpp" PROPERTIES SKIP_AUTOGEN ON)
