package com.routinebridge

/**
 * A transformation of a Boolean state.
 *
 * [then] composes transformations in chronological order:
 * this transformation is applied first, then the next one.
 */
internal enum class BooleanStateTransform {
    IDENTITY,
    ENABLE,
    DISABLE,
    TOGGLE;

    fun then(next: BooleanStateTransform): BooleanStateTransform =
        when (next) {
            IDENTITY -> this
            ENABLE -> ENABLE
            DISABLE -> DISABLE
            TOGGLE -> when (this) {
                IDENTITY -> TOGGLE
                ENABLE -> DISABLE
                DISABLE -> ENABLE
                TOGGLE -> IDENTITY
            }
        }

    fun applyTo(current: Boolean): Boolean =
        when (this) {
            IDENTITY -> current
            ENABLE -> true
            DISABLE -> false
            TOGGLE -> !current
        }

    companion object {
        fun from(action: RoutineInputAction): BooleanStateTransform =
            when (action) {
                RoutineInputAction.ENABLE -> ENABLE
                RoutineInputAction.DISABLE -> DISABLE
                RoutineInputAction.TOGGLE -> TOGGLE
            }
    }
}
