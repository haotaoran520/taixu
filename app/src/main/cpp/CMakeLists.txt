cmake_minimum_required(VERSION 3.22.1)
project(taixu_native C CXX)

add_library(pty_native SHARED pty_native.c)

target_compile_options(pty_native PRIVATE -Wall -Werror=implicit-function-declaration)

# Module 3: PLT-level file guard. ByteHook (Prefab) is used because it rewrites the GOT (PLT hook);
# ShadowHook is inline hooking and is deliberately NOT used here.
option(TAIXU_GUARD_HOOK "Build libguard_hook.so (ByteHook PLT file guard)" OFF)

if(TAIXU_GUARD_HOOK)
    # 两条路径都能满足：
    # 1) Gradle + Prefab（AGP 生成 bytehook::bytehook 的 config）；
    # 2) 直接用 NDK 交叉编译（.github/workflows/guard-hook-native.yml 走这条），
    #    此时用 -DBYTEHOOK_PREFAB=<bytehook.aar 解包后的 prefab 目录>。
    if(NOT TARGET bytehook::bytehook)
        find_package(bytehook CONFIG QUIET)
    endif()
    if(NOT TARGET bytehook::bytehook)
        if(BYTEHOOK_PREFAB)
            set(_bytehook_prefab "${BYTEHOOK_PREFAB}/modules/bytehook")
            set(_bytehook_lib_dir "${_bytehook_prefab}/libs/android.${ANDROID_ABI}")
            # bytehook 的 prefab 上游只给 libbytehook.so（shared），个别版本给 .a；两种都认。
            if(EXISTS "${_bytehook_lib_dir}/libbytehook.so")
                add_library(bytehook::bytehook SHARED IMPORTED)
                set_target_properties(bytehook::bytehook PROPERTIES
                    IMPORTED_LOCATION "${_bytehook_lib_dir}/libbytehook.so"
                    INTERFACE_INCLUDE_DIRECTORIES "${_bytehook_prefab}/include")
            elseif(EXISTS "${_bytehook_lib_dir}/libbytehook.a")
                add_library(bytehook::bytehook STATIC IMPORTED)
                set_target_properties(bytehook::bytehook PROPERTIES
                    IMPORTED_LOCATION "${_bytehook_lib_dir}/libbytehook.a"
                    INTERFACE_INCLUDE_DIRECTORIES "${_bytehook_prefab}/include")
            else()
                message(FATAL_ERROR "bytehook native lib not found under ${_bytehook_lib_dir}")
            endif()
        else()
            message(FATAL_ERROR "bytehook prefab not found: enable Prefab in Gradle or pass -DBYTEHOOK_PREFAB=<prefab dir>")
        endif()
    endif()
    add_library(guard_hook SHARED guard/guard_hook.cpp guard/guard_rules.cpp)
    target_include_directories(guard_hook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/guard)
    target_compile_features(guard_hook PRIVATE cxx_std_17)
    target_link_libraries(guard_hook PRIVATE bytehook::bytehook log android)
    target_compile_options(guard_hook PRIVATE -Wall)
endif()
