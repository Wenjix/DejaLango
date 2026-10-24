package dev.dejalango.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.dejalango.app.R
import dev.dejalango.app.appGraph
import dev.dejalango.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service (type: microphone) that owns the pipeline. The activity binds to read HudState and
 * send actions; renderers that must outlive the activity (a Presentation on USB-C glasses, the web HUD)
 * hang off the runtime here.
 */
class ListenService : Service() {

    inner class LocalBinder : Binder() {
        val service: ListenService get() = this@ListenService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _runtime = MutableStateFlow<PipelineRuntime?>(null)
    val runtimeFlow: StateFlow<PipelineRuntime?> = _runtime.asStateFlow()
    val runtime: PipelineRuntime? get() = _runtime.value
    val started: Boolean get() = _runtime.value != null
    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private var startJob: Job? = null
    private var closingRuntime: PipelineRuntime? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (startJob?.isActive != true && (_runtime.value == null || _runtime.value?.hud?.value?.modelReady == false)) {
                _runtime.value?.let { it.stop(); closingRuntime = it }
                _runtime.value = null
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                _starting.value = true
                _error.value = null
                startJob = scope.launch {
                    try {
                        appGraph.configStore.awaitReady()
                        closingRuntime?.awaitClosed()
                        closingRuntime = null
                        val rt = PipelineRuntime(this@ListenService, appGraph, scope)
                        _runtime.value = rt
                        rt.start()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        Log.e(TAG, "runtime failed to start", t)
                        _runtime.value?.let { it.stop(); closingRuntime = it }
                        _runtime.value = null
                        _error.value = t.message ?: "Couldn't start listening. Please try again."
                    } finally {
                        _starting.value = false
                    }
                }
            }
            ACTION_STOP -> {
                startJob?.cancel(); startJob = null
                _runtime.value?.let { it.stop(); closingRuntime = it }
                _runtime.value = null
                _starting.value = false
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        startJob?.cancel()
        _runtime.value?.stop()
        _runtime.value = null
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "ListenService"
        private const val CHANNEL_ID = "listen"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.dejalango.action.START"
        const val ACTION_STOP = "dev.dejalango.action.STOP"

        /** Must be called while the app is in the foreground (Android 14+ microphone FGS rule). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP))
        }
    }
}
