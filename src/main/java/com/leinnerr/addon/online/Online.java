package com.leinnerr.addon.online;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.compat.Compat;
import meteordevelopment.discordipc.DiscordIPC;
import meteordevelopment.discordipc.RichPresence;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// Discord presence, envia tambien tu nick de usuario a la pagina web de hvhtiers para un monitoreo del crecimiento del addon
// bro si tienes dudas sobre este sistemas puedes pedirme directamente a mi(Leinnerr), me puedes pedir el codigo activo en tiempo real de api/discordpresence

public final class Online {
    public static final String SITE = "https://hvh-tiers.vercel.app/";
    public static final String PING_URL = SITE + "api/discordpresence";

    private static final long DISCORD_APP_ID = 0L;
    private static final long METEOR_APP_ID = 835240968533049424L;

    private static final Online INSTANCE = new Online();

    private Online() {}

    public static void init() {
        AddonConfig.load();
        MeteorClient.EVENT_BUS.subscribe(INSTANCE);

        if (AddonConfig.get("discord-presence")) startPresence();
        if (AddonConfig.get("usage-stats")) sendPing();
    }

    public static void shutdown() {
        try {
            DiscordIPC.stop();
        } catch (Throwable ignored) {
        }
    }

    private static void startPresence() {
        try {
            boolean own = DISCORD_APP_ID != 0L;
            long appId = own ? DISCORD_APP_ID : METEOR_APP_ID;

            DiscordIPC.setOnError((code, msg) -> LeinnerrAddon.LOG.warn("Discord IPC {}: {}", code, msg));
            boolean ok = DiscordIPC.start(appId, () -> {
                RichPresence rp = new RichPresence() {
                    @Override
                    public JsonObject toJson() {
                        JsonObject o = super.toJson();
                        JsonArray buttons = new JsonArray();
                        JsonObject b = new JsonObject();
                        b.addProperty("label", "Web");
                        b.addProperty("url", SITE);
                        buttons.add(b);
                        o.add("buttons", buttons);
                        return o;
                    }
                };
                rp.setStart(System.currentTimeMillis() / 1000L);
                rp.setDetails("Leinnerr Addon");
                rp.setState("hvh-tiers.vercel.app");
                if (!own) rp.setLargeImage("meteor_client", "Leinnerr Addon");
                DiscordIPC.setActivity(rp);
            });
            if (!ok) LeinnerrAddon.LOG.info("Discord no ta abierto, omitido el discord presence");
        } catch (Throwable t) {
            LeinnerrAddon.LOG.warn("faaah no se pudo iniciar el discord precense: {}", t.toString());
        }
    }

    private static void sendPing() {
        Thread t = new Thread(() -> {
            try {
                String name = MinecraftClient.getInstance().getSession().getUsername();
                String version = FabricLoader.getInstance().getModContainer("leinnerr-addon")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

                JsonObject body = new JsonObject();
                body.addProperty("username", name);
                body.addProperty("addon", "leinnerr-addon");
                body.addProperty("version", version);
                body.addProperty("mc", Compat.TARGET);

                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(PING_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
                HttpResponse<Void> res = client.send(req, HttpResponse.BodyHandlers.discarding());
                LeinnerrAddon.LOG.info("Stats de uso enviadas ({}).", res.statusCode());
            } catch (Throwable e) {
                LeinnerrAddon.LOG.debug("nose pudieron enviar las stats: {}", e.toString());
            }
        }, "Leinnerr-Stats");
        t.setDaemon(true);
        t.start();
    }

    @EventHandler
    private void onJoin(GameJoinedEvent event) {
        if (!AddonConfig.get("usage-stats") || AddonConfig.get("notice-shown")) return;
        AddonConfig.set("notice-shown", true);
        ChatUtils.info("El addon envia tu nick de mc a la web para la gestion y conteo de usuarios"
            + "pa desactivar esto pon usage-stats=false en .minecraft/leinnerr/config.properties");
    }
}

// quien velga lee estas cosas?
// Nota: este sistema tambien a funcinara a futuro para poder comunicarse globalmente con todos los que esten usando el addon asi como el chat de mioclient
