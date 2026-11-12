// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * The answer of a {@link CefriumContentServer}: status, MIME type, headers
 * and a body stream. The SDK reads the stream on a background thread and
 * closes it.
 */
public final class CefriumContentResponse {
    private int mStatus = 200;
    private String mReason = "OK";
    private final String mMimeType;
    private String mCharset;
    private final TreeMap<String, String> mHeaders =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final InputStream mBody;
    private long mLength = -1;

    /**
     * A {@code 200 OK} response.
     *
     * @param mimeType The MIME type, e.g. {@code text/html}; see
     *     {@link CefriumContentServers#mimeTypeOf} to derive it from a path.
     * @param body The body, read to its end; {@code null} for none.
     */
    public CefriumContentResponse(String mimeType, InputStream body) {
        mMimeType = mimeType;
        mBody = body;
    }

    /** A {@code 200 OK} text response, UTF-8. */
    public static CefriumContentResponse ofText(String mimeType, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new CefriumContentResponse(mimeType, new ByteArrayInputStream(bytes))
                .setCharset("utf-8")
                .setContentLength(bytes.length);
    }

    /** Sets the status code and reason phrase (default {@code 200 OK}). */
    public CefriumContentResponse setStatus(int code, String reason) {
        mStatus = code;
        mReason = reason;
        return this;
    }

    /** Sets the charset of a text body, e.g. {@code utf-8}. */
    public CefriumContentResponse setCharset(String charset) {
        mCharset = charset;
        return this;
    }

    /**
     * Sets a response header, replacing one of the same name. Content-Type
     * and Content-Length come from the MIME type, charset and length.
     */
    public CefriumContentResponse setHeader(String name, String value) {
        mHeaders.put(name, value);
        return this;
    }

    /**
     * Sets the body length in bytes, when known. A known length lets the
     * page seek: Range requests are answered from it (media, large files).
     */
    public CefriumContentResponse setContentLength(long length) {
        mLength = length;
        return this;
    }

    public int getStatus() {
        return mStatus;
    }

    public String getReason() {
        return mReason;
    }

    public String getMimeType() {
        return mMimeType;
    }

    public String getCharset() {
        return mCharset;
    }

    /** A header set on this response, by name in any case, or {@code null}. */
    public String getHeader(String name) {
        return mHeaders.get(name);
    }

    /** A copy of the headers set on this response; names compare in any case. */
    public Map<String, String> getHeaders() {
        TreeMap<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(mHeaders);
        return copy;
    }

    public InputStream getBody() {
        return mBody;
    }

    /** The body length, or -1 when unknown. */
    public long getContentLength() {
        return mLength;
    }
}
