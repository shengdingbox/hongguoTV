cmake_minimum_required(VERSION 3.22.1)
project(hongguotv_heif LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(heif_native SHARED heif_jni.cpp)
target_include_directories(heif_native PRIVATE "${HEIF_PREFIX}/include")
target_link_libraries(heif_native PRIVATE
    "${HEIF_PREFIX}/lib/libheif.a" "${HEIF_PREFIX}/lib/libde265.a"
    jnigraphics log android)
# 只导出 JNI，允许链接器裁掉静态依赖中未使用的功能，并支持 16 KiB 页大小。
target_link_options(heif_native PRIVATE
    "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL"
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    "-Wl,-z,max-page-size=16384")
set_property(TARGET heif_native APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
