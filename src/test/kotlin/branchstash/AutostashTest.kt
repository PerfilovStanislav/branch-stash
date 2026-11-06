package branchstash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutostashTest {
    private val stashList = listOf(
        "stash@{0} On feature20: autostash:feature20",
        "stash@{1} WIP on feature2: 1a2b3c4 manual work",
        "stash@{2} On feature2: autostash:feature2",
        "stash@{3} On feature2: autostash:feature2",
        "stash@{4} On release/1.0: autostash:release/1.0",
    )

    @Test
    fun picksNewestPluginStashOfBranch() = assertEquals("stash@{2}", findAutostash(stashList, "feature2"))

    @Test
    fun handlesSlashInBranchName() = assertEquals("stash@{4}", findAutostash(stashList, "release/1.0"))

    @Test
    fun ignoresManualStashesAndOtherBranches() {
        assertNull(findAutostash(listOf("stash@{0} WIP on feature2: 1a2b3c4 manual work"), "feature2"))
        assertNull(findAutostash(stashList, "develop"))
        assertNull(findAutostash(emptyList(), "feature2"))
    }
}
