// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The User-Agent Client Hints a browser sends with an overridden User-Agent
 * ({@code Sec-CH-UA*} headers and {@code navigator.userAgentData}), as
 * Android WebView's {@code UserAgentMetadata}. See
 * {@link CefriumBrowser#setUserAgentOverride(String, CefriumUserAgentMetadata)}.
 */
@CefriumExperimental
public final class CefriumUserAgentMetadata {
    final boolean engineDefault;
    final List<String> brands;
    final List<String> brandVersions;
    final String fullVersion;
    final String platform;
    final String platformVersion;
    final String architecture;
    final String model;
    final boolean mobile;
    final String bitness;
    final List<String> formFactors;

    private static final CefriumUserAgentMetadata ENGINE =
            new CefriumUserAgentMetadata(true, new Builder());

    private CefriumUserAgentMetadata(boolean engineDefault, Builder b) {
        this.engineDefault = engineDefault;
        brands = Collections.unmodifiableList(new ArrayList<>(b.brands));
        brandVersions = Collections.unmodifiableList(new ArrayList<>(b.brandVersions));
        fullVersion = b.fullVersion;
        platform = b.platform;
        platformVersion = b.platformVersion;
        architecture = b.architecture;
        model = b.model;
        mobile = b.mobile;
        bitness = b.bitness;
        formFactors = Collections.unmodifiableList(new ArrayList<>(b.formFactors));
    }

    /**
     * The hints the engine sends without an override (its brands, platform,
     * mobile bit). For an app that only appends its own token to the
     * User-Agent.
     */
    public static CefriumUserAgentMetadata engineDefault() {
        return ENGINE;
    }

    /** Builds hints of your own. Unset values are sent empty. */
    public static final class Builder {
        private final List<String> brands = new ArrayList<>();
        private final List<String> brandVersions = new ArrayList<>();
        private String fullVersion = "";
        private String platform = "";
        private String platformVersion = "";
        private String architecture = "";
        private String model = "";
        private boolean mobile;
        private String bitness = "";
        private final List<String> formFactors = new ArrayList<>();

        /**
         * Adds a brand with its full version, e.g. {@code ("Chromium",
         * "158.0.8127.18")}. Sec-CH-UA carries the major version.
         */
        public Builder addBrand(String brand, String fullVersion) {
            if (brand == null || fullVersion == null) {
                throw new IllegalArgumentException("brand and version must not be null");
            }
            brands.add(brand);
            brandVersions.add(fullVersion);
            return this;
        }

        public Builder setFullVersion(String v) { fullVersion = nn(v); return this; }
        public Builder setPlatform(String v) { platform = nn(v); return this; }
        public Builder setPlatformVersion(String v) { platformVersion = nn(v); return this; }
        public Builder setArchitecture(String v) { architecture = nn(v); return this; }
        public Builder setModel(String v) { model = nn(v); return this; }
        public Builder setMobile(boolean v) { mobile = v; return this; }
        public Builder setBitness(String v) { bitness = nn(v); return this; }

        /** Form factors, e.g. {@code "Mobile"}, {@code "Tablet"}, {@code "Desktop"}. */
        public Builder addFormFactor(String v) {
            formFactors.add(nn(v));
            return this;
        }

        public CefriumUserAgentMetadata build() {
            return new CefriumUserAgentMetadata(false, this);
        }

        private static String nn(String v) {
            if (v == null) throw new IllegalArgumentException("value must not be null");
            return v;
        }
    }
}
