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
    fun `in-flight baseline can be supplied by the executor state read`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertNotNull(queue.beginNext(10L))
        assertNull(queue.inFlightAttemptTargetState())
        assertEquals(true, queue.setInFlightBaseline(false))
        assertEquals(true, queue.inFlightAttemptTargetState())
        assertEquals(true, queue.inFlightDesiredState())
    }

    @Test
    fun `follow-up received before executor state read uses that actual baseline`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        assertNotNull(queue.beginNext(10L))

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(true, queue.setInFlightBaseline(false))
        assertEquals(true, queue.inFlightAttemptTargetState())
        assertEquals(false, queue.inFlightDesiredState())
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
        assertEquals(true, queue.inFlightAttemptTargetState())
        assertEquals(false, queue.inFlightDesiredState())
    }

    @Test
    fun `in-flight identity is retained until actual state is restored`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertFalse(queue.advanceAfterSuccess(false, true))
        assertTrue(queue.hasInFlight())
        assertEquals(false, queue.inFlightAttemptTargetState())
        assertEquals(false, queue.inFlightDesiredState())

        assertTrue(queue.advanceAfterSuccess(true, false))
        assertFalse(queue.hasInFlight())
    }

    @Test
    fun `in-flight command completes against latest composed target`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(true, queue.inFlightAttemptTargetState())
        assertEquals(false, queue.inFlightDesiredState())
        assertFalse(queue.advanceAfterSuccess(false, true))
        assertEquals(false, queue.inFlightAttemptTargetState())
        assertTrue(queue.advanceAfterSuccess(true, false))
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
    fun `release does not retry failed attempt and preserves follow-up`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(20L, BooleanStateTransform.DISABLE)

        queue.releaseInFlight()

        assertFalse(queue.hasInFlight())
        assertEquals(
            listOf(
                RoutineCommandSnapshot(10L, BooleanStateTransform.TOGGLE),
                RoutineCommandSnapshot(20L, BooleanStateTransform.DISABLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `identity follow-up is removed when failed attempt is released`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        queue.releaseInFlight()

        assertTrue(queue.pendingSnapshot().isEmpty())
        assertFalse(queue.hasInFlight())
    }

    @Test
    fun `successful attempt advances follow-up from confirmed state`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(true, queue.inFlightAttemptTargetState())
        assertEquals(false, queue.inFlightDesiredState())

        assertFalse(queue.advanceAfterSuccess(false, true))
        assertEquals(false, queue.inFlightAttemptTargetState())

        assertTrue(queue.advanceAfterSuccess(true, false))
        assertFalse(queue.hasInFlight())
    }

    @Test
    fun `failed corrective attempt preserves its target for a later event`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        assertFalse(queue.advanceAfterSuccess(false, true))
        assertEquals(false, queue.inFlightAttemptTargetState())

        queue.releaseInFlight(preserveAttempt = true)

        assertFalse(queue.hasInFlight())
        assertEquals(
            listOf(
                RoutineCommandSnapshot(10L, BooleanStateTransform.DISABLE)
            ),
            queue.pendingSnapshot()
        )

        // A new event composes with the deferred target, not with the
        // already completed physical attempt.
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        assertEquals(
            listOf(
                RoutineCommandSnapshot(10L, BooleanStateTransform.ENABLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `failed initial attempt preserves only commands received during it`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        start(queue, false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.releaseInFlight()

        assertEquals(
            listOf(
                RoutineCommandSnapshot(10L, BooleanStateTransform.TOGGLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `failed attempt without follow-up is not requeued`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        start(queue, false)

        queue.releaseInFlight()

        assertTrue(queue.pendingSnapshot().isEmpty())
        assertFalse(queue.hasInFlight())
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

    @Test
    fun `successful toggle uses executor fresh initial state`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        start(queue, false)

        // The executor reads true even though the dispatch baseline was false.
        assertTrue(queue.advanceAfterSuccess(true, false))
        assertFalse(queue.hasInFlight())
    }

}
