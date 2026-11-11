package com.jorge.koreandiary

import com.jorge.koreandiary.ai.AzureEndpoint
import org.junit.Assert.assertEquals
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
}
