package com.jorge.koreandiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.features.Features
import com.jorge.koreandiary.features.Platform

/**
 * Lista simple de lo que la app ya hace, agrupada por pantalla, con buscador. Se abre con «?» en
 * el inicio. El contenido vive en `shared` (`features/Features.kt`) y la PC muestra el mismo; lo
 * que solo funciona en la PC sale en gris.
 */
@Composable
fun FeaturesScreen(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(query) { Features.search(query) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Qué puede hacer la app", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Buscar: escena, voz, copia…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (groups.isEmpty()) {
            Text(
                "No está en la lista, así que todavía no existe. Si la quieres, pídela.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            groups.forEach { group ->
                item(key = group.title) {
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Text(group.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = scheme.secondary)
                        HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                items(group.features, key = { "${group.title}:${it.text}" }) { feature ->
                    val missing = feature.missingLabel(Platform.PHONE)
                    val color = if (missing == null) scheme.onSurface else scheme.onSurfaceVariant
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("·", color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(feature.text, style = MaterialTheme.typography.bodyMedium, color = color)
                            if (missing != null) {
                                Text(missing, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
