// Host-side acceptance for module 3's redirect rules (runs on the Linux sandbox, no Android .so).
#include "guard_rules.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

using taixu::guard::RuleTable;

static int failures = 0;
#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);   \
            ++failures;                                                   \
        }                                                                 \
    } while (0)

int main() {
    const std::string manifest =
        "# path_prefix\tasset_name\n"
        "/data/local/tmp/secrets/\tsecret_copy.bin\n"
        "/data/local/tmp/secrets/deep/\tdeep_copy.bin\n"
        "/sdcard/Download/protected.conf\tprotected.conf\n";

    RuleTable table;
    CHECK(table.load_from_manifest(manifest) == 3);
    CHECK(table.size() == 3);

    std::string redirect;

    // 1. hit -> fd is redirected to the pre-seeded safe copy
    CHECK(table.redirect_for("/data/local/tmp/secrets/a.txt", "/files/safe", &redirect));
    CHECK(redirect == "/files/safe/secret_copy.bin");

    // 2. longest prefix wins
    redirect.clear();
    CHECK(table.redirect_for("/data/local/tmp/secrets/deep/a.txt", "/files/safe", &redirect));
    CHECK(redirect == "/files/safe/deep_copy.bin");

    // 3. exact-path rule
    redirect.clear();
    CHECK(table.redirect_for("/sdcard/Download/protected.conf", "/files/safe", &redirect));
    CHECK(redirect == "/files/safe/protected.conf");

    // 4. miss -> behaviour identical to un-hooked open (no redirect at all)
    redirect.clear();
    CHECK(!table.redirect_for("/data/local/tmp/other/a.txt", "/files/safe", &redirect));
    CHECK(redirect.empty());
    CHECK(!table.redirect_for("/data/local/tmp/secretsX/a.txt", "/files/safe", &redirect));
    CHECK(!table.redirect_for(nullptr, "/files/safe", &redirect));

    // 5. rule hit but safe copy missing -> transparent passthrough (un-hooked behaviour)
    CHECK(table.redirect_for("/data/local/tmp/secrets/a.txt", "/files/safe", &redirect));
    CHECK(!table.redirect_for_existing("/data/local/tmp/secrets/a.txt", "/files/safe", &redirect));

    // 6. rule hit with the safe copy present -> redirect happens and is observable via open(2)
    const std::string safe_root = "/tmp/taixu-guard-test";
    ::mkdir(safe_root.c_str(), 0700);
    const std::string safe_copy = safe_root + "/secret_copy.bin";
    { std::ofstream out(safe_copy); out << "safe-copy"; }
    CHECK(table.redirect_for_existing("/data/local/tmp/secrets/a.txt", safe_root, &redirect));
    CHECK(redirect == safe_copy);
    FILE *fp = std::fopen(redirect.c_str(), "rb");
    CHECK(fp != nullptr);
    if (fp != nullptr) {
        char buf[16] = {0};
        size_t n = std::fread(buf, 1, sizeof(buf) - 1, fp);
        std::fclose(fp);
        CHECK(std::string(buf, n) == "safe-copy");
    }
    ::unlink(safe_copy.c_str());
    ::rmdir(safe_root.c_str());

    if (failures == 0) {
        std::printf("PASS: guard rule engine — hit redirects, miss passes through unchanged\n");
        return 0;
    }
    std::printf("FAILED: %d assertion(s)\n", failures);
    return 1;
}
