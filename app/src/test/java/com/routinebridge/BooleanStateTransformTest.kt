package com.routinebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BooleanStateTransformTest {

    @Test
    fun `composition table matches chronological semantics`() {
        for (first in BooleanStateTransform.values()) {
            for (next in BooleanStateTransform.values()) {
                val combined = first.then(next)

                for (initial in listOf(false, true)) {
                    val expected = next.applyTo(first.applyTo(initial))
                    assertEquals(
                        "first=$first next=$next initial=$initial",
                        expected,
                        combined.applyTo(initial)
                    )
                }
            }
        }
    }

    @Test
    fun `all action mappings are correct`() {
        assertEquals(
            BooleanStateTransform.ENABLE,
            BooleanStateTransform.from(RoutineInputAction.ENABLE)
        )
        assertEquals(
            BooleanStateTransform.DISABLE,
            BooleanStateTransform.from(RoutineInputAction.DISABLE)
        )
        assertEquals(
            BooleanStateTransform.TOGGLE,
            BooleanStateTransform.from(RoutineInputAction.TOGGLE)
        )
    }

    @Test
    fun `all sequences up to length six preserve chronological result`() {
        val values = BooleanStateTransform.values().toList()

        fun verify(
            sequence: List<BooleanStateTransform>,
            remaining: Int
        ) {
            for (initial in listOf(false, true)) {
                val expected = sequence.fold(initial) { state, transform ->
                    transform.applyTo(state)
                }

                val combined = sequence.fold(BooleanStateTransform.IDENTITY) {
                    accumulated, next -> accumulated.then(next)
                }

                assertEquals(
                    "sequence=$sequence initial=$initial",
                    expected,
                    combined.applyTo(initial)
                )
            }

            if (remaining == 0) return

            for (next in values) {
                verify(sequence + next, remaining - 1)
            }
        }

        verify(emptyList(), 6)
    }

    @Test
    fun `opposite pending actions cancel to identity`() {
        assertEquals(
            BooleanStateTransform.IDENTITY,
            BooleanStateTransform.TOGGLE.then(BooleanStateTransform.TOGGLE)
        )
    }
}
