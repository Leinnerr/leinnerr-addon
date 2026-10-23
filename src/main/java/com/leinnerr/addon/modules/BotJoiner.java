package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.bots.BotConfig;
import com.leinnerr.addon.bots.BotConsole;
import com.leinnerr.addon.bots.BotEntry;
import com.leinnerr.addon.bots.BotManager;
import com.leinnerr.addon.bots.FarmConfig;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BedBlock;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class BotJoiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLobby = settings.createGroup("Lobby / menu");
    private final SettingGroup sgFarm = settings.createGroup("Farm / cama");

    private final Setting<List<String>> bots = sgGeneral.add(new StringListSetting.Builder()
        .name("bots")
        .description("Un bot por linea:  Nombre;contraseña "
            + "Tambien vale un comando fijo:  Nombre;/register contraseña contraseña")
        .defaultValue(new ArrayList<>())
        .build());

    private final Setting<String> server = sgGeneral.add(new StringSetting.Builder()
        .name("server")
        .description("host o puerto Vacio = el servidor en el que estas ahora")
        .defaultValue("")
        .build());

    private final Setting<Boolean> autoJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-join")
        .description("Mete los bots solos al activar el modulo y cada vez que entras a un servidor")
        .defaultValue(true)
        .build());

    private final Setting<Integer> joinDelay = sgGeneral.add(new IntSetting.Builder()
        .name("join-delay")
        .description("Delay entre la entrada de un bot y la del siguiente")
        .defaultValue(4000).min(500).sliderRange(500, 15000)
        .build());

    private final Setting<Boolean> autoRespawn = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-respawn")
        .description("Los bots reviven solos al morir")
        .defaultValue(true)
        .build());

    private final Setting<Integer> respawnDelay = sgGeneral.add(new IntSetting.Builder()
        .name("respawn-delay")
        .description("Latencia de respawn")
        .defaultValue(0).min(0).sliderRange(0, 2000)
        .build());

    private final Setting<Boolean> clientEmulation = sgGeneral.add(new BoolSetting.Builder()
        .name("client-emulation")
        .description("Los bots mandan lo mismo que un cliente real"
            + "Hace que mueran al instante al revivir")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> openConsole = sgGeneral.add(new BoolSetting.Builder()
        .name("open-console")
        .description("Abre la ventana de consola al activar el modulo")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> leaveOnDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("leave-on-disable")
        .description("Saca a todos los bots")
        .defaultValue(true)
        .build());

    private final Setting<String> loginText = sgLobby.add(new StringSetting.Builder()
        .name("login-success-text")
        .description("Cuando el servidor dice algo de esto")
        .defaultValue("logueado,registrado")
        .build());

    private final Setting<Integer> loginTimeout = sgLobby.add(new IntSetting.Builder()
        .name("login-timeout")
        .description("Si ese mensaje no llega, el bot sigue de todas formas.")
        .defaultValue(8000).min(1000).sliderRange(1000, 30000)
        .build());

    private final Setting<Integer> lobbyDelay = sgLobby.add(new IntSetting.Builder()
        .name("lobby-delay")
        .description("Delay en el lobby")
        .defaultValue(1500).min(0).sliderRange(0, 10000)
        .build());

    private final Setting<Integer> compassSlot = sgLobby.add(new IntSetting.Builder()
        .name("compass-slot")
        .description("Slot de la hotbar donde esta la brujula")
        .defaultValue(0).range(0, 8).sliderRange(0, 8)
        .build());

    private final Setting<Integer> menuSlot = sgLobby.add(new IntSetting.Builder()
        .name("menu-slot")
        .description("Slot del menu modalidades donde esta Anarquico.")
        .defaultValue(20).range(0, 53).sliderRange(0, 53)
        .build());

    private final Setting<Integer> menuTimeout = sgLobby.add(new IntSetting.Builder()
        .name("menu-timeout")
        .description("Si el menu no se abre vuelve a intentar")
        .defaultValue(4000).min(1000).sliderRange(1000, 15000)
        .build());

    private final Setting<Integer> menuRetries = sgLobby.add(new IntSetting.Builder()
        .name("menu-retries")
        .description("Intentos maximos de abrir el menu")
        .defaultValue(3).min(1).sliderRange(1, 10)
        .build());

    private final Setting<String> farmOwner = sgFarm.add(new StringSetting.Builder()
        .name("owner")
        .description("Nick al que los bots mandan /tpa y el que los controla a todos"
            + "Debe estar cerca de la cama")
        .defaultValue("")
        .build());

    private final Setting<Integer> tpaInterval = sgFarm.add(new IntSetting.Builder()
        .name("tpa-interval")
        .description("Latencia enntre el tpa")
        .defaultValue(3000).min(500).sliderRange(500, 15000)
        .build());

    private final Setting<Integer> tpaTimeout = sgFarm.add(new IntSetting.Builder()
        .name("tpa-timeout")
        .description("Si aceptas el tpa despues de este tiempo (ms), ese bot ya se rindio.")
        .defaultValue(60000).min(5000).sliderRange(5000, 180000)
        .build());

    private final Setting<Integer> bedTimeout = sgFarm.add(new IntSetting.Builder()
        .name("bed-timeout")
        .description("Verificar el mensaje del respawn guardado, si no llega intenta de nuevo")
        .defaultValue(4000).min(1000).sliderRange(1000, 15000)
        .build());

    private final Setting<Integer> bedRetries = sgFarm.add(new IntSetting.Builder()
        .name("bed-retries")
        .description("Intentos maximos de cada bot con la cama")
        .defaultValue(2).min(1).sliderRange(1, 6)
        .build());

    private final Setting<String> bedSuccessText = sgFarm.add(new StringSetting.Builder()
        .name("bed-success-text")
        .description("Textos")
        .defaultValue("set_spawn,respawn point set,reaparici\u00f3n establecido,reaparicion establecido")
        .build());

    public BotJoiner() {
        super(LeinnerrAddon.CATEGORY, "bot-joiner",
            "Mete bots al servidor (no estoy seguro si funciona en celulares)");
    }

    @Override
    public void onActivate() {
        if (openConsole.get()) openConsole();
        if (autoJoin.get() && mc.world != null) joinBots(false);
    }

    @Override
    public void onDeactivate() {
        if (leaveOnDisable.get()) BotManager.get().leaveAll();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (autoJoin.get()) joinBots(false);
    }

    public void joinBots() {
        joinBots(true);
    }

    private void joinBots(boolean verbose) {
        List<BotEntry> entries = BotEntry.parseAll(bots.get());
        if (entries.isEmpty()) {
            error("No hay bots validos. verifica la contraseña o nick");
            return;
        }

        BotConfig cfg = buildConfig();
        if (cfg == null) {
            error("Pon el server o entra a uno");
            return;
        }

        int joined = BotManager.get().joinAll(entries, cfg, joinDelay.get());
        if (joined > 0) info("Metiendo %d bot(s) en %s:%d", joined, cfg.host(), cfg.port());
        else if (verbose) info("Los bots ya estaban conectados");
    }

    public void startFarm(String target) {
        String owner = farmOwner.get().trim();
        if (owner.isEmpty()) {
            error("Pon el nick del owner");
            return;
        }
        if (mc.world == null || mc.player == null) {
            error("Entra a un servidor primero");
            return;
        }

        BlockPos bed = findBed(owner);
        if (bed == null) {
            error("No hay ninguna cama cerca", owner);
            return;
        }

        FarmConfig fc = new FarmConfig(owner, bed.getX(), bed.getY(), bed.getZ(),
            tpaInterval.get(), tpaTimeout.get(), bedTimeout.get(), bedRetries.get(), bedSuccessText.get());

        int started = BotManager.get().startFarm(target, fc);
        if (started == 0) {
            warning("Ningun bot esta listo para el farm");
            return;
        }
        info("Farm: %d bot(s) mandaran /tpa a %s. Cama en %d %d %d. Acepta",
            started, owner, bed.getX(), bed.getY(), bed.getZ());
    }

    private BlockPos findBed(String owner) {
        PlayerEntity center = mc.player;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getName().getString().equalsIgnoreCase(owner)) {
                center = p;
                break;
            }
        }

        BlockPos origin = center.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!(mc.world.getBlockState(pos).getBlock() instanceof BedBlock)) continue;
                    double d = center.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    public void openConsole() {
        if (!BotConsole.open()) {
            warning("Este java no puede abrir headless");
        }
    }

    private BotConfig buildConfig() {
        String address = server.get().trim();
        if (address.isEmpty()) {
            ServerInfo info = mc.getCurrentServerEntry();
            if (info == null) return null;
            address = info.address;
        }

        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(address.substring(colon + 1).trim());
                host = address.substring(0, colon).trim();
            } catch (NumberFormatException ignored) {
            }
        }

        return new BotConfig(host, port, loginText.get().trim(), loginTimeout.get(), lobbyDelay.get(),
            compassSlot.get(), menuSlot.get(), menuTimeout.get(), menuRetries.get(),
            autoRespawn.get(), respawnDelay.get(), clientEmulation.get());
    }
}
