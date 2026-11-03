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
            AppText("Lọc HTTPS · Tùy chọn", prominent = true)
            AppText(
                "Chỉ app tin CA do người dùng cài mới có thể lọc HTTPS, thường là trình duyệt. Mặc định tắt; chỉ áp dụng cho app và domain được chọn bên dưới."
            )
            AppText(
                "Ngân hàng, thanh toán và y tế trong danh sách loại trừ cố định luôn được bỏ qua. App từ chối chứng chỉ có thể lỗi một lần; kết nối kế tiếp sẽ relay TLS nguyên trạng."
            )
        }
        (feedback ?: state.error)?.let { AppCard { AppText(it) } }
        AppCard {
            AppText("1 · Tạo CA riêng trên thiết bị", prominent = true)
            AppText("Khóa ký nằm trong Android Keystore và không được xuất ra file cấu hình.")
            if (!state.created)
                AppAction("Tạo chứng chỉ CA") { action("Đã tạo CA") { repository.create() } }
            else {
                AppText(
                    if (state.hardwareBacked) "Khóa Keystore được bảo vệ bằng phần cứng"
                    else "Khóa được giữ trong Android Keystore"
                )
                AppText("SHA-256: ${state.fingerprint}")
            }
        }
        AppCard {
            AppText("2 · Cài CA qua Settings hệ thống", prominent = true)
            AppText(
                "Xuất file .crt, mở Bảo mật → Mã hóa/thông tin xác thực → Cài chứng chỉ → Chứng chỉ CA. Tên mục có thể khác theo hãng. Android 11+ yêu cầu bạn tự thực hiện bước cài này."
            )
            if (state.created) AppAction("Xuất chứng chỉ CA") { export.launch("BlockAds-CA.crt") }
            AppAction("Mở cài đặt bảo mật") {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                    )
                }
                    .onFailure { feedback = "Không mở được Settings" }
            }
            AppAction("Kiểm tra trạng thái đã cài") { action { repository.refresh() } }
            AppText(
                if (state.installed) "CA đã cài và khớp dấu vân tay"
                else "Chưa phát hiện CA này trong kho CA người dùng"
            )
        }
        AppCard {
            AppText("3 · Chọn phạm vi HTTPS", prominent = true)
            AppText(
                "Mỗi dòng một domain. Chỉ những domain này và subdomain của chúng được xét HTTPS; để trống sẽ không giải mã domain nào."
            )
            AppTextInput(
                domains ?: config.domains.joinToString("\n"),
                "Domain cho phép HTTPS",
                multiline = true,
                onChange = { domains = it },
            )
            AppTextInput(
                excluded ?: config.excluded.joinToString("\n"),
                "Domain luôn bỏ qua thêm",
                multiline = true,
                onChange = { excluded = it },
            )
            AppAction("Lưu phạm vi domain") {
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
        AppAction("Chọn thêm ứng dụng HTTPS", onApps)
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
        }
        AppPreferenceGroup {
            AppSwitchPreference(
                "Bật lọc HTTPS",
                "Cần CA đã cài, app được chọn và domain cho phép",
                config.enabled && state.installed,
            ) { enabled ->
                action { repository.setEnabled(enabled) }
            }
        }
        AppCard {
            AppText(
                "Nếu gỡ BlockAds hoặc xóa dữ liệu app, CA đã cài có thể vẫn còn trong Settings. Có thể gỡ CA đó thủ công tại mục thông tin xác thực người dùng."
            )
        }
    }
}
