package io.github.zailinn.flymefreeform.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.zailinn.flymefreeform.R
import io.github.zailinn.flymefreeform.apps.AppListStatus
import io.github.zailinn.flymefreeform.apps.AppSearch
import io.github.zailinn.flymefreeform.apps.SelectableEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent

/**
 * 搜索结果：空查询直接返回全部；否则在后台过滤（含拼音匹配，见 [AppSearch]），
 * 结果出来前保留上一次的列表，输入时不闪空。
 */
@Composable
internal fun rememberSearchResults(entries: List<SelectableEntry>, query: String): List<SelectableEntry> {
    val normalized = AppSearch.normalize(query)
    val state: State<List<SelectableEntry>> =
        produceState(initialValue = entries, entries, normalized) {
            value = if (normalized.isEmpty()) entries else withContext(Dispatchers.Default) { AppSearch.filter(entries, normalized) }
        }
    // 清空查询时立即显示全部，不等协程。
    return if (normalized.isEmpty()) entries else state.value
}

/** 应用列表尚未就绪：读取中，或读取失败（可点击重试）。 */
@Composable
internal fun AppListStatusRow(status: AppListStatus, onRetry: () -> Unit) {
    val failed = status == AppListStatus.Failed
    BasicComponent(
        title = stringResource(if (failed) R.string.available_apps_failed else R.string.available_apps_loading),
        summary = stringResource(if (failed) R.string.available_apps_failed_summary else R.string.available_apps_loading_summary),
        onClick = onRetry,
        onClickLabel = stringResource(R.string.retry_action),
        role = Role.Button,
        enabled = failed,
    )
}
