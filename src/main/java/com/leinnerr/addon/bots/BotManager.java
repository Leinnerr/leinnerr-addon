package com.leinnerr.addon.bots;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class BotManager {
    private static final BotManager INSTANCE = new BotManager();
    private static final int MAX_HISTORY = 500;

    public static BotManager get() {
        return INSTANCE;
    }

    private final Map<String, BotClient> bots = new LinkedHashMap<>();
    private final Deque<String> history = new ArrayDeque<>();
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "leinnerr-bots");
        t.setDaemon(true);
        return t;
    });

    private volatile Consumer<String> sink;

    private final Deque<BotClient> farmQueue = new ArrayDeque<>();
    private BotClient farmBusy;
    private FarmConfig farmCfg;

    private BotManager() {
    }

    public void setSink(Consumer<String> sink) {
        this.sink = sink;
    }

    public synchronized List<String> history() {
        return new ArrayList<>(history);
    }

    public void log(String bot, String message) {
        String line = bot == null ? message : "[" + bot + "] " + message;
        synchronized (this) {
            history.addLast(line);
            while (history.size() > MAX_HISTORY) history.removeFirst();
        }
        Consumer<String> s = sink;
        if (s != null) s.accept(line);
    }

    java.util.concurrent.ScheduledFuture<?> every(Runnable task, long periodMs) {
        return pool.scheduleAtFixedRate(() -> {
            try {
                task.run();
            } catch (Throwable ignored) {
                
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    void schedule(Runnable task, long delayMs) {
        pool.schedule(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log(null, "Error interno: " + t);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized int joinAll(List<BotEntry> entries, BotConfig cfg, int delayMs) {
        int i = 0;
        for (BotEntry e : entries) {
            String key = key(e.name());
            if (bots.containsKey(key)) {
                log(null, e.name() + " ya esta conectado");
                continue;
            }
            BotClient bot = new BotClient(e, cfg, this);
            bots.put(key, bot);
            schedule(bot::connect, (long) i++ * delayMs);
        }
        return i;
    }

    public synchronized boolean has(String name) {
        return bots.containsKey(key(name));
    }

    public void leave(String name) {
        BotClient bot;
        synchronized (this) {
            bot = bots.remove(key(name));
        }
        if (bot == null) {
            log(null, "No existe el bot " + name + ".");
            return;
        }
        bot.disconnect();
        log(null, name + " salio");
    }

    public void leaveAll() {
        List<BotClient> copy;
        synchronized (this) {
            copy = new ArrayList<>(bots.values());
            bots.clear();
        }
        for (BotClient bot : copy) bot.disconnect();
        if (!copy.isEmpty()) log(null, "Todos los bots salieron");
    }

    synchronized void botGone(BotClient bot) {
        bots.remove(key(bot.name()), bot);
        farmQueue.remove(bot);
        if (farmBusy == bot) {
            farmBusy = null;
            pumpFarm();
        }
    }

    public synchronized int startFarm(String target, FarmConfig cfg) {
        farmCfg = cfg;
        farmQueue.clear();
        farmBusy = null;

        boolean all = target == null || target.equalsIgnoreCase("all");
        List<BotClient> list = new ArrayList<>();
        for (BotClient b : bots.values()) {
            if (!all && !b.name().equalsIgnoreCase(target)) continue;
            if (b.state() != BotClient.State.DONE) {
                log(b.name(), "Aun no esta listo para el farm (estado " + b.state() + ")");
                continue;
            }
            list.add(b);
        }

        int i = 0;
        for (BotClient b : list) {
            schedule(() -> b.startFarm(cfg), (long) i++ * cfg.tpaIntervalMs());
        }
        return list.size();
    }

    synchronized void farmReady(BotClient bot) {
        if (!farmQueue.contains(bot) && farmBusy != bot) farmQueue.addLast(bot);
        pumpFarm();
    }

    synchronized void farmTurnDone(BotClient bot) {
        if (farmBusy == bot) farmBusy = null;
        schedule(() -> {
            synchronized (BotManager.this) {
                pumpFarm();
            }
        }, 500); 
    }

    private void pumpFarm() {
        if (farmBusy != null) return;
        BotClient next = farmQueue.pollFirst();
        if (next == null) return;
        farmBusy = next;
        FarmConfig cfg = farmCfg;
        log(null, "Turno de " + next.name() + " para la cama (en cola: " + farmQueue.size() + ").");
        schedule(() -> next.takeBedTurn(cfg), 0);
    }

    public synchronized List<String> names() {
        List<String> out = new ArrayList<>();
        for (BotClient b : bots.values()) out.add(b.name() + " (" + b.state() + ")");
        return out;
    }

    public void say(String target, String text) {
        List<BotClient> targets = new ArrayList<>();
        synchronized (this) {
            if (target == null || target.equalsIgnoreCase("all")) {
                targets.addAll(bots.values());
            } else {
                BotClient b = bots.get(key(target));
                if (b != null) targets.add(b);
            }
        }
        if (targets.isEmpty()) {
            log(null, "Ningun bot coincide con \"" + (target == null ? "all" : target) + "\".");
            return;
        }
        int delay = 0;
        for (BotClient b : targets) {
            schedule(() -> b.say(text), delay);
            delay += 150;
        }
    }

    public void dispatch(String input) {
        String line = input.trim();
        if (line.isEmpty()) return;

        if (line.startsWith("!")) {
            String[] p = line.substring(1).split("\\s+", 2);
            switch (p[0].toLowerCase(Locale.ROOT)) {
                case "list" -> log(null, "Bots: " + (names().isEmpty() ? "(ninguno)" : String.join(", ", names())));
                case "leave" -> {
                    if (p.length < 2 || p[1].equalsIgnoreCase("all")) leaveAll();
                    else leave(p[1].trim());
                }
                default -> log(null, "Comandos de la consola: !list, !leave [nombre|all]");
            }
            return;
        }

        String target = null;
        String text = line;
        int i = line.indexOf(':');
        if (i > 0) {
            String prefix = line.substring(0, i).trim();
            if (prefix.matches("[A-Za-z0-9_]{1,16}")) {
                target = prefix;
                text = line.substring(i + 1).trim();
            }
        }
        if (text.isEmpty()) return;

        say(target, text);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
