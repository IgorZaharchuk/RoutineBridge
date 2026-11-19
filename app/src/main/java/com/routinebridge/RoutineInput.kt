package com.routinebridge

enum class RoutineInputAction {
    ENABLE,
    DISABLE,
    TOGGLE
}

data class RoutineInput(
    val id: String,
    val label: String,
    val routineUuid: Long,
    val routineName: String,
    val action: RoutineInputAction
)
