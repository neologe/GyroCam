package com.example.gyrocam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/** Records 1080p30 video with all phone stabilization off, plus a Gyroflow .gcsv sidecar
 *  (same base name) in Downloads/GyroCam. Landscape-locked so the axis mapping is fixed. */
class MainActivity : Activity(), SensorEventListener {
    companion object {
        const val ORIENTATION = "YxZ"   // IMU axis mapping: verify once in Gyroflow, then adjust
        const val W = 1920; const val H = 1080; const val FPS = 30
    }

    private lateinit var tv: TextureView
    private lateinit var btn: Button
    private lateinit var status: TextView
    private lateinit var camMgr: CameraManager
    private lateinit var sm: SensorManager
    private val camThread = HandlerThread("cam").apply { start() }
    private val imuThread = HandlerThread("imu").apply { start() }
    private lateinit var camHandler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var baseName = ""
    private var realtimeClock = false

    @Volatile private var recording = false
    @Volatile private var firstFrameTs = 0L
    private val lock = Any()
    private var ts = LongArray(1 shl 16)
    private var gv = FloatArray(3 shl 16)
    private var n = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camHandler = Handler(camThread.looper)
        camMgr = getSystemService(CAMERA_SERVICE) as CameraManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        val dm = resources.displayMetrics
        val h = dm.heightPixels
        val w = minOf(dm.widthPixels, h * 16 / 9)
        val root = FrameLayout(this)
        tv = TextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        root.addView(tv, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x66000000); setPadding(16, 8, 16, 8)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        btn = Button(this).apply { text = "Record"; setOnClickListener { toggle() } }
        root.addView(btn, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) = openCamera()

    override fun onResume() { super.onResume(); openCamera() }

    override fun onPause() {
        if (recording) stopRecording()
        session?.close(); session = null
        camera?.close(); camera = null
        super.onPause()
    }

    private fun ui(msg: String) = runOnUiThread { status.text = msg }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (camera != null || !tv.isAvailable ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val id = camMgr.cameraIdList.first {
            camMgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        realtimeClock = camMgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        ui(if (realtimeClock) "Ready (camera/IMU clocks shared)" else "Ready (WARNING: clocks differ, rely on Gyroflow auto-sync)")
        camMgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) { camera = c; startPreview() }
            override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
            override fun onError(c: CameraDevice, e: Int) { c.close(); camera = null; ui("Camera error $e") }
        }, camHandler)
    }

    private fun previewSurface(): Surface {
        val st = tv.surfaceTexture!!
        st.setDefaultBufferSize(W, H)
        return Surface(st)
    }

    private fun startPreview() = createSession(listOf(previewSurface()), CameraDevice.TEMPLATE_PREVIEW) {}

    private val frameCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frame: Long) {
            if (recording && firstFrameTs == 0L) firstFrameTs = timestamp
        }
    }

    private fun createSession(surfaces: List<Surface>, template: Int, onReady: () -> Unit) {
        val cam = camera ?: return
        @Suppress("DEPRECATION")
        cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val req = cam.createCaptureRequest(template).apply {
                    surfaces.forEach { addTarget(it) }
                    // Critical: any phone-side stabilization would invalidate the gyro data.
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                }.build()
                s.setRepeatingRequest(req, frameCb, camHandler)
                onReady()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) = ui("Session config failed")
        }, camHandler)
    }

    private fun toggle() { if (recording) stopRecording() else startRecording() }

    private fun newFile(name: String, mime: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GyroCam")
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
    }

    private fun startRecording() {
        baseName = "GYRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        pfd = contentResolver.openFileDescriptor(newFile("$baseName.mp4", "video/mp4"), "w")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(pfd!!.fileDescriptor)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoEncodingBitRate(40_000_000)
        r.setVideoFrameRate(FPS)
        r.setVideoSize(W, H)
        r.setOrientationHint(0)
        r.prepare()
        recorder = r
        session?.close()
        createSession(listOf(previewSurface(), r.surface), CameraDevice.TEMPLATE_RECORD) {
            synchronized(lock) { n = 0 }
            firstFrameTs = 0L
            recording = true
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, 0, Handler(imuThread.looper))
            r.start()
            runOnUiThread { btn.text = "Stop" }
            ui("Recording...")
        }
    }

    private fun stopRecording() {
        recording = false
        sm.unregisterListener(this)
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: RuntimeException) {}
        recorder?.release(); recorder = null
        pfd?.close(); pfd = null
        btn.text = "Record"
        writeGcsv(baseName)
        startPreview()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!recording) return
        synchronized(lock) {
            if (n == ts.size) { ts = ts.copyOf(n * 2); gv = gv.copyOf(n * 6) }
            ts[n] = e.timestamp
            gv[3 * n] = e.values[0]; gv[3 * n + 1] = e.values[1]; gv[3 * n + 2] = e.values[2]
            n++
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun writeGcsv(base: String) {
        val t0 = firstFrameTs
        val (tsx, gvx, cnt) = synchronized(lock) { Triple(ts, gv, n) }
        val uri = newFile("$base.gcsv", "text/csv")
        Thread {
            var written = 0
            contentResolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                w.write("GYROFLOW IMU LOG\nversion,1.3\nid,android_gyrocam\n")
                w.write("orientation,$ORIENTATION\nvendor,android\nvideofilename,$base.mp4\n")
                w.write("tscale,0.000000001\ngscale,1.0\nt,gx,gy,gz\n")  // t in ns, gyro in rad/s
                for (i in 0 until cnt) {
                    val t = tsx[i] - t0
                    if (t < 0) continue  // drop samples before the first video frame
                    w.write("$t,${gvx[3 * i]},${gvx[3 * i + 1]},${gvx[3 * i + 2]}\n"); written++
                }
            }
            val secs = if (cnt > 1) (tsx[cnt - 1] - tsx[0]) / 1e9 else 1.0
            ui("Saved $base: $written gyro samples (~${(cnt / secs).toInt()} Hz)")
        }.start()
    }
}
