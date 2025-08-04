package com.example.realtime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val CAMERA_PERMISSION_CODE = 1001
    private lateinit var dictionary: List<String>
    private val correctionCache = mutableMapOf<String, String>()

    private lateinit var previewView: PreviewView
    private lateinit var textResult: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var textRecognizer: TextRecognizer

    private var isTtsReady = false
    private var isSpeaking = false
    private var lastTextSpoken = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        textResult = findViewById(R.id.textResult)

        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale("id", "ID")
                isTtsReady = true
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
            }
        })

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            startCamera()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startCamera()
        }

        loadDictionary()
    }

    private fun loadDictionary() {
        val assetFiles = assets.list("")
        Log.d("ASSETS", "Isi assets: ${assetFiles?.joinToString()}")

        val inputStream = assets.open("kata_baku.txt")
        dictionary = inputStream.bufferedReader().readLines()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this), TextAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, analyzer
            )
        }, ContextCompat.getMainExecutor(this))
    }

    inner class TextAnalyzer : ImageAnalysis.Analyzer {
        private var lastReadTime = 0L

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastReadTime < 2000) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            runOnUiThread {
                textResult.text = "⏳ Memproses teks..."
            }

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val originalText = visionText.text.trim()
                    val correctedText = correctText(originalText)
                    val similarityScore = similarity(correctedText, lastTextSpoken)

                    if (correctedText.isNotBlank() && similarityScore < 85 && !isSpeaking) {
                        lastReadTime = currentTime
                        lastTextSpoken = correctedText
                        runOnUiThread {
                            textResult.text = correctedText
                        }
                        if (isTtsReady) {
                            tts.speak(correctedText, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
                        }
                    }
                }
                .addOnFailureListener {
                    runOnUiThread {
                        textResult.text = "❌ Gagal memproses teks."
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun correctText(text: String): String {
        return text.split(" ")
            .take(50) // Batasi maksimum 50 kata
            .joinToString(" ") { word ->
                if (word.length <= 3) return@joinToString word // Lewati kata pendek

                correctionCache[word]?.let { return@joinToString it }

                val bestMatch = dictionary.minByOrNull { editDistance(it, word.lowercase()) }
                val score = bestMatch?.let { similarity(it, word.lowercase()) } ?: 0
                val corrected = if (score > 80) bestMatch!! else word

                correctionCache[word] = corrected
                corrected
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_SHORT).show()
            }
        }
    }


    fun similarity(s1: String, s2: String): Int {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        val longerLength = longer.length
        if (longerLength == 0) return 100
        val editDistance = editDistance(longer, shorter)
        return ((longerLength - editDistance) * 100) / longerLength
    }

    fun editDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) {
            for (j in 0..s2.length) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    s1[i - 1] == s2[j - 1] -> dp[i][j] = dp[i - 1][j - 1]
                    else -> dp[i][j] = 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[s1.length][s2.length]
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}
