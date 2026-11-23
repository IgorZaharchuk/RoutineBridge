package com.routinebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineWorkerCoordinatorTest {

    @Test
    fun `multiple enqueues schedule only one worker`() {
        var schedules = 0
        val coordinator = RoutineWorkerCoordinator { schedules++ }

        coordinator.enqueue(
            10L,
            BooleanStateTransform.ENABLE,
            "first"
        ) {}
        coordinator.enqueue(
            20L,
            BooleanStateTransform.DISABLE,
            "second"
        ) {}

        assertEquals(1, schedules)
        assertEquals(10L, coordinator.nextPendingUuid())
    }

    @Test
    fun `normal completion stops worker and notifies callbacks`() {
        var schedules = 0
        var callbacks = 0
        val coordinator = RoutineWorkerCoordinator { schedules++ }

        coordinator.enqueue(
            10L,
            BooleanStateTransform.ENABLE,
            "request"
        ) {
            callbacks++
        }

        val start = coordinator.beginNext(10L)
        assertNotNull(start)
        assertEquals(true, coordinator.setInFlightBaseline(false))
        assertTrue(coordinator.advanceAfterSuccess(false, true))

        assertTrue(coordinator.stopIfNoPending())
        val stoppedCallbacks = coordinator.takeCallbacksIfStopped()
        assertNotNull(stoppedCallbacks)
        stoppedCallbacks!!.forEach { it() }

        assertEquals(1, schedules)
        assertEquals(1, callbacks)
        assertTrue(coordinator.isWorkerStopped())
    }

    @Test
    fun `unconfirmed failure keeps follow-up for current worker`() {
        var schedules = 0
        val coordinator = RoutineWorkerCoordinator { schedules++ }

        coordinator.enqueue(
            10L,
            BooleanStateTransform.ENABLE,
            "initial"
        ) {}

        assertNotNull(coordinator.beginNext(10L))
        assertEquals(true, coordinator.setInFlightBaseline(false))

        coordinator.enqueue(
            10L,
            BooleanStateTransform.TOGGLE,
            "followup"
        ) {}

        coordinator.releaseAfterFailure(preserveAttempt = false)

        assertEquals(
            listOf(
                RoutineCommandSnapshot(10L, BooleanStateTransform.TOGGLE)
            ),
            coordinator.pendingSnapshot()
        )
        assertEquals(10L, coordinator.nextPendingUuid())
        assertFalse(coordinator.isWorkerStopped())

        assertNotNull(coordinator.beginNext(10L))
        assertEquals(true, coordinator.setInFlightBaseline(false))
        assertTrue(coordinator.advanceAfterSuccess(false, true))
        assertTrue(coordinator.stopIfNoPending())

        assertEquals(1, schedules)
    }

    @Test
    fun `failed corrective attempt preserves target and stops worker`() {
        var schedules = 0
        val coordinator = RoutineWorkerCoordinator { schedules++ }

        coordinator.enqueue(
            10L,
            BooleanStateTransform.ENABLE,
            "initial"
        ) {}

        assertNotNull(coordinator.beginNext(10L))
        assertEquals(true, coordinator.setInFlightBaseline(false))

        coordinator.enqueue(
            10L,
            BooleanStateTransform.TOGGLE,
            "followup"
        ) {}

        assertFalse(coordinator.advanceAfterSuccess(false, true))

        val correction = coordinator.currentAttempt()
        assertNotNull(correction)
        assertEquals(BooleanStateTransform.DISABLE, correction!!.transform)
        assertEquals(
            listOf("followup", "initial"),
            correction.requestIds
        )

        coordinator.releaseAfterFailure(preserveAttempt = true)

        assertTrue(coordinator.isWorkerStopped())
        assertEquals(
            listOf(
                RoutineCommandSnapshot(10L, BooleanStateTransform.DISABLE)
            ),
            coordinator.pendingSnapshot()
        )
        assertEquals(
            listOf("followup", "initial"),
            coordinator.pendingRequestIds(10L)
        )
        assertEquals(1, schedules)
    }

    @Test
    fun `new event after stopped worker schedules one new worker`() {
        var schedules = 0
        val coordinator = RoutineWorkerCoordinator { schedules++ }

        coordinator.enqueue(
            10L,
            BooleanStateTransform.ENABLE,
            "first"
        ) {}

        assertNotNull(coordinator.beginNext(10L))
        assertEquals(true, coordinator.setInFlightBaseline(false))
        assertTrue(coordinator.advanceAfterSuccess(false, true))
        assertTrue(coordinator.stopIfNoPending())
        assertNotNull(coordinator.takeCallbacksIfStopped())

        coordinator.enqueue(
            20L,
            BooleanStateTransform.DISABLE,
            "second"
        ) {}

        assertEquals(2, schedules)
        assertEquals(20L, coordinator.nextPendingUuid())
        assertFalse(coordinator.isWorkerStopped())
    }

    @Test
    fun `failure before executor starts releases selected command and continues queue`() {
        val coordinator = RoutineWorkerCoordinator {}

        coordinator.enqueue(
            10L,
            BooleanStateTransform.ENABLE,
            "failed-setup"
        ) {}
        coordinator.enqueue(
            20L,
            BooleanStateTransform.DISABLE,
            "next-command"
        ) {}

        assertNotNull(coordinator.beginNext(10L))

        val released = coordinator.releaseAfterFailure(
            preserveAttempt = false
        )

        assertNotNull(released)
        assertEquals(
            listOf(
                RoutineCommandSnapshot(20L, BooleanStateTransform.DISABLE)
            ),
            coordinator.pendingSnapshot()
        )
        assertEquals(20L, coordinator.nextPendingUuid())
        assertFalse(coordinator.isWorkerStopped())
    }

    @Test
    fun `stopped callbacks wait until physical worker stops`() {
        var schedules = 0
        var callbacks = 0
        val coordinator = RoutineWorkerCoordinator { schedules++ }

        coordinator.enqueue(
            10L,
            BooleanStateTransform.ENABLE,
            "enqueue"
        ) {
            callbacks++
        }

        assertNotNull(coordinator.beginNext(10L))

        coordinator.whenWorkerStopped {
            callbacks++
        }

        assertNull(coordinator.takeCallbacksIfStopped())
        assertEquals(0, callbacks)

        assertEquals(true, coordinator.setInFlightBaseline(false))
        assertTrue(coordinator.advanceAfterSuccess(false, true))
        assertTrue(coordinator.stopIfNoPending())

        val stoppedCallbacks = coordinator.takeCallbacksIfStopped()
        assertNotNull(stoppedCallbacks)
        stoppedCallbacks!!.forEach { it() }

        assertEquals(2, callbacks)
        assertEquals(1, schedules)
    }
}
