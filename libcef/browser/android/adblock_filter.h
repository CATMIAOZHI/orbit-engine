// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_ADBLOCK_FILTER_H_
#define CEF_LIBCEF_BROWSER_ANDROID_ADBLOCK_FILTER_H_

#include <string>
#include <unordered_set>
#include <vector>

// EasyList/ABP filter engine for network-level ad blocking.
// Parses ||domain^ and ||domain/path filter rules.
// Used by CefRequestHandler::OnBeforeResourceLoad.
class AdBlockFilter {
 public:
  AdBlockFilter();
  ~AdBlockFilter();

  // Load filters from an EasyList-format file.
  // Returns number of rules loaded.
  int LoadFromFile(const std::string& path);

  // Load filters from string content (EasyList format).
  int LoadFromString(const std::string& content);

  // Check if a URL should be blocked.
  bool ShouldBlock(const std::string& url) const;

  // Number of loaded rules.
  int domain_rule_count() const { return blocked_domains_.size(); }
  int pattern_rule_count() const { return blocked_patterns_.size(); }

  // Add built-in YouTube ad blocking rules.
  void AddYouTubeAdRules();

 private:
  // Parse a single ABP filter rule. Returns true if the rule was added.
  bool ParseRule(const std::string& line);

  // Extract domain from URL.
  static std::string ExtractDomain(const std::string& url);

  // Pure domain blocks: ||domain^
  std::unordered_set<std::string> blocked_domains_;

  // Path pattern blocks: ||domain/path
  struct PathPattern {
    std::string domain;  // domain to match (or empty for any)
    std::string path;    // path prefix to match
  };
  std::vector<PathPattern> blocked_patterns_;

  // YouTube ad URL parameter patterns.
  // Matches URLs containing these substrings (e.g., &ctier=L, &oad=).
  std::vector<std::string> url_substring_blocks_;

};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_ADBLOCK_FILTER_H_
