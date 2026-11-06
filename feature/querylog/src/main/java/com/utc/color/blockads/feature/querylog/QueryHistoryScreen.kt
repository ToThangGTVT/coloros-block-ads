package com.utc.color.blockads.feature.querylog

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.utc.color.blockads.core.common.InstalledApp
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.data.DEFAULT_REQUEST_CAPTURE_MINUTES
import com.utc.color.blockads.core.data.MAX_REQUEST_CAPTURE_MINUTES
import com.utc.color.blockads.core.data.QueryEntity
import com.utc.color.blockads.core.data.QueryLogRepository
import com.utc.color.blockads.core.data.RequestPreview
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.FormSheet
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.button
import com.utc.color.blockads.core.designsystem.choiceSheet
import com.utc.color.blockads.core.designsystem.dp
import com.utc.color.blockads.core.designsystem.hint
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.input
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.core.filter.FilterRepository
import com.utc.color.blockads.feature.querylog.databinding.RowQueryBinding
import com.utc.color.blockads.feature.querylog.databinding.ScreenQueryHistoryBinding
import com.utc.color.blockads.feature.querylog.databinding.SheetQueryDetailBinding
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import com.utc.color.blockads.core.common.R as Strings

@OptIn(ExperimentalCoroutinesApi::class)
internal class QueryHistoryScreen private constructor(
    activity: ComponentActivity,
    private val views: ScreenQueryHistoryBinding,
    state: Bundle,
    private val queries: QueryLogRepository,
    private val filters: FilterRepository,
    private val settings: SettingsRepository,
    private val apps: AppsRepository,
    private val appOnly: Boolean,
    private val fixedUid: Int?,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, queries: QueryLogRepository,
        filters: FilterRepository, settings: SettingsRepository, apps: AppsRepository,
        appOnly: Boolean = false, fixedUid: Int? = null,
    ) : this(
        activity, ScreenQueryHistoryBinding.inflate(parent.inflater, parent, false), state, queries,
        filters, settings, apps, appOnly, fixedUid,
    )

    private val locale = resources.configuration.locales[0]
    private val time = if (appOnly) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale)
    else DateFormat.getTimeInstance(DateFormat.DEFAULT, locale)
    private val manager = LinearLayoutManager(context)
    private val statusOptions = listOf(Strings.string.all, Strings.string.blocked, Strings.string.allowed).map(context::getString)
    private val selection = MutableStateFlow(Selection(state.getInt("status"), state.getInt("uid", -1)))
    private var installed = apps.apps.value
    private var appsByUid = installed.associateBy { it.uid }
    private var preferredMinutes = DEFAULT_REQUEST_CAPTURE_MINUTES
    private var captureTimer: Job? = null
    private var lastHead: QueryEntity? = null
    private var resetPosition = false
    private var newRows = emptyMap<Long, Long>()

    private val adapter = object : PagingDataAdapter<QueryEntity, QueryHolder>(QueryDiff) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            QueryHolder(RowQueryBinding.inflate(parent.inflater, parent, false))

        override fun onBindViewHolder(holder: QueryHolder, position: Int) = bindRow(holder, getItem(position))

        override fun onViewRecycled(holder: QueryHolder) {
            holder.icon?.cancel()
            holder.itemView.animate().cancel()
        }
    }

    init {
        views.captureControls.isVisible = fixedUid != null
        views.appFilter.root.isVisible = !appOnly
        views.queryList.layoutManager = manager
        views.queryList.adapter = adapter
        views.queryList.itemAnimator = null
        views.queryList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateBackToTop()
        })
        views.backToTop.setOnClickListener { manager.scrollToPositionWithOffset(0, 0) }
        adapter.addOnPagesUpdatedListener(::onPagesUpdated)
        renderFilters()
        observe(apps.apps) {
            if (installed != it) {
                installed = it
                appsByUid = it.associateBy { app -> app.uid }
                renderFilters()
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }
        observeLatest(selection.flatMapLatest { (status, uid) ->
            val blocked = listOf(-1, 1, 0)[status]
            if (appOnly) queries.appPages(fixedUid, blocked) else queries.searchPages("", blocked, uid)
        }, adapter::submitData)
        observe(adapter.loadStateFlow) {
            val label = when {
                adapter.itemCount > 0 -> null
                it.refresh is LoadState.Loading -> context.getString(Strings.string.requests_loading)
                else -> context.getString(if (appOnly) Strings.string.app_requests_empty else Strings.string.logs_empty)
            }
            val failure = it.refresh as? LoadState.Error
            views.message.showMessage(if (adapter.itemCount == 0 && failure != null) {
                failure.error.userMessage(resources) ?: context.getString(Strings.string.action_failed)
            } else label)
        }
        if (fixedUid != null) {
            renderCapture()
            observe(settings.requestCaptureMinutes) {
                preferredMinutes = it
                renderCapture()
            }
        }
    }

    private fun updateBackToTop() {
        views.backToTop.isVisible = views.queryList.canScrollVertically(-1)
    }

    private fun onPagesUpdated() {
        val snapshot = adapter.snapshot()
        val head = snapshot.items.firstOrNull()?.takeIf { snapshot.placeholdersBefore == 0 }
        val previous = lastHead
        val first = manager.getChildAt(0)
        val holder = first?.let { views.queryList.getChildViewHolder(it) as? QueryHolder }
        if (resetPosition) {
            resetPosition = false
            manager.scrollToPositionWithOffset(0, 0)
        } else if (previous != null && head != null && head.id != previous.id && holder != null) {
            val offset = manager.getDecoratedTop(first) - views.queryList.paddingTop
            if (holder.query?.id == previous.id && offset >= 0) {
                val arrivals = snapshot.items.takeWhile { RequestOrder.compare(it, previous) > 0 }
                newRows = if (arrivals.size <= 6) arrivals.mapIndexed { index, row ->
                    row.id to ((arrivals.lastIndex - index) * 70L)
                }.toMap() else emptyMap()
                manager.scrollToPositionWithOffset(0, 0)
                views.queryList.doOnNextLayout { newRows = emptyMap() }
            } else {
                val index = snapshot.items.indexOfFirst { it.id == holder.query?.id }
                if (index >= 0) manager.scrollToPositionWithOffset(snapshot.placeholdersBefore + index, offset)
            }
        }
        lastHead = head
        views.queryList.post(::updateBackToTop)
    }

    private fun renderFilters() {
        val (status, uid) = selection.value
        val title = context.getString(Strings.string.status)
        views.statusFilter.root.bind(title, statusOptions[status], trailingIcon = PreferenceIcon.Dropdown) {
            show(choiceSheet(context, title, statusOptions, status) { select(selection.value.copy(status = it)) })
        }
        if (appOnly) return
        val options = installed.distinctBy { it.uid }
        val selectedApp = options.indexOfFirst { it.uid == uid } + 1
        val names = listOf(context.getString(Strings.string.all)) + options.map { it.label }
        val appTitle = context.getString(Strings.string.apps)
        views.appFilter.root.bind(appTitle, names[selectedApp], trailingIcon = PreferenceIcon.Dropdown) {
            show(choiceSheet(context, appTitle, names, selectedApp) {
                select(selection.value.copy(uid = if (it == 0) -1 else options[it - 1].uid))
            })
        }
    }

    private fun select(value: Selection) {
        if (value == selection.value) return
        resetPosition = true
        lastHead = null
        newRows = emptyMap()
        selection.value = value
        renderFilters()
    }

    private fun bindRow(holder: QueryHolder, row: QueryEntity?) {
        holder.query = row
        holder.icon?.cancel()
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
        val binding = holder.views
        binding.root.visibility = if (row == null) View.INVISIBLE else View.VISIBLE
        if (row == null) return
        val app = appsByUid[row.uid]
        binding.identity.root.bind(row.domain, app?.label ?: fallbackName(row.uid)) { openDetails(row) }
        binding.identity.root.showIcon(app?.let { apps.cachedIcon(it.packageName) })
        if (app != null) holder.icon = scope.launch {
            binding.identity.root.showIcon(apps.icon(app.packageName))
        }
        binding.details.text = "${context.getString(row.statusLabel)} · ${time.format(Date(row.timestamp))} · ${row.protocol.uppercase()}"
        binding.details.setOnClickListener { openDetails(row) }
        newRows[row.id]?.let { delay ->
            newRows = newRows - row.id
            holder.itemView.alpha = 0f
            holder.itemView.translationY = -context.dp(12).toFloat()
            holder.itemView.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(180).start()
        }
    }

    private fun fallbackName(uid: Int?) = uid?.let { "UID $it" } ?: context.getString(Strings.string.unknown)

    private fun openDetails(row: QueryEntity) {
        val sheet = FormSheet(context, row.domain)
        val detail = SheetQueryDetailBinding.inflate(sheet.content.inflater, sheet.content, true)
        detail.status.setText(row.statusLabel)
        detail.matchedRule.text = context.getString(
            Strings.string.matched_rule, row.matchedRule ?: context.getString(Strings.string.no_matching_rule),
        )
        val owner = installed.filter { it.uid == row.uid }.joinToString { it.label }.ifBlank { fallbackName(row.uid) }
        detail.app.text = context.getString(Strings.string.app_prefix) + owner + " · " + row.protocol.uppercase()
        detail.timestamp.showMessage(time.format(Date(row.timestamp)).takeIf { appOnly })
        if (appOnly) {
            renderPreview(detail.preview, null)
            // Clearing the capture also clears this repository flow and removes the preview.
            if (captureTimer != null) {
                val preview = observe(queries.requestPreview(row.id)) { renderPreview(detail.preview, it) }
                sheet.dialog.setOnDismissListener { preview.cancel() }
            }
        }
        fun save(prefix: String) {
            detail.block.isEnabled = false
            detail.allow.isEnabled = false
            action(
                success = { sheet.dialog.dismiss() },
                failure = {
                    views.error.showMessage(it.userMessage(resources) ?: context.getString(Strings.string.action_failed))
                    detail.block.isEnabled = true
                    detail.allow.isEnabled = true
                    sheet.dialog.dismiss()
                },
            ) { filters.saveCustom(settings.customRules.first() + "\n$prefix||${row.domain}^") }
        }
        detail.allow.setOnClickListener { save("@@") }
        detail.block.setOnClickListener { save("") }
        show(sheet.dialog)
    }

    private fun renderPreview(content: ViewGroup, preview: RequestPreview?) {
        content.removeAllViews()
        if (preview == null) {
            content.hint(context.getString(Strings.string.request_preview_unavailable))
            return
        }
        content.hint("${preview.method} ${preview.path}").setTextIsSelectable(true)
        content.hint(context.getString(Strings.string.request_query_parameters))
        content.hint(preview.query?.takeIf(String::isNotEmpty)?.replace('&', '\n')
            ?: context.getString(Strings.string.request_no_query_parameters)).setTextIsSelectable(true)
        if (preview.urlTruncated) content.hint(context.getString(Strings.string.request_url_truncated))
        content.hint(context.getString(Strings.string.request_body))
        content.hint(preview.body ?: context.getString(Strings.string.request_body_unavailable)).setTextIsSelectable(true)
        if (preview.body != null && preview.bodyTruncated) content.hint(context.getString(Strings.string.request_body_truncated))
    }

    private fun renderCapture() {
        val uid = fixedUid ?: return
        val capturing = captureTimer != null
        views.capture.root.bind(context.getString(Strings.string.request_capture),
            context.getString(Strings.string.request_capture_hint), checked = capturing,
        ) {
            if (capturing) stopCapture() else startCapture(uid)
            renderCapture()
        }
        val title = context.getString(if (capturing) Strings.string.request_capture_next_duration else Strings.string.request_capture_duration)
        views.captureDuration.root.bind(
            title, resources.getQuantityString(Strings.plurals.request_capture_minutes, preferredMinutes, preferredMinutes), trailingIcon = PreferenceIcon.Arrow,
        ) { editDuration(title) }
    }

    private fun startCapture(uid: Int) {
        val duration = preferredMinutes
        queries.setRequestCapture(uid, duration)
        captureTimer = scope.launch {
            delay(duration * 60_000L)
            captureTimer = null
            queries.setRequestCapture(null)
            renderCapture()
        }
    }

    private fun stopCapture() {
        captureTimer?.let {
            it.cancel()
            captureTimer = null
            queries.setRequestCapture(null)
        }
    }

    private fun editDuration(title: String) {
        val sheet = FormSheet(context, title)
        val input = sheet.content.input(
            context.getString(Strings.string.request_capture_minute_range, MAX_REQUEST_CAPTURE_MINUTES),
            preferredMinutes.toString(), InputType.TYPE_CLASS_NUMBER,
        )
        input.input.doAfterTextChanged { input.root.error = null }
        val save = sheet.content.button(context.getString(Strings.string.save))
        save.setOnClickListener {
            val minutes = input.input.text.toString().trim().toIntOrNull()
            if (minutes == null || minutes !in 1..MAX_REQUEST_CAPTURE_MINUTES) {
                input.root.error = context.getString(Strings.string.request_capture_invalid_minutes, MAX_REQUEST_CAPTURE_MINUTES)
                return@setOnClickListener
            }
            sheet.setBusy(true)
            save.isEnabled = false
            action(
                success = { sheet.dialog.dismiss() },
                failure = {
                    input.root.error = it.userMessage(resources) ?: context.getString(Strings.string.action_failed)
                    save.isEnabled = true
                    sheet.setBusy(false)
                },
            ) { settings.setRequestCaptureMinutes(minutes) }
        }
        show(sheet.dialog)
    }

    override fun saveState() = super.saveState().apply {
        putInt("status", selection.value.status)
        putInt("uid", selection.value.uid)
    }

    override fun close() {
        stopCapture()
        super.close()
    }

    private data class Selection(val status: Int, val uid: Int)
}

private val RequestOrder = compareBy(QueryEntity::timestamp, QueryEntity::id)
private val QueryEntity.statusLabel get() = if (blocked) Strings.string.blocked else Strings.string.allowed

private class QueryHolder(val views: RowQueryBinding) : RecyclerView.ViewHolder(views.root) {
    var icon: Job? = null
    var query: QueryEntity? = null
}

private object QueryDiff : DiffUtil.ItemCallback<QueryEntity>() {
    override fun areItemsTheSame(oldItem: QueryEntity, newItem: QueryEntity) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: QueryEntity, newItem: QueryEntity) = oldItem == newItem
}
