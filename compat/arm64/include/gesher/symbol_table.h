// Owned symbol names with allocation-free C-string queries. Shared by the
// host shim, GL thunk registry and the standalone ARM64 guest overrides.
#pragma once

#include <string>
#include <string_view>
#include <unordered_map>

namespace gesher {

struct SymbolNameHash {
  using is_transparent = void;
  size_t operator()(std::string_view name) const noexcept {
    return std::hash<std::string_view>{}(name);
  }
};

using SymbolTable = std::unordered_map<std::string, void*, SymbolNameHash, std::equal_to<>>;

}  // namespace gesher
