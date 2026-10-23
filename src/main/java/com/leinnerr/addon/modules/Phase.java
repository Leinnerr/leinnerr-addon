package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.compat.Compat;
import com.leinnerr.addon.utils.BlockPlacer;
import com.leinnerr.addon.utils.LUtils;
import meteordevelopment.meteorclient.events.world.CollisionShapeEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

public class Phase extends Module {
    public enum PhaseMode { Normal, Sand, Climb, Pearl, Clip }
    public enum PearlDir { Auto, Forward, Backward, Left, Right }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPearl = settings.createGroup("Perla");

    private final Setting<PhaseMode> mode = sgGeneral.add(new EnumSetting.Builder<PhaseMode>()
        .name("mode").description("El modo de phase para entrar en los bloques")
        .defaultValue(PhaseMode.Normal).build());

    private final Setting<Double> blocksClip = sgGeneral.add(new DoubleSetting.Builder()
        .name("blocks").description("La distancia en bloques")
        .defaultValue(0.003).min(0.001).sliderMax(10)
        .visible(() -> mode.get() != PhaseMode.Pearl && mode.get() != PhaseMode.Clip).build());

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance").description("La distancia a atravesar")
        .defaultValue(0.2).min(0).sliderMax(10)
        .visible(() -> mode.get() != PhaseMode.Pearl && mode.get() != PhaseMode.Clip).build());

    private final Setting<Boolean> autoClip = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-clip").description("Te mete automaticamente en el bloque al activar el modulo")
        .defaultValue(true)
        .visible(() -> mode.get() != PhaseMode.Pearl && mode.get() != PhaseMode.Clip).build());

    private final Setting<PearlDir> pearlDir = sgPearl.add(new EnumSetting.Builder<PearlDir>()
        .name("direction").description("Hacia que pared lanzar la perla")
        .defaultValue(PearlDir.Auto).visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<Boolean> requireWall = sgPearl.add(new BoolSetting.Builder()
        .name("require-wall").description("Solo lanza si hay un bloque solido en esa direccion, En Auto prueba las otras paredes si la primera no sirve")
        .defaultValue(true).visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<Boolean> autoPitch = sgPearl.add(new BoolSetting.Builder()
        .name("auto-pitch").description("Calcula el pitch para que caiga pegada a la pared")
        .defaultValue(true).visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<Integer> minPitch = sgPearl.add(new IntSetting.Builder()
        .name("min-pitch").description("Pitch minimo que puede probar auto-pitch")
        .defaultValue(60).min(30).max(85).sliderRange(30, 85)
        .visible(() -> mode.get() == PhaseMode.Pearl && autoPitch.get()).build());

    private final Setting<Integer> pitch = sgPearl.add(new IntSetting.Builder()
        .name("pitch").description("El pitch con el que lanzar las perlas")
        .defaultValue(85).min(70).max(90).sliderRange(70, 90)
        .visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<Boolean> swapAlt = sgPearl.add(new BoolSetting.Builder()
        .name("swap-alternative").description("Usa el cambio por inventario")
        .defaultValue(true).visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<Boolean> attack = sgPearl.add(new BoolSetting.Builder()
        .name("attack").description("Ataca marcos de objetos y rompe andamios que estorban a la perla")
        .defaultValue(false).visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<Boolean> swing = sgPearl.add(new BoolSetting.Builder()
        .name("swing").description("Mueve la mano al lanzar perlas")
        .defaultValue(true).visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<Boolean> selfFill = sgPearl.add(new BoolSetting.Builder()
        .name("self-fill").description("Rellena automaticamente el bloque en el que vas a entrar")
        .defaultValue(false).visible(() -> mode.get() == PhaseMode.Pearl).build());

    private final Setting<List<Block>> fillBlocks = sgPearl.add(new BlockListSetting.Builder()
        .name("fill-blocks").description("Bloques usados por self-fill")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN)
        .visible(() -> mode.get() == PhaseMode.Pearl && selfFill.get()).build());

    private final Setting<Boolean> strictDirection = sgPearl.add(new BoolSetting.Builder()
        .name("strict-direction").description("Self-fill solo hace click en las caras de bloque que miran hacia ti")
        .defaultValue(false).visible(() -> mode.get() == PhaseMode.Pearl && selfFill.get()).build());

    public Phase() {
        super(LeinnerrAddon.CATEGORY, "phase", "Phasear y entrar dentro de bloques");
    }

    @Override
    public String getInfoString() {
        return mode.get().toString();
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) return;

        if (mode.get() == PhaseMode.Pearl) {
            throwPearl();
            return;
        }

        if (mode.get() != PhaseMode.Clip && autoClip.get()) {
            double rad = Math.toRadians(mc.player.getYaw() + 90.0F);
            double b = blocksClip.get();
            mc.player.setPosition(
                mc.player.getX() + b * Math.cos(rad),
                mc.player.getY(),
                mc.player.getZ() + b * Math.sin(rad));
        }
    }

    private void throwPearl() {

        int slot = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.ENDER_PEARL)) {
                slot = i;
                break;
            }
        }
        boolean inOffhand = slot == -1 && mc.player.getOffHandStack().isOf(Items.ENDER_PEARL);
        ItemStack pearlStack = slot != -1 ? mc.player.getInventory().getStack(slot) : mc.player.getOffHandStack();

        if ((slot == -1 && !inOffhand) || Compat.isCoolingDown(pearlStack)) {
            toggle();
            return;
        }

        Direction dir = chooseDirection();
        if (dir == null) {
            warning("No hay pared solida en esa direccion; no se lanzo la perla");
            toggle();
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        float yaw = yawOf(dir);

        if (attack.get()) attackObstacles(eye, yaw);
        if (selfFill.get()) fillBlock(yaw);

        float throwPitch = autoPitch.get() ? findPitch(dir) : pitch.get();

        boolean hotbarSwap = false;
        boolean invSwap = false;
        int containerSlot = -1;
        Hand hand;

        if (inOffhand) {
            hand = Hand.OFF_HAND;
        } else if (slot < 9 && !swapAlt.get()) {
            hand = Hand.MAIN_HAND;
            hotbarSwap = true;
        } else {
            hand = Hand.OFF_HAND;
            invSwap = true;
            containerSlot = slot < 9 ? slot + 36 : slot;
        }

        if (hotbarSwap) InvUtils.swap(slot, true);
        if (invSwap) mc.interactionManager.clickSlot(0, containerSlot, 40, SlotActionType.SWAP, mc.player);

        float sendYaw = MathHelper.wrapDegrees(yaw);
        float realYaw = mc.player.getYaw();
        float realPitch = mc.player.getPitch();
        mc.player.setYaw(sendYaw);
        mc.player.setPitch(throwPitch);
        mc.interactionManager.interactItem(mc.player, hand);
        mc.player.setYaw(realYaw);
        mc.player.setPitch(realPitch);

        if (swing.get()) mc.player.swingHand(hand);
        else LUtils.send(new HandSwingC2SPacket(hand));

        if (invSwap) mc.interactionManager.clickSlot(0, containerSlot, 40, SlotActionType.SWAP, mc.player);
        if (hotbarSwap) InvUtils.swapBack();

        BlockPlacer.resync();
        toggle();
    }


    private static float yawOf(Direction d) {
        return switch (d) {
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> 270.0F;
            default -> 0.0F;
        };
    }

    private boolean solidAt(BlockPos p) {
        return !mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty();
    }

    private boolean wallOk(BlockPos p) {
        if (!requireWall.get()) return true;
        if (solidAt(p)) return true;
        return selfFill.get() && mc.world.getBlockState(p).isReplaceable() && !mc.world.getBlockState(p.down()).isReplaceable();
    }

    private Direction chooseDirection() {
        Direction facing = mc.player.getHorizontalFacing();
        List<Direction> order = new ArrayList<>();

        switch (pearlDir.get()) {
            case Forward -> order.add(facing);
            case Backward -> order.add(facing.getOpposite());
            case Left -> order.add(facing.rotateYCounterclockwise());
            case Right -> order.add(facing.rotateYClockwise());
            default -> {
                double dx = mc.player.getX() - (Math.floor(mc.player.getX()) + 0.5);
                double dz = mc.player.getZ() - (Math.floor(mc.player.getZ()) + 0.5);
                double[] score = {dx, -dx, dz, -dz};
                Direction[] dirs = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
                for (int i = 0; i < 4; i++) {
                    for (int j = i + 1; j < 4; j++) {
                        if (score[j] > score[i]) {
                            double t = score[i]; score[i] = score[j]; score[j] = t;
                            Direction d = dirs[i]; dirs[i] = dirs[j]; dirs[j] = d;
                        }
                    }
                }
                for (Direction d : dirs) order.add(d);
            }
        }

        BlockPos feet = mc.player.getBlockPos();
        for (Direction d : order) {
            if (wallOk(feet.offset(d))) return d;
        }
        return null;
    }

    private float findPitch(Direction dir) {
        float yaw = yawOf(dir);
        Vec3d start = new Vec3d(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()) - 0.1, mc.player.getZ());
        int fx = MathHelper.floor(mc.player.getX());
        int fz = MathHelper.floor(mc.player.getZ());
        double plane = switch (dir) {
            case EAST -> fx + 1.0;
            case WEST -> fx;
            case SOUTH -> fz + 1.0;
            default -> fz;
        };

        for (float p = 90.0F; p >= minPitch.get(); p -= 0.5F) {
            Vec3d vel = Vec3d.fromPolar(p, yaw).multiply(1.5);
            Vec3d pos = start;

            for (int tick = 0; tick < 40; tick++) {
                Vec3d next = pos.add(vel);
                BlockHitResult hit = mc.world.raycast(new RaycastContext(pos, next,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));

                if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                    Vec3d at = hit.getPos();
                    double signed = dir.getOffsetX() * (plane - at.x) + dir.getOffsetZ() * (plane - at.z);
                    if (signed >= -0.05 && signed <= 0.28) return p;
                    break;
                }
                pos = next;
                vel = vel.multiply(0.99).add(0.0, -0.03, 0.0);
            }
        }
        return pitch.get();
    }

    private void attackObstacles(Vec3d eye, float yaw) {
        Vec3d end = eye.add(Vec3d.fromPolar(60.0F, yaw).multiply(3.0));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end,
            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

        for (Entity entity : mc.world.getOtherEntities(null, new Box(hit.getBlockPos()).expand(0.2))) {
            if (!(entity instanceof ItemFrameEntity frame)) continue;

            if (!frame.getHeldItemStack().isEmpty()) {
                LUtils.send(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));
            }
            LUtils.send(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));
            LUtils.send(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }

        BlockPos feet = mc.player.getBlockPos();
        if (mc.world.getBlockState(feet).getBlock() instanceof ScaffoldingBlock) {
            LUtils.send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, feet, Direction.UP));
            LUtils.send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, feet, Direction.UP));
        }
    }

    private void fillBlock(float yaw) {
        float y = yaw % 360.0F;
        if (y < 0.0F) y += 360.0F;

        BlockPos pos = mc.player.getBlockPos();
        if (y >= 22.5F && y < 67.5F) pos = pos.south().west();
        else if (y >= 67.5F && y < 112.5F) pos = pos.west();
        else if (y >= 112.5F && y < 157.5F) pos = pos.north().west();
        else if (y >= 157.5F && y < 202.5F) pos = pos.north();
        else if (y >= 202.5F && y < 247.5F) pos = pos.north().east();
        else if (y >= 247.5F && y < 292.5F) pos = pos.east();
        else if (y >= 292.5F && y < 337.5F) pos = pos.south().east();
        else pos = pos.south();

        if (!mc.world.getBlockState(pos).isReplaceable()) return;
        if (mc.world.getBlockState(pos.down()).isReplaceable()) return;

        FindItemResult item = InvUtils.findInHotbar(stack ->
            stack.getItem() instanceof BlockItem bi && fillBlocks.get().contains(bi.getBlock()));
        if (!item.found()) return;

        InvUtils.swap(item.slot(), true);
        boolean placed = BlockPlacer.place(pos, strictDirection.get(), true);
        InvUtils.swapBack();
        if (placed) BlockPlacer.resync();
    }

    @EventHandler
    private void onCollisionShape(CollisionShapeEvent event) {
        if (mc.player == null) return;

        switch (mode.get()) {
            case Normal -> {

                if (mc.player.isSneaking() && !event.shape.isEmpty()
                    && event.pos.getY() + event.shape.getMax(Direction.Axis.Y) > mc.player.getBoundingBox().minY) {
                    event.cancel();
                }
            }
            case Sand -> {
                event.cancel();
                mc.player.noClip = true;
            }
            case Climb -> {
                if (mc.player.horizontalCollision) event.cancel();

                boolean jump = mc.options.jumpKey.isPressed();
                boolean sneak = mc.options.sneakKey.isPressed();
                if (sneak || (jump && event.pos.getY() > mc.player.getY())) event.cancel();
            }
            default -> {}
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        switch (mode.get()) {
            case Normal -> {
                if (mc.player.isSneaking() && isPhasing()) {
                    double rad = Math.toRadians(mc.player.getYaw() + 90.0F);
                    double d = distance.get();
                    mc.player.setBoundingBox(mc.player.getBoundingBox().offset(d * Math.cos(rad), 0.0, d * Math.sin(rad)));
                }
            }
            case Sand -> {
                double y = 0.0;
                if (mc.isWindowFocused()) {
                    if (mc.options.jumpKey.isPressed()) y += 0.3;
                    if (mc.options.sneakKey.isPressed()) y -= 0.3;
                }
                Vec3d v = mc.player.getVelocity();
                mc.player.setVelocity(v.x, y, v.z);
                mc.player.noClip = true;
            }
            case Clip -> {
                if (mc.player.isOnGround() && !mc.player.hasVehicle()) {
                    Vec3d center = mc.player.getBlockPos().toCenterPos();
                    boolean flagX = center.x - mc.player.getX() > 0.0;
                    boolean flagZ = center.z - mc.player.getZ() > 0.0;
                    double x = center.x + 0.20000000009497754 * (flagX ? -1 : 1);
                    double z = center.z + 0.2000000000949811 * (flagZ ? -1 : 1);
                    mc.player.setPosition(x, mc.player.getY(), z);
                    toggle();
                }
            }
            default -> {}
        }
    }

    public boolean isPhasing() {
        Box bb = mc.player.getBoundingBox();

        for (int x = MathHelper.floor(bb.minX); x < MathHelper.floor(bb.maxX) + 1; x++) {
            for (int y = MathHelper.floor(bb.minY); y < MathHelper.floor(bb.maxY) + 1; y++) {
                for (int z = MathHelper.floor(bb.minZ); z < MathHelper.floor(bb.maxZ) + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.getCollisionShape(mc.world, pos).isEmpty()) continue;
                    if (bb.intersects(new Box(x, y, z, x + 1.0, y + 1.0, z + 1.0))) return true;
                }
            }
        }
        return false;
    }
}