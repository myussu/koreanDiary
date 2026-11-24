package com.jorge.koreandiary.kiwiprueba

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    Pantalla()
                }
            }
        }
    }
}

@Composable
private fun Pantalla(vm: PruebaViewModel = viewModel()) {
    val estado = vm.estado
    val context = LocalContext.current
    var oracion by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kiwi prueba", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Mide Kiwi en este celular: 1) cárgalo, 2) corre los casos y 3) copia los resultados para " +
                "pegarlos en el chat. No toca tu diario.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::cargar,
                enabled = !estado.cargado && estado.trabajando == null,
                shape = RectangleShape,
            ) { Text(if (estado.cargado) "Kiwi cargado" else "1. Cargar Kiwi") }
            Button(
                onClick = vm::correrCasos,
                enabled = estado.cargado && estado.trabajando == null,
                shape = RectangleShape,
            ) { Text(if (estado.casosHechos) "Correr otra vez" else "2. Correr casos") }
        }

        estado.trabajando?.let { texto ->
            Text(texto, style = MaterialTheme.typography.bodyMedium)
            val progreso = estado.progreso
            if (progreso != null && progreso.second > 0) {
                LinearProgressIndicator(
                    progress = { progreso.first.toFloat() / progreso.second },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${progreso.first} / ${progreso.second}", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        OutlinedButton(
            onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java)
                clip.setPrimaryClip(ClipData.newPlainText("Kiwi prueba", vm.informe()))
                Toast.makeText(context, "Resultados copiados", Toast.LENGTH_SHORT).show()
            },
            shape = RectangleShape,
        ) { Text("3. Copiar resultados") }

        HorizontalDivider()
        Text("Probar una oración", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = oracion,
            onValueChange = { oracion = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("어제 친구랑 밥을 먹는 동안 비가 왔어요.") },
            shape = RectangleShape,
        )
        OutlinedButton(
            onClick = { vm.analizar(oracion) },
            enabled = estado.cargado && estado.trabajando == null && oracion.isNotBlank(),
            shape = RectangleShape,
        ) { Text("Analizar") }

        HorizontalDivider()
        SelectionContainer {
            Text(
                vm.informe(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
