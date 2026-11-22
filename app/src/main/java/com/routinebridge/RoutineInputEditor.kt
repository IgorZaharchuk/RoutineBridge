package com.routinebridge

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView

object RoutineInputEditor {

    fun create(
        activity: Activity,
        input: RoutineInput,
        routines: List<ManualRoutine>,
        actionTitle: (RoutineInputAction) -> String,
        onSave: (RoutineInput) -> Unit,
        onDelete: () -> Unit,
        onCancel: () -> Unit
    ): View {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val white = Color.WHITE
        val secondary = Color.rgb(208, 214, 226)
        val surface = Color.rgb(30, 30, 30)
        val accent = Color.rgb(48, 64, 86)

        fun rounded(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }

        fun label(value: String) = TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(secondary)
        }

        fun params() = LinearLayout.LayoutParams(-1, -2)

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }

        form.addView(label(activity.getString(R.string.input_name)))
        val name = EditText(activity).apply {
            setSingleLine(true)
            setText(input.label)
            setTextColor(white)
            setHintTextColor(secondary)
        }
        form.addView(name, params())

        // Сохраняем старый вариант, если сценарий исчез из Provider.
        val options = routines.toMutableList()
        if (options.none { it.uuid == input.routineUuid }) {
            options.add(
                0,
                ManualRoutine(input.routineUuid, input.routineName, null, null)
            )
        }

        form.addView(
            label(activity.getString(R.string.manual_routine)).apply {
                setPadding(0, dp(10), 0, 0)
            }
        )

        val routineSpinner = Spinner(activity)
        routineSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            options.map { routine ->
                if (routine.uuid == input.routineUuid &&
                    routines.none { it.uuid == input.routineUuid }
                ) {
                    routine.name +
                        activity.getString(R.string.routine_not_found_suffix)
                } else {
                    routine.name
                }
            }
        ).also {
            it.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }
        routineSpinner.setPopupBackgroundDrawable(rounded(surface))
        routineSpinner.setSelection(
            options.indexOfFirst { it.uuid == input.routineUuid }.coerceAtLeast(0)
        )
        form.addView(routineSpinner, params())

        form.addView(
            label(activity.getString(R.string.action)).apply {
                setPadding(0, dp(10), 0, 0)
            }
        )

        val actions = RoutineInputAction.entries
        val actionSpinner = Spinner(activity)
        actionSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            actions.map(actionTitle)
        ).also {
            it.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }
        actionSpinner.setPopupBackgroundDrawable(rounded(surface))
        actionSpinner.setSelection(
            actions.indexOf(input.action).coerceAtLeast(0)
        )
        form.addView(actionSpinner, params())

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addButton(title: String, action: () -> Unit) {
            val button = Button(activity).apply {
                text = title
                isAllCaps = false
                setTextColor(white)
                textSize = 13f
                background = rounded(accent)
                stateListAnimator = null
                setOnClickListener { action() }
            }
            buttons.addView(
                button,
                LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
            )
        }

        addButton(activity.getString(R.string.delete), onDelete)
        addButton(activity.getString(R.string.cancel), onCancel)
        addButton(activity.getString(R.string.save)) {
            val value = name.text.toString().trim()
            if (value.isEmpty()) {
                name.error = activity.getString(R.string.enter_name)
                return@addButton
            }

            val routine = options[routineSpinner.selectedItemPosition]
            val action = actions[actionSpinner.selectedItemPosition]

            onSave(
                input.copy(
                    label = value,
                    routineUuid = routine.uuid,
                    routineName = routine.name,
                    action = action
                )
            )
        }

        form.addView(buttons, params())
        return form
    }
}
