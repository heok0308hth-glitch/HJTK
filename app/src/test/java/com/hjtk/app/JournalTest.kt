package com.hjtk.app

import com.hjtk.app.core.plan.Journal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalTest {

    @Test
    fun chainVerifiesAfterManyEntries() {
        val w = TestWorld()
        repeat(50) { w.journal.append("step_finished", "p1", "s$it", "device.battery", "dig", "ok") }
        val verify = w.journal.verify()
        assertTrue(verify.ok)
        assertEquals(50, verify.records)
    }

    @Test
    fun tamperingIsDetected() {
        val w = TestWorld()
        w.journal.append("plan_started", "p1")
        w.journal.append("step_finished", "p1", "s1", "device.battery", "digest-a")
        w.journal.append("plan_finished", "p1")

        // Rewrite one line's digest while keeping the old hash.
        val file = "journal.jsonl"
        val lines = w.storage.read(file)!!.lines().filter { it.isNotBlank() }
        val tampered = lines.toMutableList()
        tampered[1] = tampered[1].replace("digest-a", "digest-b")
        w.storage.write(file, tampered.joinToString("\n"))

        val fresh = Journal(w.storage, w.clock)
        val verify = fresh.verify()
        assertFalse(verify.ok)
        assertTrue(verify.reason.contains("해시"))
    }

    @Test
    fun openPlanIsDetectedAndCleared() {
        val w = TestWorld()
        w.journal.append("plan_started", "p9", detail = "중단된 작업")
        w.journal.append("step_finished", "p9", "s1", "device.battery")
        val open = w.journal.openPlan()
        assertNotNull(open)
        assertEquals("p9", open.planId)
        assertEquals(listOf("s1"), open.completedSteps)

        w.journal.append("plan_finished", "p9")
        assertNull(Journal(w.storage, w.clock).openPlan())
    }

    @Test
    fun digestsNeverContainRawText() {
        val w = TestWorld()
        val record = w.journal.append(
            "step_finished",
            "p1",
            "s1",
            "mail.send",
            Journal.digest("to=a@b.c subject=비밀"),
            "ok"
        )
        assertFalse(record.digest.contains("비밀"))
        assertFalse(record.digest.contains("a@b.c"))
    }

    @Test
    fun survivesCorruptLine() {
        val w = TestWorld()
        w.journal.append("plan_started", "p1")
        w.storage.append("journal.jsonl", "{broken json")
        val fresh = Journal(w.storage, w.clock)
        assertEquals(1, fresh.records().size)
        assertTrue(fresh.verify().ok)
    }
}

class JournalHealingTest {

    @Test
    fun tornFinalLineIsCutAndTheLogStaysUsable() {
        val w = TestWorld()
        w.journal.append("plan_started", "p1", detail = "중단 작업")
        w.journal.append("step_finished", "p1", "s1", "device.battery")
        // Simulate a power loss mid-write: half a JSON line, no newline.
        w.storage.append("journal.jsonl", "{\"seq\":3,\"at\":17000")

        val healed = Journal(w.storage, w.clock)
        assertEquals(2, healed.records().size)
        assertTrue(healed.healedTail, "잘린 꼬리를 복구했다고 표시해야 합니다")
        assertTrue(healed.verify().ok)

        // The next append continues the chain instead of corrupting it.
        healed.append("plan_finished", "p1")
        assertTrue(Journal(w.storage, w.clock).verify().ok)
        assertEquals(3, Journal(w.storage, w.clock).records().size)
    }

    @Test
    fun reorderedRecordsAreRejected() {
        val w = TestWorld()
        w.journal.append("plan_started", "p1")
        w.journal.append("step_finished", "p1", "s1")
        w.journal.append("plan_finished", "p1")
        val lines = w.storage.read("journal.jsonl")!!.lines().filter { it.isNotBlank() }
        // Swap the last two lines: the chain no longer links.
        w.storage.write("journal.jsonl", listOf(lines[0], lines[2], lines[1]).joinToString("\n") + "\n")
        val reloaded = Journal(w.storage, w.clock)
        assertTrue(reloaded.records().size <= 2, "손상된 이후 레코드는 읽지 않아야 합니다")
    }
}
