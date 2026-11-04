package com.utc.color.blockads.feature.filters

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.launch

@Composable
fun FiltersScreen(
    filters: FilterRepository,
    settings: SettingsRepository,
    onCatalog: () -> Unit,
) {
    val lists by filters.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by filters.status.collectAsStateWithLifecycle()
    val saved by settings.customRules.collectAsStateWithLifecycle(initialValue = "")
    var custom by rememberSaveable { mutableStateOf<String?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance() }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onSuccess { error = null }.onFailure { error = it.message }
        }
    }
    AppLazyList {
        item(key = "filter-status", contentType = "card") {
            AppCard {
                AppText("${status.loaded} rule", prominent = true)
                AppHint(
                    if (status.loading) "Đang tải…"
                    else "${status.ignored} rule không hỗ trợ đã bỏ qua"
                )
                (error ?: status.error)?.let { AppText(it) }
            }
        }
        item(key = "filter-title", contentType = "title") { AppSectionTitle("Danh sách lọc") }
        // Browser cosmetic lists must be discoverable before their first download as well.
        items(lists, key = { it.id }, contentType = { "filter" }) { list ->
            AppPreferenceGroup {
                AppSwitchPreference(
                    title = list.name,
                    summary =
                        list.error
                            ?: ("${list.ruleCount} rule · " +
                                if (list.updatedAt == 0L) "Chưa tải"
                                else dateFormat.format(java.util.Date(list.updatedAt))),
                    checked = list.enabled,
                    onCheckedChange = { action { filters.setEnabled(list.id, it) } },
                )
            }
        }
        item(key = "filter-actions", contentType = "card") {
            AppPreferenceGroup {
                AppArrowPreference("Chọn danh sách chặn", "Danh sách của AdGuard Home", onCatalog)
                AppArrowPreference("Cập nhật tất cả") {
                    action { lists.filter { it.enabled }.forEach { filters.download(it.id) } }
                }
            }
        }
        item(key = "add-filter", contentType = "card") {
            AppCard {
                AppTextInput(value = url, label = "URL danh sách lọc", onChange = { url = it })
                AppAction("Thêm danh sách", primary = true) {
                    action {
                        filters.addUrl(url)
                        url = ""
                    }
                }
            }
        }
        item(key = "custom-title", contentType = "title") { AppSectionTitle("Quy tắc riêng") }
        item(key = "custom-editor", contentType = "card") {
            AppCard {
                AppHint("||domain^ để chặn · @@||domain^ để cho phép")
                AppHint("example.com##.banner để ẩn phần tử · example.com#@#.banner để bỏ ẩn")
                AppHint("Ẩn phần tử cần bật lọc HTTPS và chọn trình duyệt trong danh sách ứng dụng.")
                AppTextInput(
                    value = custom ?: saved,
                    label = "Mỗi dòng một rule",
                    multiline = true,
                    error = error != null,
                    onChange = { custom = it },
                )
                AppAction("Lưu", primary = true) {
                    action {
                        filters.saveCustom(custom ?: saved)
                        custom = null
                    }
                }
            }
        }
    }
}
