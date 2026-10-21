package com.uzair.streamtranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that captures the sound other apps are playing,
 * sends short chunks to a Whisper-compatible "translations" endpoint
 * (which returns English text for any spoken language), and reads the
 * English aloud with Android text-to-speech.
 */
class CaptureService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var statusText = "Ready."
        @Volatile var listener: (() -> Unit)? = null
        val lines = CopyOnWriteArrayList<String>()

        private const val CHANNEL = "capture"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SECONDS = 7
        private const val SILENCE_RMS = 120.0
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val pending = AtomicInteger(0)
    private val net = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")

        createChannel()
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (data == null) {
            fail("Capture permission is missing. Tap Start again.")
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                // Not MEDIA, so the app does not capture and re-translate its own voice.
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                ttsReady = true
            }
        }

        running = true
        lines.clear()
        setStatus("Listening…")
        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val proj = projection ?: return
        val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, 32000))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
        } catch (e: Exception) {
            fail("Could not start audio capture: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("Audio capture did not start on this phone.")
            return
        }
        record = rec
        rec.startRecording()

        worker = Thread {
            val chunk = ShortArray(SAMPLE_RATE * CHUNK_SECONDS)
            val buf = ShortArray(4096)
            var filled = 0
            while (!stopping) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                var i = 0
                while (i < n) {
                    val take = minOf(n - i, chunk.size - filled)
                    System.arraycopy(buf, i, chunk, filled, take)
                    filled += take
                    i += take
                    if (filled == chunk.size) {
                        handleChunk(chunk.copyOf())
                        filled = 0
                    }
                }
            }
        }.also { it.start() }
    }

    private fun handleChunk(pcm: ShortArray) {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s.toDouble()
        val rms = Math.sqrt(sum / pcm.size)
        if (rms < SILENCE_RMS) {
            setStatus("Listening… (no sound heard)")
            return
        }
        if (pending.get() >= 3) return
        pending.incrementAndGet()
        val wav = toWav(pcm)
        try {
            net.execute {
                try {
                    translate(wav)
                } finally {
                    pending.decrementAndGet()
                }
            }
        } catch (e: Exception) {
            pending.decrementAndGet()
        }
    }

    private fun translate(wav: ByteArray) {
        val p = getSharedPreferences("s", Context.MODE_PRIVATE)
        val key = (p.getString("key", "") ?: "").trim()
        val base = (p.getString("base", "") ?: "").trim().trimEnd('/')
        val model = (p.getString("model", "") ?: "").trim()

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "chunk.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$base/audio/translations")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        try {
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    setStatus(
                        when (r.code) {
                            401, 403 -> "The API key was rejected. Check it in the settings."
                            429 -> "Rate limit reached. Some speech will be skipped."
                            else -> "Translation service error ${r.code}."
                        }
                    )
                    return
                }
                val out = JSONObject(txt).optString("text").trim()
                if (out.isEmpty() || isJunk(out)) return
                setStatus("Listening…")
                lines.add(0, out)
                while (lines.size > 60) lines.removeAt(lines.size - 1)
                listener?.invoke()
                if (ttsReady) {
                    tts?.speak(out, TextToSpeech.QUEUE_ADD, null, System.nanoTime().toString())
                }
            }
        } catch (e: Exception) {
            setStatus("No connection to the translation service.")
        }
    }

    // Whisper sometimes invents text on near-silent audio.
    private fun isJunk(t: String): Boolean {
        val s = t.lowercase(Locale.ROOT).trim(' ', '.', '!', '?')
        return s.isEmpty() || s == "you" ||
            s.contains("thanks for watching") || s.contains("thank you for watching")
    }

    private fun toWav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(1)
        bb.putInt(SAMPLE_RATE)
        bb.putInt(SAMPLE_RATE * 2)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray())
        bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    private fun setStatus(t: String) {
        statusText = t
        listener?.invoke()
    }

    private fun fail(msg: String) {
        setStatus(msg)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Translation", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Stream Translator")
            .setContentText("Translating sound from other apps")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopping = true
        running = false
        try { record?.stop() } catch (e: Exception) { }
        try { worker?.join(1000) } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        record = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null
        net.shutdownNow()
        setStatus("Stopped.")
        super.onDestroy()
    }
}
