package com.utc.color.blockads.core.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.utc.color.blockads.core.designsystem.databinding.FormInputBinding
import com.utc.color.blockads.core.designsystem.databinding.SheetChoiceRowBinding
import com.utc.color.blockads.core.designsystem.databinding.SheetChoicesBinding
import com.utc.color.blockads.core.designsystem.databinding.SheetFormBinding
import kotlin.math.roundToInt

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

fun Context.color(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr, 0)

val ViewGroup.inflater: LayoutInflater get() = LayoutInflater.from(context)

fun ViewGroup.card(): LinearLayout =
    (inflater.inflate(R.layout.card_column, this, false) as LinearLayout).also(::addView)

fun ViewGroup.section(text: CharSequence): TextView =
    (inflater.inflate(R.layout.section_label, this, false) as TextView).also {
        it.text = text
        addView(it)
    }

fun ViewGroup.hint(text: CharSequence): TextView =
    (inflater.inflate(R.layout.hint_text, this, false) as TextView).also {
        it.text = text
        addView(it)
    }

fun ViewGroup.preference(): PreferenceRow =
    (inflater.inflate(R.layout.preference_row, this, false) as PreferenceRow).also(::addView)

fun ViewGroup.button(text: CharSequence, click: (() -> Unit)? = null): Button =
    (inflater.inflate(R.layout.action_button, this, false) as Button).also {
        it.text = text
        if (click != null) it.setOnClickListener { click() }
        addView(it)
    }

fun ViewGroup.input(hint: CharSequence, value: CharSequence, type: Int): FormInputBinding =
    FormInputBinding.inflate(inflater, this, true).also {
        it.root.hint = hint
        it.input.inputType = type
        it.input.setText(value)
    }

fun TextView.showMessage(message: CharSequence?) {
    text = message
    isVisible = !message.isNullOrEmpty()
}

/** COUI panel holding a scrolling column of form rows. */
class FormSheet(context: Context, title: CharSequence) {
    val dialog = CouiBottomSheet(context, title)
    private val views = SheetFormBinding.inflate(LayoutInflater.from(dialog.context))
    val content get() = views.content

    init {
        dialog.setContentView(views.root)
    }

    fun setBusy(busy: Boolean) {
        dialog.allowDismiss = !busy
    }
}

/** COUI ListPreference panel: single choice rows with a reserved check slot, then Cancel. */
fun choiceSheet(
    context: Context,
    title: CharSequence,
    entries: List<String>,
    selected: Int,
    summary: (Int) -> String? = { null },
    onSelect: (Int) -> Unit,
): CouiBottomSheet {
    val dialog = CouiBottomSheet(context, title)
    val views = SheetChoicesBinding.inflate(LayoutInflater.from(dialog.context))
    views.choiceList.layoutManager = LinearLayoutManager(dialog.context)
    val checkTint = ColorStateList.valueOf(dialog.context.color(androidx.appcompat.R.attr.colorPrimary))
    views.choiceList.adapter = object : RecyclerView.Adapter<ChoiceHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ChoiceHolder(SheetChoiceRowBinding.inflate(parent.inflater, parent, false))

        override fun onBindViewHolder(holder: ChoiceHolder, position: Int) {
            val chosen = position == selected
            holder.views.choiceTitle.text = entries[position]
            holder.views.choiceSummary.showMessage(summary(position))
            holder.views.choiceCheck.imageTintList = checkTint
            holder.views.choiceCheck.visibility = if (chosen) View.VISIBLE else View.INVISIBLE
            holder.views.root.isSelected = chosen
            holder.views.root.setOnClickListener {
                dialog.dismiss()
                onSelect(position)
            }
        }
    }
    views.cancel.setOnClickListener { dialog.dismiss() }
    dialog.setContentView(views.root)
    return dialog
}

private class ChoiceHolder(val views: SheetChoiceRowBinding) : RecyclerView.ViewHolder(views.root) {
    init {
        views.root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = host.isSelected
            }
        }
    }
}

class RowAdapter<T : Any, H : RecyclerView.ViewHolder>(
    key: (T) -> Any?,
    private val create: (ViewGroup) -> H,
    private val bind: (H, T) -> Unit,
    private val recycle: (H) -> Unit = {},
) : ListAdapter<T, H>(object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = key(oldItem) == key(newItem)
    override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
}) {
    init {
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = create(parent)
    override fun onBindViewHolder(holder: H, position: Int) = bind(holder, getItem(position))
    override fun onViewRecycled(holder: H) = recycle(holder)
}
