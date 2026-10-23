package com.leinnerr.addon.compat;

import com.leinnerr.addon.utils.LUtils;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.util.math.Vec3d;

public final class Compat {
    public static final String TARGET = "1.21.5";

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private Compat() {}

    public static void registerVersionModules(Modules modules) {}

    private static boolean hc() {
        return mc.player != null && mc.player.horizontalCollision;
    }

    public static PlayerMoveC2SPacket movePos(double x, double y, double z, boolean onGround) {
        return new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, onGround, hc());
    }

    public static PlayerMoveC2SPacket moveFull(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision) {
        return new PlayerMoveC2SPacket.Full(x, y, z, yaw, pitch, onGround, horizontalCollision);
    }

    public static PlayerMoveC2SPacket moveLook(float yaw, float pitch, boolean onGround, boolean horizontalCollision) {
        return new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, horizontalCollision);
    }

    public static PlayerMoveC2SPacket moveOnGround(boolean onGround, boolean horizontalCollision) {
        return new PlayerMoveC2SPacket.OnGroundOnly(onGround, horizontalCollision);
    }

    public static void sendVehicleMove(Entity vehicle, Vec3d pos) {
        Vec3d old = LUtils.pos(vehicle);
        vehicle.setPosition(pos.x, pos.y, pos.z);
        LUtils.send(VehicleMoveC2SPacket.fromVehicle(vehicle));
        vehicle.setPosition(old.x, old.y, old.z);
    }

    public static void sendVehicleStay(Entity vehicle) {
        LUtils.send(VehicleMoveC2SPacket.fromVehicle(vehicle));
    }

    public static boolean isCoolingDown(ItemStack stack) {
        return mc.player != null && mc.player.getItemCooldownManager().isCoolingDown(stack);
    }

    public static String profileName(PlayerListEntry entry) {
        return entry.getProfile().getName();
    }
}
