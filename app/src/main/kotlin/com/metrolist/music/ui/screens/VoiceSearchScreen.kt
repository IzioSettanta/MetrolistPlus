package com.metrolist.music.ui.screens.search

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.metrolist.music.R
import com.metrolist.music.ui.screens.Screens
import java.net.URLEncoder
import java.util.Locale

@Composable
fun VoiceSearchScreen(navController: NavHostController) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var voiceValue by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("In ascolto...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Inizializziamo lo SpeechRecognizer con controlli di sicurezza
    val speechRecognizer = remember { 
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // Gestione Permessi (Necessaria per il riconoscimento "silenzioso")
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) navController.popBackStack()
    }

    // Animazione Pulsante
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "scale"
    )

    DisposableEffect(Unit) {
        // Se il recognizer non è disponibile, mostriamo errore e usciamo
        if (speechRecognizer == null) {
            navController.popBackStack()
            return@DisposableEffect onDispose {}
        }

        // Chiediamo il permesso del microfono
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            // Questo flag suggerisce al sistema di non mostrare dialoghi extra
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                statusText = "Parla ora..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { voiceValue = rmsdB }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                // Se c'è un errore (es. nessun suono), torniamo indietro
                navController.popBackStack()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.get(0) ?: ""
                if (text.isNotEmpty()) {
                    val encoded = URLEncoder.encode(text, "UTF-8")
                    navController.navigate(Screens.Search.route) {
                        popUpTo(Screens.VoiceSearch.route) {
                            inclusive = true
                        }
                    }
                    // Also set the search query in the search screen
                    navController.currentBackStackEntry?.savedStateHandle?.set("query", text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    statusText = matches[0] // Mostra quello che l'utente sta dicendo in tempo reale
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer?.setRecognitionListener(listener)
        
        // AVVIAMO IL RICONOSCIMENTO (Senza lanciare un'attività esterna)
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            errorMessage = "Errore avvio riconoscimento: ${e.message}"
            navController.popBackStack()
        }

        onDispose {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                // Ignora errori durante la pulizia
            }
        }
    }

    // INTERFACCIA PERSONALIZZATA (Stile Metrolist)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Mostra messaggio di errore se presente
            errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(80.dp))

            // Cerchio animato che reagisce al volume
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(scale + (voiceValue / 20f).coerceIn(0f, 0.4f))
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mic),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(modifier = Modifier.height(100.dp))

            OutlinedButton(
                onClick = { navController.popBackStack() },
                shape = CircleShape
            ) {
                Text("Annulla")
            }
        }
    }
}
