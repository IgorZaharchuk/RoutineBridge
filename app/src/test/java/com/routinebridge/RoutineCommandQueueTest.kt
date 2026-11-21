package com.routinebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineCommandQueueTest {

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

        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        queue.enqueue(20L, BooleanStateTransform.ENABLE)
        queue.enqueue(10L, BooleanStateTransform.DISABLE)
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
    fun `only one command can become in flight`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        queue.enqueue(20L, BooleanStateTransform.DISABLE)

        assertEquals(
            RoutineCommandSnapshot(10L, BooleanStateTransform.ENABLE),
            queue.beginNext(false)
        )
        assertNull(queue.beginNext(true))
        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.DISABLE)
            ),
            queue.pendingSnapshot()
        )
    }

    @Test
    fun `commands received during execution update in-flight transform`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertNotNull(queue.beginNext(false))
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(BooleanStateTransform.IDENTITY,
            queue.inFlightSnapshot()?.transform)
        assertEquals(false, queue.inFlightDesiredState())
    }

    @Test
    fun `in-flight identity is retained until actual state is restored`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)
        queue.beginNext(false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        // The first physical toggle may already have changed false to true.
        assertFalse(queue.completeIfSatisfied(true))
        assertTrue(queue.hasInFlight())
        assertEquals(false, queue.inFlightDesiredState())

        // Once Provider reports the original baseline, the operation completes.
        assertTrue(queue.completeIfSatisfied(false))
        assertFalse(queue.hasInFlight())
    }

    @Test
    fun `in-flight command completes against latest composed target`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        queue.beginNext(false)

        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(false, queue.inFlightDesiredState())
        assertTrue(queue.completeIfSatisfied(false))
    }

    @Test
    fun `commands for another UUID remain pending during in-flight work`() {
        val queue = RoutineCommandQueue()
        queue.enqueue(10L, BooleanStateTransform.ENABLE)
        queue.beginNext(false)

        queue.enqueue(20L, BooleanStateTransform.TOGGLE)
        queue.enqueue(10L, BooleanStateTransform.TOGGLE)

        assertEquals(BooleanStateTransform.DISABLE,
            queue.inFlightSnapshot()?.transform)
        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.TOGGLE)
            ),
            queue.pendingSnapshot()
        )
    }
}
