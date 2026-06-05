package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;
import android.view.View;

import androidx.core.view.NestedScrollingChild3;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-033: the browser surface container participates in Android nested
 * scrolling so a CefriumView can live inside a scrollable parent. This verifies
 * the wiring (the container is a NestedScrollingChild3 with nested scrolling
 * enabled). Behavioural boundary-handoff is exercised by the sample apps.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumNestedScrollTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Test
    public void testSurfaceContainerIsNestedScrollingChild() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch created = new CountDownLatch(1);
        final AtomicReference<View> container = new AtomicReference<>();

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            container.set(browser.getSurfaceContainer());
            created.countDown();
        });

        Assert.assertTrue("browser was not created", created.await(15, TimeUnit.SECONDS));
        View v = container.get();
        Assert.assertNotNull("surface container is null", v);
        Assert.assertTrue("surface container should be a NestedScrollingChild3",
                v instanceof NestedScrollingChild3);
        Assert.assertTrue("nested scrolling should be enabled",
                ((NestedScrollingChild3) v).isNestedScrollingEnabled());
    }
}
