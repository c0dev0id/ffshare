package com.caydey.ffshare

import com.caydey.ffshare.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release asset name is attacker-controlled as far as this app is concerned: it is
 * whatever the published release says, and it ends up as a path under cacheDir/updates.
 */
class UpdateCheckerTest {

    private fun release(vararg names: String): String {
        val assets = names.joinToString(",") {
            """{"name":"$it","browser_download_url":"https://example.invalid/$it"}"""
        }
        return """{"assets":[$assets]}"""
    }

    @Test
    fun `picks the universal apk and derives its version`() {
        val info = UpdateChecker.parseRelease(release("ffshare-dev-abc1234-universal.apk"))
        assertEquals("dev-abc1234", info?.versionName)
        assertEquals("ffshare-dev-abc1234-universal.apk", info?.apkName)
        // a release that publishes none reports none rather than failing to parse
        assertNull(info?.checksumsUrl)
    }

    @Test
    fun `rejects an asset name that would escape the download directory`() {
        assertNull(UpdateChecker.parseRelease(release("ffshare-../../../evil-universal.apk")))
        assertNull(UpdateChecker.parseRelease(release("ffshare-a/b-universal.apk")))
        assertNull(UpdateChecker.parseRelease(release("""ffshare-a\b-universal.apk""")))
    }

    @Test
    fun `ignores assets that are not the universal apk`() {
        assertNull(UpdateChecker.parseRelease(release("ffshare-dev-abc1234-arm64-v8a.apk")))
        assertNull(UpdateChecker.parseRelease(release("SHA256SUMS")))
        assertNull(UpdateChecker.parseRelease("""{"assets":[]}"""))
        assertNull(UpdateChecker.parseRelease("""{}"""))
    }

    @Test
    fun `finds the checksums asset alongside the apk`() {
        val info = UpdateChecker.parseRelease(
            release("ffshare-dev-abc1234-universal.apk", UpdateChecker.CHECKSUMS_NAME)
        )
        assertEquals("https://example.invalid/SHA256SUMS", info?.checksumsUrl)
    }

    @Test
    fun `reads the checksum for a named file out of a sha256sum listing`() {
        val listing = """
            aaaa1111  ffshare-dev-0000000-arm64-v8a.apk
            bbbb2222  ffshare-dev-0000000-universal.apk
        """.trimIndent()
        assertEquals("bbbb2222", UpdateChecker.parseChecksum(listing, "ffshare-dev-0000000-universal.apk"))
        assertNull(UpdateChecker.parseChecksum(listing, "ffshare-dev-0000000-armeabi-v7a.apk"))
    }

    @Test
    fun `accepts the binary-mode star sha256sum writes before the name`() {
        assertEquals("cccc3333", UpdateChecker.parseChecksum("cccc3333 *app.apk", "app.apk"))
    }

    @Test
    fun `a differing version name is an update, an identical one is not`() {
        val info = UpdateChecker.parseRelease(release("ffshare-dev-abc1234-universal.apk"))!!
        assertTrue(UpdateChecker.isNewer(info, "dev-0000000"))
        assertFalse(UpdateChecker.isNewer(info, "dev-abc1234"))
    }
}
