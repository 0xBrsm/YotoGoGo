cmake_minimum_required(VERSION 3.22)
project(shine_bridge)

include(FetchContent)

FetchContent_Declare(
    shine
    GIT_REPOSITORY https://github.com/toots/shine.git
    GIT_TAG        3.1.1
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(shine)
if(NOT shine_POPULATED)
    FetchContent_Populate(shine)
endif()

set(SHINE_SRC
    ${shine_SOURCE_DIR}/src/lib/bitstream.c
    ${shine_SOURCE_DIR}/src/lib/huffman.c
    ${shine_SOURCE_DIR}/src/lib/l3bitstream.c
    ${shine_SOURCE_DIR}/src/lib/l3loop.c
    ${shine_SOURCE_DIR}/src/lib/l3mdct.c
    ${shine_SOURCE_DIR}/src/lib/l3subband.c
    ${shine_SOURCE_DIR}/src/lib/layer3.c
    ${shine_SOURCE_DIR}/src/lib/reservoir.c
    ${shine_SOURCE_DIR}/src/lib/tables.c
)

add_library(shine_bridge SHARED
    shine_bridge.cpp
    ${SHINE_SRC}
)

target_include_directories(shine_bridge PRIVATE
    ${shine_SOURCE_DIR}/src/lib
)

target_link_libraries(shine_bridge log)
