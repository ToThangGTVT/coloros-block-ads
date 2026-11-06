package com.utc.color.blockads.feature.filters

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.utc.color.blockads.core.common.FilterList
import com.utc.color.blockads.core.common.userMessage
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.FormSheet
import com.utc.color.blockads.core.designsystem.PreferenceIcon
import com.utc.color.blockads.core.designsystem.Screen
import com.utc.color.blockads.core.designsystem.button
import com.utc.color.blockads.core.designsystem.card
import com.utc.color.blockads.core.designsystem.inflater
import com.utc.color.blockads.core.designsystem.input
import com.utc.color.blockads.core.designsystem.preference
import com.utc.color.blockads.core.designsystem.showMessage
import com.utc.color.blockads.core.filter.FilterRepository
import com.utc.color.blockads.feature.filters.databinding.ScreenFiltersBinding
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import com.utc.color.blockads.core.common.R as Strings

class FiltersScreen private constructor(
    activity: ComponentActivity,
    private val views: ScreenFiltersBinding,
    state: Bundle,
    private val filters: FilterRepository,
    settings: SettingsRepository,
    private val onCatalog: (Boolean) -> Unit,
) : Screen(activity, views.root) {
    constructor(
        activity: ComponentActivity, parent: ViewGroup, state: Bundle, filters: FilterRepository,
        settings: SettingsRepository, onCatalog: (Boolean) -> Unit,
    ) : this(activity, ScreenFiltersBinding.inflate(parent.inflater, parent, false), state, filters, settings, onCatalog)

    private val locale = resources.configuration.locales[0]
    private val date = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, locale)
    private val number = NumberFormat.getIntegerInstance(locale)
    private var selected = state.getInt("tab")
    private val scrollPositions = state.getIntArray("scrolls") ?: IntArray(2)
    private val urlDrafts = state.getStringArray("urls") ?: arrayOf("", "")
    private var customDraft: String? = state.getString("rulesDraft")
    private var editor = state.getString("editor")?.let(Editor::valueOf)
    private var activeInput: EditText? = null
    private var savedRules = ""
    private var lists = emptyList<FilterList>()
    private var status = filters.status.value
    private var error: String? = null
    private var initialScrollRestored = false

    init {
        views.filterTabs.bind(listOf(context.getString(Strings.string.dns_filters), context.getString(Strings.string.browser_filters)), selected) {
            if (selected == it) return@bind
            scrollPositions[selected] = views.pageList.scrollY
            selected = it
            render()
            restoreScroll()
        }
        views.customRules.root.bind(
            context.getString(Strings.string.custom_rules), context.getString(Strings.string.custom_rules_shared), trailingIcon = PreferenceIcon.Arrow,
        ) { editRules() }
        observe(filters.lists) { lists = it; render(); restoreInitialScroll() }
        observe(filters.status) { status = it; renderStatus() }
        observe(settings.customRules) {
            savedRules = it
            if (activeInput == null) when (editor) {
                Editor.Dns -> addUrl(false)
                Editor.Browser -> addUrl(true)
                Editor.Rules -> editRules()
                null -> Unit
            }
        }
        render()
    }

    private fun restoreInitialScroll() {
        if (!initialScrollRestored) {
            initialScrollRestored = true
            restoreScroll()
        }
    }

    private fun restoreScroll() {
        val tab = selected
        views.pageList.post { if (tab == selected) views.pageList.scrollTo(0, scrollPositions[tab]) }
    }

    private fun render() {
        val browser = selected == 1
        val visible = lists.filter { it.browser == browser }
        views.overview.root.bind(
            context.getString(if (browser) Strings.string.web_filtering else Strings.string.domain_blocking),
            context.getString(if (browser) Strings.string.browser_https_required else Strings.string.all_apps_dns_hint),
            trailingIcon = PreferenceIcon.Info,
        ) {
            val title = if (browser) Strings.string.browser_filters_help_label else Strings.string.dns_filters_help_label
            val description = if (browser) Strings.string.browser_filters_help else Strings.string.dns_filters_help
            show(MaterialAlertDialogBuilder(context).setTitle(title).setMessage(description)
                .setPositiveButton(android.R.string.ok, null).create())
        }
        views.listCount.text = resources.getQuantityString(
            Strings.plurals.lists_enabled, visible.size, visible.count { it.enabled }, visible.size,
        )
        views.catalog.root.bind(
            context.getString(Strings.string.choose_from_catalog),
            context.getString(if (browser) Strings.string.adguard_browser_filters else Strings.string.adguard_dns_lists),
            trailingIcon = PreferenceIcon.Arrow,
        ) { onCatalog(browser) }
        views.addUrl.root.bind(context.getString(Strings.string.add_by_url),
            context.getString(Strings.string.filter_url_hint), trailingIcon = PreferenceIcon.Arrow) { addUrl(browser) }
        views.empty.isVisible = visible.isEmpty()
        views.added.removeAllViews()
        visible.forEach { list ->
            val summary = list.error ?: resources.getQuantityString(
                Strings.plurals.filter_rules_prefix, list.ruleCount, number.format(list.ruleCount),
            ) + if (list.updatedAt == 0L) context.getString(Strings.string.not_downloaded) else date.format(Date(list.updatedAt))
            views.added.card().preference().bind(list.name, summary, checked = list.enabled) {
                perform { filters.setEnabled(list.id, !list.enabled) }
            }
        }
        renderStatus()
    }

    private fun renderStatus() {
        views.loading.isVisible = status.loading
        views.error.showMessage(error ?: status.error)
        views.updateAll.root.bind(
            context.getString(Strings.string.update_all),
            resources.getQuantityString(Strings.plurals.loaded_rules_summary, status.loaded, number.format(status.loaded)),
            trailingIcon = PreferenceIcon.Arrow, enabled = !status.loading,
        ) { perform { filters.updateAll() } }
        views.unsupported.showMessage(status.ignored.takeIf { it > 0 }?.let {
            resources.getQuantityString(Strings.plurals.unsupported_rules_ignored, it, number.format(it))
        })
    }

    private fun perform(block: suspend () -> Unit) {
        action({ error = null; renderStatus() }, {
            error = it.userMessage(resources) ?: context.getString(Strings.string.operation_failed)
            renderStatus()
        }, block)
    }

    private fun addUrl(browser: Boolean) {
        val tab = if (browser) 1 else 0
        showEditor(
            target = if (browser) Editor.Browser else Editor.Dns,
            title = if (browser) Strings.string.add_browser_filter else Strings.string.add_dns_filter,
            hint = Strings.string.filter_url,
            initial = urlDrafts[tab],
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            save = Strings.string.add_filter,
            pending = Strings.string.adding,
            fallback = Strings.string.filter_add_failed,
            remember = { urlDrafts[tab] = it.orEmpty() },
        ) { filters.addUrl(it, browser = browser) }
    }

    private fun editRules() {
        showEditor(
            target = Editor.Rules,
            title = Strings.string.custom_rules,
            hint = Strings.string.one_rule_per_line,
            initial = customDraft ?: savedRules,
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            save = Strings.string.save_rules,
            pending = Strings.string.saving,
            fallback = Strings.string.rules_save_failed,
            remember = { customDraft = it },
            help = Strings.string.custom_rules_help,
        ) { filters.saveCustom(it) }
    }

    private fun showEditor(
        target: Editor, title: Int, hint: Int, initial: String, type: Int, save: Int, pending: Int, fallback: Int,
        remember: (String?) -> Unit, help: Int? = null, submit: suspend (String) -> Unit,
    ) {
        val sheet = FormSheet(context, context.getString(title))
        help?.let {
            sheet.content.preference().bind(
                context.getString(Strings.string.custom_rules_help_label),
                context.getString(it),
                trailingIcon = PreferenceIcon.Info,
            )
        }
        val input = sheet.content.input(context.getString(hint), initial, type)
        editor = target
        activeInput = input.input
        if (help != null) {
            input.input.minLines = 3
            input.input.maxLines = 8
        }
        var saved = false
        val button = sheet.content.button(context.getString(save))
        button.setOnClickListener {
            val value = input.input.text.toString()
            sheet.setBusy(true)
            input.input.isEnabled = false
            button.isEnabled = false
            button.setText(pending)
            action(
                success = { saved = true; remember(null); sheet.dialog.dismiss() },
                failure = {
                    sheet.setBusy(false)
                    input.input.isEnabled = true
                    input.root.error = it.userMessage(resources) ?: context.getString(fallback)
                    button.setText(save)
                    button.isEnabled = true
                },
            ) { submit(value) }
        }
        sheet.dialog.setOnDismissListener {
            if (!saved) remember(input.input.text.toString())
            activeInput = null
            editor = null
        }
        show(sheet.dialog)
    }

    override fun saveState() = super.saveState().apply {
        scrollPositions[selected] = views.pageList.scrollY
        putInt("tab", selected)
        putIntArray("scrolls", scrollPositions.copyOf())
        val urls = urlDrafts.copyOf()
        val draft = activeInput?.text?.toString()
        if (draft != null) when (editor) {
            Editor.Dns -> urls[0] = draft
            Editor.Browser -> urls[1] = draft
            else -> Unit
        }
        putString("editor", editor?.name)
        putStringArray("urls", urls)
        val rules = if (editor == Editor.Rules) draft ?: customDraft else customDraft
        // The persisted rules can be up to 1 MB. Keep the saved-state parcel small.
        putString("rulesDraft", rules?.takeIf { it.length <= 64_000 })
    }

    private enum class Editor { Dns, Browser, Rules }
}
