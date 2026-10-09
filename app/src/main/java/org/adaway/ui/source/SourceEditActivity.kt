package org.adaway.ui.source

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adaway.R
import org.adaway.db.AppDatabase
import org.adaway.db.dao.HostsSourceDao
import org.adaway.db.entity.HostsSource
import org.adaway.db.entity.SourceType
import org.adaway.ui.compose.ExpressiveAsymmetricShape1
import org.adaway.ui.compose.ExpressiveAsymmetricShape2
import org.adaway.ui.compose.ExpressiveScaffold
import org.adaway.ui.compose.ExpressiveSection
import org.adaway.ui.compose.ExpressiveTopBar
import timber.log.Timber

@Composable
internal fun SourceEditRoute(
    sourceId: Int?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val hostsSourceDao = remember(context) {
        AppDatabase.getInstance(context.applicationContext).hostsSourceDao()
    }
    var edited by remember(sourceId) { mutableStateOf<HostsSource?>(null) }
    var screenState by remember(sourceId) {
        mutableStateOf(
            SourceEditScreenState(
                urlLocation = context.getString(R.string.source_edit_url_location_default)
            )
        )
    }
    val editing = sourceId != null
    val coroutineScope = rememberCoroutineScope()
    var deleteRequested by rememberSaveable { mutableStateOf(false) }

    val startActivityLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        val uri: Uri? = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers grant only a temporary read permission.
            }
            screenState = screenState.copy(
                fileLocation = uri.toString(),
                locationError = null
            )
        }
    }

    LaunchedEffect(sourceId, hostsSourceDao) {
        if (sourceId == null) {
            return@LaunchedEffect
        }
        val source = withContext(Dispatchers.IO) {
            hostsSourceDao.getById(sourceId).orElse(null)
        }
        if (source != null) {
            edited = source
            screenState = source.toScreenState(context)
        }
    }

    fun openDocument() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = ANY_MIME_TYPE
            addFlags(FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityLauncher.launch(intent)
    }

    fun saveSource() {
        val validation = validateSource(screenState)
        screenState = validation.state
        val source = validation.source ?: return
        val sourceToUpdate = edited
        coroutineScope.launch {
            val saved = withContext(Dispatchers.IO) {
                // The address is unique among the sources, and inserting a second one with it
                // was silently ignored, so the screen closed as if the source had been added.
                val existing = hostsSourceDao.getByUrl(source.url).orElse(null)
                if (existing != null && existing.id != sourceToUpdate?.id) {
                    return@withContext false
                }
                if (sourceToUpdate == null) {
                    hostsSourceDao.insert(source)
                } else {
                    updateSource(hostsSourceDao, sourceToUpdate, source)
                }
                true
            }
            if (saved) {
                onNavigateBack()
            } else {
                screenState = screenState.copy(locationError = R.string.source_edit_location_duplicate)
            }
        }
    }

    fun deleteEditedSource() {
        deleteRequested = false
        val source = edited ?: return
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                hostsSourceDao.delete(source)
            }
            onNavigateBack()
        }
    }

    SourceEditScreen(
        state = screenState,
        editing = editing,
        onNavigateBack = onNavigateBack,
        onSave = ::saveSource,
        onDelete = { deleteRequested = true },
        onLabelChanged = { value ->
            screenState = screenState.copy(label = value, labelError = null)
        },
        onFormatSelected = { allowFormat ->
            screenState = screenState.copy(
                allowFormat = allowFormat,
                redirectedHosts = if (allowFormat) false else screenState.redirectedHosts
            )
        },
        onTypeSelected = { type ->
            if (screenState.type != type) {
                screenState = screenState.copy(
                    type = type,
                    locationError = null,
                    urlLocation = if (type == SourceInputType.URL && screenState.urlLocation.isBlank()) {
                        context.getString(R.string.source_edit_url_location_default)
                    } else {
                        screenState.urlLocation
                    }
                )
                if (type == SourceInputType.FILE) {
                    openDocument()
                }
            }
        },
        onUrlChanged = { value ->
            screenState = screenState.copy(urlLocation = value, locationError = null)
        },
        onFileLocationClick = ::openDocument,
        onRedirectedChanged = { checked ->
            screenState = screenState.copy(redirectedHosts = checked)
        }
    )

    if (deleteRequested) {
        AlertDialog(
            onDismissRequest = { deleteRequested = false },
            title = { Text(text = stringResource(R.string.source_edit_delete_title)) },
            text = {
                Text(text = stringResource(R.string.source_edit_delete_message, screenState.label))
            },
            confirmButton = {
                TextButton(onClick = ::deleteEditedSource) {
                    Text(text = stringResource(R.string.checkbox_list_context_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequested = false }) {
                    Text(text = stringResource(R.string.button_cancel))
                }
            }
        )
    }
}

/**
 * Apply an edit to the source in place.
 *
 * It used to be deleted and inserted again, which took its hosts with it, reset its update status
 * and turned it back on if it was off. Now it keeps its id, its hosts and whether it is enabled.
 * Only when what it provides changes, its address or how it is read, is it marked as never
 * downloaded, so the next update fetches it again.
 */
private fun updateSource(dao: HostsSourceDao, source: HostsSource, edit: HostsSource) {
    val contentChanged = source.url != edit.url ||
            source.isAllowEnabled != edit.isAllowEnabled ||
            source.isRedirectEnabled != edit.isRedirectEnabled
    source.label = edit.label
    source.url = edit.url
    source.setAllowEnabled(edit.isAllowEnabled)
    source.setRedirectEnabled(edit.isRedirectEnabled)
    dao.update(source)
    if (contentChanged) {
        dao.clearProperties(source.id)
    }
}

private enum class SourceInputType {
    URL,
    FILE
}

private data class SourceEditScreenState(
    val label: String = "",
    @param:StringRes @field:StringRes val labelError: Int? = null,
    val allowFormat: Boolean = false,
    val type: SourceInputType = SourceInputType.URL,
    val urlLocation: String = "",
    val fileLocation: String = "",
    @param:StringRes @field:StringRes val locationError: Int? = null,
    val redirectedHosts: Boolean = false
)

private data class SourceValidation(
    val state: SourceEditScreenState,
    val source: HostsSource?
)

private fun HostsSource.toScreenState(context: Context): SourceEditScreenState {
    val inputType = when (type) {
        SourceType.FILE -> SourceInputType.FILE
        else -> SourceInputType.URL
    }
    return SourceEditScreenState(
        label = label,
        allowFormat = isAllowEnabled,
        type = inputType,
        urlLocation = if (inputType == SourceInputType.URL) {
            url
        } else {
            context.getString(R.string.source_edit_url_location_default)
        },
        fileLocation = if (inputType == SourceInputType.FILE) url else "",
        redirectedHosts = isRedirectEnabled
    )
}

private fun validateSource(screenState: SourceEditScreenState): SourceValidation {
    var state = screenState.copy(labelError = null, locationError = null)
    val label = state.label.trim()
    if (label.isEmpty()) {
        state = state.copy(labelError = R.string.source_edit_label_required)
    }

    val url = if (state.type == SourceInputType.URL) {
        val value = state.urlLocation.trim()
        if (value.isEmpty()) {
            state = state.copy(locationError = R.string.source_edit_url_location_required)
        } else if (!HostsSource.isValidUrl(value)) {
            state = state.copy(locationError = R.string.source_edit_location_invalid)
        }
        value
    } else {
        val value = state.fileLocation.trim()
        if (!HostsSource.isValidUrl(value)) {
            state = state.copy(locationError = R.string.source_edit_location_invalid)
        }
        value
    }

    if (state.labelError != null || state.locationError != null) {
        return SourceValidation(state, null)
    }

    val source = HostsSource().apply {
        this.label = label
        this.url = url
        setAllowEnabled(state.allowFormat)
        setRedirectEnabled(!state.allowFormat && state.redirectedHosts)
    }
    return SourceValidation(state, source)
}

@Composable
private fun SourceEditScreen(
    state: SourceEditScreenState,
    editing: Boolean,
    onNavigateBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onLabelChanged: (String) -> Unit,
    onFormatSelected: (Boolean) -> Unit,
    onTypeSelected: (SourceInputType) -> Unit,
    onUrlChanged: (String) -> Unit,
    onFileLocationClick: () -> Unit,
    onRedirectedChanged: (Boolean) -> Unit
) {
    ExpressiveScaffold(
        topBar = {
            ExpressiveTopBar(
                title = stringResource(
                    if (editing) R.string.source_edit_title else R.string.source_edit_add_title
                ),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (editing) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                painter = painterResource(R.drawable.outline_delete_24),
                                contentDescription = stringResource(R.string.checkbox_list_context_delete)
                            )
                        }
                    }
                    IconButton(onClick = onSave) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_check_24),
                            contentDescription = stringResource(R.string.checkbox_list_context_apply)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            ExpressiveSection(shape = ExpressiveAsymmetricShape1) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.source_edit_label),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = state.label,
                        onValueChange = onLabelChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(text = stringResource(R.string.source_edit_label)) },
                        singleLine = true,
                        isError = state.labelError != null,
                        supportingText = {
                            state.labelError?.let { error ->
                                Text(text = stringResource(error))
                            }
                        },
                        shape = MaterialTheme.shapes.medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            ExpressiveSection(shape = ExpressiveAsymmetricShape2) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.source_edit_format),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(
                            ButtonGroupDefaults.ConnectedSpaceBetween
                        )
                    ) {
                        SourceToggleButton(
                            text = stringResource(R.string.source_edit_format_block_list),
                            selected = !state.allowFormat,
                            leading = true,
                            modifier = Modifier.weight(1f),
                            onClick = { onFormatSelected(false) }
                        )
                        SourceToggleButton(
                            text = stringResource(R.string.source_edit_format_allow_list),
                            selected = state.allowFormat,
                            leading = false,
                            modifier = Modifier.weight(1f),
                            onClick = { onFormatSelected(true) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            ExpressiveSection(shape = ExpressiveAsymmetricShape1) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.source_edit_type),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(
                            ButtonGroupDefaults.ConnectedSpaceBetween
                        )
                    ) {
                        SourceToggleButton(
                            text = stringResource(R.string.source_edit_url),
                            selected = state.type == SourceInputType.URL,
                            leading = true,
                            modifier = Modifier.weight(1f),
                            onClick = { onTypeSelected(SourceInputType.URL) }
                        )
                        SourceToggleButton(
                            text = stringResource(R.string.source_edit_file),
                            selected = state.type == SourceInputType.FILE,
                            leading = false,
                            modifier = Modifier.weight(1f),
                            onClick = { onTypeSelected(SourceInputType.FILE) }
                        )
                    }

                    if (state.type == SourceInputType.URL) {
                        OutlinedTextField(
                            value = state.urlLocation,
                            onValueChange = onUrlChanged,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            label = { Text(text = stringResource(R.string.source_edit_url_location)) },
                            singleLine = true,
                            isError = state.locationError != null,
                            supportingText = {
                                state.locationError?.let { error ->
                                    Text(text = stringResource(error))
                                }
                            },
                            shape = MaterialTheme.shapes.medium
                        )
                    } else {
                        val fileName = rememberDisplayName(state.fileLocation)
                        val fileLocation = when {
                            fileName != null -> fileName
                            state.fileLocation.isNotEmpty() -> state.fileLocation
                            else -> stringResource(R.string.source_edit_file_hint)
                        }
                        OutlinedButton(
                            onClick = onFileLocationClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = fileLocation,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        state.locationError?.let { error ->
                            Text(
                                text = stringResource(error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 8.dp, start = 16.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !state.allowFormat) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    ExpressiveSection(shape = ExpressiveAsymmetricShape2) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            // The whole line toggles the option, not only the box beside it.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .toggleable(
                                        value = state.redirectedHosts,
                                        role = Role.Checkbox,
                                        onValueChange = onRedirectedChanged
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = state.redirectedHosts,
                                    onCheckedChange = null,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                )
                                Text(
                                    text = stringResource(R.string.source_edit_redirected_hosts),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            Text(
                                text = stringResource(R.string.source_edit_redirected_hosts_warning),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * One of two connected buttons choosing between two options. Picking the option already chosen
 * does nothing: a choice changes by picking the other one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SourceToggleButton(
    text: String,
    selected: Boolean,
    leading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { if (!selected) onClick() },
        modifier = modifier.semantics { role = Role.RadioButton },
        shapes = if (leading) {
            ButtonGroupDefaults.connectedLeadingButtonShapes()
        } else {
            ButtonGroupDefaults.connectedTrailingButtonShapes()
        }
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.baseline_check_24),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The name of the picked file, rather than the address the document provider gave for it, or
 * `null` until it is known or when the provider does not say.
 */
@Composable
private fun rememberDisplayName(location: String): String? {
    val context = LocalContext.current
    var name by remember(location) { mutableStateOf<String?>(null) }
    LaunchedEffect(location) {
        if (location.isEmpty()) {
            return@LaunchedEffect
        }
        name = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.query(
                    Uri.parse(location),
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            } catch (exception: RuntimeException) {
                // A provider may refuse once its permission is gone; the address is shown then.
                Timber.d(exception, "Failed to read the name of %s.", location)
                null
            }
        }
    }
    return name
}

private const val ANY_MIME_TYPE = "*/*"
