package com.routinebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RoutineInputStore(context: Context) {
    private val prefs = context.getSharedPreferences("routine_inputs", Context.MODE_PRIVATE)

    fun load(): List<RoutineInput> {
        val array = JSONArray(prefs.getString("items", "[]") ?: "[]")
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uuid = item.optLong("routineUuid", -1L)
                if (uuid <= 0L) continue
                val action = runCatching {
                    RoutineInputAction.valueOf(item.optString("action"))
                }.getOrDefault(RoutineInputAction.TOGGLE)
                add(RoutineInput(
                    item.optString("id"),
                    item.optString("label"),
                    uuid,
                    item.optString("routineName"),
                    action
                ))
            }
        }
    }

    fun save(inputs: List<RoutineInput>) {
        val array = JSONArray()
        inputs.forEach {
            array.put(JSONObject()
                .put("id", it.id)
                .put("label", it.label)
                .put("routineUuid", it.routineUuid)
                .put("routineName", it.routineName)
                .put("action", it.action.name))
        }
        check(prefs.edit().putString("items", array.toString()).commit()) {
            "Не удалось сохранить настройки входов"
        }
    }
}
