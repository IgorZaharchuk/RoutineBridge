package com.routinebridge

data class ManualRoutine(
    val uuid: Long,
    val name: String,
    val isRunning: Boolean?,
    val isEnabled: Boolean?
)
