// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#ifndef CEF_LIBCEF_BROWSER_ANDROID_ADBLOCK_FILTER_H_
#define CEF_LIBCEF_BROWSER_ANDROID_ADBLOCK_FILTER_H_

#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "base/functional/function_ref.h"
#include "cef/include/internal/cef_types.h"
#include "url/gurl.h"

// Network-level filter for Adblock Plus / EasyList rules.
//
// Supported: `||host^`, `||host/path` (path prefix), `@@` exceptions of both,
// `@@||host^$document` (no blocking on that site), and the options
// third-party, domain=, and the resource types script, image, stylesheet,
// object, xmlhttprequest, subdocument, ping, media, font, other (and their ~
// negations). A rule with any other option is skipped rather than applied to
// every request. Cosmetic (##) rules are ignored: this is network only.
//
// A filter is immutable once built. Loading builds a new one and publishes it
// (see Current/Load below), so the IO thread never reads one being written.
class AdBlockFilter {
 public:
  AdBlockFilter();
  AdBlockFilter(const AdBlockFilter&);
  ~AdBlockFilter();

  // Replaces the rules of list `list` with those parsed from EasyList-format
  // `content`. Returns the rules the list now has.
  int SetList(const std::string& list, const std::string& content);

  // Best-effort heuristics for YouTube's ad requests. Not part of any list,
  // so they apply only when an app asks for them (LoadYouTubeAdRules).
  void AddYouTubeAdRules();

  // Whether `url`, requested by the document at `document` as `type`, is
  // blocked. Main-frame navigations never are: a filter list must not stop
  // the user opening a page.
  bool ShouldBlock(const GURL& url,
                   const GURL& document,
                   cef_resource_type_t type) const;

  size_t rule_count() const { return block_.count + allow_.count; }

  // The filter in use, or null before any list was loaded. Thread-safe.
  static std::shared_ptr<const AdBlockFilter> Current();

  // Sets list `list` (a path or URL naming it) in the filter in use and
  // publishes the result, so loading a list again replaces it instead of
  // adding its rules twice. Thread-safe; loads are serialized. Returns the
  // list's rule count.
  static int Load(const std::string& list, const std::string& content);

  // Adds the YouTube heuristics to the filter in use. Returns rules added.
  static int LoadYouTubeAdRules();

 private:
  struct Rule {
    std::string host;         // matches the host and its subdomains
    std::string path_prefix;  // empty = any path
    std::string substring;    // with `host`: a URL substring on that host
    int third_party = 0;      // 1 = only third-party, -1 = only first-party
    uint32_t types = 0;       // resource-type mask; 0 = any
    std::vector<std::string> on_domains;      // domain=a.com
    std::vector<std::string> not_on_domains;  // domain=~b.com
    bool document = false;    // @@...$document: no blocking on that site
    int list = 0;             // which loaded list the rule came from
  };

  // Rules indexed by the host they name, looked up by walking the request
  // host's suffixes: EasyList has tens of thousands of rules, and every
  // request is checked.
  struct RuleSet {
    std::unordered_map<std::string, std::vector<Rule>> by_host;
    size_t count = 0;
    void Add(Rule rule);
    void RemoveList(int list);
    bool Any(const GURL& url,
             const GURL& document,
             uint32_t type_bit,
             bool third_party,
             bool document_rules) const;
  };

  // Copies the filter in use, applies `change`, publishes the copy.
  static int Update(base::FunctionRef<int(AdBlockFilter&)> change);

  bool ParseRule(const std::string& line, int list);
  static bool Matches(const Rule& rule,
                      const GURL& url,
                      const GURL& document,
                      uint32_t type_bit,
                      bool third_party);

  RuleSet block_;
  RuleSet allow_;
  bool has_youtube_rules_ = false;
  std::unordered_map<std::string, int> lists_;
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_ADBLOCK_FILTER_H_
