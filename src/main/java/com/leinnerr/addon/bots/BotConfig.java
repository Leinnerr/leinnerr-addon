package com.leinnerr.addon.bots;

/**
 * Ajustes compartidos por todos los bots
 *
 * @param host             servidor al que entran
 * @param port             puerto
 * @param loginSuccessText texto (sin distinguir mayusculas) que el servidor manda cuando el bot ya esta logueado
 * @param loginTimeoutMs   si ese texto no llega en este tiempo, el bot sigue igual hacia el menu
 * @param lobbyDelayMs     espera y usaa la brujula
 * @param compassSlot      slot de la hotbar donde esta la brujula
 * @param menuSlot         slot del menu modalidades donde esta Anarquico
 * @param menuTimeoutMs    tiempo de espera a que se abra el menu antes de reintentar
 * @param menuRetries      intentos maximos de abrir el menu
 * @param autoRespawn      si el bot revive solo al morir
 * @param respawnDelayMs   delay del respawn
 * @param emulateClient    manda lo mismo que un cliente real
 */
public record BotConfig(
    String host,
    int port,
    String loginSuccessText,
    int loginTimeoutMs,
    int lobbyDelayMs,
    int compassSlot,
    int menuSlot,
    int menuTimeoutMs,
    int menuRetries,
    boolean autoRespawn,
    int respawnDelayMs,
    boolean emulateClient
) {
}
