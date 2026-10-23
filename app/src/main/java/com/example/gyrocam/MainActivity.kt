package com.example.gyrocam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/** Records video with all phone stabilization off, plus a Gyroflow .gcsv sidecar (same base name). */
class MainActivity : Activity(), SensorEventListener {
    companion object {
        const val ORIENTATION = "YxZ"   // IMU axis mapping: verify once in Gyroflow, then adjust
        const val FPS = 30
    }

    private lateinit var root: FrameLayout
    private lateinit var tv: TextureView
    private lateinit var btn: Button
    private lateinit var resSpin: Spinner
    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var camMgr: CameraManager
    private lateinit var sm: SensorManager
    private val camThread = HandlerThread("cam").apply { start() }
    private val imuThread = HandlerThread("imu").apply { start() }
    private lateinit var camHandler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pSurface: Surface? = null
    private var baseName = ""
    private var vw = 1920; private var vh = 1080      // video size
    private var pw = 1920; private var ph = 1080      // preview size (same aspect, capped at 1080p)
    private var sensorOrientation = 90
    private var sizes: List<Size> = emptyList()
    private var previewSizes: Array<Size> = emptyArray()

    @Volatile private var recording = false
    @Volatile private var firstFrameTs = 0L
    private val lock = Any()
    private var ts = LongArray(1 shl 16)
    private var gv = FloatArray(3 shl 16)
    private var n = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("gc", MODE_PRIVATE)
        camHandler = Handler(camThread.looper)
        camMgr = getSystemService(CAMERA_SERVICE) as CameraManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        root = FrameLayout(this)
        tv = TextureView(this)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { pSurface?.release(); pSurface = null; return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        root.addView(tv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        // Fit preview to the window at the stream's exact aspect ratio (no stretching).
        root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val rw = r - l; val rh = b - t
            if (rw == 0 || rh == 0) return@addOnLayoutChangeListener
            val w = minOf(rw, rh * pw / ph); val h = w * ph / pw
            val lp = tv.layoutParams
            if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; tv.post { tv.layoutParams = lp } }
        }
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyRotation() }

        status = TextView(this).apply {
            setTextColor(Color.WHITE); setBackgroundColor(0x66000000); setPadding(16, 8, 16, 8)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0x66000000); setPadding(12, 12, 12, 12)
        }
        resSpin = Spinner(this)
        btn = Button(this).apply { text = "Record"; setOnClickListener { toggle() } }
        val storageBtn = Button(this).apply { text = "Storage"; setOnClickListener { chooseStorage() } }
        panel.addView(resSpin); panel.addView(btn); panel.addView(storageBtn)
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) = openCamera()

    // ---- rotation ----
    private val dispListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = applyRotation()
    }

    @Suppress("DEPRECATION")
    private fun rotation() = windowManager.defaultDisplay.rotation

    /** Degrees the sensor-oriented frames must be rotated to appear upright for the current display rotation. */
    private fun relRotation(): Int {
        val disp = when (rotation()) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return (sensorOrientation - disp + 360) % 360
    }

    private fun applyRotation() {
        val m = Matrix()
        m.postRotate(relRotation().toFloat(), tv.width / 2f, tv.height / 2f)
        tv.setTransform(m)
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(dispListener, null)
        applyRotation()
        openCamera()
    }

    override fun onPause() {
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(dispListener)
        if (recording) stopRecording()
        session?.close(); session = null
        camera?.close(); camera = null
        super.onPause()
    }

    private fun ui(msg: String) = runOnUiThread { status.text = msg }

    // ---- camera ----
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (camera != null || !tv.isAvailable ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val id = camMgr.cameraIdList.first {
            camMgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val ch = camMgr.getCameraCharacteristics(id)
        val realtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        previewSizes = map.getOutputSizes(SurfaceTexture::class.java)
        sizes = map.getOutputSizes(MediaRecorder::class.java).filter { it.height >= 720 }
            .sortedByDescending { it.width * it.height }
        setupResolutionSpinner()
        ui(if (realtime) "Ready (camera/IMU clocks shared)" else "Ready (WARNING: clocks differ, rely on Gyroflow auto-sync)")
        camMgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(c: CameraDevice) { camera = c; startPreview() }
            override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
            override fun onError(c: CameraDevice, e: Int) { c.close(); camera = null; ui("Camera error $e") }
        }, camHandler)
    }

    private fun setupResolutionSpinner() {
        if (sizes.isEmpty()) return
        val labels = sizes.map { "${it.width}x${it.height}" }
        resSpin.onItemSelectedListener = null
        resSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = listOf(prefs.getString("res", ""), "1920x1080").map { labels.indexOf(it) }.firstOrNull { it >= 0 } ?: 0
        resSpin.setSelection(idx)
        selectSize(sizes[idx])
        resSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val s = sizes[pos]
                if (recording || (s.width == vw && s.height == vh)) return
                selectSize(s)
                prefs.edit().putString("res", "${s.width}x${s.height}").apply()
                startPreview()
            }
        }
    }

    private fun selectSize(s: Size) {
        vw = s.width; vh = s.height
        val p = previewSizes.filter { it.width <= 1920 && it.width * vh == it.height * vw }.maxByOrNull { it.width }
            ?: previewSizes.filter { it.width <= 1920 }.maxByOrNull { it.width } ?: s
        pw = p.width; ph = p.height
        root.requestLayout()
    }

    private fun previewSurface(): Surface {
        val st = tv.surfaceTexture!!
        st.setDefaultBufferSize(pw, ph)
        return pSurface ?: Surface(st).also { pSurface = it }
    }

    private fun startPreview() {
        session?.close(); session = null
        createSession(listOf(previewSurface()), CameraDevice.TEMPLATE_PREVIEW) {}
    }

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

    // ---- storage ----
    private fun chooseStorage() {
        if (recording) return
        AlertDialog.Builder(this).setTitle("Save recordings to")
            .setItems(arrayOf("Phone: Download/GyroCam", "Choose folder (e.g. on SD card)...")) { _, which ->
                if (which == 0) { prefs.edit().remove("tree").apply(); ui("Saving to Download/GyroCam") }
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 2)
            }.show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val u = data?.data
        if (req == 2 && res == RESULT_OK && u != null) {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("tree", u.toString()).apply()
            ui("Saving to " + Uri.decode(DocumentsContract.getTreeDocumentId(u)))
        }
    }

    // application/octet-stream stops Android from appending ".csv" to the .gcsv name
    private fun mimeFor(name: String) = if (name.endsWith(".mp4")) "video/mp4" else "application/octet-stream"

    private fun newFile(name: String): Uri {
        prefs.getString("tree", null)?.let { tree ->
            try {
                val t = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                return DocumentsContract.createDocument(contentResolver, parent, mimeFor(name), name)!!
            } catch (e: Exception) { ui("Chosen folder unavailable, using Download/GyroCam") }
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GyroCam")
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
    }

    // ---- recording ----
    private fun toggle() { if (recording) stopRecording() else startRecording() }

    private fun startRecording() {
        baseName = "GYRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            pfd = contentResolver.openFileDescriptor(newFile("$baseName.mp4"), "w")
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd!!.fileDescriptor)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(minOf(100_000_000L, 40_000_000L * vw * vh / (1920 * 1080)).toInt())
            r.setVideoFrameRate(FPS)
            r.setVideoSize(vw, vh)
            r.setOrientationHint(relRotation())
            r.prepare()
        } catch (e: Exception) {
            r.release(); pfd?.close(); pfd = null
            ui("Can't record ${vw}x$vh: ${e.message}"); return
        }
        recorder = r
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED  // no rotating mid-clip
        resSpin.isEnabled = false
        session?.close()
        createSession(listOf(previewSurface(), r.surface), CameraDevice.TEMPLATE_RECORD) {
            synchronized(lock) { n = 0 }
            firstFrameTs = 0L
            recording = true
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, 0, Handler(imuThread.looper))
            r.start()
            runOnUiThread { btn.text = "Stop" }
            ui("Recording ${vw}x$vh...")
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
        resSpin.isEnabled = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
        val uri = newFile("$base.gcsv")
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
