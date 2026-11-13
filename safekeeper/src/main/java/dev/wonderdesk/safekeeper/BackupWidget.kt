package dev.wonderdesk.safekeeper

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*

/** When each backup last ran, and a button to run it now. */
class BackupWidget : AppWidgetProvider() {
    companion object {
        const val RUN = "dev.wonderdesk.safekeeper.RUN_NOW"
        val IDS = CardIds(R.layout.widget_card, R.layout.widget_card_small, R.id.w_root, R.id.w_img, R.id.w_label, R.id.w_title, R.id.w_text, R.id.w_bar, R.id.w_btn,
            Triple(R.drawable.wd_widget_bg, R.drawable.wd_widget_bg_dark, R.drawable.wd_widget_bg_clear))
        fun build(c: Context, id: Int): android.widget.RemoteViews {
            val s = WidgetPrefs(c, id)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            val jobs = Jobs.all(c)
            val job = jobs.find { it.id == s.str("job", "") } ?: jobs.firstOrNull()
            val open = widgetOpen(c, MainActivity::class.java, 7100 + id % 100)
            val card = if (job == null) Card("Safekeeper", "No backups yet", "Tap to set one up", open = open)
            else Card("Safekeeper", job.name,
                if (job.lastRun == 0L) "Never backed up" else (if (job.lastOk) "✓ " else "⚠ ") + relativeTime(job.lastRun) + if (job.lastFiles > 0) " · ${job.lastFiles} files" else "",
                button = "Back up now", open = open, action = widgetBroadcast(c, BackupWidget::class.java, 7200 + id % 100, RUN) { it.putExtra("job", job.id) })
            return cardWidget(c, IDS, look, card)
        }
        /** Runs [jobId] straight away in the background (same service as scheduled backups). */
        fun runNow(c: Context, jobId: String) {
            val info = JobInfo.Builder(jobId.hashCode() xor 0x5AFE, ComponentName(c, BackupJobService::class.java)).setOverrideDeadline(0)
                .setExtras(android.os.PersistableBundle().apply { putString("job", jobId) }).build()
            runCatching { c.getSystemService(JobScheduler::class.java).schedule(info) }
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } } }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == RUN) i.getStringExtra("job")?.let { runNow(c, it); android.widget.Toast.makeText(c, "Backing up…", android.widget.Toast.LENGTH_SHORT).show() }
    }
}

class BackupWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, BackupWidget.build(this, widgetId)) }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        val jobs = remember { Jobs.all(c) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        var job by remember { mutableStateOf(s.str("job", jobs.firstOrNull()?.id ?: "")) }
        WidgetSetup("Safekeeper", "Backup widget", onCancel = { finish() }, onSave = { s.put("look" to look.name, "job" to job); save() }) {
            if (jobs.isNotEmpty()) Panel(title = "Backup to show") {
                jobs.forEachIndexed { i, j -> if (i > 0) PanelDivider(); ListRow(j.name, subtitle = j.dest.substringAfterLast('/'), selected = j.id == job, onClick = { job = j.id }) }
            } else Text("Set up a backup in Safekeeper first; the widget then shows it.")
            WidgetLookPicker(look) { look = it }
        }
    }
}
