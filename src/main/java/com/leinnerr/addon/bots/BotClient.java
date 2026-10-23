package com.leinnerr.addon.bots;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;

import java.util.Locale;

public class BotClient {
    public enum State { IDLE, CONNECTING, LOGGING_IN, OPENING_MENU, DONE, DISCONNECTED }

    private final BotEntry entry;
    private final BotConfig cfg;
    private final BotManager manager;

    private volatile Session session;
    private volatile State state = State.IDLE;
    private volatile int menuTries;
    private volatile int sequence;
    private volatile boolean authSent;
    private volatile long lastRespawnMs;

    private enum FarmPhase { IDLE, TPA_SENT, WAITING_TURN, AT_BED }

    private volatile FarmPhase farmPhase = FarmPhase.IDLE;
    private volatile FarmConfig farmCfg;
    private volatile long tpaSentAt;
    private volatile int bedAttempts;
    private volatile int entityId = -1;
    private volatile boolean confirmWarned;
    private volatile boolean loadedMissingWarned;
    private volatile int loadedLogs;

    private volatile boolean posKnown;
    private volatile boolean dead;
    private volatile double px, py, pz;
    private volatile float pyaw, ppitch;
    private volatile java.util.concurrent.ScheduledFuture<?> tickTask;
    private int tickCount;

    public BotClient(BotEntry entry, BotConfig cfg, BotManager manager) {
        this.entry = entry;
        this.cfg = cfg;
        this.manager = manager;
    }

    public String name() {
        return entry.name();
    }

    public State state() {
        return state;
    }

    public void connect() {
        state = State.CONNECTING;
        manager.log(name(), "Conectando a " + cfg.host() + ":" + cfg.port() + " ...");
        try {
            Session s = BotProtocol.newSession(cfg.host(), cfg.port(), name());
            s.addListener(new SessionAdapter() {
                @Override
                public void packetReceived(Session session, Packet packet) {
                    try {
                        onPacket(packet);
                    } catch (Throwable t) {
                        manager.log(name(), "Error procesando paquete: " + t);
                    }
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    state = State.DISCONNECTED;
                    stopClientTicks();
                    manager.log(name(), "Desconectado: " + BotProtocol.plainAny(event.getReason()));
                    manager.botGone(BotClient.this);
                }
            });
            session = s;
            BotProtocol.connect(s);
        } catch (Throwable t) {
            state = State.DISCONNECTED;
            manager.log(name(), "No se pudo conectar: " + t);
            manager.botGone(this);
        }
    }

    public void disconnect() {
        Session s = session;
        state = State.DISCONNECTED;
        stopClientTicks();
        if (s != null) {
            try {
                BotProtocol.close(s);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean say(String text) {
        return sendText(text, true);
    }

    private boolean sendText(String text, boolean log) {
        Session s = session;
        if (s == null || !s.isConnected()) {
            manager.log(name(), "No esta conectado (estado " + state + "); no se envio: " + (log ? text : "(oculto)"));
            return false;
        }
        try {
            s.send(BotProtocol.message(text));
            if (log) manager.log(name(), "> " + text);
            return true;
        } catch (Throwable t) {
            manager.log(name(), "error al enviar: " + t);
            return false;
        }
    }

    private void send(Packet packet) {
        Session s = session;
        if (s != null && s.isConnected()) s.send(packet);
    }

    private void onPacket(Packet packet) {
        if (cfg.autoRespawn() && BotProtocol.isDeath(packet)) {
            onDeath();
            return;
        }

        String simple = packet.getClass().getSimpleName();

        if (simple.equals("ClientboundSetHealthPacket")) logHealth(packet);

        if (simple.equals("ClientboundRespawnPacket")) {
            dead = false;
            sendPlayerLoaded("respawn");
            manager.schedule(() -> sendPlayerLoaded("respawn +250 ms"), 250);
        }

        if (farmPhase == FarmPhase.AT_BED && simple.equals("ClientboundBlockChangedAckPacket")) {
            manager.log(name(), "El servel recibio el click en la cama");
            return;
        }

        if (BotProtocol.isTeleportSync(packet)) {
            onTeleport(packet);
            return;
        }

        if (packet instanceof ClientboundLoginPacket) {
            entityId = BotProtocol.entityId(packet);
            sendPlayerLoaded("login");

            if (state != State.CONNECTING) return;
            state = State.LOGGING_IN;
            manager.log(name(), "Dentro del lobby.");

            if (!entry.hasRawCommand() && !entry.hasPassword()) {
                manager.schedule(this::startMenuFlow, cfg.lobbyDelayMs());
                return;
            }

            if (entry.hasRawCommand()) {
                manager.schedule(() -> {
                    if (state != State.LOGGING_IN) return;
                    manager.log(name(), "> " + entry.command());
                    sendText(entry.command(), false);
                }, 1000);
            }

            manager.schedule(() -> {
                if (state == State.LOGGING_IN) {
                    manager.log(name(), "no llego el mensaje del loin tal  ves inicio de una con secion resumida");
                    startMenuFlow();
                }
            }, cfg.loginTimeoutMs());

        } else if (packet instanceof ClientboundSystemChatPacket chat) {
            String text = BotProtocol.plain(chat.getContent());
            if (text.isBlank()) return;
            manager.log(name(), text);

            String lower = text.toLowerCase(Locale.ROOT);

            FarmConfig fc = farmCfg;
            if (farmPhase == FarmPhase.AT_BED && fc != null && matchesAny(lower, fc.successText())) {
                finishBed(true);
            }

            if (state == State.LOGGING_IN && entry.hasPassword() && !authSent) {
                String pw = entry.password();
                if (lower.contains("/register") || lower.contains("/registro")) {
                    authSent = true;
                    manager.log(name(), "> /register ****");
                    sendText("/register " + pw + " " + pw, false);
                } else if (lower.contains("/login")) {
                    authSent = true;
                    manager.log(name(), "> /login ****");
                    sendText("/login " + pw, false);
                }
            }

            if (state == State.LOGGING_IN && matchesSuccess(lower)) {
                state = State.OPENING_MENU;
                manager.schedule(this::openCompass, cfg.lobbyDelayMs());
            }

        } else if (packet instanceof ClientboundPlayerChatPacket chat) {
            manager.log(name(), BotProtocol.plain(chat.getName()) + ": " + chat.getContent());

        } else if (packet instanceof ClientboundContainerSetContentPacket content) {
            if (state == State.OPENING_MENU && content.getContainerId() != 0) {
                clickMenu(content.getContainerId(), content.getStateId());
            }

        } else if (packet instanceof ClientboundDisconnectPacket kick) {
            manager.log(name(), "Expulsado: " + BotProtocol.plain(kick.getReason()));
        }
    }

    private boolean matchesSuccess(String lowerText) {
        return matchesAny(lowerText, cfg.loginSuccessText());
    }

    private static boolean matchesAny(String lowerText, String csv) {
        for (String part : csv.split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && lowerText.contains(p)) return true;
        }
        return false;
    }


    private void startClientTicks() {
        if (tickTask != null || !cfg.emulateClient()) return;
        tickTask = manager.every(this::clientTick, 50);
    }

    private void stopClientTicks() {
        java.util.concurrent.ScheduledFuture<?> task = tickTask;
        tickTask = null;
        if (task != null) task.cancel(false);
    }

    private void clientTick() {
        Session s = session;
        if (s == null || !s.isConnected() || !posKnown || dead || state == State.DISCONNECTED) return;

        if (++tickCount % 20 == 0) {
            Packet move = BotProtocol.movePos(px, py, pz, true);
            if (move != null) s.send(move);
        }
        Packet end = BotProtocol.clientTickEnd();
        if (end != null) s.send(end);
    }

    private void sendPlayerLoaded(String why) {
        Packet loaded = BotProtocol.playerLoaded();
        if (loaded == null) {
            if (!loadedMissingWarned) {
                loadedMissingWarned = true;
                manager.log(name(), "abiso: esta version de MCProtocolLib no tiene ServerboundPlayerLoadedPacket; "
                    + "sin el, el servidor deja ~3 s de inmunidad al revivir");
            }
            return;
        }
        send(loaded);
        if (loadedLogs++ < 3) manager.log(name(), "PlayerLoaded enviado (" + why + ")");
    }

    void startFarm(FarmConfig fc) {
        if (state != State.DONE) {
            manager.log(name(), "No esta listo para el farm (estado " + state + ").");
            return;
        }
        farmCfg = fc;
        farmPhase = FarmPhase.TPA_SENT;
        bedAttempts = 0;
        long sentAt = System.currentTimeMillis();
        tpaSentAt = sentAt;

        if (!sendText("/tpa " + fc.owner(), true)) {
            farmPhase = FarmPhase.IDLE;
            return;
        }
        manager.log(name(), "Esperando que " + fc.owner() + " acepte el tpa");

        manager.schedule(() -> {
            if (farmPhase == FarmPhase.TPA_SENT && tpaSentAt == sentAt) {
                farmPhase = FarmPhase.IDLE;
                manager.log(name(), "El tpa no fue aceptado a y se cancela el farm");
            }
        }, fc.tpaTimeoutMs());
    }

    private void onTeleport(Packet packet) {
        int id = BotProtocol.teleportId(packet);
        Packet confirm = id >= 0 ? BotProtocol.acceptTeleport(id) : null;
        if (confirm != null) send(confirm);

        double[] pos = BotProtocol.teleportPosition(packet);
        if (pos != null) {
            px = pos[0];
            py = pos[1];
            pz = pos[2];
            pyaw = (float) pos[3];
            ppitch = (float) pos[4];
            posKnown = true;
            if (cfg.emulateClient()) {
                Packet echo = BotProtocol.movePosRot(px, py, pz, pyaw, ppitch, false);
                if (echo != null) send(echo);
                startClientTicks();
            }
        }

        if (confirm == null && !confirmWarned) {
            confirmWarned = true;
            manager.log(name(), "abiso: no pude confirmar el teletransporte (id " + id
                + (id < 0 ? ": no encontre el getter del id" : ": no encontre el paquete de confirmacion")
                + ") Sin eso el servidor no deja usar la cama");
        }
        sendPlayerLoaded("sync de posicion");

        if (farmPhase != FarmPhase.IDLE) {
            manager.log(name(), "Sync de posicion (id " + id + "): "
                + (confirm != null ? "teletransporte confirmado" : "sin confirmar"));
        }

        FarmConfig fc = farmCfg;
        if (farmPhase == FarmPhase.TPA_SENT && fc != null && System.currentTimeMillis() - tpaSentAt > 200) {
            farmPhase = FarmPhase.WAITING_TURN;
            manager.log(name(), "Teletransportado a " + fc.owner() + " Esperando su turno para la cama");
            manager.farmReady(this);
        }
    }

    void takeBedTurn(FarmConfig fc) {
        if (farmPhase != FarmPhase.WAITING_TURN && farmPhase != FarmPhase.AT_BED) {
            manager.farmTurnDone(this);
            return;
        }
        farmPhase = FarmPhase.AT_BED;
        int attempt = ++bedAttempts;
        manager.log(name(), "Usando la cama en " + fc.bedX() + " " + fc.bedY() + " " + fc.bedZ()
            + " (intento " + attempt + "/" + fc.bedRetries() + ")");

        try {
            send(BotProtocol.useBlock(fc.bedX(), fc.bedY(), fc.bedZ(), ++sequence));
        } catch (Throwable t) {
            manager.log(name(), "No pude usar la cama: " + t);
            finishBed(false);
            return;
        }

        manager.schedule(() -> {
            if (farmPhase != FarmPhase.AT_BED || bedAttempts != attempt) return;
            if (attempt < fc.bedRetries()) {
                takeBedTurn(fc);
            } else {
                manager.log(name(), "No pude confirmar el respawn en la cama  " + attempt
                    + " intentos (revisar que este cerca la cama)");
                finishBed(false);
            }
        }, fc.bedTimeoutMs());
    }

    private void finishBed(boolean ok) {
        if (farmPhase != FarmPhase.AT_BED) return;
        farmPhase = FarmPhase.IDLE;

        if (ok) manager.log(name(), "Tomo el respawn de forma correcta en la cama");

        Packet wake = BotProtocol.stopSleeping(entityId);
        if (wake != null) send(wake);

        manager.farmTurnDone(this);
    }

    private void logHealth(Packet packet) {
        try {
            Object h = packet.getClass().getMethod("getHealth").invoke(packet);
            if (h instanceof Number n && n.floatValue() > 0f && n.floatValue() < 20f) {
                long since = lastRespawnMs == 0 ? -1 : System.currentTimeMillis() - lastRespawnMs;
                manager.log(name(), "Vida: " + n.floatValue()
                    + (since >= 0 ? " (" + since + " ms desde el respawn)" : ""));
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void onDeath() {
        long now = System.currentTimeMillis();
        if (now - lastRespawnMs < 400) return;
        String lived = lastRespawnMs == 0 ? "" : " (llevaba " + (now - lastRespawnMs) + " ms vivo)";
        lastRespawnMs = now;
        dead = true;

        Runnable respawn = () -> {
            try {
                send(BotProtocol.respawn());
                manager.log(name(), "Murio" + lived + ": respawn automatico");
            } catch (Throwable t) {
                manager.log(name(), "No pude revivir: " + t);
            }
        };

        if (cfg.respawnDelayMs() <= 0) respawn.run();
        else manager.schedule(respawn, cfg.respawnDelayMs());
    }

    private void startMenuFlow() {
        if (state != State.LOGGING_IN) return;
        state = State.OPENING_MENU;
        openCompass();
    }

    private void openCompass() {
        if (state != State.OPENING_MENU) return;

        if (++menuTries > cfg.menuRetries()) {
            manager.log(name(), "No se abrio el menu " + cfg.menuRetries() + " intentos");
            state = State.DONE;
            return;
        }

        manager.log(name(), "Brujula: slot " + cfg.compassSlot() + " + click derecho (intento " + menuTries + ")");
        send(BotProtocol.selectHotbar(cfg.compassSlot()));
        manager.schedule(() -> {
            if (state == State.OPENING_MENU) send(BotProtocol.useItem(++sequence));
        }, 300);

        manager.schedule(this::openCompass, cfg.menuTimeoutMs());
    }

    private void clickMenu(int containerId, int stateId) {
        state = State.DONE;
        manager.log(name(), "Menu abierto; click en el slot " + cfg.menuSlot());
        send(BotProtocol.clickSlot(containerId, stateId, cfg.menuSlot()));
    }
}

// este sistema es basico beta no funciona bien la verdad, lo mejorare
