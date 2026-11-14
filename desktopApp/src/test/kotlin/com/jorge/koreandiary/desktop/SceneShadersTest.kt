package com.jorge.koreandiary.desktop

import com.jorge.koreandiary.scene.SceneMaterial
import com.jorge.koreandiary.scene.SceneShaders
import org.jetbrains.skia.RuntimeEffect
import org.junit.Assert.fail
import org.junit.Test

/**
 * Los sombreadores de la escena viva compilan con Skia (el mismo SkSL que el celular compila con
 * AGSL). Si uno no compila, el celular dibujaría la escena vieja sin avisar: mejor que falle aquí.
 */
class SceneShadersTest {

    @Test
    fun everyMaterialCompiles() {
        val problems = SceneMaterial.entries.mapNotNull { m ->
            try {
                RuntimeEffect.makeForShader(SceneShaders.source(m))
                null
            } catch (e: Exception) {
                "$m: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
