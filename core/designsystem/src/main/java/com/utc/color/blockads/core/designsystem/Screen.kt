package com.utc.color.blockads.core.designsystem

import android.app.Dialog
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

abstract class Screen(
    private val activity: ComponentActivity,
    val view: View,
) {
    protected val context get() = view.context
    protected val resources get() = view.resources
    protected val scope = CoroutineScope(
        activity.lifecycleScope.coroutineContext + SupervisorJob(activity.lifecycleScope.coroutineContext[Job]),
    )

    private val launchers = mutableListOf<ActivityResultLauncher<*>>()
    private var dialog: Dialog? = null
    private var tooltip: CouiTooltip? = null

    protected fun <T> observe(flow: Flow<T>, render: suspend (T) -> Unit) = scope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collect(render) }
    }

    protected fun <T> observeLatest(flow: Flow<T>, render: suspend (T) -> Unit) = scope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) { flow.collectLatest(render) }
    }

    protected fun onResume(update: () -> Unit) = scope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) { update() }
    }

    protected fun <I, O> result(
        key: String,
        contract: ActivityResultContract<I, O>,
        receive: (O) -> Unit,
    ) = activity.activityResultRegistry.register(key, contract, receive).also { launchers += it }

    protected fun show(dialog: Dialog) {
        tooltip?.dismissNow()
        this.dialog?.dismiss()
        this.dialog = dialog
        dialog.show()
    }

    /** COUI help tooltip below [anchor]; tapping the anchor again closes it. */
    protected fun showHelp(anchor: View, text: CharSequence) {
        CouiTooltip.toggle(anchor, text)?.let { shown ->
            if (tooltip !== shown) tooltip?.dismissNow()
            tooltip = shown
        }
    }

    protected fun showHelp(anchor: View, @StringRes text: Int) = showHelp(anchor, context.getText(text))

    protected fun action(
        success: () -> Unit = {},
        failure: (Exception) -> Unit,
        block: suspend () -> Unit,
    ) = scope.launch {
        try {
            block()
            success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure(error)
        }
    }

    open fun saveState(): Bundle = Bundle().apply {
        val hierarchy = SparseArray<Parcelable>()
        view.saveHierarchyState(hierarchy)
        putSparseParcelableArray("views", hierarchy)
    }

    fun restoreViews(state: Bundle) {
        BundleCompat.getSparseParcelableArray(state, "views", Parcelable::class.java)?.let(view::restoreHierarchyState)
    }

    open fun close() {
        tooltip?.dismissNow()
        dialog.let { if (it is CouiBottomSheet) it.dismissNow() else it?.dismiss() }
        launchers.forEach { it.unregister() }
        scope.cancel()
    }
}
