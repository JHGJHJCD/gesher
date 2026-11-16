// gesher-base: fnmatch (<fnmatch.h>), POSIX shell patterns: '*', '?',
// bracket expressions ("[a-z]", "[!...]" or "[^...]", and the character
// classes "[:alpha:]" and friends), backslash escapes, and the GNU flags bionic
// supports (FNM_LEADING_DIR, FNM_CASEFOLD). Matching is on bytes, as in the
// C locale.
#include <ctype.h>
#include <fnmatch.h>
#include <string.h>

namespace {

int Fold(int c, int flags) {
  return (flags & FNM_CASEFOLD) != 0 ? tolower(c) : c;
}

bool InClass(const char* name, size_t len, int c) {
  struct Class {
    const char* name;
    int (*test)(int);
  };
  static const Class kClasses[] = {
      {"alnum", isalnum}, {"alpha", isalpha}, {"blank", isblank}, {"cntrl", iscntrl},
      {"digit", isdigit}, {"graph", isgraph}, {"lower", islower}, {"print", isprint},
      {"punct", ispunct}, {"space", isspace}, {"upper", isupper}, {"xdigit", isxdigit},
  };
  for (const Class& k : kClasses) {
    if (strlen(k.name) == len && memcmp(k.name, name, len) == 0) return k.test(c) != 0;
  }
  return false;
}

// Matches c against the bracket expression at *pattern (just past '[').
// Returns 1 on a match, 0 on none, -1 if the expression is not closed (the
// '[' is then an ordinary character). Advances *pattern past the ']'.
int MatchBracket(const char** pattern, int c, int flags) {
  const char* p = *pattern;
  bool negate = false;
  if (*p == '!' || *p == '^') {
    negate = true;
    ++p;
  }
  bool matched = false;
  bool first = true;
  int fc = Fold(c, flags);
  while (first || *p != ']') {
    first = false;
    if (*p == '\0') return -1;
    if (*p == '[' && p[1] == ':') {
      const char* end = strstr(p + 2, ":]");
      if (end != nullptr) {
        if (InClass(p + 2, static_cast<size_t>(end - (p + 2)), c)) matched = true;
        p = end + 2;
        continue;
      }
    }
    int lo;
    if (*p == '\\' && (flags & FNM_NOESCAPE) == 0 && p[1] != '\0') ++p;
    lo = static_cast<unsigned char>(*p++);
    if ((flags & FNM_PATHNAME) != 0 && lo == '/') return 0;
    int hi = lo;
    if (*p == '-' && p[1] != ']' && p[1] != '\0') {
      ++p;
      if (*p == '\\' && (flags & FNM_NOESCAPE) == 0 && p[1] != '\0') ++p;
      hi = static_cast<unsigned char>(*p++);
    }
    if (lo <= c && c <= hi) matched = true;
    if ((flags & FNM_CASEFOLD) != 0 && Fold(lo, flags) <= fc && fc <= Fold(hi, flags)) {
      matched = true;
    }
  }
  *pattern = p + 1;
  return matched != negate ? 1 : 0;
}

bool LeadingPeriod(const char* s, const char* start, int flags) {
  if (*s != '.' || (flags & FNM_PERIOD) == 0) return false;
  return s == start || ((flags & FNM_PATHNAME) != 0 && s[-1] == '/');
}

int Match(const char* p, const char* s, const char* start, int flags) {
  // A later star can absorb any extra suffix bytes that earlier stars could
  // have consumed. Retry only this most recent star, advancing one string
  // byte each time. This avoids an exponential suffix tree and uses constant
  // auxiliary storage, without a recursive pattern-depth limit.
  const char* retry_pattern = nullptr;
  const char* retry_string = nullptr;
  for (;;) {
    int pc = static_cast<unsigned char>(*p++);
    bool matched = true;
    switch (pc) {
      case '\0':
        if (*s == '\0') return 0;
        if ((flags & FNM_LEADING_DIR) != 0 && *s == '/') return 0;
        matched = false;
        break;
      case '?':
        matched = *s != '\0' && !(*s == '/' && (flags & FNM_PATHNAME) != 0) &&
                  !LeadingPeriod(s, start, flags);
        if (matched) ++s;
        break;
      case '*': {
        while (*p == '*') ++p;
        if (LeadingPeriod(s, start, flags)) {
          matched = false;
          break;
        }
        if (*p == '\0') {
          if ((flags & FNM_PATHNAME) == 0 || (flags & FNM_LEADING_DIR) != 0) return 0;
          return strchr(s, '/') == nullptr ? 0 : FNM_NOMATCH;
        }
        retry_pattern = p;
        retry_string = s;
        break;
      }
      case '[': {
        if (*s == '\0' || (*s == '/' && (flags & FNM_PATHNAME) != 0) ||
            LeadingPeriod(s, start, flags)) {
          matched = false;
          break;
        }
        const char* q = p;
        int r = MatchBracket(&q, static_cast<unsigned char>(*s), flags);
        if (r < 0) {
          // An unclosed '[' matches itself.
          matched = *s == '[';
        } else if (r == 0) {
          matched = false;
        } else {
          p = q;
        }
        if (matched) ++s;
        break;
      }
      case '\\':
        if ((flags & FNM_NOESCAPE) == 0) {
          pc = static_cast<unsigned char>(*p++);
          if (pc == '\0') {
            pc = '\\';
            --p;
          }
        }
        [[fallthrough]];
      default:
        matched = Fold(pc, flags) == Fold(static_cast<unsigned char>(*s), flags);
        if (matched) ++s;
        break;
    }
    if (matched) continue;
    if (retry_pattern == nullptr || *retry_string == '\0' ||
        (*retry_string == '/' && (flags & FNM_PATHNAME) != 0)) return FNM_NOMATCH;
    s = ++retry_string;
    p = retry_pattern;
  }
}

}  // namespace

extern "C" int fnmatch(const char* pattern, const char* string, int flags) {
  return Match(pattern, string, string, flags);
}
