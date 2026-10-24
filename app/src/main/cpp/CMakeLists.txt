cmake_minimum_required(VERSION 3.22.1)
project(taixu_native C CXX)

add_library(pty_native SHARED pty_native.c)

target_compile_options(pty_native PRIVATE -Wall -Werror=implicit-function-declaration)

# Module 3: PLT-level file guard. ByteHook (Prefab) is used because it rewrites the GOT (PLT hook);
# ShadowHook is inline hooking and is deliberately NOT used here.
option(TAIXU_GUARD_HOOK "Build libguard_hook.so (ByteHook PLT file guard)" OFF)

if(TAIXU_GUARD_HOOK)
    find_package(bytehook REQUIRED CONFIG)
    add_library(guard_hook SHARED guard/guard_hook.cpp guard/guard_rules.cpp)
    target_include_directories(guard_hook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/guard)
    target_compile_features(guard_hook PRIVATE cxx_std_17)
    target_link_libraries(guard_hook PRIVATE bytehook::bytehook log android)
    target_compile_options(guard_hook PRIVATE -Wall)
endif()
