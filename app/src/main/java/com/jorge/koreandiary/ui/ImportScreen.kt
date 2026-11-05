package com.jorge.koreandiary.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.ai.GrammarTxtParser
import com.jorge.koreandiary.ai.ImportParser
import com.jorge.koreandiary.ai.ImportPayload
import com.jorge.koreandiary.ai.ParseException
import com.jorge.koreandiary.ai.Prompts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var level by remember { mutableStateOf(container.settings.state.value.level) }
    var pasted by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ImportPayload?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                }
                preview = GrammarTxtParser.parse(text, level.trim().ifEmpty { "3A" })
                message = null
            } catch (e: ParseException) {
                message = e.message
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Estudio") }
            Text("Importar", style = MaterialTheme.typography.titleLarge)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Lista de gramática en TXT", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Lee el formato “Unit N: patrón | patrón” sin usar IA. El apéndice se guarda como nivel “base”.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(value = level, onValueChange = { level = it }, label = { Text("Nivel de las unidades (p. ej. 3A)") }, singleLine = true)
                Button(onClick = { picker.launch(arrayOf("text/*")) }) { Text("Elegir archivo TXT") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Cualquier lista, con un chat", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Copia el prompt, pégalo en Claude o ChatGPT junto con tu lista (texto o fotos) y pega aquí la respuesta.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("prompt", Prompts.importList("(pega tu lista aquí)")))
                    message = "Prompt copiado. Reemplaza “(pega tu lista aquí)” con tu lista."
                }) { Text("Copiar prompt de importación") }
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Respuesta JSON del chat") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 240.dp),
                )
                Button(onClick = {
                    try {
                        preview = ImportParser.parse(pasted)
                        message = null
                    } catch (e: ParseException) {
                        message = e.message
                    }
                }, enabled = pasted.isNotBlank()) { Text("Leer respuesta") }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

        preview?.let { payload ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Vista previa", style = MaterialTheme.typography.titleMedium)
                    Text("${payload.words.size} palabras · ${payload.grammar.size} puntos de gramática")
                    (payload.words.take(5).map { "${it.baseForm} (${it.type})" } + payload.grammar.take(5).map { it.pattern })
                        .forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = {
                        scope.launch {
                            val r = container.study.import(payload)
                            message = "Guardado: ${r.wordsAdded} palabras y ${r.grammarAdded} puntos de gramática. ${r.duplicates} repetidos omitidos."
                            preview = null
                            pasted = ""
                        }
                    }) { Text("Guardar") }
                }
            }
        }
    }
}
