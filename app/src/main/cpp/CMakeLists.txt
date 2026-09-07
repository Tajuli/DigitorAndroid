cmake_minimum_required(VERSION 3.22.1)
project(digitor_ppmatting_ncnn LANGUAGES CXX)

if(NOT DEFINED PPMATTING_MODEL_SIZE)
    set(PPMATTING_MODEL_SIZE 512)
endif()
if(NOT (PPMATTING_MODEL_SIZE EQUAL 256 OR PPMATTING_MODEL_SIZE EQUAL 384 OR PPMATTING_MODEL_SIZE EQUAL 512))
    message(FATAL_ERROR "PPMATTING_MODEL_SIZE must be 256, 384 or 512, got ${PPMATTING_MODEL_SIZE}")
endif()

add_library(digitor_ppmatting_ncnn SHARED PpMattingNcnnVulkanJni.cpp)
target_compile_features(digitor_ppmatting_ncnn PRIVATE cxx_std_17)
target_compile_options(digitor_ppmatting_ncnn PRIVATE -O3 -ffast-math -fvisibility=hidden)
target_compile_definitions(digitor_ppmatting_ncnn PRIVATE PPMATTING_MODEL_SIZE=${PPMATTING_MODEL_SIZE})

if(NOT DEFINED NCNN_ANDROID_ROOT)
    message(FATAL_ERROR "NCNN_ANDROID_ROOT is required for the PP-MattingV2 Vulkan backend")
endif()

set(ncnn_DIR "${NCNN_ANDROID_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn" CACHE PATH "ncnn package directory" FORCE)
if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake")
    message(FATAL_ERROR "ncnn Vulkan package missing for ${ANDROID_ABI}: ${ncnn_DIR}/ncnnConfig.cmake")
endif()

find_package(ncnn REQUIRED)
find_library(log-lib log)

target_link_libraries(digitor_ppmatting_ncnn ncnn ${log-lib})
