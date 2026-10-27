package sh.haven.core.data

import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #510 — the terminal flavour drops the desktop and media native libraries,
 * so anything that offers those features has to ask whether they shipped.
 * Pins that the answer comes from the filesystem rather than a constant.
 */
class NativeFeaturesTest {

    @get:Rule
    val libDir = TemporaryFolder()

    private fun features(): NativeFeatures {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = libDir.root.absolutePath
        }
        return NativeFeatures(context)
    }

    private fun ship(vararg names: String) {
        for (n in names) {
            File(libDir.root, n).apply {
                writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
                setExecutable(true)
            }
        }
    }

    @Test
    fun `an empty native dir reports no desktop or media features`() {
        val f = features()

        assertFalse("rdp", f.rdp)
        assertFalse("ffmpeg", f.ffmpeg)
        assertFalse("anyDesktop", f.anyDesktop)
    }

    @Test
    fun `shipping the libraries reports them`() {
        ship(
            "librdp_transport.so",
            "libffmpeg.so", "libffprobe.so", "libavcodec.so",
        )
        val f = features()

        assertTrue("rdp", f.rdp)
        assertTrue("ffmpeg", f.ffmpeg)
        assertTrue("anyDesktop", f.anyDesktop)
    }

    /**
     * The executables are thin wrappers since the shared-libav change — the
     * codec code lives in libavcodec.so. ffmpeg present without it would pass
     * a naive check and then fail at exec with "library not found".
     */
    @Test
    fun `ffmpeg needs its shared libraries, not just the executables`() {
        ship("libffmpeg.so", "libffprobe.so")

        assertFalse(features().ffmpeg)
    }

    /** A file that is present but not executable is not a usable library. */
    @Test
    fun `a non-executable library does not count`() {
        ship("librdp_transport.so")
        File(libDir.root, "librdp_transport.so").setExecutable(false)

        assertFalse(features().rdp)
    }

    /**
     * A Context with no native library directory reports nothing rather than
     * throwing. Android never leaves it null for an installed package, but a
     * mocked Context does — and get_app_info taking an NPE because a test
     * built it that way is a worse failure than answering "not detected".
     * Three McpTools tests found this the hard way.
     */
    @Test
    fun `a context with no native library dir reports nothing`() {
        val context = mockk<Context>()
        every { context.applicationInfo } returns ApplicationInfo()
        val f = NativeFeatures(context)

        assertFalse("rdp", f.rdp)
        assertFalse("ffmpeg", f.ffmpeg)
        assertFalse("anyDesktop", f.anyDesktop)
    }

    /** One desktop transport is enough for the desktop UI to be worth showing. */
    @Test
    fun `anyDesktop is true with only one transport present`() {
        ship("librdp_transport.so")
        val f = features()

        assertTrue(f.anyDesktop)
    }
}
