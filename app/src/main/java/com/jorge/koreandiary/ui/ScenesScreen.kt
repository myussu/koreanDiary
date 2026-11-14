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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jorge.koreandiary.AppContainer
import com.jorge.koreandiary.data.db.PageKind
import com.jorge.koreandiary.ui.page.SceneThumb
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Escenas: páginas para describir un lugar o un momento, vivido o imaginado. Usan el mismo lienzo,
 * Revisar y Verificar que el diario; aquí se prueban las funciones nuevas antes de llevarlas al diario.
 */
@Composable
fun ScenesScreen(container: AppContainer, onBack: () -> Unit, onOpenPage: (Long) -> Unit) {
    val scenes by container.diary.sceneSummaries.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onBack) { Text("‹ Diario") }
            Text("Escenas", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Describe un lugar o un momento, vivido o imaginado: lo que ves, oyes y sientes. " +
                "Se escribe, se revisa y se verifica igual que el diario.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            "Cuenta para tu racha cuando está revisada y reescribiste todas sus correcciones.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        AppButton(
            onClick = {
                creating = true
                scope.launch {
                    val id = newPageWithSuggestions(container, PageKind.SCENE)
                    creating = false
                    onOpenPage(id)
                }
            },
            enabled = !creating,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
        ) { Text("Nueva escena") }

        if (scenes.isEmpty()) {
            Text(
                "Aún no hay escenas. Piensa en una playa, un café, una calle de noche… y escríbela con el S Pen.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        val today = LocalDate.now()
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        ) {
            items(scenes, key = { it.pageId }) { p ->
                PageSummaryCard(p, today, onClick = { onOpenPage(p.pageId) }, leading = { SceneThumb(container.sceneImages, p.pageId) })
            }
        }
    }
}
