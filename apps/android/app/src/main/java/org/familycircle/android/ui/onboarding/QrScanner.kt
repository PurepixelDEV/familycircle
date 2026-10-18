package org.familycircle.android.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.familycircle.android.R
import org.familycircle.android.api.QrLink
import org.familycircle.android.ui.permissions.openAppSettings
import org.familycircle.android.util.Logger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private fun Context.hasCamera() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Full-screen QR scanner for the server screen. Asks for the camera with a plain-language
 * explanation; without it (or without a camera) the user goes back to manual entry. Only
 * FamilyCircle links ([QrLink]) are accepted; the scanned text is never logged.
 */
@Composable
fun QrScannerScreen(onLink: (QrLink) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var granted by remember { mutableStateOf(context.hasCamera()) }
    var deniedPermanently by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = context.hasCamera() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        deniedPermanently = !ok && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.qr_title), style = MaterialTheme.typography.headlineMedium)
            if (granted) {
                Text(stringResource(R.string.qr_instructions), style = MaterialTheme.typography.bodyLarge)
                ScannerPreview(onLink)
            } else {
                Text(stringResource(R.string.qr_camera_explanation), style = MaterialTheme.typography.bodyLarge)
                if (asked) {
                    Text(
                        stringResource(if (deniedPermanently) R.string.qr_camera_denied_settings else R.string.qr_camera_denied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (deniedPermanently) {
                    OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.perm_open_settings))
                    }
                } else {
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.qr_allow_camera))
                    }
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.qr_manual))
            }
        }
    }
}

@Composable
private fun ScannerPreview(onLink: (QrLink) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLink by rememberUpdatedState(onLink)
    var hint by remember { mutableIntStateOf(0) } // 0 none, 1 not a FamilyCircle code, 2 no camera
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val previewCd = stringResource(R.string.qr_preview_cd)

    DisposableEffect(lifecycleOwner) {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        var lastRejected: String? = null
        val analyzer = QrAnalyzer { text ->
            main.post {
                if (done.get()) return@post
                val link = QrLink.parse(text)
                if (link != null) {
                    done.set(true)
                    currentOnLink(link)
                } else if (text != lastRejected) {
                    lastRejected = text
                    hint = 1
                }
            }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(executor, analyzer) }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // No back camera / camera in use / CameraX init failure.
                Logger.w("QrScanner", "camera not available", e)
                hint = 2
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            done.set(true)
            provider?.unbind(preview, analysis)
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().semantics { contentDescription = previewCd })
        Box(
            Modifier
                .fillMaxSize(0.62f)
                .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
        )
    }
    if (hint != 0) {
        Text(
            stringResource(if (hint == 1) R.string.qr_not_familycircle else R.string.qr_no_camera),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Feeds the luminance (Y) plane of each camera frame to [QrDecoder]. Runs on the analysis executor;
 * frames are skipped while one is processed (KEEP_ONLY_LATEST).
 */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = QrDecoder()
    private var buffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val height = image.height
            val rowStride = plane.rowStride
            val data = plane.buffer
            data.rewind()
            val needed = rowStride * height
            if (buffer.size != needed) buffer = ByteArray(needed)
            // The last row may be shorter than rowStride; copy what is there.
            data.get(buffer, 0, minOf(data.remaining(), needed))
            decoder.decode(buffer, rowStride, image.width, height)?.let(onText)
        } catch (e: RuntimeException) {
            Logger.w("QrScanner", "frame not analysed", e)
        } finally {
            image.close()
        }
    }
}
