package com.utc.color.blockads.core.tunnel

import com.utc.color.blockads.core.common.AppFilterMode
import com.utc.color.blockads.core.data.AppsRepository
import com.utc.color.blockads.core.data.SettingsRepository
import com.utc.color.blockads.core.enginebridge.EngineBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

data class AppliedTrafficPolicy(val policies: Map<String, AppFilterMode>, val blockQuic: Boolean)

class TrafficPolicyRepository(
    settings: SettingsRepository,
    apps: AppsRepository,
    engine: EngineBridge,
) {
    private val _applied = MutableStateFlow<AppliedTrafficPolicy?>(null)
    val applied = _applied.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            combine(
                    apps.apps.filter { it.isNotEmpty() },
                    settings.appPolicies,
                    settings.blockQuic,
                ) { installed, policies, blockQuic ->
                    val packages = JSONObject()
                    installed
                        .groupBy { it.uid }
                        .forEach { (uid, all) ->
                            packages.put(uid.toString(), JSONArray(all.map { it.packageName }))
                        }
                    val bypass =
                        installed
                            .filter { policies[it.packageName] == AppFilterMode.BypassFiltering }
                            .map { it.uid }
                            .distinct()
                    JSONObject()
                        .put("blockQuic", blockQuic)
                        .put("packages", packages)
                        .put("bypassUids", JSONArray(bypass))
                        .toString() to AppliedTrafficPolicy(policies, blockQuic)
                }
                .distinctUntilChanged()
                .collect { (json, policy) ->
                    check(engine.configurePolicy(json)) { "Traffic policy rejected" }
                    _applied.value = policy
                    if (!ready.isCompleted) ready.complete(Unit)
                }
        }
    }

    suspend fun awaitReady() = ready.await()
}
