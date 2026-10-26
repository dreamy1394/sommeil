package com.example.sommeil

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Tâche de fond (2 fois par jour) : met à jour l'historique même si on n'ouvre pas l'appli.
 * Android ne conserve les événements d'écran qu'environ une semaine, d'où l'enregistrement local.
 */
class SleepWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        SleepRefresher.refresh(applicationContext, days = 3)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SleepWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sommeil-refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
