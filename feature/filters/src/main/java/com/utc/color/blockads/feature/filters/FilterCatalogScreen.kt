package com.utc.color.blockads.feature.filters

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.designsystem.*
import com.utc.color.blockads.core.filter.CatalogEntry
import com.utc.color.blockads.core.filter.FilterRepository
import kotlinx.coroutines.launch

/** Registry groupId → title and summary, in AdGuard Home's order. */
private val groups =
    listOf(
        Triple(1, "Chung", "Chặn quảng cáo và theo dõi cho hầu hết thiết bị"),
        Triple(2, "Khác", "Bộ lọc chặn khác"),
        Triple(3, "Khu vực", "Theo từng quốc gia, ngôn ngữ"),
        Triple(4, "Bảo mật", "Chặn tên miền mã độc và lừa đảo"),
    )

@Composable
fun FilterCatalogScreen(filters: FilterRepository) {
    val lists by filters.lists.collectAsStateWithLifecycle(initialValue = emptyList())
    var catalog by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(setOf<Int>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt) {
        error = null
        runCatching { filters.catalog() }
            .onSuccess { catalog = it }
            .onFailure { error = it.message ?: "Không tải được danh sách" }
    }
    val added = lists.associateBy { it.url }
    fun isOn(entry: CatalogEntry) = added[entry.url]?.enabled == true
    AppColumn {
        error?.let {
            AppCard {
                AppText(it)
                if (catalog == null) AppAction("Thử lại") { attempt++ }
            }
        }
        val entries = catalog
        if (entries == null) {
            if (error == null) AppCard { AppHint("Đang tải…") }
            return@AppColumn
        }
        groups.forEach { (id, title, summary) ->
            val group = entries.filter { it.group == id }
            if (group.isEmpty()) return@forEach
            val on = group.count(::isOn)
            AppPreferenceGroup {
                AppArrowPreference(title, if (on == 0) summary else "$summary · $on đang dùng") {
                    expanded = if (id in expanded) expanded - id else expanded + id
                }
                if (id in expanded)
                    group.forEach { entry ->
                        AppSwitchPreference(
                            title = entry.name,
                            checked = isOn(entry),
                            onCheckedChange = { checked ->
                                scope.launch {
                                    runCatching {
                                            val list = added[entry.url]
                                            when {
                                                !checked -> list?.let { filters.remove(it.id) }
                                                list != null -> filters.setEnabled(list.id, true)
                                                else -> filters.addUrl(entry.url, entry.name)
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
