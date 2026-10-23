package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.utils.BlockPlacer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AutoCrawlTrap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Colocacion");
    private final SettingGroup sgRender = settings.createGroup("Renderizado");

    private final Setting<Double> enemyRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("enemy-range").description("Distancia maxima al objetivo")
        .defaultValue(10.0).min(0.1).sliderMax(15).build());

    private final Setting<Boolean> preventDownwards = sgGeneral.add(new BoolSetting.Builder()
        .name("prevent-downwards").description("Tambien coloca un bloque debajo del player")
        .defaultValue(true).build());

    private final Setting<Integer> extrapolationTicks = sgGeneral.add(new IntSetting.Builder()
        .name("extrapolation-ticks").description("Tiene en cuenta el movimiento del objetivo al calcular posiciones")
        .defaultValue(0).min(0).sliderMax(10).build());

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks").description("Bloques usados para la trampa")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN).build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("No trapea mientras comes")
        .defaultValue(true).build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range").description("Distancia maxima al bloque que se coloca")
        .defaultValue(4.0).min(0.1).sliderMax(6).build());

    private final Setting<Integer> blocksPerTick = sgPlace.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("Cantidad de bloques a colocar por tick")
        .defaultValue(2).min(1).sliderMax(10).build());

    private final Setting<Double> placeDelay = sgPlace.add(new DoubleSetting.Builder()
        .name("place-delay").description("Ticks a esperar antes de volver a colocar en la misma posicion")
        .defaultValue(1.0).min(0).sliderMax(5).build());

    private final Setting<Boolean> rotate = sgPlace.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio hacia la cara del bloque antes de colocar")
        .defaultValue(false).build());

    private final Setting<Boolean> strictDirection = sgPlace.add(new BoolSetting.Builder()
        .name("strict-direction").description("Solo hace click en las caras de bloque que miran hacia ti")
        .defaultValue(false).build());

    private final Setting<Boolean> stopMotion = sgPlace.add(new BoolSetting.Builder()
        .name("stop-motion").description("Cancela tu movimiento mientras colocas")
        .defaultValue(false).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Dibuja los bloques que se van colocando (el render)")
        .defaultValue(false).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Como se dibujan las forma")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Color")
        .defaultValue(new SettingColor(120, 60, 200, 40)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Color de las lineas")
        .defaultValue(new SettingColor(120, 60, 200, 150)).visible(render::get).build());

    private final Setting<Integer> fadeTime = sgRender.add(new IntSetting.Builder()
        .name("fade-time").description("Cuanto tarda en desvanecerse")
        .defaultValue(250).min(0).sliderMax(1000).visible(render::get).build());

    private List<BlockPos> trap = new ArrayList<>();
    private List<BlockPos> placements = new ArrayList<>();
    private PlayerEntity target;
    private Vec3d lastTargetPos;
    private UUID lastTargetId;

    private volatile Set<BlockPos> trapSnapshot = Set.of();
    private final Map<BlockPos, Long> placedAt = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> fadeList = new HashMap<>();

    private boolean rotatedThisCall;

    public AutoCrawlTrap() {
        super(LeinnerrAddon.CATEGORY, "auto-crawl-trap", "Atrapa tus enemigos en crawl");
    }

    @Override
    public void onDeactivate() {
        clearState();
        fadeList.clear();
        target = null;
        lastTargetPos = null;
        lastTargetId = null;
    }

    private void clearState() {
        trap = new ArrayList<>();
        placements = new ArrayList<>();
        trapSnapshot = Set.of();
        placedAt.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if ((pauseOnUse.get() && mc.player.isUsingItem()) || (stopMotion.get() && !mc.player.isOnGround())) {
            softClear();
            return;
        }

        FindItemResult item = findBlock();
        if (!item.found()) {
            softClear();
            return;
        }

        target = findTarget();
        if (target == null) {
            softClear();
            return;
        }

        Vec3d current = new Vec3d(target.getX(), target.getY(), target.getZ());
        Vec3d motion = lastTargetPos != null && target.getUuid().equals(lastTargetId)
            ? current.subtract(lastTargetPos) : Vec3d.ZERO;
        lastTargetPos = current;
        lastTargetId = target.getUuid();

        BlockPos targetPos = roundedPos(target);
        trap = getCrawlTrap(target, targetPos, motion);
        trapSnapshot = new HashSet<>(trap);

        if (!canCrawlTrap(target, targetPos) || trap.isEmpty()) {
            placements = new ArrayList<>();
            return;
        }

        placements = getPlacements(blockOf(item));
        if (placements.isEmpty()) return;

        placements.sort(Comparator.comparingInt(BlockPos::getY));

        Vec3d prevMotion = mc.player.getVelocity();
        if (stopMotion.get()) mc.player.setVelocity(0, 0, 0);

        rotatedThisCall = false;
        InvUtils.swap(item.slot(), true);

        int placed = 0;
        for (BlockPos pos : placements) {
            if (placed >= blocksPerTick.get()) break;
            if (placeAt(pos)) placed++;
        }

        InvUtils.swapBack();
        if (rotatedThisCall) BlockPlacer.resync();

        if (stopMotion.get()) mc.player.setVelocity(prevMotion);
    }

    private void softClear() {
        trap = new ArrayList<>();
        placements = new ArrayList<>();
        trapSnapshot = Set.of();
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;

        Packet<?> packet = event.packet;
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> p : bundle.getPackets()) handlePacket(p);
        } else {
            handlePacket(packet);
        }
    }

    private void handlePacket(Packet<?> packet) {
        if (!(packet instanceof BlockUpdateS2CPacket p)) return;

        BlockPos pos = p.getPos();
        if (!trapSnapshot.contains(pos)) return;

        if (p.getState().isReplaceable()) {
            mc.execute(() -> replaceNow(pos));
        } else {
            placedAt.remove(pos);
        }
    }

    private void replaceNow(BlockPos pos) {
        if (!isActive() || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (!trapSnapshot.contains(pos)) return;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;
        if (mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > placeRange.get() * placeRange.get()) return;

        FindItemResult item = findBlock();
        if (!item.found()) return;

        rotatedThisCall = false;
        InvUtils.swap(item.slot(), true);
        placeAt(pos);
        InvUtils.swapBack();
        if (rotatedThisCall) BlockPlacer.resync();
    }

    private FindItemResult findBlock() {
        return InvUtils.findInHotbar(stack -> stack.getItem() instanceof BlockItem bi && blocks.get().contains(bi.getBlock()));
    }

    private Block blockOf(FindItemResult item) {
        ItemStack stack = mc.player.getInventory().getStack(item.slot());
        return stack.getItem() instanceof BlockItem bi ? bi.getBlock() : Blocks.OBSIDIAN;
    }

    private boolean placeAt(BlockPos pos) {
        boolean ok = BlockPlacer.place(pos, strictDirection.get(), rotate.get());
        if (!ok) return false;

        if (rotate.get()) rotatedThisCall = true;

        long now = System.currentTimeMillis();
        placedAt.put(pos, now);
        fadeList.put(pos, now);
        return true;
    }

    private List<BlockPos> getPlacements(Block block) {
        List<BlockPos> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        double delayMs = placeDelay.get() * 50.0;
        double rangeSq = placeRange.get() * placeRange.get();

        for (BlockPos pos : trap) {
            Long placed = placedAt.get(pos);
            if (delayMs > 0 && placed != null && now - placed < delayMs) continue;
            if (mc.player.squaredDistanceTo(Vec3d.ofCenter(pos)) > rangeSq) continue;
            if (!BlockPlacer.canPlace(pos, block)) continue;
            out.add(pos);
        }
        return out;
    }

    private PlayerEntity findTarget() {
        Entity e = TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity p) || p == mc.player) return false;
            if (!p.isAlive() || p.isSpectator()) return false;
            if (p.distanceTo(mc.player) > enemyRange.get()) return false;
            return Friends.get().shouldAttack(p);
        }, SortPriority.LowestDistance);

        return e instanceof PlayerEntity p ? p : null;
    }

    private BlockPos roundedPos(PlayerEntity player) {
        return BlockPos.ofFloored(player.getX(), Math.round(player.getY()), player.getZ());
    }

    private List<BlockPos> getCrawlTrap(PlayerEntity entity, BlockPos playerPos, Vec3d motion) {
        List<BlockPos> result = new ArrayList<>();
        result.add(playerPos.up());
        if (preventDownwards.get()) result.add(playerPos.down());

        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();

        for (int ticks = 0; ticks <= extrapolationTicks.get(); ticks++) {
            double ox = motion.x * ticks;
            double oz = motion.z * ticks;
            BlockPos blockPos = BlockPos.ofFloored(x + ox, y, z + oz);

            if (!result.contains(blockPos.up())) result.add(blockPos.up());
            if (preventDownwards.get() && !result.contains(blockPos.down())) result.add(blockPos.down());
        }
        return result;
    }

    private boolean canCrawlTrap(PlayerEntity player, BlockPos playerPos) {
        return player.isOnGround()
            || !mc.world.getBlockState(playerPos.up()).isReplaceable()
            || !mc.world.getBlockState(playerPos.up(2)).isReplaceable();
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
