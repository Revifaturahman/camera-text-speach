package com.example.realtime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class ShortFragment : Fragment() {

    private lateinit var previewView: PreviewView
    private lateinit var textResult: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var dictionary: List<String>
    private val correctionCache = mutableMapOf<String, String>()

    private var isTtsReady = false
    private var isSpeaking = false
    private var lastTextSpoken = ""
    private var lastReadTime = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_short, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        textResult = view.findViewById(R.id.textResult)

        tts = TextToSpeech(requireContext()) {
            if (it == TextToSpeech.SUCCESS) {
                tts.language = Locale("id", "ID")
                isTtsReady = true
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) { isSpeaking = false }
            override fun onError(utteranceId: String?) { isSpeaking = false }
        })

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.CAMERA),
                1001
            )
        } else {
            startCamera()
        }

        loadDictionary()
    }

    private fun loadDictionary() {
        val inputStream = requireContext().assets.open("kata_baku.txt")
        dictionary = inputStream.bufferedReader().readLines()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(requireContext()), TextAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner, cameraSelector, preview, analyzer
            )
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    inner class TextAnalyzer : ImageAnalysis.Analyzer {
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

            if (!isSpeaking && lastTextSpoken.isEmpty()) {
                requireActivity().runOnUiThread {
                    textResult.text = "⏳ Memproses teks..."
                }
            }

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val originalText = visionText.text.trim()
                    val correctedText = correctText(originalText)
                    val similarityScore = similarity(correctedText, lastTextSpoken)

                    if (correctedText.isNotBlank() && similarityScore < 85 && !isSpeaking) {
                        lastReadTime = currentTime
                        lastTextSpoken = correctedText
                        requireActivity().runOnUiThread {
                            textResult.text = correctedText
                        }
                        if (isTtsReady) {
                            tts.speak(correctedText, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
                        }
                    }
                }
                .addOnFailureListener {
                    requireActivity().runOnUiThread {
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
            .take(50)
            .joinToString(" ") { word ->
                if (word.length <= 3) return@joinToString word
                correctionCache[word]?.let { return@joinToString it }

                val bestMatch = dictionary.minByOrNull { editDistance(it, word.lowercase()) }
                val score = bestMatch?.let { similarity(it, word.lowercase()) } ?: 0
                val corrected = if (score > 80) bestMatch!! else word

                correctionCache[word] = corrected
                corrected
            }
    }

    private fun similarity(s1: String, s2: String): Int {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        val longerLength = longer.length
        if (longerLength == 0) return 100
        val editDistance = editDistance(longer, shorter)
        return ((longerLength - editDistance) * 100) / longerLength
    }

    private fun editDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) {
            for (j in 0..s2.length) {
                dp[i][j] = when {
                    i == 0 -> j
                    j == 0 -> i
                    s1[i - 1] == s2[j - 1] -> dp[i - 1][j - 1]
                    else -> 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[s1.length][s2.length]
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts.stop()
        tts.shutdown()
    }


}