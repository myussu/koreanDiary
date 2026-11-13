package com.jorge.koreandiary.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Micrófono de los audífonos Bluetooth, solo mientras se usa (las pausas de Escuchar). Usar el
 * micrófono pasa los audífonos al modo llamada, que suena peor; por eso se suelta apenas sigue la
 * narración y el audio vuelve a la calidad normal. Android 12 o más nuevo (`setCommunicationDevice`);
 * en versiones anteriores sigue el micrófono del celular.
 */
class HeadsetMic(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var routed = false

    /** Audífonos Bluetooth con micrófono conectados, o null. */
    fun device(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audio == null) return null
        return audio.availableCommunicationDevices.firstOrNull { it.type in TYPES }
    }

    /** Pasa el micrófono a los audífonos. Devuelve si se pudo; si no, se usa el del celular. */
    fun route(): Boolean {
        if (routed) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || audio == null) return false
        val target = device() ?: return false
        routed = runCatching { audio.setCommunicationDevice(target) }.getOrDefault(false)
        return routed
    }

    /** Devuelve los audífonos a la calidad normal para la narración. */
    fun release() {
        if (!routed) return
        routed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audio?.clearCommunicationDevice() }
    }

    private companion object {
        /** Audífonos clásicos (HFP) y los nuevos de LE Audio. */
        val TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
