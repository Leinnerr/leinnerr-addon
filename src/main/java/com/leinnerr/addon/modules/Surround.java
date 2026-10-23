package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.compat.Compat;
import com.leinnerr.addon.utils.LUtils;
import it.unimi.dsi.fastutil.ints.IntList;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Surround extends Module {
    public enum Timing { Vanilla, Sequential }
    public enum ReplaceMode { Normal, Strict, Fast }

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Colocacion");
    private final SettingGroup sgRender = settings.createGroup("Renderizado");

    private final Setting<Timing> timing = sgGeneral.add(new EnumSetting.Builder<Timing>()
        .name("timing").description("Momento para reponer bloques rotos")
        .defaultValue(Timing.Vanilla).build());

    private final Setting<ReplaceMode> replaceMode = sgGeneral.add(new EnumSetting.Builder<ReplaceMode>()
        .name("replace").description("Que paquete activa la recolocacion")
        .defaultValue(ReplaceMode.Normal).visible(() -> timing.get() == Timing.Sequential).build());

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks").description("Bloques para el surround")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN).build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable").description("Se desactiva si saltas")
        .defaultValue(true).build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("No se coloca mientras comes")
        .defaultValue(true).build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range").description("Distancia maxima al bloque")
        .defaultValue(4.0).min(0).sliderMax(6).build());

    private final Setting<Integer> blocksPerTick = sgPlace.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("Cantidad de bloques por tick")
        .defaultValue(2).min(1).sliderMax(10).build());

    private final Setting<Double> placeDelay = sgPlace.add(new DoubleSetting.Builder()
        .name("place-delay").description("Delay entre Ticks")
        .defaultValue(1.0).min(0).sliderMax(5).build());

    private final Setting<Boolean> attackCrystals = sgPlace.add(new BoolSetting.Builder()
        .name("attack-crystals").description("Ataca los cristales del end")
        .defaultValue(true).build());

    private final Setting<Boolean> extend = sgPlace.add(new BoolSetting.Builder()
        .name("extend").description("Amplia el surround si no estas en el centro de un bloque")
        .defaultValue(true).build());

    private final Setting<Boolean> coverHead = sgPlace.add(new BoolSetting.Builder()
        .name("cover-head").description("Coloca un bloque sobre tu cabeza")
        .defaultValue(false).build());

    private final Setting<Boolean> support = sgPlace.add(new BoolSetting.Builder()
        .name("support").description("Coloca un bloque de suelo")
        .defaultValue(false).build());

    private final Setting<Boolean> rotate = sgPlace.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio hacia la cara del bloque antes de colocar")
        .defaultValue(false).build());

    private final Setting<Boolean> strictDirection = sgPlace.add(new BoolSetting.Builder()
        .name("strict-direction").description("Solo hace click en los bloques que miran hacia ti")
        .defaultValue(false).build());

    private final Setting<Boolean> stopMotion = sgPlace.add(new BoolSetting.Builder()
        .name("stop-motion").description("Cancela tu movimiento mientras colocas")
        .defaultValue(false).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Dibujar el render")
        .defaultValue(false).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Como se dibujan")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Color")
        .defaultValue(new SettingColor(120, 60, 200, 40)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Color de las lineas")
        .defaultValue(new SettingColor(120, 60, 200, 150)).visible(render::get).build());

    private final Setting<Integer> fadeTime = sgRender.add(new IntSetting.Builder()
        .name("fade-time").description("Tienpo que se renderiza")
        .defaultValue(250).min(0).sliderMax(1000).visible(render::get).build());

    private record PlaceInfo(BlockPos neighbor, Direction side, Vec3d hitVec) {}

    private double prevY;
    private boolean rotatedThisCall;
    private List<BlockPos> surround = new ArrayList<>();
    private List<BlockPos> placements = new ArrayList<>();

    private volatile Set<BlockPos> surroundSnapshot = Set.of();
    private final Map<BlockPos, Long> placedAt = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> fadeList = new HashMap<>();

    public Surround() {
        super(LeinnerrAddon.CATEGORY, "surround-plus", "Surrond, rodea tus pies de obsidiana pa protegerte");
    }

    @Override
    public void onActivate() {
        if (mc.player != null) prevY = mc.player.getY();
    }

    @Override
    public void onDeactivate() {
        clearState();
        fadeList.clear();
    }

    private void clearState() {
        surround = new ArrayList<>();
        placements = new ArrayList<>();
        surroundSnapshot = Set.of();
        placedAt.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (autoDisable.get() && (mc.player.getY() - prevY > 0.5 || mc.player.fallDistance > 1.5)) {
            info("Saliste del hoyo, desactivando.");
            toggle();
            return;
        }

        if (pauseOnUse.get() && mc.player.isUsingItem()) return;
        if (stopMotion.get() && !mc.player.isOnGround()) return;

        FindItemResult item = findBlock();
        if (!item.found()) {
            surround = new ArrayList<>();
            placements = new ArrayList<>();
            surroundSnapshot = Set.of();
            return;
        }

        surround = getSurround();
        surroundSnapshot = new HashSet<>(surround);
        if (surround.isEmpty()) return;

        if (attackCrystals.get()) attackBlockingCrystals();

        placements = getPlacements(blockOf(item));
        if (placements.isEmpty()) return;

        if (support.get()) {
            int feetY = mc.player.getBlockY();
            for (BlockPos pos : new ArrayList<>(placements)) {
                if (pos.getY() > feetY + 1) continue;
                if (findPlaceInfo(pos) == null && !placements.contains(pos.down())) placements.add(pos.down());
            }
        }

        placements.sort(Comparator.comparingInt(BlockPos::getY));

        Vec3d prevMotion = mc.player.getVelocity();
        if (stopMotion.get()) mc.player.setVelocity(0, 0, 0);

        rotatedThisCall = false;
        InvUtils.swap(item.slot(), true);

        int placed = 0;
        for (BlockPos pos : placements) {
            if (placed >= blocksPerTick.get()) break;
            if (placeBlock(pos)) placed++;
        }

        InvUtils.swapBack();
        if (rotatedThisCall) resyncRotation();

        if (stopMotion.get()) mc.player.setVelocity(prevMotion);
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (timing.get() != Timing.Sequential) return;
        if (mc.player == null || mc.world == null) return;

        Packet<?> packet = event.packet;
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> p : bundle.getPackets()) handlePacket(p);
        } else {
            handlePacket(packet);
        }
    }

    private void handlePacket(Packet<?> packet) {
        Set<BlockPos> snap = surroundSnapshot;
        if (snap.isEmpty()) return;

        if (packet instanceof BlockUpdateS2CPacket p) {
            BlockPos pos = p.getPos();
            if (!snap.contains(pos)) return;

            if (p.getState().isReplaceable()) queueReplace(pos);
            else placedAt.remove(pos);
        } else if (packet instanceof ExplosionS2CPacket p) {
            if (replaceMode.get() != ReplaceMode.Fast) return;
            BlockPos pos = BlockPos.ofFloored(p.center());
            if (snap.contains(pos)) queueReplace(pos);
        } else if (packet instanceof EntitiesDestroyS2CPacket p) {
            if (replaceMode.get() != ReplaceMode.Normal) return;
            IntList ids = p.getEntityIds();

            mc.execute(() -> {
                if (mc.world == null) return;
                for (int id : ids) {
                    Entity e = mc.world.getEntityById(id);
                    if (e instanceof EndCrystalEntity && surroundSnapshot.contains(e.getBlockPos())) {
                        replaceNow(e.getBlockPos());
                    }
                }
            });
        } else if (packet instanceof EntitySpawnS2CPacket p) {
            if (replaceMode.get() != ReplaceMode.Strict || p.getEntityType() != EntityType.END_CRYSTAL) return;
            BlockPos pos = BlockPos.ofFloored(p.getX(), p.getY(), p.getZ());
            if (snap.contains(pos)) queueReplace(pos);
        }
    }

    private void queueReplace(BlockPos pos) {
        mc.execute(() -> replaceNow(pos));
    }

    private void replaceNow(BlockPos pos) {
        if (!isActive() || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (!surroundSnapshot.contains(pos)) return;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;

        FindItemResult item = findBlock();
        if (!item.found()) return;

        rotatedThisCall = false;
        InvUtils.swap(item.slot(), true);
        placeBlock(pos);
        InvUtils.swapBack();
        if (rotatedThisCall) resyncRotation();
    }

    private FindItemResult findBlock() {
        return InvUtils.findInHotbar(stack -> stack.getItem() instanceof BlockItem bi && blocks.get().contains(bi.getBlock()));
    }

    private Block blockOf(FindItemResult item) {
        ItemStack stack = mc.player.getInventory().getStack(item.slot());
        return stack.getItem() instanceof BlockItem bi ? bi.getBlock() : Blocks.OBSIDIAN;
    }

    private boolean placeBlock(BlockPos pos) {
        PlaceInfo info = findPlaceInfo(pos);
        if (info == null) return false;

        if (rotate.get()) rotateTo(info.hitVec());

        BlockHitResult hit = new BlockHitResult(info.hitVec(), info.side(), info.neighbor(), false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);

        long now = System.currentTimeMillis();
        placedAt.put(pos, now);
        fadeList.put(pos, now);
        return true;
    }

    private PlaceInfo findPlaceInfo(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        PlaceInfo best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbor);
            if (state.isAir() || state.isReplaceable()) continue;
            if (!mc.player.isSneaking() && isClickable(state.getBlock())) continue;

            Direction side = dir.getOpposite();
            Vec3d center = Vec3d.ofCenter(neighbor);
            Vec3d hit = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);

            if (strictDirection.get()) {
                Vec3d toEye = eye.subtract(center);
                double along = toEye.x * side.getOffsetX() + toEye.y * side.getOffsetY() + toEye.z * side.getOffsetZ();
                if (along <= 0.5) continue;
            }

            double dist = eye.squaredDistanceTo(hit);
            if (dist < bestDist) {
                bestDist = dist;
                best = new PlaceInfo(neighbor, side, hit);
            }
        }
        return best;
    }

    private boolean isClickable(Block block) {
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

    private void rotateTo(Vec3d target) {
        Vec3d eye = mc.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        float pitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, horizontal)), -90f, 90f);

        LUtils.send(Compat.moveLook(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        rotatedThisCall = true;
    }

    private void resyncRotation() {
        LUtils.send(Compat.moveLook(mc.player.getYaw(), mc.player.getPitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    private void attackBlockingCrystals() {
        for (BlockPos pos : surround) {
            Entity crystal = crystalAt(pos);
            if (crystal == null) continue;

            LUtils.send(PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking()));
            LUtils.send(new HandSwingC2SPacket(Hand.MAIN_HAND));
            return;
        }
    }

    private Entity crystalAt(BlockPos pos) {
        List<Entity> list = mc.world.getOtherEntities(null, new Box(pos), e -> e instanceof EndCrystalEntity);
        return list.isEmpty() ? null : list.get(0);
    }

    private boolean canPlaceAt(BlockPos pos, Block block) {
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        if (mc.world.canPlace(block.getDefaultState(), pos, ShapeContext.absent())) return true;
        return crystalAt(pos) != null;
    }

    private List<BlockPos> getPlacements(Block block) {
        List<BlockPos> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        double delayMs = placeDelay.get() * 50.0;
        double rangeSq = placeRange.get() * placeRange.get();

        for (BlockPos pos : surround) {
            Long placed = placedAt.get(pos);
            if (delayMs > 0 && placed != null && now - placed < delayMs) continue;
            if (mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > rangeSq) continue;
            if (!canPlaceAt(pos, block)) continue;
            out.add(pos);
        }
        return out;
    }

    private List<BlockPos> getSurround() {
        List<BlockPos> result = getSurroundNoDown();
        List<BlockPos> playerBlocks = getPlayerBlocks();

        for (BlockPos pb : playerBlocks) {
            if (!pb.equals(mc.player.getBlockPos()) && !result.contains(pb.down())) result.add(pb.down());
        }

        if (coverHead.get()) result.add(mc.player.getBlockPos().up(2));

        return result;
    }

    private List<BlockPos> getSurroundNoDown() {
        List<BlockPos> result = new ArrayList<>();
        List<BlockPos> playerBlocks = getPlayerBlocks();

        for (BlockPos pos : playerBlocks) {
            for (Direction dir : HORIZONTAL) {
                BlockPos next = pos.offset(dir);
                if (!result.contains(next) && !playerBlocks.contains(next)) result.add(next);
            }
        }
        return result;
    }

    private List<BlockPos> getPlayerBlocks() {
        BlockPos base = new BlockPos(
            MathHelper.floor(mc.player.getX()),
            MathHelper.floor(mc.player.getY() + 1.0E-4),
            MathHelper.floor(mc.player.getZ()));

        List<BlockPos> list = new ArrayList<>();
        if (!extend.get()) {
            list.add(base);
            return list;
        }

        Box box = mc.player.getBoundingBox();
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.floor(box.maxX - 1.0E-7);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.floor(box.maxZ - 1.0E-7);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) list.add(new BlockPos(x, base.getY(), z));
        }
        return list;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || fadeList.isEmpty()) return;

        long now = System.currentTimeMillis();
        int fade = Math.max(1, fadeTime.get());

        Iterator<Map.Entry<BlockPos, Long>> it = fadeList.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            double factor = 1.0 - (now - entry.getValue()) / (double) fade;
            if (factor <= 0) {
                it.remove();
                continue;
            }

            SettingColor s = sideColor.get();
            SettingColor l = lineColor.get();
            Color side = new Color(s.r, s.g, s.b, (int) (s.a * factor));
            Color line = new Color(l.r, l.g, l.b, (int) (l.a * factor));
            event.renderer.box(entry.getKey(), side, line, shapeMode.get(), 0);
        }
    }

    public boolean isPlacing() {
        return !placements.isEmpty();
    }
}
