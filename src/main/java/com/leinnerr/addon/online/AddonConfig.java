package com.leinnerr.addon.online;

import com.leinnerr.addon.LeinnerrAddon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class AddonConfig {
    private static final Properties props = new Properties();
    private static Path file;

    private AddonConfig() {}

    public static void load() {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("leinnerr");
            Files.createDirectories(dir);
            file = dir.resolve("config.properties");

            props.setProperty("discord-presence", "true");
            props.setProperty("usage-stats", "true");
            props.setProperty("notice-shown", "false");

            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            }
            save();
        } catch (IOException e) {
            LeinnerrAddon.LOG.warn("faaah no se pudo leer/crear la config: {}", e.toString());
        }
    }

    public static boolean get(String key) {
        return Boolean.parseBoolean(props.getProperty(key, "true"));
    }

    public static void set(String key, boolean value) {
        props.setProperty(key, Boolean.toString(value));
        save();
    }

    private static void save() {
        if (file == null) return;
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Leinnerr Addon\n"
                + "discord-presence: muestra el presence y la web\n"
                + "usage-stats: enviar tu nic de minecraft a la web para el conteo");
        } catch (IOException ignored) {
        }
    }
}
