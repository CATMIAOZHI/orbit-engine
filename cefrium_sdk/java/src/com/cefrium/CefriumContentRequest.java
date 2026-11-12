// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A request to a {@link CefriumContentServer}.
 */
public final class CefriumContentRequest {
    private final String mMethod;
    private final String mUrl;
    private final String mPath;
    private final String mQuery;
    private final Map<String, String> mHeaders;
    private final byte[] mBody;

    CefriumContentRequest(String method, String url, String path, String query,
            Map<String, String> headers, byte[] body) {
        mMethod = method;
        mUrl = url;
        mPath = path;
        mQuery = query;
        TreeMap<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.putAll(headers);
        mHeaders = Collections.unmodifiableMap(h);
        mBody = body;
    }

    /** A copy with another path, for a server that answers below a prefix. */
    CefriumContentRequest withPath(String path) {
        return new CefriumContentRequest(mMethod, mUrl, path, mQuery, mHeaders, mBody);
    }

    /** The method, upper case: {@code GET}, {@code POST}, ... */
    public String getMethod() {
        return mMethod;
    }

    /** The full URL, as the page requested it. */
    public String getUrl() {
        return mUrl;
    }

    /**
     * The path, percent-decoded, starting with {@code /}. Below a
     * {@link CefriumPathContentServer} prefix it is the part after the prefix:
     * {@code /img/a.png} for {@code /assets/img/a.png} under {@code /assets/}.
     */
    public String getPath() {
        return mPath;
    }

    /** The query without the {@code ?}, still encoded, or {@code null}. */
    public String getQuery() {
        return mQuery;
    }

    /**
     * A request header, by name in any case.
     *
     * @return Its value, or {@code null}.
     */
    public String getHeader(String name) {
        return mHeaders.get(name);
    }

    /** All request headers; names compare in any case. */
    public Map<String, String> getHeaders() {
        return mHeaders;
    }

    /**
     * The body of a POST or PUT, or {@code null}. Parts the page sent as a
     * file or blob are not included.
     */
    public byte[] getBody() {
        return mBody;
    }
}
