package helium314.keyboard.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SiteXLSyncWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        if (!SiteXLSyncManager.isAutoSyncEnabled(applicationContext) ||
            !SiteXLSyncManager.isConfigured(applicationContext)
        ) return Result.success()
        return try {
            SiteXLSyncManager.sync(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
