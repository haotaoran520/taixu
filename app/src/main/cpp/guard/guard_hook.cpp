// libguard_hook.so — PLT-level file-open interception for the TaiXu process only.
//
// Library choice: ByteHook (PLT hook via GOT rewrite). ShadowHook is an INLINE hook and is the
// wrong primitive here: inline patching of libc open/openat/fopen is fragile across bionic
// versions and trips integrity checks, while PLT hooking only rewrites the calling module's GOT.
//
// Scope: hooks are installed with `bytehook_hook_all` for the TaiXu package only; nothing is
// injected into other apps.
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <string>

#include "bytehook.h"
#include "guard_rules.h"

#define LOG_TAG "TaiXuGuard"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

taixu::guard::RuleTable g_rules;
std::string g_safe_root;
bool g_installed = false;

int (*g_orig_open)(const char *, int, ...) = nullptr;
int (*g_orig_openat)(int, const char *, int, ...) = nullptr;
FILE *(*g_orig_fopen)(const char *, const char *) = nullptr;

// Returns the redirected path when (and only when) the rule hits and the safe copy exists.
const char *resolve(const char *path, std::string *storage) {
    if (path == nullptr || g_rules.size() == 0) return path;
    if (!g_rules.redirect_for_existing(path, g_safe_root, storage)) return path;
    LOGI("redirect %s -> %s", path, storage->c_str());
    return storage->c_str();
}

int proxy_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    va_list args;
    va_start(args, flags);
    if (flags & O_CREAT) mode = static_cast<mode_t>(va_arg(args, int));
    va_end(args);
    std::string redirected;
    const char *final_path = resolve(path, &redirected);
    return g_orig_open(final_path, flags, mode);
}

int proxy_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    va_list args;
    va_start(args, flags);
    if (flags & O_CREAT) mode = static_cast<mode_t>(va_arg(args, int));
    va_end(args);
    std::string redirected;
    const char *final_path = resolve(path, &redirected);
    return g_orig_openat(dirfd, final_path, flags, mode);
}

FILE *proxy_fopen(const char *path, const char *mode) {
    std::string redirected;
    const char *final_path = resolve(path, &redirected);
    return g_orig_fopen(final_path, mode);
}

bool install_hooks() {
    if (g_installed) return true;
    g_orig_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_DEFAULT, "open"));
    g_orig_openat = reinterpret_cast<int (*)(int, const char *, int, ...)>(dlsym(RTLD_DEFAULT, "openat"));
    g_orig_fopen = reinterpret_cast<FILE *(*)(const char *, const char *)>(dlsym(RTLD_DEFAULT, "fopen"));
    if (g_orig_open == nullptr || g_orig_fopen == nullptr) {
        LOGE("dlsym failed; hooks not installed");
        return false;
    }
    bytehook_stub_t a = bytehook_hook_all(nullptr, "open", reinterpret_cast<void *>(proxy_open), nullptr, nullptr);
    bytehook_stub_t b = bytehook_hook_all(nullptr, "openat", reinterpret_cast<void *>(proxy_openat), nullptr, nullptr);
    bytehook_stub_t c = bytehook_hook_all(nullptr, "fopen", reinterpret_cast<void *>(proxy_fopen), nullptr, nullptr);
    g_installed = (a != nullptr) && (c != nullptr);
    LOGI("bytehook open=%p openat=%p fopen=%p", a, b, c);
    return g_installed;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) vm;
    (void) reserved;
    int status = bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);
    LOGI("JNI_OnLoad bytehook_init=%d", status);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_top_wkbin_taixu_core_guard_GuardHook_nativeInit(JNIEnv *env, jobject thiz, jstring manifest, jstring safeRoot) {
    (void) thiz;
    const char *m = env->GetStringUTFChars(manifest, nullptr);
    const char *r = env->GetStringUTFChars(safeRoot, nullptr);
    const size_t count = g_rules.load_from_manifest(m == nullptr ? "" : m);
    g_safe_root.assign(r == nullptr ? "" : r);
    if (m != nullptr) env->ReleaseStringUTFChars(manifest, m);
    if (r != nullptr) env->ReleaseStringUTFChars(safeRoot, r);
    LOGI("rules=%zu safeRoot=%s installed=%d", count, g_safe_root.c_str(), install_hooks() ? 1 : 0);
    return static_cast<jint>(count);
}
