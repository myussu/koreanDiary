package com.jorge.koreandiary

import com.jorge.koreandiary.ai.AzureEndpoint
import com.jorge.koreandiary.ai.AzureOpenAIProvider
import com.jorge.koreandiary.ai.CorrectionRequest
import com.jorge.koreandiary.ai.ProviderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AzureEndpointTest {
    private val expected = "https://mi-recurso.openai.azure.com/openai/v1"

    @Test
    fun acceptsBareHost() = assertEquals(expected, AzureEndpoint.baseUrl("mi-recurso.openai.azure.com"))

    @Test
    fun acceptsTrailingSlashAndSpaces() = assertEquals(expected, AzureEndpoint.baseUrl("  https://mi-recurso.openai.azure.com/ "))

    @Test
    fun stripsExistingPath() = assertEquals(expected, AzureEndpoint.baseUrl("https://mi-recurso.openai.azure.com/openai/v1/"))

    @Test
    fun acceptsFoundryProjectUrl() = assertEquals(
        "https://mi-recurso.services.ai.azure.com/openai/v1",
        AzureEndpoint.baseUrl("https://mi-recurso.services.ai.azure.com/api/projects/diario"),
    )

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmpty() {
        AzureEndpoint.baseUrl("   ")
    }

    /** Un espacio o un guion bajo no son una dirección: antes salía otra excepción y la app se cerraba. */
    @Test
    fun aBadlyWrittenEndpointIsAlwaysTheSameError() {
        for (bad in listOf("mi recurso.openai.azure.com", "https://x.openai.azure.com/openai v1", "https://mi_recurso.openai.azure.com", "https://")) {
            try {
                AzureEndpoint.baseUrl(bad)
                fail("«$bad» debería rechazarse")
            } catch (e: IllegalArgumentException) {
                // Lo esperado.
            }
        }
    }

    /** El proveedor no falla al crearse: el error llega al pedir, como cualquier otro de la IA. */
    @Test
    fun theProviderReportsItInsteadOfCrashing() = runBlocking {
        val provider = AzureOpenAIProvider("mi recurso.openai.azure.com", "gpt", "clave", "low")
        try {
            provider.complete(CorrectionRequest("hola"))
            fail("debería fallar con un endpoint inválido")
        } catch (e: ProviderException) {
            assertTrue(e.message!!.contains("endpoint"))
        }
    }
}
