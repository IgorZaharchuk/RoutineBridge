package com.routinebridge

enum class RoutineInputAction(val title: String) {
    ENABLE("Включить"),
    DISABLE("Выключить"),
    TOGGLE("Переключить")
}

data class RoutineInput(
    val id: String,
    val label: String,
    val routineUuid: Long,
    val routineName: String,
    val action: RoutineInputAction
)
