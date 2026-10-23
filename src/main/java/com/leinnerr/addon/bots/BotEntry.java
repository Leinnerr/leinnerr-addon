package com.leinnerr.addon.bots;

import java.util.ArrayList;
import java.util.List;

public record BotEntry(String name, String command) {

    public boolean hasRawCommand() {
        return command.startsWith("/");
    }

    public boolean hasPassword() {
        return !command.isEmpty() && !command.startsWith("/");
    }

    public String password() {
        return command;
    }

    public static BotEntry parse(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        int i = trimmed.indexOf(';');
        String name = (i < 0 ? trimmed : trimmed.substring(0, i)).trim();
        String command = i < 0 ? "" : trimmed.substring(i + 1).trim();

        if (!name.matches("[A-Za-z0-9_]{3,16}")) return null;
        if (!command.startsWith("/") && command.matches(".*\\s.*")) return null;
        return new BotEntry(name, command);
    }

    public static List<BotEntry> parseAll(List<String> lines) {
        List<BotEntry> out = new ArrayList<>();
        for (String line : lines) {
            BotEntry e = parse(line);
            if (e != null) out.add(e);
        }
        return out;
    }
}
