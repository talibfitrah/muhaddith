package org.murabbie.muhaddith

import org.murabbie.muhaddith.data.RemoteApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAppTest {
    private val app = RemoteApp(16, "0.9.0", "", mapOf("arm64" to "http://x/a64.apk", "arm32" to "http://x/a32.apk"), emptyMap())

    @Test fun picksArm64WhenSupported() { assertEquals("arm64" to "http://x/a64.apk", app.urlFor(listOf("arm64-v8a", "armeabi-v7a"))) }
    @Test fun fallsBackToArm32() { assertEquals("arm32" to "http://x/a32.apk", app.urlFor(listOf("armeabi-v7a", "armeabi"))) }
    @Test fun noApksMeansNothing() { assertNull(RemoteApp(1, "1", "", emptyMap(), emptyMap()).urlFor(listOf("arm64-v8a"))) }
}
