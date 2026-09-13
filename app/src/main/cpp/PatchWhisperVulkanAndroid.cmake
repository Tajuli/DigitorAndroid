# whisper.cpp v1.9.4's ggml-vulkan backend calls the Vulkan 1.1 core entry point
# vkGetPhysicalDeviceFeatures2 directly. Digitor keeps minSdk 24, whose NDK Vulkan
# import library does not export that core symbol, even though newer device loaders
# can expose it at runtime through vkGetInstanceProcAddr.
#
# Route the three pinned upstream calls through Vulkan-Hpp's dynamic dispatcher.
# This removes the API-28 link-time dependency without lowering the runtime Vulkan
# requirement: ggml-vulkan still checks for Vulkan 1.2 and JNI falls back to CPU
# when the device/driver cannot provide a usable GPU backend.

if(NOT DEFINED GGML_VULKAN_SOURCE OR NOT EXISTS "${GGML_VULKAN_SOURCE}")
    message(FATAL_ERROR "ggml-vulkan source was not supplied to the Android compatibility patch")
endif()

file(READ "${GGML_VULKAN_SOURCE}" _digitor_vk_source)
set(_digitor_vk_dynamic_call "VULKAN_HPP_DEFAULT_DISPATCHER.vkGetPhysicalDeviceFeatures2(")

# FetchContent can retain a populated source tree across CMake reconfiguration, so
# make the patch idempotent instead of prefixing the dispatcher more than once.
string(REGEX MATCHALL "VULKAN_HPP_DEFAULT_DISPATCHER\\.vkGetPhysicalDeviceFeatures2\\(" _digitor_patched_calls "${_digitor_vk_source}")
list(LENGTH _digitor_patched_calls _digitor_patched_count)
if(_digitor_patched_count EQUAL 3)
    message(STATUS "Whisper Vulkan Android API-24 compatibility patch already applied")
    return()
elseif(_digitor_patched_count GREATER 0)
    message(FATAL_ERROR "Whisper Vulkan source is only partially patched (${_digitor_patched_count}/3 calls)")
endif()

string(REGEX MATCHALL "vkGetPhysicalDeviceFeatures2\\(" _digitor_direct_calls "${_digitor_vk_source}")
list(LENGTH _digitor_direct_calls _digitor_direct_count)
if(NOT _digitor_direct_count EQUAL 3)
    message(FATAL_ERROR "Pinned whisper.cpp Vulkan source changed: expected 3 direct vkGetPhysicalDeviceFeatures2 calls, found ${_digitor_direct_count}")
endif()

string(REPLACE "vkGetPhysicalDeviceFeatures2(" "${_digitor_vk_dynamic_call}" _digitor_vk_patched "${_digitor_vk_source}")
file(WRITE "${GGML_VULKAN_SOURCE}" "${_digitor_vk_patched}")

string(REGEX MATCHALL "VULKAN_HPP_DEFAULT_DISPATCHER\\.vkGetPhysicalDeviceFeatures2\\(" _digitor_verify_calls "${_digitor_vk_patched}")
list(LENGTH _digitor_verify_calls _digitor_verify_count)
if(NOT _digitor_verify_count EQUAL 3)
    message(FATAL_ERROR "Whisper Vulkan Android compatibility patch verification failed")
endif()

message(STATUS "Patched whisper.cpp Vulkan feature queries for Android API-24 dynamic loading")
