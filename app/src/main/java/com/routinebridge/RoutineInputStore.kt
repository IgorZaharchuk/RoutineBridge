package com.routinebridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RoutineInputStore(context: Context) {
    private val prefs = context.getSharedPreferences("routine_inputs", Context.MODE_PRIVATE)
    private val messages = AppLocale.wrap(context)

    fun load(): List<RoutineInput> {
        val array = JSONArray(prefs.getString("items", "[]") ?: "[]")
        return buildList {
            val ids = mutableSetOf<String>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: throw IllegalStateException(messages.getString(R.string.store_invalid_item, i))

                val id = item.optString("id").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(messages.getString(R.string.store_missing_id))
                val label = item.optString("label").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(messages.getString(R.string.store_missing_input_name))
                val routineName = item.optString("routineName").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException(messages.getString(R.string.store_missing_routine_name))

                if (!ids.add(id)) {
                    throw IllegalStateException(messages.getString(R.string.store_duplicate_id, id))
                }

                val uuidValue = item.opt("routineUuid")
                val uuid = try {
                    when (uuidValue) {
                        is Int -> uuidValue.toLong()
                        is Long -> uuidValue
                        else -> throw IllegalArgumentException(messages.getString(R.string.store_uuid_not_integer))
                    }
                } catch (e: Exception) {
                    throw IllegalStateException(messages.getString(R.string.store_invalid_uuid), e)
                }

                val action = try {
                    RoutineInputAction.valueOf(item.getString("action"))
                } catch (e: Exception) {
                    throw IllegalStateException(messages.getString(R.string.store_invalid_action), e)
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
            messages.getString(R.string.store_save_failed)
        }
    }
}
