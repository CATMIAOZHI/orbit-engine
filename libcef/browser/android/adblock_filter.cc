// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/adblock_filter.h"

#include <fstream>
#include <iterator>
#include <sstream>

#include "base/logging.h"

AdBlockFilter::AdBlockFilter() = default;
AdBlockFilter::~AdBlockFilter() = default;

int AdBlockFilter::LoadFromFile(const std::string& path) {
  std::ifstream file(path);
  if (!file.is_open()) {
    LOG(WARNING) << "AdBlockFilter: cannot open " << path;
    return 0;
  }
  std::string content((std::istreambuf_iterator<char>(file)),
                       std::istreambuf_iterator<char>());
  return LoadFromString(content);
}

int AdBlockFilter::LoadFromString(const std::string& content) {
  int count = 0;
  std::istringstream stream(content);
  std::string line;
  while (std::getline(stream, line)) {
    // Trim \r
    if (!line.empty() && line.back() == '\r')
      line.pop_back();
    if (ParseRule(line))
      count++;
  }
  LOG(INFO) << "AdBlockFilter: loaded " << count << " rules ("
            << blocked_domains_.size() << " domains, "
            << blocked_patterns_.size() << " patterns)";
  return count;
}

bool AdBlockFilter::ParseRule(const std::string& line) {
  // Skip comments, empty lines, cosmetic rules, exceptions.
  if (line.empty() || line[0] == '!' || line[0] == '[')
    return false;
  if (line.find("##") != std::string::npos)
    return false;  // Cosmetic filter
  if (line.find("#@#") != std::string::npos)
    return false;  // Cosmetic exception
  if (line.substr(0, 2) == "@@")
    return false;  // Exception rule (allowlist)

  // We only handle ||domain^ and ||domain/path rules.
  if (line.substr(0, 2) != "||")
    return false;

  // Strip the || prefix.
  std::string rule = line.substr(2);

  // Strip options after $ (e.g., $third-party,domain=...).
  // Keep the rule part before $.
  auto dollar = rule.find('$');
  if (dollar != std::string::npos) {
    // Skip rules with $domain= restrictions (site-specific).
    std::string options = rule.substr(dollar + 1);
    if (options.find("domain=") != std::string::npos)
      return false;
    rule = rule.substr(0, dollar);
  }

  if (rule.empty())
    return false;

  // Pure domain rule: ends with ^ or nothing after domain.
  // e.g., ||doubleclick.net^ or ||ad.example.com^
  if (rule.back() == '^') {
    std::string domain = rule.substr(0, rule.size() - 1);
    // Only add if it looks like a domain (has a dot, no path chars).
    if (domain.find('/') == std::string::npos &&
        domain.find('*') == std::string::npos &&
        domain.find('.') != std::string::npos) {
      blocked_domains_.insert(domain);
      return true;
    }
  }

  // Path pattern rule: ||domain/path or ||domain/path^
  // e.g., ||youtube.com/pagead/ or ||google.com/pagead/conversion_async.js
  auto slash = rule.find('/');
  if (slash != std::string::npos) {
    std::string domain = rule.substr(0, slash);
    std::string path = rule.substr(slash);
    // Remove trailing ^
    if (!path.empty() && path.back() == '^')
      path.pop_back();
    // Skip wildcard domains for now.
    if (domain.find('*') != std::string::npos)
      return false;
    if (!domain.empty() && !path.empty()) {
      blocked_patterns_.push_back({domain, path});
      return true;
    }
  }

  return false;
}

std::string AdBlockFilter::ExtractDomain(const std::string& url) {
  // Find domain from URL: https://sub.domain.com/path → sub.domain.com
  auto proto_end = url.find("://");
  if (proto_end == std::string::npos)
    return "";
  auto domain_start = proto_end + 3;
  auto domain_end = url.find('/', domain_start);
  if (domain_end == std::string::npos)
    domain_end = url.size();
  // Strip port.
  auto port = url.find(':', domain_start);
  if (port != std::string::npos && port < domain_end)
    domain_end = port;
  return url.substr(domain_start, domain_end - domain_start);
}

bool AdBlockFilter::ShouldBlock(const std::string& url) const {
  std::string domain = ExtractDomain(url);
  if (domain.empty())
    return false;

  // 1. Check exact domain match and parent domain match.
  // e.g., for "sub.ad.doubleclick.net", check:
  //   sub.ad.doubleclick.net, ad.doubleclick.net, doubleclick.net
  std::string check = domain;
  while (!check.empty()) {
    if (blocked_domains_.count(check))
      return true;
    auto dot = check.find('.');
    if (dot == std::string::npos)
      break;
    check = check.substr(dot + 1);
  }

  // 2. Check path patterns.
  // Extract path from URL.
  auto proto_end = url.find("://");
  if (proto_end == std::string::npos)
    return false;
  auto path_start = url.find('/', proto_end + 3);
  std::string path = (path_start != std::string::npos)
                         ? url.substr(path_start)
                         : "/";

  for (const auto& pattern : blocked_patterns_) {
    // Check if the URL's domain ends with (or equals) the pattern domain.
    if (domain == pattern.domain ||
        (domain.size() > pattern.domain.size() &&
         domain.substr(domain.size() - pattern.domain.size()) == pattern.domain &&
         domain[domain.size() - pattern.domain.size() - 1] == '.')) {
      // Check if path starts with the pattern path.
      if (path.find(pattern.path) == 0 ||
          path.find(pattern.path) != std::string::npos) {
        return true;
      }
    }
  }

  return false;
}
