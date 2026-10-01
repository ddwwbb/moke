package com.briqt.moke.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `stable patch version is newer`() {
        assertTrue(UpdateChecker.isNewer("0.1.17", "0.1.16"))
        assertFalse(UpdateChecker.isNewer("0.1.16", "0.1.16"))
    }

    @Test
    fun `stable release is newer than matching prerelease`() {
        assertTrue(UpdateChecker.isNewer("0.1.17", "0.1.17-rc.1"))
        assertFalse(UpdateChecker.isNewer("0.1.17-rc.1", "0.1.17"))
    }

    @Test
    fun `prerelease identifiers follow semver precedence`() {
        assertTrue(UpdateChecker.isNewer("0.1.17-rc.3", "0.1.17-rc.2"))
        assertTrue(UpdateChecker.isNewer("0.1.17-beta.11", "0.1.17-beta.2"))
        assertTrue(UpdateChecker.isNewer("0.1.17-rc.1", "0.1.17-rc"))
        assertFalse(UpdateChecker.isNewer("0.1.17-1", "0.1.17-alpha"))
    }

    @Test
    fun `older stable release is not newer than current prerelease`() {
        assertFalse(UpdateChecker.isNewer("0.1.16", "0.1.17-rc.1"))
    }

    @Test
    fun `two component fork version compares as patch zero`() {
        assertTrue(UpdateChecker.isNewer("v0.9.1", "0.9"))
        assertTrue(UpdateChecker.isNewer("0.10", "0.9"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.9"))
        assertFalse(UpdateChecker.isNewer("0.9", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.1.99", "0.9"))
    }

    @Test
    fun `build metadata does not change precedence`() {
        assertFalse(UpdateChecker.isNewer("0.9.0+build-2", "0.9+build-1"))
        assertTrue(UpdateChecker.isNewer("0.9.1-rc-test.2", "0.9.1-rc-test.1"))
    }

    @Test
    fun `invalid version is never offered`() {
        assertFalse(UpdateChecker.isNewer("latest", "0.1.16"))
        assertFalse(UpdateChecker.isNewer("0.1.17", "debug"))
        assertFalse(UpdateChecker.isNewer("0.09.1", "0.9"))
        assertFalse(UpdateChecker.isNewer("0.9.1-rc.01", "0.9"))
        assertFalse(UpdateChecker.isNewer("0.9.1+build..2", "0.9"))
    }
}
