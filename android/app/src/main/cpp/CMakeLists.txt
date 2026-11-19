cmake_minimum_required(VERSION 3.22.1)
project(airplay_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

add_definitions(-DSTANDALONE)
add_definitions(-DTARGET_POSIX)
add_definitions(-D_LINUX)
add_definitions(-D__STDC_CONSTANT_MACROS)
add_definitions(-D__STDC_LIMIT_MACROS)
add_definitions(-DNOHOLD)
add_definitions(-DUSE_MDNS=1)
add_definitions(-DOPENSSL_API_COMPAT=0x10101000L)
add_definitions(-DPLIST_210)
add_definitions(-DPLIST_230)

set(UXPLAY_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

# OpenSSL via Google Android NDK prefab package
find_package(openssl REQUIRED CONFIG)

# Build libplist from source via FetchContent
include(FetchContent)
FetchContent_Declare(
    libplist
    GIT_REPOSITORY https://github.com/libimobiledevice/libplist.git
    GIT_TAG 2.3.0
)
FetchContent_GetProperties(libplist)
if(NOT libplist_POPULATED)
    FetchContent_Populate(libplist)

    set(LIBPLIST_SOURCES
        ${libplist_SOURCE_DIR}/src/base64.c
        ${libplist_SOURCE_DIR}/src/bytearray.c
        ${libplist_SOURCE_DIR}/src/hashtable.c
        ${libplist_SOURCE_DIR}/src/ptrarray.c
        ${libplist_SOURCE_DIR}/src/time64.c
        ${libplist_SOURCE_DIR}/src/xplist.c
        ${libplist_SOURCE_DIR}/src/bplist.c
        ${libplist_SOURCE_DIR}/src/jsmn.c
        ${libplist_SOURCE_DIR}/src/jplist.c
        ${libplist_SOURCE_DIR}/src/oplist.c
        ${libplist_SOURCE_DIR}/src/out-default.c
        ${libplist_SOURCE_DIR}/src/out-plutil.c
        ${libplist_SOURCE_DIR}/src/out-limd.c
        ${libplist_SOURCE_DIR}/src/plist.c
        ${libplist_SOURCE_DIR}/libcnary/node.c
        ${libplist_SOURCE_DIR}/libcnary/node_list.c
    )
    add_library(plist STATIC ${LIBPLIST_SOURCES})
    target_include_directories(plist PUBLIC
        ${libplist_SOURCE_DIR}/include
        ${libplist_SOURCE_DIR}/src
        ${libplist_SOURCE_DIR}/libcnary/include
    )
    target_compile_definitions(plist PRIVATE
        PACKAGE_VERSION="2.3.0"
        HAVE_STRNDUP=1
        HAVE_STRPTIME=1
        HAVE_MEMMEM=1
    )
    target_compile_options(plist PRIVATE -Wno-typedef-redefinition)
endif()

include_directories(
    .
    ${UXPLAY_ROOT}/lib
    ${UXPLAY_ROOT}/lib/playfair
    ${UXPLAY_ROOT}/lib/llhttp
    ${UXPLAY_ROOT}/lib/mdnsd
    ${libplist_SOURCE_DIR}/include
)

# UxPlay Core C sources
set(UXPLAY_LIB_SOURCES
    ${UXPLAY_ROOT}/lib/airplay_video.c
    ${UXPLAY_ROOT}/lib/byteutils.c
    ${UXPLAY_ROOT}/lib/compat.c
    ${UXPLAY_ROOT}/lib/crypto.c
    ${UXPLAY_ROOT}/lib/dnssd.c
    ${UXPLAY_ROOT}/lib/fairplay_playfair.c
    ${UXPLAY_ROOT}/lib/httpd.c
    ${UXPLAY_ROOT}/lib/http_request.c
    ${UXPLAY_ROOT}/lib/http_response.c
    ${UXPLAY_ROOT}/lib/logger.c
    ${UXPLAY_ROOT}/lib/mirror_buffer.c
    ${UXPLAY_ROOT}/lib/netutils.c
    ${UXPLAY_ROOT}/lib/pairing.c
    ${UXPLAY_ROOT}/lib/raop.c
    ${UXPLAY_ROOT}/lib/raop_buffer.c
    ${UXPLAY_ROOT}/lib/raop_ntp.c
    ${UXPLAY_ROOT}/lib/raop_rtp.c
    ${UXPLAY_ROOT}/lib/raop_rtp_mirror.c
    ${UXPLAY_ROOT}/lib/srp.c
    ${UXPLAY_ROOT}/lib/utils.c
)

# LLHTTP sources
set(LLHTTP_SOURCES
    ${UXPLAY_ROOT}/lib/llhttp/api.c
    ${UXPLAY_ROOT}/lib/llhttp/http.c
    ${UXPLAY_ROOT}/lib/llhttp/llhttp.c
)

# Playfair (FairPlay) sources
set(PLAYFAIR_SOURCES
    ${UXPLAY_ROOT}/lib/playfair/hand_garble.c
    ${UXPLAY_ROOT}/lib/playfair/modified_md5.c
    ${UXPLAY_ROOT}/lib/playfair/omg_hax.c
    ${UXPLAY_ROOT}/lib/playfair/playfair.c
    ${UXPLAY_ROOT}/lib/playfair/sap_hash.c
)

# Embedded mdnsd sources
set(MDNSD_SOURCES
    ${UXPLAY_ROOT}/lib/mdnsd/dnssd_mdnsd.c
    ${UXPLAY_ROOT}/lib/mdnsd/mdnsd.c
)

# Android JNI and Hardware Renderers
set(ANDROID_SOURCES
    airplay_jni.cpp
    android_video_renderer.c
    android_audio_renderer.c
    alac.c
)

add_library(airplay_native SHARED
    ${ANDROID_SOURCES}
    ${UXPLAY_LIB_SOURCES}
    ${LLHTTP_SOURCES}
    ${PLAYFAIR_SOURCES}
    ${MDNSD_SOURCES}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(airplay_native
    ${log-lib}
    ${android-lib}
    plist
    openssl::crypto
    openssl::ssl
)
