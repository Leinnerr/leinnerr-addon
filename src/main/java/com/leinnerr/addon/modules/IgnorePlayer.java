package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IgnorePlayer extends Module {
    public enum Mode { SoloSuyos, Todo }

    private static final Pattern SEPARATOR = Pattern.compile("[:»›>➤▸➜→⟫]");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> players = sgGeneral.add(new StringListSetting.Builder()
        .name("players").description("Nicks a ignorar")
        .defaultValue(new ArrayList<>()).build());

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("SoloSuyos: oculta unicamente lo que ese jugador escribe")
        .defaultValue(Mode.SoloSuyos).build());

    private final Setting<Boolean> showCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-count").description("Cuenta cuantos mensajes se ocultaron")
        .defaultValue(true).build());

    private int hidden;
    private List<String> compiledFrom = new ArrayList<>();
    private List<Pattern> compiled = new ArrayList<>();

    public IgnorePlayer() {
        super(LeinnerrAddon.CATEGORY, "ignore-player",
            "Ignora los jugadores que pogas en la lista");
    }

    @Override
    public void onActivate() {
        hidden = 0;
    }

    @Override
    public String getInfoString() {
        return showCount.get() ? String.valueOf(hidden) : null;
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        Text message = event.getMessage();
        if (message == null) return;
        refresh();
        if (compiled.isEmpty()) return;

        String plain = message.getString();
        boolean hide = mode.get() == Mode.Todo ? matchesAny(plain) : sentByIgnored(message, plain);

        if (hide) {
            event.cancel();
            hidden++;
        }
    }

    private boolean sentByIgnored(Text message, String plain) {
        if (message.getContent() instanceof TranslatableTextContent t && t.getKey().startsWith("chat.type.")) {
            Object[] args = t.getArgs();
            if (args.length > 0) {
                String sender = args[0] instanceof Text tx ? tx.getString() : String.valueOf(args[0]);
                return matchesAny(sender);
            }
        }

        Matcher m = SEPARATOR.matcher(plain);
        if (!m.find() || m.start() > 80) return false;
        return matchesAny(plain.substring(0, m.start()));
    }

    private boolean matchesAny(String text) {
        for (Pattern p : compiled) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private void refresh() {
        List<String> raw = players.get();
        if (raw.equals(compiledFrom)) return;

        compiledFrom = new ArrayList<>(raw);
        compiled = new ArrayList<>();
        for (String name : raw) {
            if (name == null || name.isBlank()) continue;
            compiled.add(Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name.trim()) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE));
        }
    }
}