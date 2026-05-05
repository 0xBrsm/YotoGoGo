cmake_minimum_required(VERSION 3.22)
project(lame_bridge)

include(FetchContent)

FetchContent_Declare(lame
    URL https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
)
FetchContent_GetProperties(lame)
if(NOT lame_POPULATED)
    FetchContent_Populate(lame)
endif()

# Minimal config.h for Android (replaces autoconf-generated one)
file(WRITE ${lame_BINARY_DIR}/config.h [[
#pragma once
#define HAVE_CONFIG_H 1
#define HAVE_STDINT_H 1
#define HAVE_STRING_H 1
#define HAVE_LIMITS_H 1
#define HAVE_MEMSET 1
#define HAVE_MEMCPY 1
#define STDC_HEADERS 1
typedef float ieee754_float32_t;
#define PRI_INT64_T "lld"
#define PRI_UINT64_T "llu"
]])

set(LAME_SRC
    ${lame_SOURCE_DIR}/libmp3lame/bitstream.c
    ${lame_SOURCE_DIR}/libmp3lame/encoder.c
    ${lame_SOURCE_DIR}/libmp3lame/fft.c
    ${lame_SOURCE_DIR}/libmp3lame/gain_analysis.c
    ${lame_SOURCE_DIR}/libmp3lame/id3tag.c
    ${lame_SOURCE_DIR}/libmp3lame/lame.c
    ${lame_SOURCE_DIR}/libmp3lame/mpglib_interface.c
    ${lame_SOURCE_DIR}/libmp3lame/newmdct.c
    ${lame_SOURCE_DIR}/libmp3lame/presets.c
    ${lame_SOURCE_DIR}/libmp3lame/psymodel.c
    ${lame_SOURCE_DIR}/libmp3lame/quantize.c
    ${lame_SOURCE_DIR}/libmp3lame/quantize_pvt.c
    ${lame_SOURCE_DIR}/libmp3lame/reservoir.c
    ${lame_SOURCE_DIR}/libmp3lame/set_get.c
    ${lame_SOURCE_DIR}/libmp3lame/tables.c
    ${lame_SOURCE_DIR}/libmp3lame/takehiro.c
    ${lame_SOURCE_DIR}/libmp3lame/util.c
    ${lame_SOURCE_DIR}/libmp3lame/vbrquantize.c
    ${lame_SOURCE_DIR}/libmp3lame/version.c
)

add_library(lame_bridge SHARED
    lame_bridge.cpp
    ${LAME_SRC}
)

target_include_directories(lame_bridge PRIVATE
    ${lame_SOURCE_DIR}/include
    ${lame_SOURCE_DIR}/libmp3lame
    ${lame_BINARY_DIR}
)

target_compile_definitions(lame_bridge PRIVATE HAVE_CONFIG_H)

target_link_libraries(lame_bridge log)
