package com.utc.color.blockads.feature.filters

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.CancellationException
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val states = rememberSaveableStateHolder()
    val browser = tab == 1
    val visibleLists = remember(lists, browser) { lists.filter { it.browser == browser } }
    var custom by rememberSaveable { mutableStateOf<String?>(null) }
    var dnsUrl by rememberSaveable { mutableStateOf("") }
    var browserUrl by rememberSaveable { mutableStateOf("") }
    // Capture the target type when opening the sheet, independent of the selected tab.
    var addingBrowser by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var editingCustom by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetError by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance() }
    val numberFormat = remember { java.text.NumberFormat.getIntegerInstance() }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Không thực hiện được thao tác"
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = AppSpacing.page)) {
            AppContentTabs(listOf("Bộ lọc DNS", "Bộ lọc trình duyệt"), tab) { tab = it }
        }
        Box(Modifier.weight(1f)) {
            states.SaveableStateProvider(tab) {
                AppLazyList {
                    item(key = "overview", contentType = "card") {
                        AppCard {
                            AppColumn {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(AppSpacing.gap),
                                    ) {
                                        AppText(
                                            if (browser) "Lọc nội dung trang web"
                                            else "Chặn tên miền",
                                            prominent = true,
                                        )
                                        AppHint(
                                            if (browser) "Cần bật lọc HTTPS cho trình duyệt"
                                            else "Áp dụng cho mọi ứng dụng qua DNS"
                                        )
                                    }
                                    AppHelpTooltip(
                                        text =
                                            if (browser)
                                                "Chặn quảng cáo và ẩn phần tử trên trang web. Cần bật lọc HTTPS và chọn trình duyệt trong danh sách ứng dụng. Các bộ lọc này không chặn DNS."
                                            else
                                                "Chặn tên miền quảng cáo, theo dõi và mã độc cho các ứng dụng được lọc khi VPN BlockAds đang bật.",
                                        label =
                                            if (browser) "Hướng dẫn bộ lọc trình duyệt"
                                            else "Hướng dẫn bộ lọc DNS",
                                    )
                                }
                                AppText(
                                    "${visibleLists.count { it.enabled }} / ${visibleLists.size} danh sách đang bật"
                                )
                                if (status.loading) AppHint("Đang tải bộ lọc…")
                            }
                        }
                    }
                    (error ?: status.error)?.let { message ->
                        item(key = "error", contentType = "card") { AppCard { AppText(message) } }
                    }
                    item(key = "sources", contentType = "card") {
                        AppPreferenceGroup {
                            AppArrowPreference(
                                "Chọn từ thư viện",
                                if (browser) "Bộ lọc trình duyệt AdGuard"
                                else "Danh sách DNS AdGuard Home",
                                if (browser) onBrowserCatalog else onCatalog,
                            )
                            AppArrowPreference("Thêm bằng URL", "Nhập liên kết HTTPS của bộ lọc") {
                                sheetError = null
                                addingBrowser = browser
                            }
                        }
                    }
                    item(key = "lists-title", contentType = "title") {
                        AppSectionTitle("Danh sách đã thêm")
                    }
                    if (visibleLists.isEmpty()) {
                        item(key = "empty", contentType = "card") {
                            AppCard {
                                AppText("Chưa có bộ lọc nào")
                                AppHint("Chọn từ thư viện hoặc thêm bằng URL để bắt đầu.")
                            }
                        }
                    }
                    items(visibleLists, key = { it.id }, contentType = { "filter" }) { list ->
                        AppPreferenceGroup {
                            AppSwitchPreference(
                                title = list.name,
                                summary =
                                    list.error
                                        ?: ("${numberFormat.format(list.ruleCount)} quy tắc · " +
                                            if (list.updatedAt == 0L) "Chưa tải"
                                            else dateFormat.format(java.util.Date(list.updatedAt))),
                                checked = list.enabled,
                                onCheckedChange = { action { filters.setEnabled(list.id, it) } },
                            )
                        }
                    }
                    item(key = "shared-title", contentType = "title") {
                        AppSectionTitle("Quản lý chung")
                    }
                    item(key = "shared-actions", contentType = "card") {
                        AppPreferenceGroup {
                            AppArrowPreference(
                                "Cập nhật tất cả",
                                "Cả DNS và trình duyệt · ${numberFormat.format(status.loaded)} quy tắc đã nạp",
                            ) {
                                if (!status.loading) action { filters.updateAll() }
                            }
                            AppArrowPreference(
                                "Quy tắc riêng",
                                "Dùng chung cho DNS và trình duyệt",
                            ) {
                                sheetError = null
                                editingCustom = true
                            }
                            if (status.ignored > 0)
                                AppGroupContent {
                                    AppHint(
                                        "${numberFormat.format(status.ignored)} quy tắc không hỗ trợ đã bỏ qua"
                                    )
                                }
                        }
                    }
                }
            }
        }
    }
    val addBrowser = addingBrowser
    AppBottomSheet(
        show = addBrowser != null,
        title = if (addBrowser == true) "Thêm bộ lọc trình duyệt" else "Thêm bộ lọc DNS",
        onDismiss = { if (!busy) addingBrowser = null },
    ) {
        if (addBrowser != null)
            AppColumn {
                AppTextInput(
                    value = if (addBrowser) browserUrl else dnsUrl,
                    label = "URL bộ lọc (HTTPS)",
                    error = sheetError != null,
                    onChange = {
                        if (addBrowser) browserUrl = it else dnsUrl = it
                        sheetError = null
                    },
                )
                sheetError?.let { AppText(it) }
                AppAction(if (busy) "Đang thêm…" else "Thêm bộ lọc", primary = true) {
                    if (!busy) {
                        val url = if (addBrowser) browserUrl else dnsUrl
                        busy = true
                        scope.launch {
                            try {
                                filters.addUrl(url, browser = addBrowser)
                                if (addBrowser) browserUrl = "" else dnsUrl = ""
                                addingBrowser = null
                                sheetError = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                sheetError = failure.message ?: "Không thêm được bộ lọc"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            }
    }
    AppBottomSheet(
        show = editingCustom,
        title = "Quy tắc riêng",
        onDismiss = { if (!busy) editingCustom = false },
    ) {
        AppColumn {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { AppHint("Dùng chung cho DNS và trình duyệt") }
                AppHelpTooltip(
                    text =
                        "Mỗi dòng một quy tắc.\n||domain^ để chặn · @@||domain^ để cho phép.\nexample.com##.banner để ẩn phần tử · example.com#@#.banner để bỏ ẩn.\nẨn phần tử cần bật lọc HTTPS và chọn trình duyệt trong danh sách ứng dụng.",
                    label = "Hướng dẫn viết quy tắc riêng",
                )
            }
            AppTextInput(
                value = custom ?: saved,
                label = "Mỗi dòng một quy tắc",
                multiline = true,
                error = sheetError != null,
                onChange = {
                    custom = it
                    sheetError = null
                },
            )
            sheetError?.let { AppText(it) }
            AppAction(if (busy) "Đang lưu…" else "Lưu quy tắc", primary = true) {
                if (!busy) {
                    val rules = custom ?: saved
                    busy = true
                    scope.launch {
                        try {
                            filters.saveCustom(rules)
                            custom = null
                            editingCustom = false
                            sheetError = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            sheetError = failure.message ?: "Không lưu được quy tắc"
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}
