package cz.peelco.jolt.features.shared

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Renders [content] as a QR code; a grey square if it can't be encoded. */
@Composable
fun QrCodeImage(
    content: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val bitmap =
        remember(content) {
            runCatching {
                val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
                createBitmap(matrix.width, matrix.height).apply {
                    for (x in 0 until matrix.width) {
                        for (y in 0 until matrix.height) this[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                }
            }.getOrNull()
        }
    if (bitmap == null) {
        Box(modifier.size(size).background(Color.Gray.copy(alpha = 0.3f)))
    } else {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "QR code",
            filterQuality = FilterQuality.None,
            modifier = modifier.size(size),
        )
    }
}

/**
 * A live camera preview that reports the first QR code it reads, then stops.
 * Asks for the camera permission itself. Re-key it to scan again.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Jolt needs the camera to scan a QR code.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) { Text("Allow camera") }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val delivered = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner =
        remember {
            BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier.semantics { contentDescription = "Camera viewfinder" },
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    if (image == null || delivered.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner
                        .process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val value = codes.firstNotNullOfOrNull { it.rawValue }
                            if (value != null && delivered.compareAndSet(false, true)) {
                                ContextCompat.getMainExecutor(viewContext).execute { onScanned(value) }
                                provider.unbindAll()
                            }
                        }.addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(viewContext))
            previewView
        },
    )
}
