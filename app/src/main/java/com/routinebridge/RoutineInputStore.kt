package com.routinebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RoutineInputStore(context: Context) {
    private val prefs = context.getSharedPreferences("routine_inputs", Context.MODE_PRIVATE)

    fun load(): List<RoutineInput> {
        val array = JSONArray(prefs.getString("items", "[]") ?: "[]")
        return buildList {
            val ids = mutableSetOf<String>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: throw IllegalStateException("Повреждена запись настроек: элемент $i не является объектом")

                val id = item.optString("id").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Повреждена запись настроек: отсутствует ID")
                val label = item.optString("label").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Повреждена запись настроек: отсутствует название входа")
                val routineName = item.optString("routineName").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Повреждена запись настроек: отсутствует название сценария")

                if (!ids.add(id)) {
                    throw IllegalStateException("Повреждены настройки: повторяется ID входа $id")
                }

                val uuidValue = item.opt("routineUuid")
                val uuid = try {
                    when (uuidValue) {
                        is Int -> uuidValue.toLong()
                        is Long -> uuidValue
                        else -> throw IllegalArgumentException("UUID должен быть целым числом")
                    }
                } catch (e: Exception) {
                    throw IllegalStateException("Повреждена запись настроек: неверный UUID", e)
                }

                val action = try {
                    RoutineInputAction.valueOf(item.getString("action"))
                } catch (e: Exception) {
                    throw IllegalStateException("Повреждена запись настроек: неверное действие", e)
                }

                add(RoutineInput(id, label, uuid, routineName, action))
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
