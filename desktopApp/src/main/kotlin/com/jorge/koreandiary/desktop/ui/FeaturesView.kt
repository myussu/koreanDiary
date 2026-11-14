package com.jorge.koreandiary.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jorge.koreandiary.features.Features
import com.jorge.koreandiary.features.Platform

/**
 * «Qué puede hacer la app» en la PC: lo mismo que el celular (`shared`, `features/Features.kt`), en
 * dos vistas: «Funciones», la lista con buscador, y «Cómo se usa», cada pantalla paso a paso,
 * dibujada. Lo que todavía funciona solo en el celular sale en gris, para ver qué falta aquí.
 */
@Composable
fun FeaturesView(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var showFlows by remember { mutableStateOf(false) }
    // El recorrido al que se saltó desde la lista: se busca cuando la vista ya está armada.
    var jumpTo by remember { mutableStateOf<String?>(null) }
    val flowsState = rememberLazyListState()
    val groups = remember(query) { Features.search(query) }

    LaunchedEffect(showFlows, jumpTo) {
        val target = jumpTo ?: return@LaunchedEffect
        if (!showFlows) return@LaunchedEffect
        val index = Features.flows.indexOfFirst { it.title == target }
        if (index >= 0) flowsState.scrollToItem(index)
        jumpTo = null
    }

    Column(modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextButton(onClick = onBack) { Text("‹ Diario") }
                Text("Qué puede hacer la app", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "En la PC funcionan ${Features.countOn(Platform.DESKTOP)} de ${Features.total}. " +
                    "Lo que sale en gris todavía está solo en el celular.",
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppToggle("Funciones", !showFlows) { showFlows = false }
                AppToggle("Cómo se usa", showFlows) { showFlows = true }
            }
            if (!showFlows) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar: escena, voz, copia…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            if (!showFlows && groups.isEmpty()) {
                Text(
                    "No está en la lista, así que todavía no existe. Si la quieres, pídela.",
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        if (showFlows) {
            LazyColumn(
                Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                state = flowsState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            ) {
                items(Features.flows, key = { it.title }) { flow -> FeatureFlowCard(flow, Platform.DESKTOP) }
            }
        } else LazyColumn(
            Modifier.widthIn(max = 900.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            groups.forEach { group ->
                item(key = group.title) {
                    val flow = Features.flowFor(group.title)
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                group.title.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.secondary,
                                modifier = Modifier.weight(1f),
                            )
                            if (flow != null) {
                                AppTextButton(onClick = {
                                    jumpTo = flow.title
                                    showFlows = true
                                }) { Text("Ver cómo se usa ›", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                items(group.features, key = { "${group.title}:${it.text}" }) { feature ->
                    val missing = feature.missingLabel(Platform.DESKTOP)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("·", color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                        Text(
                            feature.text,
                            color = if (missing == null) scheme.onSurface else scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (missing != null) {
                            Text(
                                missing,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
