cmake_minimum_required(VERSION 3.22.1)
project(digitor_ppmatting_opencl LANGUAGES CXX)

add_library(digitor_ppmatting_opencl SHARED PpMattingOpenClJni.cpp)
target_compile_features(digitor_ppmatting_opencl PRIVATE cxx_std_17)
target_compile_options(digitor_ppmatting_opencl PRIVATE -O3 -ffast-math -fvisibility=hidden)

find_library(log-lib log)
find_library(dl-lib dl)
find_library(android-lib android)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    if(NOT DEFINED PADDLE_LITE_ROOT)
        message(FATAL_ERROR "PADDLE_LITE_ROOT is required for arm64 Paddle Lite OpenCL")
    endif()

    set(PADDLE_INCLUDE "${PADDLE_LITE_ROOT}/cxx/include")
    set(PADDLE_STATIC "${PADDLE_LITE_ROOT}/cxx/libs/arm64-v8a/libpaddle_api_light_bundled.a")

    if(NOT EXISTS "${PADDLE_INCLUDE}/paddle_api.h")
        message(FATAL_ERROR "Paddle Lite header missing: ${PADDLE_INCLUDE}/paddle_api.h")
    endif()
    if(NOT EXISTS "${PADDLE_STATIC}")
        message(FATAL_ERROR "Paddle Lite OpenCL static library missing: ${PADDLE_STATIC}")
    endif()

    add_library(paddle_light_api_bundled STATIC IMPORTED)
    set_target_properties(paddle_light_api_bundled PROPERTIES IMPORTED_LOCATION "${PADDLE_STATIC}")

    target_compile_definitions(digitor_ppmatting_opencl PRIVATE DIGITOR_PADDLE_OPENCL=1)
    target_include_directories(digitor_ppmatting_opencl PRIVATE "${PADDLE_INCLUDE}")
    # Static bundle avoids linking the old prebuilt Paddle shared ELF directly with NDK 28's lld.
    # Paddle Lite resolves the vendor OpenCL implementation dynamically at runtime.
    target_link_libraries(
        digitor_ppmatting_opencl
        paddle_light_api_bundled
        ${log-lib}
        ${dl-lib}
        ${android-lib}
        atomic
        m
    )
else()
    # Emulator/x86 builds keep a tiny JNI stub. Kotlin will report OpenCL unavailable and use the
    # existing ONNX fallback, while the real arm64 phone APK embeds Paddle Lite OpenCL statically.
    target_link_libraries(digitor_ppmatting_opencl ${log-lib})
endif()
