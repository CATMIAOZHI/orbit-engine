// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.nio.ByteBuffer;

/**
 * Raw PCM tap of the page's audio, mirroring CEF's {@code CefAudioHandler}.
 *
 * <p><b>Opt-in and exclusive:</b> registering an audio handler REDIRECTS the
 * page's audio to this callback &mdash; it no longer plays through the device
 * speakers. Use it for capture/analysis, not alongside normal playback. Set
 * with {@link CefriumBrowser#setAudioHandler(CefriumAudioHandler)}; pass
 * {@code null} to disable and restore normal playback.
 *
 * <p>{@link #onAudioStreamPacket} is called on a dedicated audio thread; the
 * others on the UI thread.
 */
public interface CefriumAudioHandler {
    /**
     * The audio stream started.
     *
     * @param channels   Number of channels (interleaved in each packet).
     * @param sampleRate Sample rate in Hz.
     */
    default void onAudioStreamStarted(int channels, int sampleRate) {}

    /**
     * A PCM packet. {@code data} holds {@code frames * channels} 32-bit floats,
     * interleaved. The buffer is only valid for the duration of this call.
     *
     * @param data   Interleaved float32 samples.
     * @param frames Number of frames in this packet.
     * @param pts    Presentation timestamp (ms).
     */
    default void onAudioStreamPacket(ByteBuffer data, int frames, long pts) {}

    /** The audio stream stopped. */
    default void onAudioStreamStopped() {}

    /** An audio stream error occurred (the stream is stopped). */
    default void onAudioStreamError(String message) {}
}
