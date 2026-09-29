package org.espsketchide.app.editor

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EditorLanguagesTest {

    @Test
    fun whenReadyReportsSuccessOnTheMainThread() {
        val latch = CountDownLatch(1)
        var succeeded = false
        var onMainThread = false
        EditorLanguages.whenReady(ApplicationProvider.getApplicationContext()) { ok ->
            succeeded = ok
            onMainThread = Looper.myLooper() == Looper.getMainLooper()
            latch.countDown()
        }
        assertTrue("callback not invoked", latch.await(30, TimeUnit.SECONDS))
        assertTrue("TextMate setup failed", succeeded)
        assertTrue("callback ran off the main thread", onMainThread)
    }
}
