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
    onBrowserCatalog: () -> Unit,
) {
    val lists by filters.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    val status by filters.status.collectAsStateWithLifecycle()
    val saved by settings.customRules.collectAsStateWithLifecycle(initialValue = "")
    var custom by rememberSaveable { mutableStateOf<String?>(null) }
    var dnsUrl by rememberSaveable { mutableStateOf("") }
    var browserUrl by rememberSaveable { mutableStateOf("") }
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
        // DNS lists block domains for every app; browser lists only filter decrypted HTTPS.
        listOf(false, true).forEach { browser ->
            val section = if (browser) "browser" else "dns"
            item(key = "$section-title", contentType = "title") {
                AppSectionTitle(if (browser) "Bộ lọc trình duyệt" else "Danh sách DNS")
            }
            item(key = "$section-hint", contentType = "hint") {
                AppHint(
                    if (browser)
                        "Chặn quảng cáo, ẩn phần tử như tiện ích trình duyệt. Chỉ áp dụng cho trình duyệt đã bật lọc HTTPS, không chặn DNS."
                    else "Chặn tên miền cho mọi ứng dụng qua DNS."
                )
            }
            // Browser cosmetic lists must be discoverable before their first download as well.
            items(
                lists.filter { it.browser == browser },
                key = { it.id },
                contentType = { "filter" },
            ) { list ->
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
            item(key = "$section-catalog", contentType = "card") {
                AppPreferenceGroup {
                    if (browser)
                        AppArrowPreference(
                            "Chọn bộ lọc trình duyệt",
                            "Bộ lọc của tiện ích AdGuard",
                            onBrowserCatalog,
                        )
                    else
                        AppArrowPreference(
                            "Chọn danh sách DNS",
                            "Danh sách của AdGuard Home",
                            onCatalog,
                        )
                }
            }
            item(key = "$section-add", contentType = "card") {
                AppCard {
                    AppTextInput(
                        value = if (browser) browserUrl else dnsUrl,
                        label = if (browser) "URL bộ lọc trình duyệt" else "URL danh sách DNS",
                        onChange = { if (browser) browserUrl = it else dnsUrl = it },
                    )
                    AppAction("Thêm", primary = true) {
                        action {
                            filters.addUrl(if (browser) browserUrl else dnsUrl, browser = browser)
                            if (browser) browserUrl = "" else dnsUrl = ""
                        }
                    }
                }
            }
        }
        item(key = "filter-update", contentType = "card") {
            AppPreferenceGroup {
                AppArrowPreference("Cập nhật tất cả") {
                    action { filters.updateAll() }
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
