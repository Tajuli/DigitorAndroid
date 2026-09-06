cmake_minimum_required(VERSION 3.22.1)
project(digitor_ppmatting_ncnn LANGUAGES CXX)

add_library(digitor_ppmatting_ncnn SHARED PpMattingNcnnVulkanJni.cpp)
target_compile_features(digitor_ppmatting_ncnn PRIVATE cxx_std_17)
target_compile_options(digitor_ppmatting_ncnn PRIVATE -O3 -ffast-math -fvisibility=hidden)

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
