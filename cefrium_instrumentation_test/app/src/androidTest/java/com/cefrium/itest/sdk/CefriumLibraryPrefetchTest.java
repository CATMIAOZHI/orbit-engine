// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Scanner;
import java.util.function.BooleanSupplier;

/**
 * The startup read-ahead uses a range list only for the library it was
 * recorded from. Drives the SDK's internal LibraryPrefetch on this APK's real
 * libcef.so; the build-id is checked against the system's readelf.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumLibraryPrefetchTest {
    private File mLib;
    private Method mPlan;
    private Method mBuildId;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mLib = new File(context.getApplicationInfo().nativeLibraryDir, "libcef.so");
        Assert.assertTrue("no extracted libcef.so at " + mLib, mLib.isFile());
        Class<?> c = Class.forName("com.cefrium.LibraryPrefetch");
        mPlan = c.getDeclaredMethod("plan", File.class, InputStream.class);
        mPlan.setAccessible(true);
        mBuildId = c.getDeclaredMethod("buildId", File.class);
        mBuildId.setAccessible(true);
    }

    @Test
    public void buildIdIsTheOneReadelfReports() throws Exception {
        Process p = new ProcessBuilder("readelf", "-n", mLib.getPath()).start();
        String out = new Scanner(p.getInputStream(), "UTF-8").useDelimiter("\\A").next();
        String expected = null;
        for (String line : out.split("\n")) {
            if (line.contains("NT_GNU_BUILD_ID")) {
                String[] f = line.trim().split("\\s+");
                expected = f[f.length - 1];
            }
        }
        Assert.assertNotNull("readelf printed no build-id:\n" + out, expected);
        Assert.assertEquals(expected, mBuildId.invoke(null, mLib));
    }

    @Test
    public void aListRecordedFromThisLibraryIsUsed() throws Exception {
        String list = "# comment\nbuild-id " + mBuildId.invoke(null, mLib)
                + "\n8192 16384\n0 4096\n";
        Assert.assertArrayEquals(new long[] {8192, 16384, 0, 4096}, plan(list));
    }

    @Test
    public void aListFromAnotherBuildIsIgnored() throws Exception {
        String id = (String) mBuildId.invoke(null, mLib);
        String other = id.substring(0, id.length() - 1) + (id.endsWith("0") ? "1" : "0");
        Assert.assertNull(plan("build-id " + other + "\n0 4096\n"));
    }

    @Test
    public void aListWithoutABuildIdIsIgnored() throws Exception {
        Assert.assertNull(plan("0 4096\n"));
    }

    @Test
    public void aRangePastTheEndOfTheLibraryIsRejected() throws Exception {
        Assert.assertNull(plan("build-id " + mBuildId.invoke(null, mLib) + "\n0 "
                + (mLib.length() + 4096) + "\n"));
    }

    /**
     * A build ships a list recorded from its own arm64 libcef.so. On another
     * ABI the same list belongs to the other library and must be ignored.
     */
    @Test
    public void theShippedListMatchesOnlyTheLibraryItWasRecordedFrom() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        long[] ranges;
        try (InputStream in = context.getAssets().open("cefrium/libcef-hot-ranges.txt")) {
            ranges = (long[]) mPlan.invoke(null, mLib, in);
        }
        if (!"arm64-v8a".equals(android.os.Build.SUPPORTED_ABIS[0])) {
            Assert.assertNull("an arm64 list was used for another ABI's library", ranges);
            return;
        }
        Assert.assertNotNull("the shipped list was not recorded from this libcef.so", ranges);
        long bytes = 0;
        for (int i = 0; i < ranges.length; i += 2) bytes += ranges[i + 1] - ranges[i];
        Assert.assertTrue("list covers " + bytes + " of " + mLib.length() + " bytes: "
                        + Arrays.toString(Arrays.copyOf(ranges, Math.min(6, ranges.length))),
                bytes > (16L << 20) && bytes < mLib.length());
    }

    /**
     * An app may turn the read-ahead off in Application.onCreate, after the
     * read began at process start: the read stops at the next chunk. The same
     * read left on goes past that point.
     */
    @Test
    public void turningTheReadAheadOffStopsAReadUnderWay() throws Exception {
        Method send = Class.forName("com.cefrium.LibraryPrefetch").getDeclaredMethod("send",
                FileChannel.class, long[].class, FileOutputStream.class, BooleanSupplier.class);
        send.setAccessible(true);
        long[] ranges = {0, 64L << 20};
        int[] calls = {0};
        BooleanSupplier offAfterTwoChunks = () -> calls[0]++ < 2;
        try (FileInputStream in = new FileInputStream(mLib);
             FileOutputStream sink = new FileOutputStream("/dev/null")) {
            long stopped = (long) send.invoke(null, in.getChannel(), ranges, sink, offAfterTwoChunks);
            Assert.assertEquals(8L << 20, stopped);
            long left = (long) send.invoke(null, in.getChannel(), ranges, sink,
                    (BooleanSupplier) () -> true);
            Assert.assertTrue("a read left on stopped at " + left, left > (8L << 20));
        }
    }

    private long[] plan(String list) throws Exception {
        return (long[]) mPlan.invoke(
                null, mLib, new ByteArrayInputStream(list.getBytes(StandardCharsets.US_ASCII)));
    }
}
