package com.routinebridge

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object RoutineInputCards {

    fun render(
        activity: Activity,
        container: LinearLayout,
        inputs: List<RoutineInput>,
        routines: List<ManualRoutine>,
        collapsedGroups: MutableSet<Long>,
        showInputCount: Boolean,
        emptyText: String,
        expandAllText: String,
        collapseAllText: String,
        actionTitle: (RoutineInputAction) -> String,
        onInputClick: (RoutineInput) -> Unit,
        onEditClick: (RoutineInput) -> Unit,
        expandedContent: (RoutineInput) -> View?
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val bg = Color.rgb(30, 30, 30)
        val variant = Color.rgb(25, 31, 45)
        val blue = Color.rgb(74, 144, 217)
        val white = Color.WHITE
        val secondary = Color.rgb(208, 214, 226)

        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

        fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }

        fun params() = LinearLayout.LayoutParams(
            -1, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        container.removeAllViews()

        if (inputs.isEmpty()) {
            container.addView(text(emptyText, 14f, secondary).apply {
                setPadding(dp(14), dp(18), dp(14), dp(18))
                background = rounded(variant, 12)
            }, params())
            return
        }

        val groups = inputs.groupBy { it.routineUuid }
        val allCollapsed = groups.keys.all { it in collapsedGroups }

        val expandButton = TextView(activity).apply {
            text = if (allCollapsed) expandAllText else collapseAllText
            textSize = 14f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(variant, 8)
            isClickable = true
            setOnClickListener {
                if (allCollapsed) collapsedGroups.clear()
                else collapsedGroups.addAll(groups.keys)
                render(
                    activity, container, inputs, routines, collapsedGroups,
                    showInputCount, emptyText, expandAllText, collapseAllText,
                    actionTitle, onInputClick, onEditClick, expandedContent
                )
            }
        }
        container.addView(expandButton, params().apply { bottomMargin = dp(8) })

        groups.forEach { (uuid, group) ->
            val groupBlock = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(variant, 12)
                isClickable = true
                isFocusable = true
            }

            header.addView(
                text(if (uuid in collapsedGroups) "›" else "⌄", 22f, blue, true)
                    .apply { setPadding(0, 0, dp(12), 0) }
            )

            val current = routines.firstOrNull { it.uuid == uuid }
            val name = current?.name ?: group.first().routineName
            val displayedName = if (current == null) {
                "$name" + activity.getString(R.string.routine_not_found_suffix)
            } else name

            header.addView(
                text(displayedName, 17f, white, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            if (showInputCount) {
                header.addView(text(
                    activity.resources.getQuantityString(
                        R.plurals.input_count, group.size, group.size
                    ), 12f, secondary
                ))
            }

            header.setOnClickListener {
                if (!collapsedGroups.add(uuid)) collapsedGroups.remove(uuid)
                render(
                    activity, container, inputs, routines, collapsedGroups,
                    showInputCount, emptyText, expandAllText, collapseAllText,
                    actionTitle, onInputClick, onEditClick, expandedContent
                )
            }
            groupBlock.addView(header, params())

            if (uuid !in collapsedGroups) {
                group.forEach { input ->
                    val card = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        background = rounded(bg, 12)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onInputClick(input) }
                    }

                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val details = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    details.addView(text(input.label, 16f, white, true))
                    details.addView(text(actionTitle(input.action), 13f, blue).apply {
                        setPadding(0, dp(4), 0, 0)
                    })
                    row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))

                    val edit = text("✎", 23f, blue, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(14), dp(4), dp(4), dp(4))
                        contentDescription = activity.getString(R.string.edit_input)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            it.isPressed = false
                            onEditClick(input)
                        }
                    }
                    row.addView(edit, LinearLayout.LayoutParams(-2, dp(44)))
                    card.addView(row, params())

                    expandedContent(input)?.let { card.addView(it, params()) }

                    groupBlock.addView(card, params().apply {
                        topMargin = dp(6)
                    })
                }
            }

            container.addView(groupBlock, params().apply {
                topMargin = dp(10)
            })
        }
    }
}
