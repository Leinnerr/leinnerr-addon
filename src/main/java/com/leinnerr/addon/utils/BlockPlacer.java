package com.leinnerr.addon.utils;

import com.leinnerr.addon.compat.Compat;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class BlockPlacer {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public record Info(BlockPos neighbor, Direction side, Vec3d hitVec) {}

    private BlockPlacer() {}

    public static Info find(BlockPos pos, boolean strictDirection) {
        Vec3d eye = mc.player.getEyePos();
        Info best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbor);
            if (state.isAir() || state.isReplaceable()) continue;
            if (!mc.player.isSneaking() && isClickable(state.getBlock())) continue;

            Direction side = dir.getOpposite();
            Vec3d center = Vec3d.ofCenter(neighbor);
            Vec3d hit = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);

            if (strictDirection) {
                Vec3d toEye = eye.subtract(center);
                double along = toEye.x * side.getOffsetX() + toEye.y * side.getOffsetY() + toEye.z * side.getOffsetZ();
                if (along <= 0.5) continue;
            }

            double dist = eye.squaredDistanceTo(hit);
            if (dist < bestDist) {
                bestDist = dist;
                best = new Info(neighbor, side, hit);
            }
        }
        return best;
    }

    public static boolean place(BlockPos pos, boolean strictDirection, boolean rotate) {
        Info info = find(pos, strictDirection);
        if (info == null) return false;

        if (rotate) rotateTo(info.hitVec());

        BlockHitResult hit = new BlockHitResult(info.hitVec(), info.side(), info.neighbor(), false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public static boolean canPlace(BlockPos pos, Block block) {
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        return mc.world.canPlace(block.getDefaultState(), pos, ShapeContext.absent());
    }

    public static void rotateTo(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90f, 90f);

        LUtils.send(Compat.moveLook(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    public static void resync() {
        LUtils.send(Compat.moveLook(mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    public static boolean isClickable(Block block) {
        return block instanceof BlockWithEntity
            || block instanceof CraftingTableBlock
            || block instanceof AnvilBlock
            || block instanceof DoorBlock
            || block instanceof TrapdoorBlock
            || block instanceof FenceGateBlock
            || block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof BedBlock
            || block instanceof NoteBlock
            || block instanceof RespawnAnchorBlock
            || block instanceof RepeaterBlock
            || block instanceof ComparatorBlock
            || block instanceof LoomBlock
            || block instanceof CartographyTableBlock
            || block instanceof StonecutterBlock
            || block instanceof GrindstoneBlock
            || block instanceof SmithingTableBlock;
    }
}
