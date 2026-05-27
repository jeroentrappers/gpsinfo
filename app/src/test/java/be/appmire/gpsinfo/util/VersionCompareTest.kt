package be.appmire.gpsinfo.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test fun newer_patch() = assertTrue(VersionCompare.isNewer("2.0.2", "2.0.1"))
    @Test fun newer_minor() = assertTrue(VersionCompare.isNewer("2.1.0", "2.0.9"))
    @Test fun newer_major() = assertTrue(VersionCompare.isNewer("3.0.0", "2.9.9"))

    @Test fun equal_is_not_newer() = assertFalse(VersionCompare.isNewer("2.0.1", "2.0.1"))
    @Test fun older_is_not_newer() = assertFalse(VersionCompare.isNewer("2.0.0", "2.0.1"))

    // Leading "v" on the GitHub tag is tolerated.
    @Test fun tolerates_v_prefix() = assertTrue(VersionCompare.isNewer("v2.1.0", "2.0.1"))
    @Test fun v_prefix_equal() = assertFalse(VersionCompare.isNewer("v2.0.1", "2.0.1"))

    // Missing trailing components are zero-padded: "2.1" == "2.1.0".
    @Test fun short_equals_padded() = assertFalse(VersionCompare.isNewer("2.1", "2.1.0"))
    @Test fun short_is_newer_than_lower() = assertTrue(VersionCompare.isNewer("2.2", "2.1.9"))

    // Non-numeric suffixes are stripped per-component.
    @Test fun ignores_prerelease_suffix() =
        assertFalse(VersionCompare.isNewer("2.0.1-beta", "2.0.1"))

    // Malformed input degrades to 0 rather than throwing.
    @Test fun blank_is_not_newer() = assertFalse(VersionCompare.isNewer("", "1.0.0"))
}
