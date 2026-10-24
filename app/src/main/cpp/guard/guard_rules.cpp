#include "guard_rules.h"

#include <algorithm>
#include <cstdio>
#include <sys/stat.h>

namespace taixu::guard {

void RuleTable::add(const std::string &path_prefix, const std::string &asset_name) {
    if (path_prefix.empty() || asset_name.empty()) return;
    rules_.push_back(Rule{path_prefix, asset_name});
    std::sort(rules_.begin(), rules_.end(), [](const Rule &a, const Rule &b) {
        return a.path_prefix.size() > b.path_prefix.size();
    });
}

size_t RuleTable::load_from_manifest(const std::string &manifest) {
    size_t loaded = 0;
    size_t pos = 0;
    while (pos < manifest.size()) {
        size_t end = manifest.find('\n', pos);
        if (end == std::string::npos) end = manifest.size();
        std::string line = manifest.substr(pos, end - pos);
        pos = end + 1;
        if (line.empty() || line[0] == '#') continue;
        const size_t tab = line.find('\t');
        if (tab == std::string::npos) continue;
        add(line.substr(0, tab), line.substr(tab + 1));
        ++loaded;
    }
    return loaded;
}

bool RuleTable::redirect_for(const char *path, const std::string &safe_root, std::string *redirect) const {
    if (path == nullptr) return false;
    const std::string target(path);
    for (const auto &rule : rules_) {
        if (target.size() < rule.path_prefix.size()) continue;
        if (target.compare(0, rule.path_prefix.size(), rule.path_prefix) != 0) continue;
        if (redirect != nullptr) {
            *redirect = safe_root;
            if (!redirect->empty() && redirect->back() != '/') redirect->push_back('/');
            redirect->append(rule.asset_name);
        }
        return true;
    }
    return false;
}

bool RuleTable::redirect_for_existing(const char *path, const std::string &safe_root, std::string *redirect) const {
    std::string candidate;
    if (!redirect_for(path, safe_root, &candidate)) return false;
    struct stat st {};
    if (::stat(candidate.c_str(), &st) != 0) return false;  // no safe copy -> transparent passthrough
    if (redirect != nullptr) *redirect = candidate;
    return true;
}

}  // namespace taixu::guard
