package dev.wonderdesk.safekeeper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : WonderActivity() {
    @Composable override fun Content() = SafekeeperApp()
}

private const val ABOUT = "Safekeeper backs up the folders you choose to an SD card, USB drive or any folder Android can reach, including cloud storage apps. " +
    "Keep versioned snapshots with checksums, or a mirror that copies only what changed, on a schedule or whenever you tap Back up. " +
    "It can't back up other apps' private data or Android itself."

private fun Context.treeLabel(s: String): String = runCatching {
    val id = DocumentsContract.getTreeDocumentId(Uri.parse(s))
    val vol = id.substringBefore(':'); val path = id.substringAfter(':')
    (if (vol == "primary") "Phone" else if (vol.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}"))) "SD card / USB" else DocumentFile.fromTreeUri(this, Uri.parse(s))?.name ?: vol) +
        (if (path.isNotBlank()) " › " + path.replace("/", " › ") else "")
}.getOrDefault(DocumentFile.fromTreeUri(this, Uri.parse(s))?.name ?: "Folder")

@Composable
fun SafekeeperApp() {
    val context = LocalContext.current
    var jobs by remember { mutableStateOf(Jobs.all(context)) }
    var editing by remember { mutableStateOf<Job?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf("backups") }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var runningPath by remember { mutableStateOf("") }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun refresh() { jobs = Jobs.all(context) }
    fun runNow(j: Job) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        running = Triple(j.name, 0, 0)
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Engine.run(context, j) { i, n, p -> scope.launch { running = Triple(j.name, i, n); runningPath = p } } } }
            running = null
            r.onSuccess { done -> Jobs.update(context, done); Jobs.addLog(context, "${j.name}: ${done.lastResult}"); toastRef.value(done.lastResult) }
                .onFailure { e -> Jobs.update(context, j.copy(lastRun = System.currentTimeMillis(), lastOk = false, lastResult = e.message ?: "Failed")); Jobs.addLog(context, "${j.name} failed: ${e.message}"); toastRef.value(e.message ?: "Backup failed") }
            refresh()
        }
    }
    val e = editing
    if (e != null) { JobEditor(e, onClose = { editing = null }, onSave = { j -> Jobs.update(context, j); refresh(); editing = null; openId = j.id },
        onDelete = { Jobs.save(context, Jobs.all(context).filter { it.id != e.id }); refresh(); editing = null; openId = null }); return }
    val open = openId?.let { id -> jobs.find { it.id == id } }
    if (open != null) { JobDetail(open, running, runningPath, onBack = { openId = null }, onEdit = { editing = open }, onRun = { runNow(open) }); return }
    WonderApp(appName = "Safekeeper", about = ABOUT,
        tabs = listOf(WonderTab("backups", "Backups", WonderIcons.Backup), WonderTab("log", "History", WonderIcons.History)), selectedTab = tab, onTab = { tab = it },
        actions = { if (tab == "backups") ToolbarButton(WonderIcons.Add, "New backup", { editing = Job() }) }) {
        toastRef.value = LocalToast.current
        val p = wonderPalette()
        if (tab == "log") {
            val log = remember { Jobs.log(context) }
            if (log.isEmpty()) EmptyState("No backups yet", "Each backup and restore is listed here.", icon = WonderIcons.History)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                itemsIndexed(log) { i, l -> ListRow(l.substringAfter(" · "), Modifier.panelItem(i, log.size), subtitle = l.substringBefore(" · "),
                    icon = if ("failed" in l) WonderIcons.Error else WonderIcons.CheckCircle, tint = if ("failed" in l) p.danger else p.success) }
            }
            return@WonderApp
        }
        if (jobs.isEmpty()) {
            EmptyState("Protect your files", "Make a backup of your photos, documents or any folder. Save it to an SD card, a USB drive or a cloud folder, and let it run by itself.",
                action = { WButton("Set up a backup", { editing = Job() }, icon = WonderIcons.Add, kind = ButtonKind.Primary) })
            return@WonderApp
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(jobs, key = { _, j -> j.id }) { _, j ->
                Panel {
                    Row(Modifier.clickable { openId = j.id }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(if (j.mode == Mode.Snapshot) WonderIcons.Backup else WonderIcons.Sync, if (j.mode == Mode.Snapshot) Color(0xFF2E9E44) else Color(0xFF1E7FE8), 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(j.name, style = MaterialTheme.typography.titleMedium)
                            Text("${j.sources.size} folder${if (j.sources.size == 1) "" else "s"} → ${context.treeLabel(j.dest)}", style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (j.lastRun == 0L) "Never backed up" else (if (j.lastOk) "✓ " else "⚠ ") + relativeTime(j.lastRun) + " · " + j.lastResult,
                                style = MaterialTheme.typography.labelSmall, color = if (j.lastOk) p.secondaryText else p.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(j.schedule.label + if (j.schedule != Schedule.Manual && j.charging) ", while charging" else "", style = MaterialTheme.typography.labelSmall, color = p.accent)
                        }
                        WButton("Back up", { runNow(j) }, kind = ButtonKind.Primary, enabled = running == null)
                    }
                }
            }
        }
    }
    running?.let { (name, i, n) -> ProgressDialog("Backing up $name", i, n, runningPath) }
}

@Composable
private fun ProgressDialog(title: String, i: Int, n: Int, path: String) {
    val p = wonderPalette()
    WonderDialog(onDismissRequest = {}, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AquaProgress(if (n > 0) i.toFloat() / n else null, Modifier.fillMaxWidth())
            Text(if (n > 0) "$i of $n files" else "Looking through your folders…", style = MaterialTheme.typography.bodyMedium)
            if (path.isNotEmpty()) Text(path, style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }, confirmButton = { TextButton(onClick = { Engine.cancel = true }) { Text("Cancel") } })
}

/* ------------------------------------------------------- editor ---------------------------------------------- */

@Composable
private fun JobEditor(initial: Job, onClose: () -> Unit, onSave: (Job) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var j by remember { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val src = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { t ->
        if (t != null) { runCatching { context.contentResolver.takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; j = j.copy(sources = (j.sources + t.toString()).distinct())
            if (j.name == "My backup") j = j.copy(name = DocumentFile.fromTreeUri(context, t)?.name ?: j.name) }
    }
    val dst = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { t ->
        if (t != null) { runCatching { context.contentResolver.takePersistableUriPermission(t, flags) }; j = j.copy(dest = t.toString()) }
    }
    val isNew = Jobs.all(context).none { it.id == initial.id }
    WonderApp(appName = "Safekeeper", about = ABOUT, title = if (isNew) "New backup" else "Edit backup", onBack = onClose, actions = {
        TextButton(enabled = j.sources.isNotEmpty() && j.dest.isNotEmpty(), onClick = { onSave(j) }) { Text("Save", color = p.toolbarText, style = MaterialTheme.typography.titleMedium) }
    }, menu = if (!isNew) listOf(WonderMenuItem("Delete this backup", WonderIcons.Delete, danger = true) { confirmDelete = true }) else emptyList()) {
        Page {
            Panel { Column(Modifier.padding(12.dp)) { Field(j.name, { j = j.copy(name = it) }, "Name") } }
            Panel(title = "What to back up", footer = "Tip: choose DCIM for camera photos, Documents, or Download.") {
                j.sources.forEachIndexed { i, s ->
                    if (i > 0) PanelDivider()
                    ListRow(context.treeLabel(s), icon = WonderIcons.Folder, tint = Color(0xFF4A90E2), trailing = { IconButton(onClick = { j = j.copy(sources = j.sources - s) }) { Icon(WonderIcons.Close, "Remove", tint = p.secondaryText) } })
                }
                if (j.sources.isNotEmpty()) PanelDivider()
                ListRow("Add a folder", leading = { Icon(WonderIcons.AddCircle, null, tint = p.accent) }, onClick = { src.launch(null) })
            }
            Panel(title = "Where to save it", footer = "Pick a folder on an SD card or USB drive, or in a cloud app such as Google Drive, so a lost phone doesn't take your backup with it.") {
                ListRow(if (j.dest.isEmpty()) "Choose a folder" else context.treeLabel(j.dest), icon = WonderIcons.SdStorage, tint = Color(0xFFE67E22), chevron = true, onClick = { dst.launch(null) })
            }
            Panel(title = "How") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(Mode.entries.map { it.label }, j.mode.ordinal) { j = j.copy(mode = Mode.entries[it]) }
                    Text(if (j.mode == Mode.Snapshot) "Each run saves a complete, timestamped ZIP with checksums you can verify and restore from." else "Keeps one copy that matches your folders, copying only new and changed files each time.",
                        style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    if (j.mode == Mode.Snapshot) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep the newest", Modifier.weight(1f))
                        listOf(1, 3, 5, 10, 30).forEach { k -> Chip("$k", j.keep == k, { j = j.copy(keep = k) }); Spacer(Modifier.width(4.dp)) }
                    }
                }
                if (j.mode == Mode.Mirror) { PanelDivider(0.dp); SwitchRow("Remove files I've deleted", j.mirrorDeletes, { j = j.copy(mirrorDeletes = it) }, subtitle = "Keeps the mirror an exact copy") }
                PanelDivider(0.dp)
                SwitchRow("Skip hidden files", j.skipHidden, { j = j.copy(skipHidden = it) }, subtitle = "Files and folders whose names start with a dot")
            }
            Panel(title = "When") {
                Schedule.entries.forEachIndexed { i, s ->
                    if (i > 0) PanelDivider(16.dp)
                    ListRow(s.label, selected = j.schedule == s, trailing = { if (j.schedule == s) Icon(WonderIcons.Check, null, tint = p.accent) }, onClick = { j = j.copy(schedule = s) })
                }
                if (j.schedule != Schedule.Manual) {
                    PanelDivider(0.dp)
                    SwitchRow("Only while charging", j.charging, { j = j.copy(charging = it) }, icon = WonderIcons.BatteryChargingFull, tint = Color(0xFF2E9E44))
                    PanelDivider()
                    SwitchRow("Only on Wi-Fi", j.unmetered, { j = j.copy(unmetered = it) }, subtitle = "For cloud folders", icon = WonderIcons.Wifi, tint = Color(0xFF1E7FE8))
                }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this backup?", "The schedule stops. Snapshots already saved stay where they are.", "Delete", onConfirm = onDelete, onDismiss = { confirmDelete = false }, danger = true)
}

/* ------------------------------------------------------- detail ---------------------------------------------- */

@Composable
private fun JobDetail(j: Job, running: Triple<String, Int, Int>?, runningPath: String, onBack: () -> Unit, onEdit: () -> Unit, onRun: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var snaps by remember { mutableStateOf<List<Snapshot>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var browsing by remember { mutableStateOf<Snapshot?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    LaunchedEffect(j.lastRun) { snaps = withContext(Dispatchers.IO) { Engine.snapshots(context, j) } }
    var restoreReq by remember { mutableStateOf<Pair<Snapshot, Set<String>?>?>(null) }
    val target = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { t ->
        val req = restoreReq ?: return@rememberLauncherForActivityResult
        if (t == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        busy = "Restoring…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Engine.restore(context, req.first, DocumentFile.fromTreeUri(context, t)!!, req.second) { n, _ -> scope.launch { busy = "Restoring… $n files" } } } }
            busy = null; browsing = null
            r.onSuccess { Jobs.addLog(context, "Restored $it files from ${j.name}"); toastRef.value("Restored $it files") }.onFailure { toastRef.value(it.message ?: "Restore failed") }
        }
    }
    val b = browsing
    if (b != null) { SnapshotBrowser(b, onBack = { browsing = null }, onRestore = { only -> restoreReq = b to only; target.launch(null) }); return }
    WonderApp(appName = "Safekeeper", about = ABOUT, title = j.name, onBack = onBack, actions = { ToolbarButton(WonderIcons.Edit, "Edit", onEdit) }) {
        toastRef.value = LocalToast.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (j.lastRun == 0L) "Not backed up yet" else "Last backup " + relativeTime(j.lastRun), style = MaterialTheme.typography.titleLarge)
                        if (j.lastResult.isNotEmpty()) Text(j.lastResult, color = if (j.lastOk) p.secondaryText else p.danger)
                        Text("${j.mode.label} · ${j.schedule.label}", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                        Spacer(Modifier.height(6.dp))
                        WButton("Back up now", onRun, Modifier.fillMaxWidth(), icon = WonderIcons.Backup, kind = ButtonKind.Primary, enabled = running == null)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (j.mode == Mode.Snapshot) {
                item { SectionLabel("Snapshots", Modifier.padding(start = 6.dp, bottom = 6.dp)) }
                val list = snaps
                when {
                    list == null -> item { AquaProgress(null, Modifier.fillMaxWidth()) }
                    list.isEmpty() -> item { Panel { ListRow("No snapshots yet", subtitle = "Tap Back up now to make the first one", icon = WonderIcons.Info, tint = Color(0xFF7A7F87)) } }
                    else -> itemsIndexed(list, key = { j, s -> "${s.name}#$j" }) { i, s ->
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            ListRow(DateUtils.formatDateTime(context, s.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR),
                                Modifier.panelItem(i, list.size), subtitle = formatSize(s.size) + if (i == 0) " · newest" else "", icon = WonderIcons.Inventory2, tint = Color(0xFF2E9E44), chevron = true,
                                onClick = { menu = true })
                            DropdownMenu(menu, { menu = false }, Modifier.background(p.panel)) {
                                DropdownMenuItem({ Text("Browse and restore files") }, { menu = false; browsing = s }, leadingIcon = { Icon(WonderIcons.FolderOpen, null) })
                                DropdownMenuItem({ Text("Restore everything…") }, { menu = false; restoreReq = s to null; target.launch(null) }, leadingIcon = { Icon(WonderIcons.Restore, null) })
                                DropdownMenuItem({ Text("Verify checksums") }, {
                                    menu = false; busy = "Verifying…"
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { runCatching { Engine.verify(context, s) { n, t -> scope.launch { busy = "Verifying… $n of $t" } } } }
                                        busy = null
                                        toastRef.value(r.fold({ if (it.isEmpty()) "Every file matches its checksum" else "${it.size} problem${if (it.size == 1) "" else "s"}: ${it.first()}" }, { it.message ?: "Couldn't verify" }))
                                    }
                                }, leadingIcon = { Icon(WonderIcons.Verified, null) })
                                DropdownMenuItem({ Text("Delete snapshot", color = p.danger) }, { menu = false; s.file.delete(); snaps = snaps?.minus(s) }, leadingIcon = { Icon(WonderIcons.Delete, null, tint = p.danger) })
                            }
                        }
                    }
                }
            } else item {
                Panel { ListRow("Mirror folder", subtitle = context.treeLabel(j.dest) + " › ${j.name} › mirror", icon = WonderIcons.Sync, tint = Color(0xFF1E7FE8)) }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Panel(title = "Backing up") {
                    j.sources.forEachIndexed { i, s -> if (i > 0) PanelDivider(); ListRow(context.treeLabel(s), icon = WonderIcons.Folder, tint = Color(0xFF4A90E2)) }
                    PanelDivider()
                    ListRow("Saved to " + context.treeLabel(j.dest), icon = WonderIcons.SdStorage, tint = Color(0xFFE67E22))
                }
            }
        }
    }
    running?.let { (name, i, n) -> ProgressDialog("Backing up $name", i, n, runningPath) }
    busy?.let { msg -> WonderDialog(onDismissRequest = {}, title = { Text(msg) }, text = { AquaProgress(null, Modifier.fillMaxWidth()) }, confirmButton = {}) }
}

@Composable
private fun SnapshotBrowser(s: Snapshot, onBack: () -> Unit, onRestore: (Set<String>?) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var files by remember { mutableStateOf<List<ManifestEntry>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var dir by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(s) { withContext(Dispatchers.IO) { runCatching { Engine.readManifest(context, s) } }.onSuccess { files = it }.onFailure { err = it.message } }
    WonderApp(appName = "Safekeeper", about = ABOUT, title = if (selected.isEmpty()) "Snapshot" else "${selected.size} selected",
        subtitle = DateUtils.formatDateTime(context, s.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), onBack = { if (dir.isNotEmpty()) dir = dir.substringBeforeLast('/', "") else onBack() },
        actions = { ToolbarButton(WonderIcons.Restore, if (selected.isEmpty()) "Restore all" else "Restore selected", { onRestore(selected.takeIf { it.isNotEmpty() }) }) }) {
        val list = files
        when {
            err != null -> EmptyState("Can't read this snapshot", err!!, icon = WonderIcons.Error)
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
            else -> {
                val prefix = if (dir.isEmpty()) "" else "$dir/"
                val children = list.filter { it.path.startsWith(prefix) }.groupBy { it.path.removePrefix(prefix).substringBefore('/') }
                    .map { (name, items) -> Triple(name, items.size > 1 || items[0].path.removePrefix(prefix).contains('/'), items) }.sortedWith(compareByDescending<Triple<String, Boolean, List<ManifestEntry>>> { it.second }.thenBy { it.first.lowercase() })
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    item { Text((if (dir.isEmpty()) "" else "$dir · ") + "${list.size} files · ${formatSize(list.sumOf { it.size })}", Modifier.padding(6.dp, 0.dp, 6.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
                    itemsIndexed(children) { i, (name, isDir, items) ->
                        val key = prefix + name
                        val sel = key in selected
                        ListRow(name, Modifier.panelItem(i, children.size), selected = sel, subtitle = if (isDir) "${items.size} files · ${formatSize(items.sumOf { it.size })}" else formatSize(items[0].size),
                            icon = if (isDir) WonderIcons.Folder else WonderIcons.InsertDriveFile, tint = if (isDir) Color(0xFF4A90E2) else Color(0xFF95A5A6),
                            trailing = { Checkbox(sel, { on -> selected = if (on) selected + key else selected - key }) },
                            onClick = { if (isDir) dir = key else selected = if (sel) selected - key else selected + key })
                    }
                }
            }
        }
    }
}
