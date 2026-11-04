package com.utc.color.blockads.feature.filters

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.CatalogEntry
import com.utc.color.blockads.core.filter.CatalogSource
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.launch

/** Registry groupId → title and summary, in AdGuard Home's order. */
private val dnsGroups =
    listOf(
        Triple(1, "Chung", "Chặn quảng cáo và theo dõi cho hầu hết thiết bị"),
        Triple(2, "Khác", "Bộ lọc chặn khác"),
        Triple(3, "Khu vực", "Theo từng quốc gia, ngôn ngữ"),
        Triple(4, "Bảo mật", "Chặn tên miền mã độc và lừa đảo"),
    )

/** AdGuard browser extension groupId → title and summary, in the extension's order. */
private val browserGroups =
    listOf(
        Triple(1, "Chặn quảng cáo", "Quảng cáo trên trang web"),
        Triple(2, "Riêng tư", "Chặn trình theo dõi và tham số theo dõi trong URL"),
        Triple(3, "Mạng xã hội", "Nút Thích, Chia sẻ và tiện ích mạng xã hội"),
        Triple(4, "Phiền toái", "Thông báo cookie, popup trong trang, banner mời cài ứng dụng"),
        Triple(5, "Bảo mật", "Chặn trang lừa đảo và mã độc"),
        Triple(6, "Khác", "Bộ lọc không thuộc nhóm nào"),
        Triple(7, "Theo ngôn ngữ", "Quảng cáo trên trang của từng ngôn ngữ"),
    )

@Composable
fun FilterCatalogScreen(filters: FilterRepository, source: CatalogSource) {
    val lists by filters.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    var catalog by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    val scope = rememberCoroutineScope()
    val groups = if (source.browser) browserGroups else dnsGroups
    val language = remember { java.util.Locale.getDefault().language }
    LaunchedEffect(attempt) {
        error = null
        runCatching { filters.catalog(source) }
            .onSuccess { catalog = it }
            .onFailure { error = it.message ?: "Không tải được danh sách" }
    }
    val added =
        remember(lists) { lists.filter { it.browser == source.browser }.associateBy { it.url } }
    // The device language's lists come first, as the extension suggests them.
    val grouped =
        remember(catalog) {
            catalog.orEmpty().sortedBy { language !in it.languages }.groupBy { it.group }
        }
    fun isOn(entry: CatalogEntry) = added[entry.url]?.enabled == true
    AppLazyList {
        item(key = "catalog-status", contentType = "status") {
            error?.let {
                AppCard {
                    AppText(it)
                    if (catalog == null) AppAction("Thử lại") { attempt++ }
                }
            }
            val entries = catalog
            if (entries == null) {
                if (error == null) AppCard { AppHint("Đang tải…") }
            }
        }
        groups.forEach { (id, title, summary) ->
            val group = grouped[id].orEmpty()
            if (group.isEmpty()) return@forEach
            val on = group.count(::isOn)
            item(key = "group-$id", contentType = "group") {
                AppPreferenceGroup {
                    AppArrowPreference(
                        title,
                        if (on == 0) summary else "$summary · $on đang dùng",
                    ) {
                        expanded = if (id in expanded) expanded - id else expanded + id
                    }
                }
            }
            if (id in expanded)
                items(group, key = { it.url }, contentType = { "catalog-entry" }) { entry ->
                    AppPreferenceGroup {
                        AppSwitchPreference(
                            title = entry.name,
                            summary =
                                listOfNotNull(
                                        "Khuyên dùng".takeIf {
                                            entry.recommended &&
                                                (entry.languages.isEmpty() ||
                                                    language in entry.languages)
                                        },
                                        entry.languages
                                            .takeIf { it.isNotEmpty() }
                                            ?.joinToString(", "),
                                    )
                                    .joinToString(" · ")
                                    .ifEmpty { null },
                            checked = isOn(entry),
                            onCheckedChange = { checked ->
                                scope.launch {
                                    runCatching {
                                        val list = added[entry.url]
                                        when {
                                            !checked -> list?.let { filters.remove(it.id) }
                                            list != null -> filters.setEnabled(list.id, true)
                                            else ->
                                                filters.addUrl(
                                                    entry.url,
                                                    entry.name,
                                                    browser = source.browser,
                                                )
                                        }
                                    }
                                        .onSuccess { error = null }
                                        .onFailure { error = it.message }
                                }
                            },
                        )
                    }
                }
        }
    }
}
