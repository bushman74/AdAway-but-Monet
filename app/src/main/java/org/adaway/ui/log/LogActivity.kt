package org.adaway.ui.log

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.adaway.R
import org.adaway.db.entity.ListType
import org.adaway.ui.adblocking.ApplyConfigurationSnackbar
import org.adaway.ui.compose.ExpressiveAsymmetricShape1
import org.adaway.ui.compose.ExpressiveAsymmetricShape2
import org.adaway.ui.compose.ExpressiveScaffold
import org.adaway.ui.compose.ExpressiveSearchField
import org.adaway.ui.compose.ExpressiveSection
import org.adaway.ui.compose.ExpressiveTopBar
import org.adaway.ui.compose.safeCombinedClickable
import org.adaway.util.Clipboard
import org.adaway.util.RegexUtils
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

private fun onLogEntryAction(
    context: android.content.Context,
    viewModel: LogViewModel,
    applySnackbar: ApplyConfigurationSnackbar,
    entry: LogEntry,
    targetType: ListType,
    onRequestRedirection: (String) -> Unit
) {
    if (entry.type == targetType) {
        viewModel.removeListItem(entry.host)
        applySnackbar.notifyUpdateAvailable()
        return
    }
    if (targetType == ListType.REDIRECTED) {
        onRequestRedirection(entry.host)
        return
    }
    viewModel.addListItem(entry.host, targetType, null)
    applySnackbar.notifyUpdateAvailable()
}

private fun openHostInBrowser(context: android.content.Context, hostName: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("http://$hostName")
    }
    context.startActivity(intent)
}

@Composable
internal fun LogRoute(
    onNavigateBack: () -> Unit,
    viewModel: LogViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootView = LocalView.current
    val applySnackbar = remember(rootView) {
        ApplyConfigurationSnackbar(rootView, false, false)
    }
    var redirectHost by remember { mutableStateOf<String?>(null) }
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val togglingRecording by viewModel.togglingRecording.collectAsStateWithLifecycle()
    val recordingMessage by viewModel.recordingMessage.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val logs by viewModel.visibleLogs.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val blockedRequestsIgnored = remember(viewModel) { viewModel.areBlockedRequestsIgnored() }
    // The file is created where the user picks it, exactly as a configuration backup is.
    val exportLauncher = rememberLauncherForActivityResult(
        CreateDocument(LogExporter.MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            LogExporter.export(context, uri, logs)
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.updateLogs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LogScreen(
        logs = logs,
        loaded = loaded,
        recording = recording,
        togglingRecording = togglingRecording,
        refreshing = refreshing,
        searchQuery = searchQuery,
        sort = sort,
        blockedRequestsIgnored = blockedRequestsIgnored,
        onNavigateBack = onNavigateBack,
        onSort = viewModel::toggleSort,
        onClear = viewModel::clearLogs,
        onRefresh = viewModel::updateLogs,
        onSearch = viewModel::search,
        onToggleRecording = viewModel::toggleRecording,
        onExport = { exportLauncher.launch(LogExporter.FILE_NAME) },
        onEntryAction = { entry, targetType ->
            onLogEntryAction(
                context = context,
                viewModel = viewModel,
                applySnackbar = applySnackbar,
                entry = entry,
                targetType = targetType,
                onRequestRedirection = { host -> redirectHost = host }
            )
        },
        onOpenHost = { hostName -> openHostInBrowser(context, hostName) },
        onCopyHost = { hostName -> Clipboard.copyHostToClipboard(context, hostName) }
    )

    recordingMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRecordingMessage,
            title = { Text(text = stringResource(message.titleRes)) },
            text = { Text(text = message.text) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRecordingMessage) {
                    Text(text = stringResource(android.R.string.ok))
                }
            }
        )
    }

    redirectHost?.let { hostName ->
        RedirectIpDialog(
            onDismiss = { redirectHost = null },
            onConfirm = { ip ->
                viewModel.addListItem(hostName, ListType.REDIRECTED, ip)
                applySnackbar.notifyUpdateAvailable()
                redirectHost = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(
    logs: List<LogEntry>,
    loaded: Boolean,
    recording: Boolean,
    togglingRecording: Boolean,
    refreshing: Boolean,
    searchQuery: String,
    sort: LogEntrySort,
    blockedRequestsIgnored: Boolean,
    onNavigateBack: () -> Unit,
    onSort: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onToggleRecording: () -> Unit,
    onExport: () -> Unit,
    onEntryAction: (LogEntry, ListType) -> Unit,
    onOpenHost: (String) -> Unit,
    onCopyHost: (String) -> Unit
) {
    var searching by remember { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        onSearch("")
    }

    ExpressiveScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ExpressiveTopBar(
                title = stringResource(R.string.shortcut_dns_requests),
                onNavigateBack = if (searching) closeSearch else onNavigateBack,
                titleContent = if (searching) {
                    {
                        ExpressiveSearchField(
                            query = searchQuery,
                            onQueryChange = onSearch,
                            placeholder = stringResource(R.string.log_search_hint),
                            clearContentDescription =
                                stringResource(R.string.log_search_clear_description)
                        )
                    }
                } else {
                    null
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = closeSearch) {
                            Icon(
                                painter = painterResource(R.drawable.baseline_close_24),
                                contentDescription = stringResource(R.string.log_search_close_description)
                            )
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(
                                painter = painterResource(R.drawable.baseline_search_24),
                                contentDescription = stringResource(R.string.log_search_description)
                            )
                        }
                        // The icon stands for the sort in use, so pressing it visibly switches.
                        AnimatedContent(
                            targetState = sort,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "sortIcon"
                        ) { currentSort ->
                            IconButton(onClick = onSort) {
                                Icon(
                                    painter = painterResource(currentSort.icon),
                                    contentDescription = stringResource(R.string.tcpdump_menu_sort)
                                )
                            }
                        }
                        IconButton(onClick = onClear) {
                            Icon(
                                painter = painterResource(R.drawable.outline_delete_24),
                                contentDescription = stringResource(R.string.tcpdump_menu_clear)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Nothing to export until something has been recorded.
                if (logs.isNotEmpty()) {
                    ExportButton(onClick = onExport)
                }
                RecordingButton(
                    recording = recording,
                    busy = togglingRecording,
                    onClick = onToggleRecording
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            RecordingStatus(recording = recording, busy = togglingRecording)

            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Everything lives in the list, including the placeholders, so the screen can
                // always be pulled down to refresh.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = 88.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (logs.isEmpty()) {
                        // Nothing is said about an empty list until the recorded requests have
                        // been read: telling the user to start recording, only to replace it with
                        // what was already recorded, read as a screen that had lost its content.
                        if (loaded) {
                            item(key = "empty") {
                                EmptyMessage(
                                    searchQuery = searchQuery,
                                    blockedRequestsIgnored = blockedRequestsIgnored
                                )
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = logs,
                            key = { _, entry -> entry.host }
                        ) { index, entry ->
                            LogEntryRow(
                                entry = entry,
                                shape = if (index % 2 == 0) {
                                    ExpressiveAsymmetricShape1
                                } else {
                                    ExpressiveAsymmetricShape2
                                },
                                onAction = onEntryAction,
                                onOpenHost = onOpenHost,
                                onCopyHost = onCopyHost,
                                // Reordering the list slides the rows to their new place rather
                                // than swapping their content in place.
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The recording state, in a strip that is always laid out so showing it moves nothing.
 * Starting a capture takes a moment, and without this the screen looked inert until it finished.
 */
@Composable
private fun RecordingStatus(recording: Boolean, busy: Boolean) {
    val text = when {
        busy && recording -> stringResource(R.string.log_recording_stopping)
        busy -> stringResource(R.string.log_recording_starting)
        recording -> stringResource(R.string.log_recording_active)
        else -> null
    }
    val alpha by animateFloatAsState(
        targetValue = if (text == null) 0f else 1f,
        animationSpec = tween(200),
        label = "recordingStatusAlpha"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RECORDING_STATUS_HEIGHT)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (alpha > 0f && text != null) {
            Row(
                modifier = Modifier.alpha(alpha),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The control writing the recorded requests to a file.
 *
 * Built like the recording control beside it, in the colours that one wears while it is idle, so
 * the two read as a pair rather than as one button and an afterthought.
 */
@Composable
private fun ExportButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_save_24dp),
            contentDescription = stringResource(R.string.log_export_description),
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * The record and pause control.
 *
 * Its icon is swapped with a spinner while the capture is being started or stopped: that goes
 * through a privileged shell and waits for the capture to prove it is alive, so the state cannot
 * change the instant the control is pressed.
 */
@Composable
private fun RecordingButton(
    recording: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (recording) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        animationSpec = tween(300),
        label = "recordingButtonContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (recording) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        animationSpec = tween(300),
        label = "recordingButtonContent"
    )
    FloatingActionButton(
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large
    ) {
        AnimatedContent(
            targetState = if (busy) null else recording,
            transitionSpec = {
                (fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f))
            },
            label = "recordingButtonIcon"
        ) { state ->
            if (state == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                Icon(
                    painter = painterResource(
                        if (state) R.drawable.ic_pause_24dp else R.drawable.ic_record_24dp
                    ),
                    contentDescription = stringResource(R.string.log_toggle_recording_description),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyMessage(
    searchQuery: String,
    blockedRequestsIgnored: Boolean
) {
    val message = if (searchQuery.isNotBlank()) {
        stringResource(R.string.log_search_no_result, searchQuery)
    } else {
        buildString {
            append(stringResource(R.string.log_start_recording))
            if (blockedRequestsIgnored) {
                append(stringResource(R.string.log_blocked_requests_ignored))
            }
        }
    }
    ExpressiveSection {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp)
        )
    }
}

@Composable
private fun LogEntryRow(
    entry: LogEntry,
    shape: Shape,
    onAction: (LogEntry, ListType) -> Unit,
    onOpenHost: (String) -> Unit,
    onCopyHost: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    ExpressiveSection(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LogActionButton(
                iconRes = R.drawable.baseline_block_24,
                active = entry.type == ListType.BLOCKED,
                activeColor = MaterialTheme.colorScheme.error,
                onClick = { onAction(entry, ListType.BLOCKED) }
            )
            LogActionButton(
                iconRes = R.drawable.baseline_check_24,
                active = entry.type == ListType.ALLOWED,
                activeColor = MaterialTheme.colorScheme.tertiary,
                onClick = { onAction(entry, ListType.ALLOWED) }
            )
            LogActionButton(
                iconRes = R.drawable.baseline_compare_arrows_24,
                active = entry.type == ListType.REDIRECTED,
                activeColor = MaterialTheme.colorScheme.secondary,
                onClick = { onAction(entry, ListType.REDIRECTED) }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 6.dp)
                    .safeCombinedClickable(
                        onClick = { onOpenHost(entry.host) },
                        onLongClick = { onCopyHost(entry.host) }
                    )
            ) {
                Text(
                    text = entry.host,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val lastSeen = entry.lastSeen
                if (lastSeen != null) {
                    Text(
                        text = rememberFormattedTime(lastSeen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Format the date and time a request was last seen, to the second.
 *
 * The fields are ordered and punctuated the way the system does it for the current language, and
 * the clock follows the twelve or twenty four hour setting. The date is always shown: a recording
 * kept across days is otherwise ambiguous, and the plain time formats leave the seconds out.
 */
@Composable
private fun rememberFormattedTime(instant: Instant): String {
    val context = LocalContext.current
    val formatter = remember(context) {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val datePattern = DateFormat.getBestDateTimePattern(locale, "yyyyMMdd")
        val timePattern = DateFormat.getBestDateTimePattern(
            locale,
            if (DateFormat.is24HourFormat(context)) "HHmmss" else "hmmss"
        )
        SimpleDateFormat("$datePattern, $timePattern", locale)
    }
    return remember(instant, formatter) { formatter.format(Date.from(instant)) }
}

@Composable
private fun LogActionButton(
    iconRes: Int,
    active: Boolean,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        targetValue = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "logActionTint"
    )
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun RedirectIpDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var redirection by remember { mutableStateOf("0.0.0.0") }
    val valid = RegexUtils.isValidIP(redirection)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_redirect_dialog_title)) },
        text = {
            OutlinedTextField(
                value = redirection,
                onValueChange = { redirection = it },
                singleLine = true,
                isError = redirection.isNotBlank() && !valid,
                label = { Text(stringResource(R.string.list_dialog_ip)) }
            )
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(redirection.trim()) }
            ) {
                Text(stringResource(R.string.button_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        }
    )
}

/**
 * The height reserved for the recording status, so showing it does not move the content.
 */
private val RECORDING_STATUS_HEIGHT = 24.dp
