package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.compat.Compat;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class AntiSpamKills extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilters = settings.createGroup("Excepciones");

    private final Setting<Boolean> showCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-count").description("muestra cuantos mensajes se ocultaron")
        .defaultValue(true).build());

    private final Setting<Boolean> keepOwn = sgFilters.add(new BoolSetting.Builder()
        .name("keep-own").description("Concerva tus kills")
        .defaultValue(true).build());

    private final Setting<Boolean> keepFriends = sgFilters.add(new BoolSetting.Builder()
        .name("keep-friends").description("Concerva las kills de tus amigos")
        .defaultValue(true).build());

    private final Setting<List<String>> customPatterns = sgFilters.add(new StringListSetting.Builder()
        .name("custom-patterns")
        .description("Patrones regex extra para ocultar tambien (pa servidores que envian las muertes como texto plano)")
        .defaultValue(new ArrayList<>()).build());

    private int hidden;
    private List<String> compiledFrom = new ArrayList<>();
    private List<Pattern> compiled = new ArrayList<>();

    public AntiSpamKills() {
        super(LeinnerrAddon.CATEGORY, "anti-spam-kills", "oculta los mensajes de muertes en el chat (pa esos perros farmkills)");
    }

    @Override
    public void onActivate() {
        hidden = 0;
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        Text message = event.getMessage();
        if (message == null || !isDeathMessage(message)) return;

        String plain = message.getString();

        if (keepOwn.get() && mentionsSelf(plain)) return;
        if (keepFriends.get() && mentionsFriend(plain)) return;

        event.cancel();
        hidden++;
    }

    private boolean isDeathMessage(Text text) {
        return hasDeathKey(text) || matchesCustom(text.getString());
    }

    private boolean hasDeathKey(Text text) {
        if (text.getContent() instanceof TranslatableTextContent translatable
            && translatable.getKey().startsWith("death.")) {
            return true;
        }

        for (Text sibling : text.getSiblings()) {
            if (hasDeathKey(sibling)) return true;
        }
        return false;
    }

    private boolean matchesCustom(String plain) {
        List<String> raw = customPatterns.get();
        if (raw.isEmpty()) return false;

        if (!raw.equals(compiledFrom)) {
            compiledFrom = new ArrayList<>(raw);
            compiled = new ArrayList<>();
            for (String s : raw) {
                if (s == null || s.isBlank()) continue;
                try {
                    compiled.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException e) {
                    warning("Regex invalido ignorado: %s", s);
                }
            }
        }

        for (Pattern p : compiled) {
            if (p.matcher(plain).find()) return true;
        }
        return false;
    }

    private boolean mentionsSelf(String plain) {
        return mc.player != null && plain.contains(mc.player.getName().getString());
    }

    private boolean mentionsFriend(String plain) {
        if (mc.getNetworkHandler() == null) return false;

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            String name = Compat.profileName(entry);
            if (name != null && plain.contains(name) && Friends.get().get(name) != null) return true;
        }
        return false;
    }

    @Override
    public String getInfoString() {
        return showCount.get() ? String.valueOf(hidden) : null;
    }
}
