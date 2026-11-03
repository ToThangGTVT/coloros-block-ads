package com.utc.color.blockads.feature.filters

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.launch

@Composable
fun FiltersScreen(filters: FilterRepository, settings: SettingsRepository) {
    val lists by filters.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by filters.status.collectAsStateWithLifecycle()
    val saved by settings.customRules.collectAsStateWithLifecycle(initialValue = "")
    var custom by rememberSaveable { mutableStateOf<String?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun action(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onSuccess { error = null }.onFailure { error = it.message }
        }
    }
    AppColumn {
        AppCard {
            AppText("${status.loaded} rule đang hoạt động", prominent = true)
            AppText(
                if (status.loading) "Đang tải hoặc biên dịch…"
                else "Bỏ qua ${status.ignored} rule không hỗ trợ"
            )
            (error ?: status.error)?.let { AppText(it) }
        }
        lists.forEach { list ->
            AppPreferenceGroup {
                AppSwitchPreference(
                    title = list.name,
                    summary =
                        "${list.ruleCount} rule · " +
                            if (list.updatedAt == 0L) "Chưa tải"
                            else
                                java.text.DateFormat.getDateTimeInstance()
                                    .format(java.util.Date(list.updatedAt)),
                    checked = list.enabled,
                    onCheckedChange = { action { filters.setEnabled(list.id, it) } },
                )
                list.error?.let { AppText(it) }
                AppAction("Tải / cập nhật") { action { filters.download(list.id) } }
            }
        }
        AppCard {
            AppTextInput(value = url, label = "URL filter HTTPS", onChange = { url = it })
            AppAction("Thêm danh sách") {
                action {
                    filters.addUrl(url)
                    url = ""
                }
            }
        }
        AppCard {
            AppText("Quy tắc riêng", prominent = true)
            AppText(
                "Hosts hoặc ||domain^ để chặn; @@||domain^ để cho phép. Cosmetic rule không áp dụng cho DNS."
            )
            AppTextInput(
                value = custom ?: saved,
                label = "Mỗi dòng một rule",
                multiline = true,
                error = error != null,
                onChange = { custom = it },
            )
            AppAction("Kiểm tra và lưu") {
                action {
                    filters.saveCustom(custom ?: saved)
                    custom = null
                }
            }
        }
    }
}
