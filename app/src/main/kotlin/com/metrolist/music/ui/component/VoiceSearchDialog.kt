package com.metrolist.music.ui.component

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat

@Composable
fun VoiceSearchDialog(
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
    pureBlack: Boolean = false
) {
    val context = LocalContext.current

    var isListening by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
    }

    LaunchedEffect(Unit) {
        // Verifica disponibilità del riconoscimento vocale
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            errorMessage = "Riconoscimento vocale non disponibile su questo dispositivo"
            return@LaunchedEffect
        }

        // Verifica permesso
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            errorMessage = "Permesso microfono non concesso"
            return@LaunchedEffect
        }

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            if (recognizer == null) {
                errorMessage = "Impossibile creare il riconoscitore vocale"
                return@LaunchedEffect
            }

            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    errorMessage = null
                }

                override fun onBeginningOfSpeech() {
                    recognizedText = "Parlando..."
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Errore audio"
                        SpeechRecognizer.ERROR_CLIENT -> "Errore client"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permesso microfono mancante"
                        SpeechRecognizer.ERROR_NETWORK -> "Errore di rete"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout di rete"
                        SpeechRecognizer.ERROR_NO_MATCH -> "Nessuna corrispondenza trovata"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Riconoscitore occupato"
                        SpeechRecognizer.ERROR_SERVER -> "Errore del server"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nessun parlato rilevato"
                        else -> "Errore sconosciuto ($error)"
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()

                    if (!text.isNullOrEmpty()) {
                        onResult(text)
                        onDismiss()
                    } else {
                        errorMessage = "Nessun risultato riconosciuto"
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    recognizedText = matches?.firstOrNull() ?: ""
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer?.startListening(intent)
        } catch (e: Exception) {
            errorMessage = "Errore durante l'inizializzazione: ${e.message}"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
            } catch (e: Exception) {
                // Ignora errori durante la pulizia
            }
        }
    }

    Dialog(onDismissRequest = {
        recognizer?.stopListening()
        onDismiss()
    }) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = MaterialTheme.shapes.large,
            color = if (pureBlack) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = when {
                        errorMessage != null -> "Errore"
                        isListening -> "Ti ascolto..."
                        recognizedText.isNotEmpty() -> recognizedText
                        else -> "Inizializzazione..."
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                if (isListening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                }

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                TextButton(
                    onClick = {
                        recognizer?.stopListening()
                        onDismiss()
                    }
                ) {
                    Text("Annulla")
                }
            }
        }
    }
}