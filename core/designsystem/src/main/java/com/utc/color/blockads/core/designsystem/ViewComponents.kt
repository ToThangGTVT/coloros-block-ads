package com.utc.color.blockads.core.designsystem

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.utc.color.blockads.core.designsystem.databinding.FormInputBinding
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

class FormSheet(context: Context, title: CharSequence) {
    val dialog = BottomSheetDialog(context)
    private val views = SheetFormBinding.inflate(LayoutInflater.from(dialog.context))
    val content get() = views.content

    init {
        views.title.text = title
        dialog.setContentView(views.root)
        dialog.behavior.maxHeight = (context.resources.displayMetrics.heightPixels * 0.88f).roundToInt()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun setBusy(busy: Boolean) {
        dialog.setCancelable(!busy)
        dialog.behavior.isDraggable = !busy
    }
}

fun choiceSheet(
    context: Context,
    title: CharSequence,
    entries: List<String>,
    selected: Int,
    summary: (Int) -> String? = { null },
    onSelect: (Int) -> Unit,
): BottomSheetDialog {
    val dialog = BottomSheetDialog(context)
    val views = SheetChoicesBinding.inflate(LayoutInflater.from(dialog.context))
    views.title.text = title
    views.choiceList.layoutManager = LinearLayoutManager(context)
    views.choiceList.adapter = object : RecyclerView.Adapter<ChoiceHolder>() {
        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ChoiceHolder(parent.inflater.inflate(R.layout.preference_row, parent, false) as PreferenceRow)

        override fun onBindViewHolder(holder: ChoiceHolder, position: Int) {
            holder.row.bind(
                entries[position], summary(position), trailingIcon = PreferenceIcon.Check.takeIf { position == selected },
            ) {
                dialog.dismiss()
                onSelect(position)
            }
            holder.row.isSelected = position == selected
            holder.row.backgroundTintList = ColorStateList.valueOf(
                if (position == selected) context.color(com.google.android.material.R.attr.colorSecondaryContainer)
                else android.graphics.Color.TRANSPARENT,
            )
            holder.row.setBackgroundResource(R.drawable.bg_card)
        }
    }
    views.cancel.setOnClickListener { dialog.dismiss() }
    dialog.setContentView(views.root)
    dialog.behavior.maxHeight = (context.resources.displayMetrics.heightPixels * 0.76f).roundToInt()
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    return dialog
}

private class ChoiceHolder(val row: PreferenceRow) : RecyclerView.ViewHolder(row)

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
