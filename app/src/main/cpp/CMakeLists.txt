cmake_minimum_required(VERSION 3.22.1)
project(digitor_ppmatting_opencl LANGUAGES CXX)

add_library(digitor_ppmatting_opencl SHARED PpMattingOpenClJni.cpp)
target_compile_features(digitor_ppmatting_opencl PRIVATE cxx_std_17)
target_compile_options(digitor_ppmatting_opencl PRIVATE -O3 -ffast-math -fvisibility=hidden)

find_library(log-lib log)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    if(NOT DEFINED PADDLE_LITE_ROOT)
        message(FATAL_ERROR "PADDLE_LITE_ROOT is required for arm64 Paddle Lite OpenCL")
    endif()

    set(PADDLE_INCLUDE "${PADDLE_LITE_ROOT}/cxx/include")
    set(PADDLE_SHARED_RAW "${PADDLE_LITE_ROOT}/cxx/libs/arm64-v8a/libpaddle_light_api_shared.so")

    if(NOT EXISTS "${PADDLE_INCLUDE}/paddle_api.h")
        message(FATAL_ERROR "Paddle Lite header missing: ${PADDLE_INCLUDE}/paddle_api.h")
    endif()
    if(NOT EXISTS "${PADDLE_SHARED_RAW}")
        message(FATAL_ERROR "Paddle Lite OpenCL library missing: ${PADDLE_SHARED_RAW}")
    endif()
    if(NOT CMAKE_STRIP)
        message(FATAL_ERROR "Android llvm-strip was not provided by the NDK toolchain")
    endif()

    # Paddle Lite v2.14's published arm64 OpenCL ELF contains three local linker symbols
    # (__bss_start/_edata/_end) after the ELF local-symbol boundary. Modern lld rejects the
    # otherwise loadable DSO while reading its full .symtab. Strip only the non-runtime symbol
    # table from a build-local copy before linking. llvm-strip keeps the dynamic symbol table
    # (.dynsym), relocations and SONAME that Android needs at runtime.
    set(PADDLE_SANITIZED_DIR "${CMAKE_CURRENT_BINARY_DIR}/paddle-opencl-runtime")
    set(PADDLE_SHARED "${PADDLE_SANITIZED_DIR}/libpaddle_light_api_shared.so")
    add_custom_command(
        OUTPUT "${PADDLE_SHARED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${PADDLE_SANITIZED_DIR}"
        COMMAND "${CMAKE_COMMAND}" -E copy "${PADDLE_SHARED_RAW}" "${PADDLE_SHARED}"
        COMMAND "${CMAKE_STRIP}" --strip-all "${PADDLE_SHARED}"
        DEPENDS "${PADDLE_SHARED_RAW}"
        COMMENT "Sanitizing Paddle Lite OpenCL ELF for modern Android lld"
        VERBATIM
    )
    add_custom_target(paddle_opencl_runtime_sanitized DEPENDS "${PADDLE_SHARED}")

    add_library(paddle_light_api_shared SHARED IMPORTED)
    set_target_properties(
        paddle_light_api_shared
        PROPERTIES IMPORTED_LOCATION "${PADDLE_SHARED}"
    )

    add_dependencies(digitor_ppmatting_opencl paddle_opencl_runtime_sanitized)
    target_compile_definitions(digitor_ppmatting_opencl PRIVATE DIGITOR_PADDLE_OPENCL=1)
    target_include_directories(digitor_ppmatting_opencl PRIVATE "${PADDLE_INCLUDE}")
    target_link_libraries(digitor_ppmatting_opencl paddle_light_api_shared ${log-lib})
else()
    # Emulator/x86 builds keep a tiny JNI stub. Kotlin will report OpenCL unavailable and use the
    # existing ONNX fallback, while the real arm64 phone APK links Paddle Lite OpenCL.
    target_link_libraries(digitor_ppmatting_opencl ${log-lib})
endif()
