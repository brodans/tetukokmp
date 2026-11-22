package id.tetuko.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class TetukoAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val request = OneTimeWorkRequestBuilder<TetukoAutomationWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
        TetukoAndroidAutomation.install(context)
    }
}
