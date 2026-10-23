package com.leinnerr.addon.utils;

import com.leinnerr.addon.compat.Compat;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class LUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private LUtils() {}

    public static Vec3d pos(Entity e) {
        return new Vec3d(e.getX(), e.getY(), e.getZ());
    }

    public static void send(Packet<?> packet) {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler != null) handler.sendPacket(packet);
    }

    public static boolean chunkLoaded(BlockPos pos) {
        return mc.world != null && mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean invalid(Vec3d pos, Entity entity) {
        ClientWorld world = mc.world;
        if (world == null || entity == null) return true;

        BlockPos bp = BlockPos.ofFloored(pos);
        if (!chunkLoaded(bp)) return true;
        if (pos.y < world.getBottomY() || pos.y > world.getBottomY() + world.getHeight()) return true;

        Box box = entity.getBoundingBox().offset(pos.x - entity.getX(), pos.y - entity.getY(), pos.z - entity.getZ());

        for (BlockPos b : BlockPos.iterate(
            BlockPos.ofFloored(box.minX, box.minY, box.minZ),
            BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = world.getBlockState(b);
            if (state.isOf(Blocks.LAVA)) return true;
            if (!state.getCollisionShape(world, b).isEmpty()) return true;
        }

        for (Entity e : world.getOtherEntities(entity, box)) {
            if (e.collidesWith(entity)) return true;
        }

        return false;
    }

    public static boolean hasClearPath(Vec3d start, Vec3d end, Entity entity, int minSteps) {
        if (invalid(start, entity) || invalid(end, entity)) return false;

        int steps = Math.max(minSteps, (int) (start.distanceTo(end) * 2.5));
        for (int i = 1; i < steps; i++) {
            if (invalid(start.lerp(end, (double) i / steps), entity)) return false;
        }
        return true;
    }

    public static PlayerMoveC2SPacket copyWithOnGround(PlayerMoveC2SPacket p, boolean onGround) {
        boolean hc = mc.player != null && mc.player.horizontalCollision;

        if (p.changesPosition() && p.changesLook()) {
            return Compat.moveFull(p.getX(0), p.getY(0), p.getZ(0), p.getYaw(0), p.getPitch(0), onGround, hc);
        } else if (p.changesPosition()) {
            return Compat.movePos(p.getX(0), p.getY(0), p.getZ(0), onGround);
        } else if (p.changesLook()) {
            return Compat.moveLook(p.getYaw(0), p.getPitch(0), onGround, hc);
        }
        return Compat.moveOnGround(onGround, hc);
    }
}
