// Path redirect rule engine for libguard_hook.so (module 3).
// Pure C++ (no Android/bionic dependency) so it can be exercised by a host-side test.
#ifndef TAIXU_GUARD_RULES_H
#define TAIXU_GUARD_RULES_H

#include <string>
#include <vector>

namespace taixu::guard {

struct Rule {
    std::string path_prefix;   // absolute prefix, e.g. /data/local/tmp/secrets/
    std::string asset_name;    // pre-seeded copy under the app's safe root
};

// Longest-prefix-wins matcher. Rules are loaded from a line-based manifest:
//   <path_prefix><TAB><asset_name>
class RuleTable {
public:
    void add(const std::string &path_prefix, const std::string &asset_name);
    size_t load_from_manifest(const std::string &manifest);
    size_t size() const { return rules_.size(); }

    // Returns true and fills `redirect` (safe_root/asset_name) when `path` hits a rule.
    bool redirect_for(const char *path, const std::string &safe_root, std::string *redirect) const;

    // A hit only redirects when the safe copy actually exists; otherwise behaviour is unchanged.
    bool redirect_for_existing(const char *path, const std::string &safe_root, std::string *redirect) const;

private:
    std::vector<Rule> rules_;  // kept sorted by prefix length (desc) for longest-match-wins
};

}  // namespace taixu::guard
#endif  // TAIXU_GUARD_RULES_H
