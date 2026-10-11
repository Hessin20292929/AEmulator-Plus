/* Modified for AEmulator Plus, 2026-10-11: per-VM vendor / modem-firmware / system partition import. GPL-3.0; see LICENSE. */
/* AEmulator Sunset addition, 2026-10-03. GPL-3.0; see LICENSE. */
package app.aemu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VmImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit, apply: (VmSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bootPresent by remember { mutableStateOf(BootPartitionImport.present(VmPaths(ctx, img.id).dir)) }
    var warning by remember { mutableStateOf<VmArchiveStorage.SettingsImport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    fun accept(source: VmArchiveStorage.SettingsImport) {
        apply(source.image.settings)
        message = ctx.getString(R.string.vm_import_settings_done)
    }
    val pickSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val source = VmArchiveStorage.readSettings(ctx, uri)
                    source to source.differs(ctx, ImageStore.get(ctx, img.id) ?: img)
                } }
                setBusy(false)
                result.onSuccess { (source, differs) -> if (differs) warning = source else accept(source) }
                    .onFailure { message = ctx.getString(R.string.vm_import_failed, it.message.orEmpty()) }
            }
        }
    }
    val pickBoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            message = ctx.getString(R.string.vm_import_boot_working)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { BootPartitionImport.install(ctx, img.id, uri) } }
                bootPresent = BootPartitionImport.present(VmPaths(ctx, img.id).dir)
                setBusy(false)
                message = result.fold({ ctx.getString(R.string.vm_import_boot_done) },
                    { ctx.getString(R.string.vm_import_failed, it.message.orEmpty()) })
            }
        }
    }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickSettings.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_import_settings)) }
    if (!bootPresent) TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickBoot.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_import_boot)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    warning?.let { source -> AlertDialog(
        onDismissRequest = { warning = null },
        title = { Text(stringResource(R.string.vm_import_settings_warning_title)) },
        text = { Text(stringResource(R.string.vm_import_settings_warning,
            "${source.image.brand} ${source.image.model} / ${source.image.release} / ${source.image.skin}",
            "${img.brand} ${img.model} / ${img.release} / ${img.skin}")) },
        dismissButton = { TextButton(onClick = { warning = null }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { warning = null; accept(source) }) { Text(stringResource(R.string.vm_import_settings)) } },
    ) }
}

/** Import of the stock OEM partition; shown next to the recovery option in the VM settings. */
@Composable
internal fun OemImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var oemPresent by remember { mutableStateOf(OemPartitionImport.present(VmPaths(ctx, img.id).dir)) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val pickOem = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            message = ctx.getString(R.string.vm_import_oem_working)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { OemPartitionImport.install(ctx, img.id, uri) } }
                oemPresent = OemPartitionImport.present(VmPaths(ctx, img.id).dir)
                setBusy(false)
                message = result.fold({ ctx.getString(R.string.vm_import_oem_done) },
                    { ctx.getString(R.string.vm_import_failed, it.message.orEmpty()) })
            }
        }
    }
    if (!oemPresent) TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickOem.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_import_oem)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/**
 * Added for AEmulator Plus: import of the vendor and modem/firmware partitions, and replacement of /system, for an
 * existing VM. Shown under the OEM option in the VM settings; everything needs all VMs stopped.
 */
@Composable
internal fun PartitionImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = VmPaths(ctx, img.id).dir
    var vendorPresent by remember { mutableStateOf(ExtraPartitionImport.present(dir, ExtraPartitionImport.Kind.VENDOR)) }
    var firmwarePresent by remember { mutableStateOf(ExtraPartitionImport.present(dir, ExtraPartitionImport.Kind.FIRMWARE)) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var confirmSystem by remember { mutableStateOf(false) }

    fun launchImport(working: Int, done: Int, job: () -> Unit) {
        setBusy(true)
        message = ctx.getString(working)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { job() } }
            vendorPresent = ExtraPartitionImport.present(dir, ExtraPartitionImport.Kind.VENDOR)
            firmwarePresent = ExtraPartitionImport.present(dir, ExtraPartitionImport.Kind.FIRMWARE)
            setBusy(false)
            message = result.fold({ ctx.getString(done) }, { ctx.getString(R.string.vm_import_failed, it.message.orEmpty()) })
        }
    }

    val pickVendor = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) launchImport(R.string.vm_import_vendor_working, R.string.vm_import_vendor_done) {
            ExtraPartitionImport.install(ctx, img.id, uri, ExtraPartitionImport.Kind.VENDOR)
        }
    }
    val pickFirmware = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) launchImport(R.string.vm_import_firmware_working, R.string.vm_import_firmware_done) {
            ExtraPartitionImport.install(ctx, img.id, uri, ExtraPartitionImport.Kind.FIRMWARE)
        }
    }
    val pickSystem = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking = false
        if (uri != null) launchImport(R.string.vm_import_system_working, R.string.vm_import_system_done) {
            SystemPartitionImport.install(ctx, img.id, uri)
        }
    }

    if (!vendorPresent) TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickVendor.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_import_vendor)) }
    if (!firmwarePresent) TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickFirmware.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_import_firmware)) }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = { confirmSystem = true }) {
        Text(stringResource(R.string.vm_import_system))
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (confirmSystem) AlertDialog(
        onDismissRequest = { confirmSystem = false },
        title = { Text(stringResource(R.string.vm_import_system_title)) },
        text = { Text(stringResource(R.string.vm_import_system_warning)) },
        dismissButton = { TextButton(onClick = { confirmSystem = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            confirmSystem = false; picking = true; pickSystem.launch(arrayOf("*/*"))
        }) { Text(stringResource(R.string.vm_import_system)) } },
    )
}
