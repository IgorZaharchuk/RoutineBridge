package com.routinebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineCommandQueueTest {

    private fun start(queue: RoutineCommandQueue, baseline: Boolean) {
        val uuid = queue.nextPendingUuid()
        assertNotNull(uuid)
        assertNotNull(queue.beginNext(uuid!!, baseline))
    }

    @Test
    fun `commands for same UUID coalesce across other UUIDs`() {
        val queue = RoutineCommandQueue()

        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        queue.enqueue(20L, BooleanStateTransform.TOGGLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(
            listOf(
                RoutineCommandSnapshot(10L, BooleanStateTransform.DISABLE),
                RoutineCommandSnapshot(20L, BooleanStateTransform.TOGGLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `coalesced identity removes pending UUID`() {
        val queue = RoutineCommandQueue()

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(20L, BooleanStateTransform.ENABLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.ENABLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `removing identity and adding UUID again gives it a new position`() {
        val queue = RoutineCommandQueue()

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(20L, BooleanStateTransform.ENABLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.ENABLE),
                RoutineCommandSnapshot(10L, BooleanStateTransform.TOGGLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `only selected pending UUID can become in flight`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        queue.enqueue(20L, BooleanStateTransform.DISABLE)

        assertEquals(10L, queue.nextPendingUuid())
        assertEquals(
            RoutineCommandSnapshot(10L, BooleanStateTransform.ENABLE),
            queue.beginNext(10L, false)
        )
        assertNull(queue.beginNext(20L, true))
        assertEquals(20L, queue.nextPendingUuid())
        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.DISABLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `beginNext rejects UUID that is no longer first`() {
        val queue = RoutineCommandQueue()

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(20L, BooleanStateTransform.ENABLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.ENABLE),
                RoutineCommandSnapshot(10L, BooleanStateTransform.TOGGLE)
            ),
            queue.pendingSnapshot()
        )
        assertNull(queue.beginNext(10L, false))
        assertEquals(20L, queue.nextPendingUuid())
        assertEquals(
            RoutineCommandSnapshot(20L, BooleanStateTransform.ENABLE),
            queue.beginNext(20L, false)
        )
    }

    @Test
    fun `selected UUID removed before begin is not started`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertNull(queue.nextPendingUuid())
        assertNull(queue.beginNext(10L, false))
        assertFalse(queue.hasInFlight())
    }

    @Test
    fun `commands received during execution update in-flight transform`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(
            BooleanStateTransform.IDENTITY,
            queue.inFlightSnapshot()?.transform
        )
        assertEquals(false, queue.inFlightDesiredState())
    }

    @Test
    fun `in-flight identity is retained until actual state is restored`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertFalse(queue.completeIfSatisfied(true))
        assertTrue(queue.hasInFlight())
        assertEquals(false, queue.inFlightDesiredState())

        assertTrue(queue.completeIfSatisfied(false))
        assertFalse(queue.hasInFlight())
    }

    @Test
    fun `in-flight command completes against latest composed target`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(false, queue.inFlightDesiredState())
        assertTrue(queue.completeIfSatisfied(false))
    }

    @Test
    fun `commands for another UUID remain pending during in-flight work`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(20L, BooleanStateTransform.TOGGLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(
            BooleanStateTransform.DISABLE,
            queue.inFlightSnapshot()?.transform
        )
        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.TOGGLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `failed or timed out operation can release in-flight slot`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(20L, BooleanStateTransform.ENABLE)
        start(queue, false)

        assertEquals(
            RoutineCommandSnapshot(10L, BooleanStateTransform.TOGGLE),
            queue.releaseInFlight()
        )
        assertFalse(queue.hasInFlight())
        assertEquals(20L, queue.nextPendingUuid())
    }

    @Test
    fun `releasing without active operation is harmless`() {
        val queue = RoutineCommandQueue()

        assertNull(queue.releaseInFlight())
        assertFalse(queue.hasInFlight())
    }

    @Test
    fun `newer pending revision survives an older failure`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)

        val oldToken = queue.pendingToken(10L)!!
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertFalse(queue.discardPending(oldToken))
        assertEquals(
            listOf(RoutineCommandSnapshot(10L, BooleanStateTransform.DISABLE)),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `matching pending revision can be discarded`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)

        val token = queue.pendingToken(10L)!!

        assertTrue(queue.discardPending(token))
        assertTrue(queue.pendingSnapshot().isEmpty())
    }
}
