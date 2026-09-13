cmake_minimum_required(VERSION 3.22.1)
project(digitor_native LANGUAGES C CXX)

# V69 chooses 256/320/384/512 at runtime. The selected ncnn graph is fixed-shape, but the JNI bridge
# itself must not bake one compile-time tensor size into the shared library.
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
find_library(android-lib android)

target_link_libraries(digitor_ppmatting_ncnn ncnn ${log-lib})

# V80/V84 Auto Caption: pin whisper.cpp and build ggml's cross-vendor Vulkan backend. Android
# phones with a usable Vulkan compute device offload Whisper tensors to the GPU; JNI keeps a CPU
# fallback for devices/drivers where Vulkan cannot initialize.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build whisper.cpp statically inside Digitor JNI" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan GPU inference for Auto Caption" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_DEBUG OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_VALIDATE OFF CACHE BOOL "" FORCE)

# ggml-vulkan cross-compiles the Android runtime but compiles its shader generator on the host.
# Android's toolchain normally searches packages inside the NDK sysroot, so explicitly expose the
# host SPIR-V headers and glslc when they are installed by CI / Linux developer environments.
if(ANDROID)
    if(NOT DEFINED SPIRV-Headers_DIR)
        foreach(_spirv_dir
            "/usr/share/cmake/SPIRV-Headers"
            "/usr/local/share/cmake/SPIRV-Headers"
        )
            if(EXISTS "${_spirv_dir}/SPIRV-HeadersConfig.cmake")
                set(SPIRV-Headers_DIR "${_spirv_dir}" CACHE PATH "Host SPIR-V headers package" FORCE)
                break()
            endif()
        endforeach()
    endif()

    if(NOT Vulkan_GLSLC_EXECUTABLE)
        find_program(DIGITOR_HOST_GLSLC NAMES glslc NO_CMAKE_FIND_ROOT_PATH)
        if(DIGITOR_HOST_GLSLC)
            set(Vulkan_GLSLC_EXECUTABLE "${DIGITOR_HOST_GLSLC}" CACHE FILEPATH "Host glslc for ggml Vulkan shaders" FORCE)
        endif()
    endif()

    # NDK Vulkan C headers do not include Vulkan-Hpp (`vulkan/vulkan.hpp`), which ggml-vulkan uses.
    # Prefer an already-installed host Vulkan-Hpp directory, but never add /usr/include wholesale to
    # the Android cross-compile because that can leak host libc headers into the target build.
    foreach(_vulkan_hpp_root "/usr/include" "/usr/local/include")
        if(EXISTS "${_vulkan_hpp_root}/vulkan/vulkan.hpp")
            set(DIGITOR_VULKAN_HPP_INCLUDE "${CMAKE_BINARY_DIR}/digitor-vulkan-hpp")
            file(MAKE_DIRECTORY "${DIGITOR_VULKAN_HPP_INCLUDE}")
            file(COPY "${_vulkan_hpp_root}/vulkan" DESTINATION "${DIGITOR_VULKAN_HPP_INCLUDE}")
            break()
        endif()
    endforeach()

    # CI/Android Studio hosts do not always ship Vulkan-Hpp. Fetch a pinned Khronos header snapshot
    # as a portable fallback. Only headers are consumed; the Android Vulkan loader still comes from
    # the NDK/runtime, so this does not add a second Vulkan implementation to the APK.
    if(NOT DIGITOR_VULKAN_HPP_INCLUDE)
        FetchContent_Declare(
            digitor_vulkan_headers
            GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
            GIT_TAG v1.4.321
            GIT_SHALLOW TRUE
            GIT_PROGRESS FALSE
        )
        FetchContent_GetProperties(digitor_vulkan_headers)
        if(NOT digitor_vulkan_headers_POPULATED)
            FetchContent_Populate(digitor_vulkan_headers)
        endif()
        set(DIGITOR_VULKAN_HPP_INCLUDE "${digitor_vulkan_headers_SOURCE_DIR}/include")
    endif()

    # ggml-vulkan.cpp also includes SPIR-V's generated C++ enums (`spirv/unified1/spirv.hpp`).
    # The Ubuntu/CI package installs them under /usr/include, which the Android toolchain correctly
    # excludes from its sysroot. Stage only the self-contained `spirv/` directory so host libc/C++
    # headers cannot leak into the Android target compilation.
    foreach(_spirv_include_root "/usr/include" "/usr/local/include")
        if(EXISTS "${_spirv_include_root}/spirv/unified1/spirv.hpp")
            set(DIGITOR_SPIRV_INCLUDE "${CMAKE_BINARY_DIR}/digitor-spirv-headers")
            file(MAKE_DIRECTORY "${DIGITOR_SPIRV_INCLUDE}")
            file(COPY "${_spirv_include_root}/spirv" DESTINATION "${DIGITOR_SPIRV_INCLUDE}")
            break()
        endif()
    endforeach()
endif()

FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.4
    GIT_SHALLOW TRUE
    GIT_PROGRESS FALSE
)

# Populate first so the pinned source can receive a tiny Android loader compatibility patch before
# whisper/ggml targets are created. Android API 24's libvulkan.so exports Vulkan 1.0 symbols only;
# ggml-vulkan v1.9.4 still makes three raw vkGetPhysicalDeviceFeatures2 calls (Vulkan 1.1 core),
# even though the same file already initializes Vulkan-Hpp's dynamic dispatcher. Calling those raw
# symbols makes the final JNI link fail at minSdk 24. Route them through the dispatcher instead and
# fall back to the KHR alias when a Vulkan 1.0 driver exposes VK_KHR_get_physical_device_properties2.
FetchContent_GetProperties(whisper_cpp)
if(NOT whisper_cpp_POPULATED)
    FetchContent_Populate(whisper_cpp)
endif()

if(ANDROID)
    set(_digitor_ggml_vk_source "${whisper_cpp_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    if(NOT EXISTS "${_digitor_ggml_vk_source}")
        message(FATAL_ERROR "Pinned whisper.cpp ggml-vulkan source missing: ${_digitor_ggml_vk_source}")
    endif()

    file(READ "${_digitor_ggml_vk_source}" _digitor_ggml_vk_text)
    if(NOT _digitor_ggml_vk_text MATCHES "digitor_vkGetPhysicalDeviceFeatures2")
        string(REGEX MATCHALL "vkGetPhysicalDeviceFeatures2\\(" _digitor_vk_feature2_matches "${_digitor_ggml_vk_text}")
        list(LENGTH _digitor_vk_feature2_matches _digitor_vk_feature2_count)
        if(NOT _digitor_vk_feature2_count EQUAL 3)
            message(FATAL_ERROR "Unexpected whisper.cpp v1.9.4 vkGetPhysicalDeviceFeatures2 call count: ${_digitor_vk_feature2_count}")
        endif()

        # Replace raw link-time calls before injecting the helper so the helper's dispatcher members
        # themselves are not rewritten by this string replacement.
        string(REPLACE
            "vkGetPhysicalDeviceFeatures2("
            "digitor_vkGetPhysicalDeviceFeatures2("
            _digitor_ggml_vk_text
            "${_digitor_ggml_vk_text}"
        )

        set(_digitor_vk_helper [=[

// Digitor Android compatibility: API 24's Vulkan loader does not export the Vulkan 1.1 core
// vkGetPhysicalDeviceFeatures2 symbol. Vulkan-Hpp's dispatcher is initialized with the instance
// before ggml enumerates devices, so use its proc-address table and accept the Vulkan 1.0 KHR alias.
static void digitor_vkGetPhysicalDeviceFeatures2(
    VkPhysicalDevice physical_device,
    VkPhysicalDeviceFeatures2 * features) {
    auto & dispatcher = ggml_vk_default_dispatcher();
    if (dispatcher.vkGetPhysicalDeviceFeatures2 != nullptr) {
        dispatcher.vkGetPhysicalDeviceFeatures2(physical_device, features);
        return;
    }
#if defined(VK_KHR_get_physical_device_properties2)
    if (dispatcher.vkGetPhysicalDeviceFeatures2KHR != nullptr) {
        dispatcher.vkGetPhysicalDeviceFeatures2KHR(physical_device, features);
        return;
    }
#endif
    // Extremely old Vulkan 1.0 implementations without the KHR feature-query extension cannot
    // populate extension feature chains. Fill only base 1.0 features; ggml will reject unavailable
    // advanced capabilities and Whisper's JNI layer can retry on its CPU backend.
    if (features != nullptr) {
        vkGetPhysicalDeviceFeatures(physical_device, &features->features);
    }
}
]=])

        string(REPLACE
            "#include <vulkan/vulkan.hpp>\n"
            "#include <vulkan/vulkan.hpp>\n${_digitor_vk_helper}\n"
            _digitor_ggml_vk_text
            "${_digitor_ggml_vk_text}"
        )

        # Vulkan-Hpp documents a second dispatcher init with the created VkDevice for device-level
        # extension pointers. This also matches the Android fix proposed upstream for ggml-vulkan.
        string(REPLACE
            "        device->device = device->physical_device.createDevice(device_create_info);\n"
            "        device->device = device->physical_device.createDevice(device_create_info);\n        VULKAN_HPP_DEFAULT_DISPATCHER.init(device->device);\n"
            _digitor_ggml_vk_text
            "${_digitor_ggml_vk_text}"
        )

        file(WRITE "${_digitor_ggml_vk_source}" "${_digitor_ggml_vk_text}")
        message(STATUS "Applied Digitor Android Vulkan dispatcher patch to whisper.cpp v1.9.4")
    endif()
endif()

# Equivalent to FetchContent_MakeAvailable after the pre-add_subdirectory compatibility patch.
add_subdirectory("${whisper_cpp_SOURCE_DIR}" "${whisper_cpp_BINARY_DIR}")

if(TARGET ggml-vulkan AND DIGITOR_VULKAN_HPP_INCLUDE)
    target_include_directories(ggml-vulkan PRIVATE "${DIGITOR_VULKAN_HPP_INCLUDE}")
endif()
if(TARGET ggml-vulkan AND DIGITOR_SPIRV_INCLUDE)
    target_include_directories(ggml-vulkan PRIVATE "${DIGITOR_SPIRV_INCLUDE}")
endif()

# The Android Gradle native build historically supplies -ffast-math for the PP-Matting path.
# ggml deliberately relies on NaN/Infinity semantics and refuses to compile with finite-math-only.
# Reset fast-math only for whisper/ggml targets while preserving the existing PP-Matting tuning.
foreach(whisper_math_target whisper ggml ggml-base ggml-cpu ggml-vulkan)
    if(TARGET ${whisper_math_target})
        target_compile_options(${whisper_math_target} PRIVATE -fno-fast-math -fno-finite-math-only)
    endif()
endforeach()

add_library(digitor_whisper_jni SHARED WhisperAutoCaptionJni.cpp)
target_compile_features(digitor_whisper_jni PRIVATE cxx_std_17)
target_compile_options(digitor_whisper_jni PRIVATE -O3 -fno-fast-math -fno-finite-math-only -fvisibility=hidden)
target_include_directories(digitor_whisper_jni PRIVATE ${whisper_cpp_SOURCE_DIR}/include)
target_link_libraries(digitor_whisper_jni whisper ${log-lib} ${android-lib})
