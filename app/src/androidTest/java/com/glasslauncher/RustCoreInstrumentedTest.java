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
        assertEquals("0,2", RustCore.prepareSearch("glass-snapshot\u001f1\u001ei\u001fcom.google.android.youtube.tv\u001fYouTube\u001f1"
                + "\u001ei\u001fcom.samsung.x\u001fHidden\u001f1\u001ee\u001fshortcut\u001f1\u001f\u001fWi-Fi\u001fwifi"));
        assertEquals("1", RustCore.search("wifi", 24));
    }
}
