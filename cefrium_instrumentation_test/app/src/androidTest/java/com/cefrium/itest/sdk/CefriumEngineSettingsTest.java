package com.cefrium.itest.sdk;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.cefrium.Cefrium;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Engine settings take effect only before the engine initializes (SC-rc-08).
 *
 * By the time a test runs, the host application has already initialized the
 * engine, so every setter here is called too late on purpose. The value must
 * not change: a setter that silently "succeeded" after startup would tell an
 * embedder a security or privacy setting is in force when it is not.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumEngineSettingsTest {

    @Test
    public void lateGpuProcessSettingIsRefused() {
        boolean before = Cefrium.isGpuProcessEnabled();
        Cefrium.setGpuProcessEnabled(!before);
        Assert.assertEquals("setGpuProcessEnabled after initialization must not change "
                + "the value", before, Cefrium.isGpuProcessEnabled());
    }

    @Test
    public void lateComponentUpdateSettingIsRefused() {
        boolean before = Cefrium.areComponentUpdatesEnabled();
        Cefrium.setComponentUpdatesEnabled(!before);
        Assert.assertEquals("setComponentUpdatesEnabled after initialization must not "
                + "change the value", before, Cefrium.areComponentUpdatesEnabled());
    }
}
