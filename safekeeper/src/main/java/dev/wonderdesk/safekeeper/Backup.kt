package dev.wonderdesk.safekeeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class Mode(val label: String) { Snapshot("Snapshots"), Mirror("Mirror") }
enum class Schedule(val label: String, val ms: Long) { Manual("Only when I tap Back up", 0), Daily("Every day", 86_400_000L), Weekly("Every week", 7 * 86_400_000L) }

data class Job(
    val id: String = UUID.randomUUID().toString(), val name: String = "My backup",
    val sources: List<String> = emptyList(), val dest: String = "", val mode: Mode = Mode.Snapshot,
    val keep: Int = 5, val schedule: Schedule = Schedule.Manual, val charging: Boolean = true, val unmetered: Boolean = false,
    val skipHidden: Boolean = true, val mirrorDeletes: Boolean = false,
    val lastRun: Long = 0, val lastResult: String = "", val lastOk: Boolean = true, val lastBytes: Long = 0, val lastFiles: Int = 0,
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("sources", JSONArray(sources)).put("dest", dest).put("mode", mode.name).put("keep", keep)
        .put("schedule", schedule.name).put("charging", charging).put("unmetered", unmetered).put("skipHidden", skipHidden).put("mirrorDeletes", mirrorDeletes)
        .put("lastRun", lastRun).put("lastResult", lastResult).put("lastOk", lastOk).put("lastBytes", lastBytes).put("lastFiles", lastFiles)
    companion object {
        fun from(o: JSONObject) = Job(o.getString("id"), o.optString("name"), o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            o.optString("dest"), runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.Snapshot), o.optInt("keep", 5),
            runCatching { Schedule.valueOf(o.optString("schedule")) }.getOrDefault(Schedule.Manual), o.optBoolean("charging", true), o.optBoolean("unmetered"),
            o.optBoolean("skipHidden", true), o.optBoolean("mirrorDeletes"), o.optLong("lastRun"), o.optString("lastResult"), o.optBoolean("lastOk", true), o.optLong("lastBytes"), o.optInt("lastFiles"))
    }
}

data class Snapshot(val file: DocumentFile, val name: String, val time: Long, val size: Long)
data class ManifestEntry(val path: String, val size: Long, val mtime: Long, val sha256: String)

class BackupError(msg: String) : Exception(msg)

object Jobs {
    private fun prefs(c: Context) = c.getSharedPreferences("safekeeper", Context.MODE_PRIVATE)
    fun all(c: Context): List<Job> = runCatching { JSONArray(prefs(c).getString("jobs", "[]")).let { a -> (0 until a.length()).map { Job.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
    fun save(c: Context, list: List<Job>) { prefs(c).edit().putString("jobs", JSONArray(list.map { it.toJson() }).toString()).apply(); schedule(c, list) }
    fun update(c: Context, j: Job) { save(c, all(c).map { if (it.id == j.id) j else it }.let { l -> if (l.none { it.id == j.id }) l + j else l }); dev.wonderdesk.shared.refreshWidgets(c, BackupWidget::class.java) }

    fun log(c: Context): List<String> = runCatching { JSONArray(prefs(c).getString("log", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    fun addLog(c: Context, line: String) { val l = (listOf(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date()) + " · " + line) + log(c)).take(100)
        prefs(c).edit().putString("log", JSONArray(l).toString()).apply() }

    fun schedule(c: Context, list: List<Job>) {
        val js = c.getSystemService(JobScheduler::class.java)
        list.forEach { j ->
            val jid = j.id.hashCode()
            if (j.schedule == Schedule.Manual) { js.cancel(jid); return@forEach }
            val info = JobInfo.Builder(jid, ComponentName(c, BackupJobService::class.java))
                .setPeriodic(j.schedule.ms, 6 * 3_600_000L)
                .setRequiresCharging(j.charging)
                .setRequiredNetworkType(if (j.unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_NONE)
                .setRequiresStorageNotLow(true).setPersisted(true)
                .setExtras(android.os.PersistableBundle().apply { putString("job", j.id) })
                .build()
            js.schedule(info)
        }
        js.allPendingJobs.filter { p -> list.none { it.id.hashCode() == p.id } }.forEach { js.cancel(it.id) }
    }
}

object Engine {
    @Volatile var cancel = false

    private fun sha256(c: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        c.contentResolver.openInputStream(uri)!!.use { i -> val b = ByteArray(128 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Every file under the job's sources as (relative path, document). */
    fun collect(c: Context, j: Job): List<Pair<String, DocumentFile>> {
        val out = mutableListOf<Pair<String, DocumentFile>>()
        j.sources.forEach { s ->
            val root = DocumentFile.fromTreeUri(c, Uri.parse(s)) ?: return@forEach
            if (!root.canRead()) throw BackupError("Safekeeper lost access to ${root.name}. Edit the backup and add the folder again.")
            fun walk(d: DocumentFile, path: String) {
                if (cancel) throw BackupError("Cancelled")
                d.listFiles().forEach { f ->
                    val n = f.name ?: return@forEach
                    if (j.skipHidden && n.startsWith(".")) return@forEach
                    if (f.isDirectory) walk(f, "$path/$n") else out += "$path/$n" to f
                }
            }
            walk(root, root.name ?: "Folder")
        }
        return out
    }

    fun destFolder(c: Context, j: Job): DocumentFile {
        val d = DocumentFile.fromTreeUri(c, Uri.parse(j.dest)) ?: throw BackupError("Choose where to save backups")
        if (!d.canWrite()) throw BackupError("Safekeeper can't write to the backup folder. Is the SD card or drive connected?")
        val safeName = j.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Backup" }
        return d.findFile(safeName)?.takeIf { it.isDirectory } ?: d.createDirectory(safeName) ?: throw BackupError("Couldn't create the backup folder")
    }

    fun snapshots(c: Context, j: Job): List<Snapshot> = runCatching {
        destFolder(c, j).listFiles().filter { it.name?.startsWith("snapshot-") == true && it.name!!.endsWith(".zip") }.map { f ->
            val t = runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).parse(f.name!!.removePrefix("snapshot-").removeSuffix(".zip"))!!.time }.getOrDefault(f.lastModified())
            Snapshot(f, f.name!!, t, f.length())
        }.sortedByDescending { it.time }
    }.getOrDefault(emptyList())

    /** Runs a job and returns a one-line summary. */
    fun run(c: Context, j: Job, onProgress: (Int, Int, String) -> Unit): Job {
        cancel = false
        val files = collect(c, j)
        if (files.isEmpty()) throw BackupError("The chosen folders are empty")
        val dest = destFolder(c, j)
        var bytes = 0L
        var count = 0
        when (j.mode) {
            Mode.Snapshot -> {
                val name = "snapshot-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip"
                val out = dest.createFile("application/zip", name) ?: throw BackupError("Couldn't create the snapshot file")
                val manifest = JSONArray()
                try {
                    c.contentResolver.openOutputStream(out.uri, "wt")!!.use { os ->
                        ZipOutputStream(os.buffered()).use { z ->
                            files.forEachIndexed { i, (path, f) ->
                                if (cancel) throw BackupError("Cancelled")
                                onProgress(i + 1, files.size, path)
                                val md = MessageDigest.getInstance("SHA-256")
                                z.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
                                c.contentResolver.openInputStream(f.uri)!!.use { input -> val b = ByteArray(128 * 1024); while (true) { val n = input.read(b); if (n < 0) break; z.write(b, 0, n); md.update(b, 0, n); bytes += n } }
                                z.closeEntry()
                                manifest.put(JSONObject().put("path", path).put("size", f.length()).put("mtime", f.lastModified()).put("sha256", md.digest().joinToString("") { "%02x".format(it) }))
                                count++
                            }
                            z.putNextEntry(ZipEntry(MANIFEST))
                            z.write(JSONObject().put("app", "Wonderdesk Safekeeper").put("job", j.name).put("created", System.currentTimeMillis()).put("files", manifest).toString(2).toByteArray())
                            z.closeEntry()
                        }
                    }
                } catch (e: Exception) { out.delete(); throw e }
                // Keep the newest [keep] snapshots.
                snapshots(c, j).drop(j.keep.coerceAtLeast(1)).forEach { it.file.delete() }
            }
            Mode.Mirror -> {
                val mirror = dest.findFile("mirror")?.takeIf { it.isDirectory } ?: dest.createDirectory("mirror") ?: throw BackupError("Couldn't create the mirror folder")
                val dirs = HashMap<String, DocumentFile>()
                fun folder(path: String): DocumentFile {
                    if (path.isEmpty()) return mirror
                    dirs[path]?.let { return it }
                    val parent = folder(path.substringBeforeLast('/', ""))
                    val n = path.substringAfterLast('/')
                    return (parent.findFile(n)?.takeIf { it.isDirectory } ?: parent.createDirectory(n) ?: throw BackupError("Couldn't create $n")).also { dirs[path] = it }
                }
                val seen = HashSet<String>()
                files.forEachIndexed { i, (path, f) ->
                    if (cancel) throw BackupError("Cancelled")
                    onProgress(i + 1, files.size, path); seen += path
                    val parent = folder(path.substringBeforeLast('/', ""))
                    val n = path.substringAfterLast('/')
                    val existing = parent.findFile(n)
                    if (existing != null && existing.length() == f.length() && existing.lastModified() >= f.lastModified()) return@forEachIndexed
                    existing?.delete()
                    val t = parent.createFile("application/octet-stream", n) ?: throw BackupError("Couldn't create $n")
                    c.contentResolver.openInputStream(f.uri)!!.use { input -> c.contentResolver.openOutputStream(t.uri)!!.use { o -> bytes += input.copyTo(o, 128 * 1024) } }
                    count++
                }
                if (j.mirrorDeletes) {
                    fun prune(d: DocumentFile, path: String) {
                        d.listFiles().forEach { f -> val p = if (path.isEmpty()) f.name!! else "$path/${f.name}"
                            if (f.isDirectory) prune(f, p) else if (p !in seen) f.delete() }
                    }
                    prune(mirror, "")
                }
            }
        }
        val summary = if (j.mode == Mode.Snapshot) "Saved $count files (${formatSize(bytes)})" else if (count == 0) "Mirror already up to date" else "Copied $count changed files (${formatSize(bytes)})"
        return j.copy(lastRun = System.currentTimeMillis(), lastResult = summary, lastOk = true, lastBytes = bytes, lastFiles = files.size)
    }

    fun readManifest(c: Context, snap: Snapshot): List<ManifestEntry> {
        c.contentResolver.openInputStream(snap.file.uri)!!.use { i ->
            ZipInputStream(i.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == MANIFEST) {
                        val o = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                        val a = o.getJSONArray("files")
                        return (0 until a.length()).map { a.getJSONObject(it).let { f -> ManifestEntry(f.getString("path"), f.getLong("size"), f.getLong("mtime"), f.getString("sha256")) } }
                    }
                }
            }
        }
        throw BackupError("This snapshot has no manifest")
    }

    /** Re-reads every file and checks its checksum against the manifest. Returns problems found. */
    fun verify(c: Context, snap: Snapshot, onProgress: (Int, Int) -> Unit): List<String> {
        val expected = readManifest(c, snap).associateBy { it.path }
        val problems = mutableListOf<String>()
        val found = HashSet<String>()
        var n = 0
        c.contentResolver.openInputStream(snap.file.uri)!!.use { i ->
            ZipInputStream(i.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == MANIFEST || e.isDirectory) continue
                    val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(128 * 1024)
                    while (true) { val r = z.read(b); if (r < 0) break; md.update(b, 0, r) }
                    val sum = md.digest().joinToString("") { "%02x".format(it) }
                    found += e.name
                    if (expected[e.name]?.sha256 != sum) problems += "${e.name} doesn't match its checksum"
                    onProgress(++n, expected.size)
                }
            }
        }
        expected.keys.filter { it !in found }.forEach { problems += "$it is missing" }
        return problems
    }

    /** Restores the whole snapshot, or only [only], into a folder the user picked. Never overwrites: existing names get a suffix. */
    fun restore(c: Context, snap: Snapshot, target: DocumentFile, only: Set<String>?, onProgress: (Int, String) -> Unit): Int {
        val dirs = HashMap<String, DocumentFile>()
        fun folder(path: String): DocumentFile {
            if (path.isEmpty()) return target
            dirs[path]?.let { return it }
            val parent = folder(path.substringBeforeLast('/', ""))
            val n = path.substringAfterLast('/')
            return (parent.findFile(n)?.takeIf { it.isDirectory } ?: parent.createDirectory(n)!!).also { dirs[path] = it }
        }
        var count = 0
        c.contentResolver.openInputStream(snap.file.uri)!!.use { i ->
            ZipInputStream(i.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == MANIFEST || e.isDirectory) continue
                    if (e.name.split('/').any { it == ".." }) continue
                    if (only != null && only.none { e.name == it || e.name.startsWith("$it/") }) continue
                    val parent = folder(e.name.substringBeforeLast('/', ""))
                    var n = e.name.substringAfterLast('/')
                    if (parent.findFile(n) != null) n = n.substringBeforeLast('.', n) + " (restored)" + n.substringAfterLast('.', "").let { if (it.isEmpty() || it == n) "" else ".$it" }
                    val f = parent.createFile("application/octet-stream", n) ?: continue
                    c.contentResolver.openOutputStream(f.uri)!!.use { z.copyTo(it, 128 * 1024) }
                    onProgress(++count, e.name)
                }
            }
        }
        return count
    }

    const val MANIFEST = "safekeeper-manifest.json"
}

fun formatSize(b: Long) = dev.wonderdesk.shared.formatBytes(b)

/* ------------------------------------------------------ scheduling ------------------------------------------- */

object Notes {
    const val CHANNEL = "safekeeper-backups"
    fun channel(c: Context) = c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_LOW))
    fun post(c: Context, id: Int, title: String, text: String, progress: Pair<Int, Int>? = null) {
        channel(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle(title).setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
        if (progress != null) b.setProgress(progress.second, progress.first, false).setOngoing(true) else b.setAutoCancel(true)
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, b.build()) }
    }
}

class BackupJobService : JobService() {
    private var worker: Thread? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString("job") ?: return false
        val job = Jobs.all(this).find { it.id == id } ?: return false
        worker = Thread {
            val nid = id.hashCode()
            val result = runCatching {
                Engine.run(this, job) { i, n, _ -> if (i % 20 == 0 || i == n) Notes.post(this, nid, "Backing up ${job.name}", "$i of $n files", i to n) }
            }
            result.onSuccess { j -> Jobs.update(this, j); Jobs.addLog(this, "${job.name}: ${j.lastResult}"); Notes.post(this, nid, "${job.name} backed up", j.lastResult) }
                .onFailure { e -> Jobs.update(this, job.copy(lastRun = System.currentTimeMillis(), lastOk = false, lastResult = e.message ?: "Failed")); Jobs.addLog(this, "${job.name} failed: ${e.message}")
                    Notes.post(this, nid, "${job.name} didn't finish", e.message ?: "Backup failed") }
            jobFinished(params, result.isFailure)
        }.also { it.start() }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { Engine.cancel = true; return true }
}
