package dev.dejalango.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import dev.dejalango.app.appGraph
import dev.dejalango.app.service.ListenService
import kotlinx.coroutines.flow.MutableStateFlow

/** Hosts the bench. Owns nothing: audio and models live in ListenService. */
class MainActivity : ComponentActivity() {

    private val service = MutableStateFlow<ListenService?>(null)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = (binder as? ListenService.LocalBinder)?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service.value = null
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) ListenService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                    val svc by service.collectAsState()
                    BenchScreen(
                        service = svc,
                        graph = appGraph,
                        onStartListening = ::startListening,
                        onStopListening = { ListenService.stop(this) },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, ListenService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) unbindService(connection)
        bound = false
        service.value = null
        super.onStop()
    }

    private fun startListening() {
        val needed = ArrayList<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isEmpty()) ListenService.start(this) else permissions.launch(needed.toTypedArray())
    }
}
