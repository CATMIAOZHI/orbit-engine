package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalMediaServer;

import android.app.Activity;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #16: media in an off-screen (OSR) browser. The media are test assets
 * (h264.mp4 and vp9.webm: 320x240 test pattern, 4 s; tone.mp3: 440 Hz, 6 s)
 * served from loopback.
 *
 * <ul>
 *   <li>Video frames reach onPaint: the test pattern's colours show up in the
 *       video's rectangle (0 coloured pixels before the fix, for both the
 *       hardware-decoded H.264 and the software-decoded VP9).</li>
 *   <li>Audio plays: Android reports a started output stream for this app.</li>
 *   <li>Fullscreen from a tap enters and leaves without killing the browser
 *       process (it asserted in ScreenOrientationProviderImpl before).</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class CefriumOsrMediaTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String VIDEO_PAGE = "<html><body style='margin:0;background:#fff'>"
            + "<video id=v autoplay muted loop playsinline width=320 height=240"
            + " style='position:absolute;left:0;top:0;background:#000' src='MEDIA'></video>"
            + "<script>v.onerror=function(){document.title='error:'+v.error.code;};"
            + "setInterval(function(){if(v.currentTime>2)document.title='played';},200);"
            + "</script></body></html>";

    private static final String AUDIO_PAGE = "<html><body style='margin:0'>"
            + "<audio id=a loop src='/tone.mp3'></audio>"
            + "<button id=b style='position:fixed;left:0;top:0;width:100%;height:100%'>play</button>"
            + "<script>a.onerror=function(){document.title='error:'+a.error.code;};"
            + "b.onclick=function(){a.play().catch(function(e){document.title='error:'+e.name;});};"
            + "a.onplaying=function(){document.title='playing';};"
            + "</script></body></html>";

    private static final String FULLSCREEN_PAGE = "<html><body style='margin:0'>"
            + "<video id=v controls muted loop playsinline width=320 height=180 src='/h264.mp4'></video>"
            + "<button id=b style='position:fixed;left:0;top:0;width:100%;height:100%;opacity:0.01'>go</button>"
            + "<script>var n=0;document.onfullscreenchange=function(){"
            + "if(document.fullscreenElement){document.title='entered';document.exitFullscreen();}"
            + "else{document.title='left';}};"
            + "b.onclick=function(){v.play();v.requestFullscreen().catch(function(e){"
            + "document.title='error:'+e.name;});};"
            + "setInterval(function(){n++;},100);"
            + "window.alive=function(){return n;};"
            + "</script></body></html>";

    @Test
    public void h264FramesReachOnPaint() throws Exception {
        assertVideoPainted("/h264.mp4");
    }

    @Test
    public void vp9FramesReachOnPaint() throws Exception {
        assertVideoPainted("/vp9.webm");
    }

    private void assertVideoPainted(String media) throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicInteger coloured = new AtomicInteger(-1);
        try (LocalMediaServer server = new LocalMediaServer(VIDEO_PAGE.replace("MEDIA", media))) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = track(new CefriumBrowser(400, 300, 1.0f));
                b.setRenderHandler(new CefriumRenderHandler() {
                    @Override
                    public void onPaint(int width, int height, ByteBuffer px) {
                        coloured.set(colouredPixels(width, height, px));
                    }
                });
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            waitForTitle(title, "played", 30_000);
            // A frame or two more after the playhead passed 2 s.
            Thread.sleep(500);
            Assert.assertTrue("no video in onPaint for " + media + ": " + coloured.get()
                    + " coloured pixels in the video's rectangle", coloured.get() > 1000);
        }
    }

    @Test
    public void audioPlays() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser[] holder = new CefriumBrowser[1];
        try (LocalMediaServer server = new LocalMediaServer(AUDIO_PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = track(new CefriumBrowser(400, 300, 1.0f));
                b.setRenderHandler(new CefriumRenderHandler() {
                    @Override
                    public void onPaint(int width, int height, ByteBuffer px) {}
                });
                holder[0] = b;
                b.loadUrl(server.url());
                b.setOnTitleChangedListener(title::set);
            });
            // Playing with sound needs a user gesture: tap the page's button.
            long deadline = System.currentTimeMillis() + 30_000;
            while (!"playing".equals(title.get()) && System.currentTimeMillis() < deadline) {
                activity.runOnUiThread(() -> holder[0].sendTap(200f, 150f));
                Thread.sleep(1000);
                if (title.get().startsWith("error:")) Assert.fail(title.get());
            }
            Assert.assertEquals("the audio element never started", "playing", title.get());
            String uid = "u/pid:" + android.os.Process.myUid() + "/";
            String started = null;
            for (int i = 0; i < 20 && started == null; i++) {
                for (String line : shell("dumpsys audio").split("\n")) {
                    if (line.contains(uid) && line.contains("state:started")) started = line;
                }
                if (started == null) Thread.sleep(500);
            }
            Assert.assertNotNull("Android has no started audio stream from this app", started);
        }
    }

    @Test
    public void fullscreenFromATapKeepsTheBrowserAlive() throws Exception {
        Activity activity = activityRule.getActivity();
        AtomicReference<String> title = new AtomicReference<>("");
        CefriumBrowser[] holder = new CefriumBrowser[1];
        try (LocalMediaServer server = new LocalMediaServer(FULLSCREEN_PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser b = track(new CefriumBrowser(400, 300, 1.0f));
                b.setRenderHandler(new CefriumRenderHandler() {
                    @Override
                    public void onPaint(int width, int height, ByteBuffer px) {}
                });
                holder[0] = b;
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            Thread.sleep(3000);
            activity.runOnUiThread(() -> holder[0].sendTap(200f, 150f));
            waitForTitle(title, "left", 20_000);
            // Still running: the page answers after the round trip.
            activity.runOnUiThread(() ->
                    holder[0].evaluateJavaScript("document.title='alive:'+alive()"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (!title.get().startsWith("alive:") && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the page stopped answering after fullscreen: " + title.get(),
                    title.get().startsWith("alive:"));
        }
    }

    private CefriumBrowser track(CefriumBrowser b) {
        return browsers.track(b);
    }

    // Pixels of the video's rectangle (CSS 0,0 320x240 at scale 1) whose
    // channels differ by more than 60: the test pattern, not black or white.
    private static int colouredPixels(int width, int height, ByteBuffer px) {
        int n = 0;
        for (int y = 10; y < 230 && y < height; y += 3) {
            for (int x = 10; x < 310 && x < width; x += 3) {
                int i = (y * width + x) * 4;
                int r = px.get(i) & 0xff, g = px.get(i + 1) & 0xff, b = px.get(i + 2) & 0xff;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 60) n++;
            }
        }
        return n;
    }

    private static void waitForTitle(AtomicReference<String> title, String want, long ms)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            String t = title.get();
            if (t.startsWith("error:")) Assert.fail(t);
            if (t.equals(want)) return;
            Thread.sleep(100);
        }
        Assert.fail("no '" + want + "' title; last=" + title.get());
    }

    private static String shell(String cmd) throws Exception {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(cmd);
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            pfd.close();
        }
    }
}
