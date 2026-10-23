package com.leinnerr.addon.bots;

/**
 * Datos para .bots farm
 *
 * @param owner         nick al que los bots mandan /tpa
 * @param bedX          cama donde guardan el respawn
 * @param bedY          idem
 * @param bedZ          idem
 * @param tpaIntervalMs espera entre el /tpa de un bot y el del siguiente
 * @param tpaTimeoutMs  si el tpa no se acepta en este tiempo se cancela el farm
 * @param bedTimeoutMs  tiempo que espera el mensaje de respawn guardado antes de reintentar
 * @param bedRetries    intentos maximos por bot
 * @param successText   textos
 */
public record FarmConfig(
    String owner,
    int bedX,
    int bedY,
    int bedZ,
    int tpaIntervalMs,
    int tpaTimeoutMs,
    int bedTimeoutMs,
    int bedRetries,
    String successText
) {
}
