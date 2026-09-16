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
    fun `a differing version name is an update, an identical one is not`() {
        val info = UpdateChecker.parseRelease(release("ffshare-dev-abc1234-universal.apk"))!!
        assertTrue(UpdateChecker.isNewer(info, "dev-0000000"))
        assertFalse(UpdateChecker.isNewer(info, "dev-abc1234"))
    }
}
