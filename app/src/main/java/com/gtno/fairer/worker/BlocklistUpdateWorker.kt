package com.gtno.fairer.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.gtno.fairer.data.BlocklistUpdater

internal class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        return when (BlocklistUpdater.update(applicationContext)) {
            is BlocklistUpdater.Result.Success -> Result.success()
            is BlocklistUpdater.Result.Failure -> Result.retry()
        }
    }
}
