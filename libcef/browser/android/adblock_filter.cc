// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

#include "cef/libcef/browser/android/adblock_filter.h"

#include <iterator>
#include <sstream>

#include "base/functional/function_ref.h"
#include "base/logging.h"
#include "base/no_destructor.h"
#include "base/strings/string_split.h"
#include "base/strings/string_util.h"
#include "base/synchronization/lock.h"
#include "net/base/registry_controlled_domains/registry_controlled_domain.h"

namespace {

uint32_t Bit(cef_resource_type_t type) {
  return 1u << type;
}

// ABP resource-type option -> the CEF resource types it covers. 0 = unknown.
uint32_t TypeBits(const std::string& option) {
  if (option == "script") {
    return Bit(RT_SCRIPT);
  }
  if (option == "image") {
    return Bit(RT_IMAGE) | Bit(RT_FAVICON);
  }
  if (option == "stylesheet") {
    return Bit(RT_STYLESHEET);
  }
  if (option == "object") {
    return Bit(RT_OBJECT) | Bit(RT_PLUGIN_RESOURCE);
  }
  if (option == "xmlhttprequest") {
    return Bit(RT_XHR);
  }
  if (option == "subdocument") {
    return Bit(RT_SUB_FRAME);
  }
  if (option == "ping") {
    return Bit(RT_PING) | Bit(RT_CSP_REPORT);
  }
  if (option == "media") {
    return Bit(RT_MEDIA);
  }
  if (option == "font") {
    return Bit(RT_FONT_RESOURCE);
  }
  if (option == "other") {
    return Bit(RT_SUB_RESOURCE) | Bit(RT_PREFETCH) | Bit(RT_WORKER) |
           Bit(RT_SHARED_WORKER) | Bit(RT_SERVICE_WORKER);
  }
  return 0;
}

constexpr uint32_t kAllTypes = (1u << RT_NUM_VALUES) - 1;
// $popup: a window, not a resource. Outside kAllTypes, so a rule without the
// option never matches a window and a $popup rule never matches a resource.
constexpr uint32_t kPopupBit = 1u << 31;
static_assert(RT_NUM_VALUES < 31, "resource types collide with kPopupBit");

// host == domain, or host is a subdomain of it.
bool OnDomain(const std::string& host, const std::string& domain) {
  return host == domain ||
         (host.size() > domain.size() && base::EndsWith(host, domain) &&
          host[host.size() - domain.size() - 1] == '.');
}

base::Lock& FilterLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}

base::Lock& LoadLock() {
  static base::NoDestructor<base::Lock> lock;
  return *lock;
}

std::shared_ptr<const AdBlockFilter>& FilterInUse() {
  static base::NoDestructor<std::shared_ptr<const AdBlockFilter>> filter;
  return *filter;
}

}  // namespace

AdBlockFilter::AdBlockFilter() = default;
AdBlockFilter::AdBlockFilter(const AdBlockFilter&) = default;
AdBlockFilter::~AdBlockFilter() = default;

void AdBlockFilter::RuleSet::RemoveList(int list) {
  for (auto it = by_host.begin(); it != by_host.end();) {
    count -= std::erase_if(it->second,
                           [list](const Rule& r) { return r.list == list; });
    it = it->second.empty() ? by_host.erase(it) : std::next(it);
  }
}

void AdBlockFilter::RuleSet::Add(Rule rule) {
  count++;
  std::string host = rule.host;
  by_host[host].push_back(std::move(rule));
}

bool AdBlockFilter::RuleSet::Any(const GURL& url,
                                 const GURL& document,
                                 uint32_t type_bit,
                                 bool third_party,
                                 bool document_rules) const {
  // Document rules name the site the request comes from, not its target.
  std::string host(document_rules ? document.host() : url.host());
  while (!host.empty()) {
    auto it = by_host.find(host);
    if (it != by_host.end()) {
      for (const Rule& rule : it->second) {
        if (rule.document == document_rules &&
            Matches(rule, url, document, type_bit, third_party)) {
          return true;
        }
      }
    }
    size_t dot = host.find('.');
    if (dot == std::string::npos) {
      break;
    }
    host.erase(0, dot + 1);
  }
  return false;
}

int AdBlockFilter::SetList(const std::string& list, const std::string& content) {
  auto [it, added] = lists_.emplace(list, static_cast<int>(lists_.size()) + 1);
  const int id = it->second;
  if (!added) {
    block_.RemoveList(id);
    allow_.RemoveList(id);
  }
  int count = 0;
  std::istringstream stream(content);
  std::string line;
  while (std::getline(stream, line)) {
    if (!line.empty() && line.back() == '\r') {
      line.pop_back();
    }
    if (ParseRule(line, id)) {
      count++;
    }
  }
  return count;
}

bool AdBlockFilter::ParseRule(const std::string& raw, int list) {
  std::string line(base::TrimWhitespaceASCII(raw, base::TRIM_ALL));
  if (line.empty() || line[0] == '!' || line[0] == '[') {
    return false;
  }
  // Cosmetic rules and their exceptions.
  if (line.find("##") != std::string::npos ||
      line.find("#@#") != std::string::npos ||
      line.find("#?#") != std::string::npos ||
      line.find("#$#") != std::string::npos) {
    return false;
  }

  Rule rule;
  rule.list = list;
  bool exception = false;
  if (base::StartsWith(line, "@@")) {
    exception = true;
    line.erase(0, 2);
  }
  // Only host-anchored rules: ||host^ and ||host/path.
  if (!base::StartsWith(line, "||")) {
    return false;
  }
  line.erase(0, 2);

  size_t dollar = line.find('$');
  if (dollar != std::string::npos) {
    uint32_t include = 0;
    uint32_t exclude = 0;
    for (const std::string& option : base::SplitString(
             line.substr(dollar + 1), ",", base::TRIM_WHITESPACE,
             base::SPLIT_WANT_NONEMPTY)) {
      bool negated = base::StartsWith(option, "~");
      std::string name = negated ? option.substr(1) : option;
      if (name == "third-party") {
        rule.third_party = negated ? -1 : 1;
      } else if (base::StartsWith(name, "domain=") && !negated) {
        for (const std::string& d :
             base::SplitString(name.substr(7), "|", base::TRIM_WHITESPACE,
                               base::SPLIT_WANT_NONEMPTY)) {
          if (base::StartsWith(d, "~")) {
            rule.not_on_domains.push_back(d.substr(1));
          } else {
            rule.on_domains.push_back(d);
          }
        }
      } else if (name == "document" && exception && !negated) {
        rule.document = true;
      } else if (name == "match-case") {
        // Hosts are case-insensitive and paths are compared as written.
      } else if (uint32_t bits = TypeBits(name)) {
        (negated ? exclude : include) |= bits;
      } else if (name == "popup" && !negated) {
        include |= kPopupBit;
      } else {
        // popup, csp, redirect, removeparam, ...: not ours to apply.
        return false;
      }
    }
    if (include || exclude) {
      rule.types = (include ? include : kAllTypes) & ~exclude;
      if (!rule.types) {
        return false;
      }
    }
    line.erase(dollar);
  }

  if (!line.empty() && line.back() == '^') {
    line.pop_back();
  }
  size_t slash = line.find('/');
  rule.host = base::ToLowerASCII(line.substr(0, slash));
  if (slash != std::string::npos) {
    rule.path_prefix = line.substr(slash);
  }
  // Wildcards, ports and separators inside the rule are not handled.
  if (rule.host.empty() || rule.host.find_first_of("*^:|") != std::string::npos ||
      rule.path_prefix.find_first_of("*^|") != std::string::npos) {
    return false;
  }
  if (rule.document && !rule.path_prefix.empty()) {
    return false;
  }

  (exception ? allow_ : block_).Add(std::move(rule));
  return true;
}

// static
bool AdBlockFilter::Matches(const Rule& rule,
                            const GURL& url,
                            const GURL& document,
                            uint32_t type_bit,
                            bool third_party) {
  if (rule.document) {
    return true;
  }
  if (type_bit == kPopupBit ? !(rule.types & kPopupBit)
                             : (rule.types && !(rule.types & type_bit))) {
    return false;
  }
  if ((rule.third_party == 1 && !third_party) ||
      (rule.third_party == -1 && third_party)) {
    return false;
  }
  if (!rule.path_prefix.empty() &&
      !base::StartsWith(url.PathForRequestPiece(), rule.path_prefix)) {
    return false;
  }
  if (!rule.substring.empty() &&
      url.spec().find(rule.substring) == std::string::npos) {
    return false;
  }
  if (!rule.on_domains.empty() || !rule.not_on_domains.empty()) {
    std::string site(document.host());
    for (const std::string& d : rule.not_on_domains) {
      if (OnDomain(site, d)) {
        return false;
      }
    }
    if (!rule.on_domains.empty()) {
      bool on = false;
      for (const std::string& d : rule.on_domains) {
        on = on || OnDomain(site, d);
      }
      if (!on) {
        return false;
      }
    }
  }
  return true;
}

bool AdBlockFilter::ShouldBlock(const GURL& url,
                                const GURL& document,
                                cef_resource_type_t type) const {
  if (type == RT_MAIN_FRAME || type == RT_NAVIGATION_PRELOAD_MAIN_FRAME ||
      !url.SchemeIsHTTPOrHTTPS()) {
    return false;
  }
  const uint32_t type_bit = Bit(type);
  const bool third_party =
      !document.is_valid() ||
      !net::registry_controlled_domains::SameDomainOrHost(
          url, document,
          net::registry_controlled_domains::INCLUDE_PRIVATE_REGISTRIES);
  if (!block_.Any(url, document, type_bit, third_party, false)) {
    return false;
  }
  if (document.is_valid() &&
      allow_.Any(url, document, type_bit, third_party, true)) {
    return false;
  }
  return !allow_.Any(url, document, type_bit, third_party, false);
}

bool AdBlockFilter::ShouldBlockPopup(const GURL& url,
                                     const GURL& opener) const {
  if (!url.SchemeIsHTTPOrHTTPS()) {
    return false;
  }
  const bool third_party =
      !opener.is_valid() ||
      !net::registry_controlled_domains::SameDomainOrHost(
          url, opener,
          net::registry_controlled_domains::INCLUDE_PRIVATE_REGISTRIES);
  if (!block_.Any(url, opener, kPopupBit, third_party, false)) {
    return false;
  }
  if (opener.is_valid() &&
      allow_.Any(url, opener, kPopupBit, third_party, true)) {
    return false;
  }
  return !allow_.Any(url, opener, kPopupBit, third_party, false);
}

void AdBlockFilter::AddYouTubeAdRules() {
  const char* const kPaths[][2] = {
      {"youtube.com", "/pagead/"},
      {"youtube.com", "/ptracking"},
      {"youtube.com", "/api/stats/ads"},
      {"youtube.com", "/api/stats/atr"},
      {"youtube.com", "/get_midroll_"},
      {"youtube.com", "/youtubei/v1/player/ad_break"},
      {"youtube.com", "/youtubei/v1/log_event"},
      {"doubleclick.net", "/pagead/"},
      {"googlesyndication.com", "/pagead/"},
      {"google.com", "/pagead/"},
  };
  for (const auto& p : kPaths) {
    Rule rule;
    rule.host = p[0];
    rule.path_prefix = p[1];
    block_.Add(std::move(rule));
  }
  // Ad video streams, by their URL parameters. Scoped to the hosts that serve
  // them: as bare substrings they matched any site's URLs.
  for (const char* host : {"googlevideo.com", "youtube.com"}) {
    for (const char* s : {"&ctier=L", "&oad=", "&ad_type=", "&adformat="}) {
      Rule rule;
      rule.host = host;
      rule.substring = s;
      block_.Add(std::move(rule));
    }
  }
  for (const char* p : {"/pagead/conversion/", "/pagead/lvz", "/error_204"}) {
    Rule rule;
    rule.host = "youtube.com";
    rule.path_prefix = p;
    block_.Add(std::move(rule));
  }
}

// static
std::shared_ptr<const AdBlockFilter> AdBlockFilter::Current() {
  base::AutoLock lock(FilterLock());
  return FilterInUse();
}

// static
int AdBlockFilter::Update(base::FunctionRef<int(AdBlockFilter&)> change) {
  base::AutoLock load_lock(LoadLock());
  std::shared_ptr<const AdBlockFilter> current = Current();
  auto next = current ? std::make_shared<AdBlockFilter>(*current)
                      : std::make_shared<AdBlockFilter>();
  int added = change(*next);
  {
    base::AutoLock lock(FilterLock());
    FilterInUse() = std::move(next);
  }
  return added;
}

// static
int AdBlockFilter::Load(const std::string& list, const std::string& content) {
  int rules = Update(
      [&](AdBlockFilter& filter) { return filter.SetList(list, content); });
  LOG(INFO) << "AdBlockFilter: " << rules << " rules from " << list;
  return rules;
}

// static
int AdBlockFilter::LoadYouTubeAdRules() {
  return Update([](AdBlockFilter& filter) {
    if (filter.has_youtube_rules_) {
      return 0;
    }
    size_t before = filter.rule_count();
    filter.AddYouTubeAdRules();
    filter.has_youtube_rules_ = true;
    return static_cast<int>(filter.rule_count() - before);
  });
}
