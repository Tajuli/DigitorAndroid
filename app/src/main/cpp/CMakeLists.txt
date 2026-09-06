cmake_minimum_required(VERSION 3.22.1)
project(digitor_ppmatting_opencl LANGUAGES CXX)

if(NOT DEFINED PADDLE_LITE_ROOT)
    message(FATAL_ERROR "PADDLE_LITE_ROOT is required")
endif()

set(PADDLE_INCLUDE "${PADDLE_LITE_ROOT}/cxx/include")
set(PADDLE_SHARED "${PADDLE_LITE_ROOT}/cxx/libs/${ANDROID_ABI}/libpaddle_light_api_shared.so")

if(NOT EXISTS "${PADDLE_INCLUDE}/paddle_api.h")
    message(FATAL_ERROR "Paddle Lite header missing: ${PADDLE_INCLUDE}/paddle_api.h")
endif()
if(NOT EXISTS "${PADDLE_SHARED}")
    message(FATAL_ERROR "Paddle Lite OpenCL library missing for ${ANDROID_ABI}: ${PADDLE_SHARED}")
endif()

add_library(paddle_light_api_shared SHARED IMPORTED)
set_target_properties(paddle_light_api_shared PROPERTIES IMPORTED_LOCATION "${PADDLE_SHARED}")

add_library(digitor_ppmatting_opencl SHARED PpMattingOpenClJni.cpp)
target_include_directories(digitor_ppmatting_opencl PRIVATE "${PADDLE_INCLUDE}")
target_compile_features(digitor_ppmatting_opencl PRIVATE cxx_std_17)
target_compile_options(digitor_ppmatting_opencl PRIVATE -O3 -ffast-math -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(digitor_ppmatting_opencl paddle_light_api_shared ${log-lib})
