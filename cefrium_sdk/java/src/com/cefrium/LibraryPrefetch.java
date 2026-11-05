// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.ActivityManager;
import android.app.ApplicationStartInfo;
import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Reads libcef.so into the page cache on a background thread while the engine
 * starts. A start needs about half of the file, in an order a sequential read
 * does not follow, so the build ships the ranges a recorded start read, in
 * the order it read them (assets/cefrium/libcef-hot-ranges.txt). The list is
 * only used for the exact library it was recorded from; any other library is
 * read whole.
 */
final class LibraryPrefetch {
    static final String RANGES_ASSET = "cefrium/libcef-hot-ranges.txt";

    // The kernel's own read-ahead advice (fadvise WILLNEED) did nothing on the
    // phone tried, and mapping the file to touch it keeps it in the app's
    // memory until a GC; sendfile to /dev/null fills the page cache and stops.
    // Reading a fully cached file still costs time, so the first 16 MiB decide:
    // cached they came back in 1.5-5.5 ms on the phones measured, from storage
    // in 22-109 ms.
    private static final long PROBE_BYTES = 16L << 20;
    private static final long CACHED_NANOS = 12_000_000L;
    // Turning the read-ahead off stops one under way within a chunk: an app may
    // do it in Application.onCreate, after the read began at process start.
    private static final long CHUNK_BYTES = 4L << 20;

    private static final AtomicBoolean sStarted = new AtomicBoolean();

    /**
     * Whether this process is being started to show an Activity, as opposed to
     * a push message, a job or a broadcast. Android 15+ says so while the
     * start is in progress (that record has no pid yet, so its state picks it
     * out); before 15 there is no way to tell, and the answer is no.
     */
    static boolean isStartingForActivity(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false;
        ActivityManager am = context.getSystemService(ActivityManager.class);
        if (am == null) return false;
        try {
            for (ApplicationStartInfo info : am.getHistoricalProcessStartReasons(4)) {
                if (info.getStartupState() == ApplicationStartInfo.STARTUP_STATE_STARTED) {
                    return isActivityStart(info);
                }
            }
        } catch (RuntimeException e) {
            // Not available on this build: start when the engine does.
        }
        return false;
    }

    // getStartComponent() is Android 16; on 15 the start reason says the same.
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private static boolean isActivityStart(ApplicationStartInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            return info.getStartComponent() == ApplicationStartInfo.START_COMPONENT_ACTIVITY;
        }
        switch (info.getReason()) {
            case ApplicationStartInfo.START_REASON_LAUNCHER:
            case ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS:
            case ApplicationStartInfo.START_REASON_START_ACTIVITY:
                return true;
            default:
                return false;
        }
    }

    /** Starts the read once per process; later calls do nothing. */
    static void start(Context appContext) {
        if (!sStarted.compareAndSet(false, true)) return;
        final File lib = new File(appContext.getApplicationInfo().nativeLibraryDir, "libcef.so");
        if (!lib.isFile()) return;  // loaded from inside the APK: nothing to read ahead
        Thread t = new Thread(() -> {
            long[] ranges = null;
            try (InputStream list = appContext.getAssets().open(RANGES_ASSET)) {
                ranges = plan(lib, list);
            } catch (IOException noList) {
                // No list in this build: read the whole file.
            }
            try (FileInputStream in = new FileInputStream(lib);
                 FileOutputStream sink = new FileOutputStream("/dev/null")) {
                FileChannel from = in.getChannel();
                if (ranges == null) ranges = new long[] {0, from.size()};
                send(from, ranges, sink, Cefrium::isLibraryPrefetchEnabled);
            } catch (IOException ignored) {
                // Only a head start: the library loads the same way without it.
            }
        }, "CefriumPrefetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The ranges to read from {@code lib}, as [start, end) pairs, or null when
     * the list was not recorded from this library (or cannot be read).
     */
    static long[] plan(File lib, InputStream list) throws IOException {
        String id = buildId(lib);
        if (id == null) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(list, StandardCharsets.US_ASCII));
        long[] out = new long[64];
        int n = 0;
        boolean matched = false;
        long size = lib.length();
        for (String line; (line = r.readLine()) != null; ) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("build-id ")) {
                matched = id.equals(line.substring("build-id ".length()).trim());
                if (!matched) return null;
                continue;
            }
            if (!matched) return null;  // ranges before the build-id: not ours
            String[] f = line.trim().split(" ");
            long start = Long.parseLong(f[0]);
            long end = Long.parseLong(f[1]);
            if (start < 0 || end <= start || end > size) return null;
            if (n + 2 > out.length) out = Arrays.copyOf(out, out.length * 2);
            out[n++] = start;
            out[n++] = end;
        }
        return matched && n > 0 ? Arrays.copyOf(out, n) : null;
    }

    /** The GNU build-id of a 64-bit little-endian ELF file, in hex, or null. */
    static String buildId(File elf) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(elf, "r")) {
            ByteBuffer h = read(f, 0, 64);
            if (h.getInt(0) != 0x464c457f || h.get(4) != 2 || h.get(5) != 1) return null;
            long phoff = h.getLong(0x20);
            int phentsize = h.getShort(0x36) & 0xffff;
            int phnum = h.getShort(0x38) & 0xffff;
            for (int i = 0; i < phnum; i++) {
                ByteBuffer ph = read(f, phoff + (long) i * phentsize, 56);
                if (ph.getInt(0) != 4) continue;  // PT_NOTE
                long off = ph.getLong(8);
                int len = (int) Math.min(ph.getLong(32), 1 << 16);
                ByteBuffer notes = read(f, off, len);
                for (int p = 0; p + 12 <= len; ) {
                    int namesz = notes.getInt(p);
                    int descsz = notes.getInt(p + 4);
                    int type = notes.getInt(p + 8);
                    int name = p + 12;
                    int desc = name + ((namesz + 3) & ~3);
                    if (desc + descsz > len) break;
                    if (type == 3 && namesz == 4 && notes.get(name) == 'G'
                            && notes.get(name + 1) == 'N' && notes.get(name + 2) == 'U') {
                        StringBuilder hex = new StringBuilder();
                        for (int b = 0; b < descsz; b++) {
                            hex.append(String.format("%02x", notes.get(desc + b) & 0xff));
                        }
                        return hex.toString();
                    }
                    p = desc + ((descsz + 3) & ~3);
                }
            }
            return null;
        }
    }

    private static ByteBuffer read(RandomAccessFile f, long pos, int len) throws IOException {
        byte[] b = new byte[len];
        f.seek(pos);
        f.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Sends the ranges in order, a chunk at a time while {@code keepGoing}
     * holds, and stops after the first 16 MiB if they were cached. Returns the
     * bytes sent.
     */
    static long send(FileChannel from, long[] ranges, FileOutputStream sink,
            BooleanSupplier keepGoing) throws IOException {
        long start = System.nanoTime();
        long sent = 0;
        boolean probing = true;
        for (int i = 0; i < ranges.length; i += 2) {
            for (long pos = ranges[i]; pos < ranges[i + 1]; ) {
                if (!keepGoing.getAsBoolean()) return sent;
                long end = Math.min(ranges[i + 1], pos + CHUNK_BYTES);
                if (probing) end = Math.min(end, pos + (PROBE_BYTES - sent));
                long stop = transfer(from, pos, end, sink);
                sent += stop - pos;
                if (stop < end) return sent;
                pos = stop;
                if (probing && sent >= PROBE_BYTES) {
                    probing = false;
                    if (System.nanoTime() - start < CACHED_NANOS) return sent;
                }
            }
        }
        return sent;
    }

    /** Sends [pos, end) of the file to the sink; returns where it stopped. */
    private static long transfer(FileChannel from, long pos, long end, FileOutputStream sink)
            throws IOException {
        while (pos < end) {
            long n = from.transferTo(pos, end - pos, sink.getChannel());
            if (n <= 0) break;
            pos += n;
        }
        return pos;
    }

    private LibraryPrefetch() {}
}
