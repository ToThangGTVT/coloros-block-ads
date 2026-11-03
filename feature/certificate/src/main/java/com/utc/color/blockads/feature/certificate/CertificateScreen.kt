package com.utc.color.blockads.feature.certificate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.utc.color.blockads.core.certificate.CertificateRepository
import com.utc.color.blockads.core.common.*
import com.utc.color.blockads.core.data.*
import com.utc.color.blockads.core.designsystem.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@Composable
fun CertificateScreen(
    repository: CertificateRepository,
    settings: SettingsRepository,
    apps: AppsRepository,
    onApps: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val config by settings.https.collectAsStateWithLifecycle(initialValue = HttpsSettings())
    val installed by apps.apps.collectAsStateWithLifecycle()
    var domains by rememberSaveable { mutableStateOf<String?>(null) }
    var excluded by rememberSaveable { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun action(success: String? = null, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onSuccess { feedback = success }
                .onFailure { feedback = it.message ?: "Không thực hiện được" }
        }
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")
        ) { uri ->
            if (uri != null)
                action("Đã xuất CA công khai") {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(repository.exportCertificate())
                        } ?: error("Không mở được file")
                    }
                }
        }
    AppColumn {
        AppCard {
            AppHint("Chỉ app tin CA người dùng, thường là trình duyệt.")
            AppHint("Ngân hàng, thanh toán, y tế luôn được bỏ qua.")
        }
        (feedback ?: state.error)?.let { AppCard { AppText(it) } }
        AppSectionTitle("1 · Tạo CA")
        AppCard {
            if (!state.created) {
                AppHint("Khóa nằm trong Android Keystore, không rời thiết bị.")
                AppAction("Tạo CA", primary = true) { action("Đã tạo CA") { repository.create() } }
            } else {
                AppText(
                    if (state.hardwareBacked) "Khóa bảo vệ bằng phần cứng"
                    else "Khóa trong Android Keystore"
                )
                AppHint("SHA-256 ${state.fingerprint}")
            }
        }
        AppSectionTitle("2 · Cài CA")
        AppPreferenceGroup {
            if (state.created)
                AppArrowPreference("Xuất CA", "Lưu file BlockAds-CA.crt") {
                    export.launch("BlockAds-CA.crt")
                }
            AppArrowPreference("Mở cài đặt", "Bảo mật → Cài chứng chỉ → Chứng chỉ CA") {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    )
                }
                    .onFailure { feedback = "Không mở được Settings" }
            }
            AppArrowPreference(
                "Kiểm tra lại",
                if (state.installed) "Đã cài và khớp" else "Chưa cài",
            ) {
                action { repository.refresh() }
            }
        }
        AppSectionTitle("3 · Chọn domain")
        AppCard {
            AppHint("Mỗi dòng một domain, gồm cả subdomain")
            AppTextInput(
                domains ?: config.domains.joinToString("\n"),
                "Domain lọc HTTPS",
                multiline = true,
                onChange = { domains = it },
            )
            AppTextInput(
                excluded ?: config.excluded.joinToString("\n"),
                "Domain luôn bỏ qua",
                multiline = true,
                onChange = { excluded = it },
            )
            AppAction("Lưu domain", primary = true) {
                action("Đã lưu domain") {
                    val saved = settings.https.first()
                    settings.setHttps(
                        saved.copy(
                            domains =
                                (domains ?: saved.domains.joinToString("\n"))
                                    .lineSequence()
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .map(::normalizeHttpsDomain)
                                    .toList(),
                            excluded =
                                (excluded ?: saved.excluded.joinToString("\n"))
                                    .lineSequence()
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .map(::normalizeHttpsDomain)
                                    .toList(),
                        )
                    )
                    domains = null
                    excluded = null
                }
            }
        }
        AppSectionTitle("Ứng dụng")
        AppPreferenceGroup {
            installed
                .filter { it.packageName in HttpsSettings().apps || it.packageName in config.apps }
                .forEach { app ->
                    AppSwitchPreference(
                        app.label,
                        app.packageName,
                        app.packageName in config.apps,
                    ) { checked ->
                        action {
                            val saved = settings.https.first()
                            settings.setHttps(
                                saved.copy(
                                    apps =
                                        if (checked) (saved.apps + app.packageName).distinct()
                                        else saved.apps - app.packageName
                                )
                            )
                        }
                    }
                }
            AppArrowPreference("Thêm ứng dụng", onClick = onApps)
        }
        AppPreferenceGroup {
            AppSwitchPreference(
                "Bật lọc HTTPS",
                "Cần CA đã cài và ít nhất một domain",
                config.enabled && state.installed,
            ) { enabled ->
                action { repository.setEnabled(enabled) }
            }
        }
        AppHint("Gỡ app không tự gỡ CA. Có thể gỡ trong Cài đặt → Thông tin xác thực.")
    }
}
