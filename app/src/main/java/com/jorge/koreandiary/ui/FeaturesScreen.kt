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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Lo que la app ya hace, con «?» en el inicio. Dos vistas: «Funciones», la lista agrupada por
 * pantalla con buscador, y «Cómo se usa», el recorrido de cada pantalla paso a paso, dibujado. El
 * contenido vive en `shared` (`features/Features.kt`) y la PC muestra el mismo; lo que solo
 * funciona en la PC sale en gris.
 */
@Composable
fun FeaturesScreen(onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var showFlows by rememberSaveable { mutableStateOf(false) }
    // El recorrido al que se saltó desde la lista: se busca cuando la vista ya está armada.
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val flowsState = rememberLazyListState()

    LaunchedEffect(showFlows, jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        if (!showFlows) return@LaunchedEffect
        val index = Features.flows.indexOfFirst { it.title == target }
        if (index >= 0) flowsState.scrollToItem(index)
        jumpTo = null
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Qué puede hacer la app", style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppToggle("Funciones", !showFlows) { showFlows = false }
            AppToggle("Cómo se usa", showFlows) { showFlows = true }
        }
        if (showFlows) {
            LazyColumn(
                state = flowsState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            ) {
                items(Features.flows, key = { it.title }) { flow -> FeatureFlowCard(flow, Platform.PHONE) }
            }
        } else {
            FeatureList(query, { query = it }) { flow ->
                jumpTo = flow
                showFlows = true
            }
        }
    }
}

/** «Funciones»: la lista con buscador. Cada grupo con recorrido lleva a «Cómo se usa». */
@Composable
private fun FeatureList(query: String, onQuery: (String) -> Unit, onShowFlow: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val groups = remember(query) { Features.search(query) }
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
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
                val flow = Features.flowFor(group.title)
                Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            group.title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.secondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (flow != null) {
                            AppTextButton(
                                onClick = { onShowFlow(flow.title) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) { Text("Ver cómo se usa ›", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
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
