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
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
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
            AppHint("Có thể ẩn quảng cáo bằng quy tắc CSS theo website trong Bộ lọc → Quy tắc riêng.")
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
        AppSectionTitle("3 · Chọn ứng dụng")
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
                "Áp dụng cho ứng dụng đã chọn, cần CA đã cài",
                config.enabled && state.installed,
            ) { enabled ->
                action { repository.setEnabled(enabled) }
            }
        }
        AppPreferenceGroup {
            AppArrowPreference(
                if (deleting) "Đang xóa CA…" else "Xóa CA đã lưu",
                "Xóa khóa trong bộ nhớ bảo mật và chứng chỉ của app",
            ) {
                if (!deleting) confirmDelete = true
            }
        }
        AppHint("Gỡ app không tự gỡ CA. Có thể gỡ trong Cài đặt → Thông tin xác thực.")
    }
    AppBottomSheet(
        show = confirmDelete,
        title = "Xóa CA đã lưu?",
        onDismiss = { confirmDelete = false },
    ) {
        AppColumn {
            AppText("Khóa riêng trong Android Keystore sẽ bị xóa vĩnh viễn. Lọc HTTPS sẽ tắt.")
            AppHint("Chứng chỉ đã cài trong Android và các file CA đã xuất vẫn còn. Gỡ chứng chỉ trong Cài đặt → Bảo mật → Thông tin xác thực người dùng.")
            AppHint("Nếu tạo CA mới, bạn cần cài lại chứng chỉ mới để dùng lọc HTTPS.")
            AppButtonRow {
                AppButton("Hủy") { confirmDelete = false }
                AppButton("Xóa CA", primary = true) {
                    confirmDelete = false
                    if (!deleting) {
                        deleting = true
                        scope.launch {
                            try {
                                repository.delete()
                                feedback = "Đã xóa khóa CA trong Keystore và chứng chỉ của app. Lọc HTTPS đã tắt."
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                feedback = error.message ?: "Không xóa được CA"
                            } finally {
                                deleting = false
                            }
                        }
                    }
                }
            }
        }
    }
}
