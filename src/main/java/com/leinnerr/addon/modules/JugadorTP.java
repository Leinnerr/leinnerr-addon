package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.utils.LUtils;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.PlayerMoveC2SPacketAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JugadorTP extends Module {
    public enum TargetMode { Nearest, Specific }
    public enum PathMode { Original, Smart }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGhost = settings.createGroup("Ghost Tick");

    private final Setting<TargetMode> targetMode = sgGeneral.add(new EnumSetting.Builder<TargetMode>()
        .name("target-mode")
        .description("como se elige al jugador objetivo")
        .defaultValue(TargetMode.Nearest)
        .build());

    private final Setting<String> specificPlayer = sgGeneral.add(new StringSetting.Builder()
        .name("specific-player")
        .description("Nombre del jugador especifico")
        .defaultValue("")
        .visible(() -> targetMode.get() == TargetMode.Specific)
        .build());

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Ignora a tus amigos")
        .defaultValue(true)
        .visible(() -> targetMode.get() == TargetMode.Nearest)
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Distancia maxima a un objetivo valido")
        .defaultValue(50.0)
        .min(1)
        .sliderMax(200)
        .build());

    private final Setting<Boolean> stopAtTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("stop-at-target")
        .description("Deja de moverte al llegar al objetivo")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> noFallDamage = sgGeneral.add(new BoolSetting.Builder()
        .name("no-fall-damage")
        .description("Falsea onGround al caer rapido para q no haga daño")
        .defaultValue(true)
        .build());

    private final Setting<Double> ghostStepSize = sgGhost.add(new DoubleSetting.Builder()
        .name("step-size")
        .description("Distancia entre cada paquete de movimiento")
        .defaultValue(0.5)
        .min(0.05)
        .sliderMax(10)
        .build());

    private final Setting<Double> wallScanStep = sgGhost.add(new DoubleSetting.Builder()
        .name("wall-scan-step")
        .description("Resolucion usada para encontrar el otro lado de una pared")
        .defaultValue(0.05)
        .min(0.01)
        .sliderMax(0.2)
        .build());

    private final Setting<Double> vclipStep = sgGhost.add(new DoubleSetting.Builder()
        .name("vclip-step")
        .description("Altura de cada paso vertical al pasar sobre obsttaculos")
        .defaultValue(0.5)
        .min(0.05)
        .sliderMax(4)
        .build());

    private final Setting<Integer> maxWallClimb = sgGhost.add(new IntSetting.Builder()
        .name("max-wall-climb")
        .description("Numero max de pasos verticales a probar sobre un obstaculo")
        .defaultValue(10)
        .min(1)
        .sliderMax(50)
        .build());

    private final Setting<Integer> ghostDelay = sgGhost.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build());

    private final Setting<Boolean> ghostFaceTarget = sgGhost.add(new BoolSetting.Builder()
        .name("face-target")
        .description("Mira al objetivo")
        .defaultValue(true)
        .build());

    private final Setting<PathMode> pathMode = sgGhost.add(new EnumSetting.Builder<PathMode>()
        .name("path-mode")
        .description("Original = recorrido paso a paso Smart = busca saltos entre espacios libres para llegar a jugadores encerrados en estructuras")
        .defaultValue(PathMode.Original)
        .build());

    private final Setting<Double> maxHop = sgGhost.add(new DoubleSetting.Builder()
        .name("max-hop")
        .description("Distancia maxima de cada salto en modo Smart")
        .defaultValue(8.0)
        .min(2)
        .sliderMax(9.5)
        .visible(() -> pathMode.get() == PathMode.Smart)
        .build());

    private final Setting<Integer> searchLimit = sgGhost.add(new IntSetting.Builder()
        .name("search-limit")
        .description("maximo de posiciones a probar al buscar el camino en modo Smart")
        .defaultValue(300)
        .min(50)
        .sliderMax(2000)
        .visible(() -> pathMode.get() == PathMode.Smart)
        .build());

    private int[][] hopOffsets;
    private int hopOffsetsFor = -1;

    private PlayerEntity target;
    private int ghostTimer;

    public JugadorTP() {
        super(LeinnerrAddon.CATEGORY, "jugadorTP", "Te tepea a un jugador");
    }

    @Override
    public void onActivate() {
        target = null;
        ghostTimer = 0;
    }

    @Override
    public void onDeactivate() {
        if (mc.player != null) mc.player.setVelocity(0, 0, 0);
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!noFallDamage.get() || mc.player == null) return;
        if (mc.player.getAbilities().creativeMode || mc.player.isGliding()) return;

        if (event.packet instanceof PlayerMoveC2SPacket packet && mc.player.getVelocity().y < -0.5) {
            ((PlayerMoveC2SPacketAccessor) packet).setOnGround(true);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (target == null || !target.isAlive()) {
            target = findTarget();
            return;
        }

        if (stopAtTarget.get() && distanceTo(target) <= 0.5) {
            mc.player.setVelocity(0, 0, 0);
        } else {
            moveGhostTick();
        }
    }

    private void moveGhostTick() {
        if (ghostTimer > 0) {
            ghostTimer--;
            return;
        }

        double fromX = mc.player.getX(), fromY = mc.player.getY(), fromZ = mc.player.getZ();
        double toX = target.getX(), toY = target.getY(), toZ = target.getZ();

        if (distanceTo(target) < 0.1) return;

        if (ghostFaceTarget.get()) faceTarget(toX, toY, toZ);

        List<Vec3d> route = null;
        if (pathMode.get() == PathMode.Smart) {
            route = planSmart(new Vec3d(fromX, fromY, fromZ), new Vec3d(toX, toY, toZ));
        }

        if (route != null) {
            for (Vec3d p : route) sendPos(p.x, p.y, p.z);
            sendPos(toX, toY, toZ);
            sendPos(toX, toY, toZ);
        } else {
            double[] pos = sendPhaseXZ(fromX, fromY, fromZ, toX, toZ);
            sendPhaseY(pos[0], pos[1], pos[2], toX, toY, toZ);
        }

        mc.player.setPosition(toX, toY, toZ);
        mc.player.setVelocity(0, 0, 0);
        ghostTimer = ghostDelay.get();
    }

    private double[] sendPhaseXZ(double fromX, double fromY, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        if (distXZ < 0.01) return new double[]{fromX, fromY, fromZ};

        double dirX = dx / distXZ;
        double dirZ = dz / distXZ;
        double step = ghostStepSize.get();
        double scanStep = wallScanStep.get();
        double vStep = vclipStep.get();
        int maxClimb = maxWallClimb.get();

        double curX = fromX, curY = fromY, curZ = fromZ;
        double traveled = 0;

        while (traveled < distXZ - 0.001) {
            double remaining = distXZ - traveled;
            double thisStep = Math.min(step, remaining);
            double nx = curX + dirX * thisStep;
            double nz = curZ + dirZ * thisStep;

            if (!isSolid(nx, curY, nz)) {
                sendPos(nx, curY, nz);
                curX = nx;
                curZ = nz;
                traveled += thisStep;
                continue;
            }

            boolean passed = false;
            for (int climb = 1; climb <= maxClimb; climb++) {
                double tryY = curY + climb * vStep;
                if (!isSolid(nx, tryY, nz)) {
                    for (int c = 1; c <= climb; c++) {
                        sendPos(curX, curY + c * vStep, curZ);
                    }
                    curY = tryY;
                    sendPos(nx, tryY, nz);
                    curX = nx;
                    curZ = nz;
                    traveled += thisStep;
                    passed = true;
                    break;
                }
            }
            if (passed) continue;

            double scanX = curX + dirX * scanStep;
            double scanZ = curZ + dirZ * scanStep;
            double scanned = scanStep;
            while (isSolid(scanX, curY, scanZ) && traveled + scanned < distXZ) {
                scanX += dirX * scanStep;
                scanZ += dirZ * scanStep;
                scanned += scanStep;
            }

            if (isSolid(scanX, curY, scanZ)) break;

            sendPos(scanX, curY, scanZ);
            curX = scanX;
            curZ = scanZ;
            traveled += scanned;
        }

        return new double[]{curX, curY, curZ};
    }

    private void sendPhaseY(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dy = toY - fromY;
        double distY = Math.abs(dy);

        if (distY < 0.01) {
            sendPos(toX, toY, toZ);
            return;
        }

        double dirY = Math.signum(dy);
        double step = ghostStepSize.get();
        double scanStep = wallScanStep.get();
        double curY = fromY;
        double traveled = 0;

        while (traveled < distY - 0.001) {
            double remaining = distY - traveled;
            double thisStep = Math.min(step, remaining);
            double ny = curY + dirY * thisStep;

            if (!isSolid(toX, ny, toZ)) {
                sendPos(toX, ny, toZ);
                curY = ny;
                traveled += thisStep;
                continue;
            }

            double scanY = ny;
            double scanned = thisStep;
            while (isSolid(toX, scanY, toZ) && traveled + scanned < distY) {
                scanY += dirY * scanStep;
                scanned += scanStep;
            }

            if (isSolid(toX, scanY, toZ)) break;

            sendPos(toX, scanY, toZ);
            curY = scanY;
            traveled += scanned;
        }

        sendPos(toX, toY, toZ);
        sendPos(toX, toY, toZ);
    }

    private void sendPos(double x, double y, double z) {
        LUtils.send(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, false));
    }

    private void buildOffsets(int hop) {
        if (hopOffsetsFor == hop) return;

        List<int[]> list = new ArrayList<>();
        for (int x = -hop; x <= hop; x++) {
            for (int y = -hop; y <= hop; y++) {
                for (int z = -hop; z <= hop; z++) {
                    int sq = x * x + y * y + z * z;
                    if (sq >= 1 && sq <= hop * hop) list.add(new int[]{x, y, z});
                }
            }
        }
        hopOffsets = list.toArray(new int[0][]);
        hopOffsetsFor = hop;
    }

    private List<Vec3d> planSmart(Vec3d from, Vec3d to) {
        double hop = maxHop.get();
        List<Vec3d> path = new ArrayList<>();
        if (from.squaredDistanceTo(to) <= hop * hop) return path;

        buildOffsets((int) Math.floor(hop));

        double leash = from.distanceTo(to) + hop * 3.0;
        int[] budget = {searchLimit.get(), searchLimit.get() * 40};

        boolean found = searchHop(from, to, 0, 0, 0, from, hop * hop, leash * leash, path,
            new HashSet<>(), new HashMap<>(), budget);

        return found ? path : null;
    }

    private boolean searchHop(Vec3d cur, Vec3d goal, int ix, int iy, int iz, Vec3d origin, double hopSq, double leashSq,
                              List<Vec3d> path, Set<Long> visited, Map<Long, Boolean> freeCache, int[] budget) {
        if (cur.squaredDistanceTo(goal) <= hopSq) return true;
        if (path.size() >= 64) return false;

        int n = hopOffsets.length;
        long[] order = new long[n];
        for (int i = 0; i < n; i++) {
            int[] o = hopOffsets[i];
            double dx = cur.x + o[0] - goal.x;
            double dy = cur.y + o[1] - goal.y;
            double dz = cur.z + o[2] - goal.z;
            long dist = (long) ((dx * dx + dy * dy + dz * dz) * 1000.0);
            order[i] = (dist << 12) | i;
        }
        Arrays.sort(order);

        int expanded = 0;
        for (long entry : order) {
            if (budget[1] <= 0) return false;

            int[] o = hopOffsets[(int) (entry & 0xFFF)];
            int nx = ix + o[0], ny = iy + o[1], nz = iz + o[2];
            long id = ((long) (nx + 4096) << 40) | ((long) (ny + 4096) << 20) | (nz + 4096);

            if (!visited.add(id)) continue;
            budget[1]--;

            Vec3d next = origin.add(nx, ny, nz);
            if (next.squaredDistanceTo(goal) > leashSq) continue;

            Boolean free = freeCache.get(id);
            if (free == null) {
                free = !LUtils.invalid(next, mc.player);
                freeCache.put(id, free);
            }
            if (!free) continue;

            if (--budget[0] < 0) return false;

            path.add(next);
            if (searchHop(next, goal, nx, ny, nz, origin, hopSq, leashSq, path, visited, freeCache, budget)) return true;
            path.remove(path.size() - 1);

            if (++expanded >= 6) break;
        }
        return false;
    }

    private void faceTarget(double toX, double toY, double toZ) {
        double dx = toX - mc.player.getX();
        double dz = toZ - mc.player.getZ();
        double dy = toY - mc.player.getY();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private boolean isSolid(double x, double y, double z) {
        if (mc.world == null || mc.player == null) return false;

        Box original = mc.player.getBoundingBox();
        double w = (original.maxX - original.minX) / 2.0;
        double h = original.maxY - original.minY;
        Box box = new Box(x - w, y, z - w, x + w, y + h, z + w);
        return !mc.world.isSpaceEmpty(box);
    }

    private double distanceTo(PlayerEntity p) {
        double dx = p.getX() - mc.player.getX();
        double dy = p.getY() - mc.player.getY();
        double dz = p.getZ() - mc.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private PlayerEntity findTarget() {
        if (mc.world == null) return null;

        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;
        String wanted = specificPlayer.get();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive()) continue;

            double dist = distanceTo(p);
            if (dist > range.get()) continue;

            if (targetMode.get() == TargetMode.Specific) {
                if (wanted.isEmpty() || !p.getName().getString().equalsIgnoreCase(wanted)) continue;
                return p;
            }

            if (ignoreFriends.get() && Friends.get().isFriend(p)) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best = p;
            }
        }

        return best;
    }

    @Override
    public String getInfoString() {
        if (target == null) return "Ghost";
        return String.format("Ghost %s %.1fm", target.getName().getString(), distanceTo(target));
    }
}

// acepto q no es tan bueno pero intentare mejorarlo :c