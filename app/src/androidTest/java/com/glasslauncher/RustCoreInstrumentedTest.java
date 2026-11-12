package com.glasslauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** On-device / emulator check that libglass.so was packaged for this ABI, loads and answers through JNI. */
@RunWith(AndroidJUnit4.class)
public class RustCoreInstrumentedTest {
    @Test public void nativeCoreLoadsAndSearches() {
        assertTrue("status: " + RustCore.status(), RustCore.isAvailable());
        assertEquals(2, RustCore.setSearchIndex("app\u001fYouTube\u001eshortcut\u001fWi-Fi\u001fwifi"));
        assertEquals("1", RustCore.search("wifi", 24));
    }
}
