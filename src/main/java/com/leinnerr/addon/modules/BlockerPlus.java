package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.utils.BlockPlacer;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockerPlus extends Module {
    public enum Shape { Ring, Filled }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgShape = settings.createGroup("Forma");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks").description("Bloques q usar")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN).build());

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("Bloques por tick")
        .defaultValue(3).min(1).sliderMax(10).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks")
        .defaultValue(0).min(0).sliderMax(10).build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range").description("Alcance maximo")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place").description("Coloca aunque no haya bloque donde apoyarse")
        .defaultValue(true).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio hacia el bloque")
        .defaultValue(true).build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Mueve la mano al coloca")
        .defaultValue(true).build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("No coloca mientras comes")
        .defaultValue(true).build());

    private final Setting<Boolean> autoRefill = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-refill").description("Pasa bloques a la hotbar")
        .defaultValue(true).build());

    private final Setting<Shape> shape = sgShape.add(new EnumSetting.Builder<Shape>()
        .name("shape").description("Ring: anillo hueco a la distancia del radio, Filled: rellena todo el area")
        .defaultValue(Shape.Ring).build());

    private final Setting<Integer> radius = sgShape.add(new IntSetting.Builder()
        .name("radius").description("Radio alrededor de ti")
        .defaultValue(3).min(1).sliderMin(1).sliderMax(6).build());

    private final Setting<Integer> thickness = sgShape.add(new IntSetting.Builder()
        .name("ring-thickness").description("Grosor del anillo hacia adentro.")
        .defaultValue(1).min(1).sliderMax(4).visible(() -> shape.get() == Shape.Ring).build());

    private final Setting<Boolean> round = sgShape.add(new BoolSetting.Builder()
        .name("round").description("Forma circular en vez de cuadrada")
        .defaultValue(false).build());

    private final Setting<Integer> layers = sgShape.add(new IntSetting.Builder()
        .name("layers").description("Altura: cuantas capas hacia arriba")
        .defaultValue(1).min(1).sliderMax(4).build());

    private final Setting<Integer> yOffset = sgShape.add(new IntSetting.Builder()
        .name("y-offset").description("Desplazamiento vertical de la primera capa")
        .defaultValue(0).min(-3).sliderMax(3).build());

    private final Setting<Boolean> keepOwnColumn = sgShape.add(new BoolSetting.Builder()
        .name("keep-own-column").description("Nunca coloca en tu propia columna")
        .defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Dibuja los bloques no puestos")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Como se dibujan")
        .defaultValue(ShapeMode.Both).visible(render::get).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Color de caras")
        .defaultValue(new SettingColor(40, 6, 86, 40)).visible(render::get).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Color de lineas")
        .defaultValue(new SettingColor(187, 22, 113, 160)).visible(render::get).build());

    private int timer;
    private final List<BlockPos> pending = new ArrayList<>();
    private final Map<Long, Integer> recent = new HashMap<>();
    private int tick;

    public BlockerPlus() {
        super(LeinnerrAddon.CATEGORY, "blocker-plus",
            "Es un surround con radio, Rodea (Ring) o rellena (Filled)");
    }

    @Override
    public void onActivate() {
        timer = 0;
        tick = 0;
        pending.clear();
        recent.clear();
    }

    @Override
    public String getInfoString() {
        return pending.isEmpty() ? null : String.valueOf(pending.size());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        pending.clear();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        tick++;
        recent.values().removeIf(t -> t < tick);

        computePending();
        if (pending.isEmpty()) return;

        if (timer > 0) {
            timer--;
            return;
        }
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;

        int slot = blockSlot();
        if (slot == -1) return;

        InvUtils.swap(slot, true);
        int placed = 0;
        for (BlockPos p : pending) {
            if (placed >= blocksPerTick.get()) break;
            if (place(p)) {
                recent.put(p.asLong(), tick + 2);
                placed++;
            }
        }
        InvUtils.swapBack();
        if (placed > 0 && rotate.get()) BlockPlacer.resync();
        timer = delay.get();
    }

    private void computePending() {
        int px = MathHelper.floor(mc.player.getX());
        int pz = MathHelper.floor(mc.player.getZ());
        int py = MathHelper.floor(mc.player.getY() + 0.001) + yOffset.get();
        int r = radius.get();
        int inner = shape.get() == Shape.Ring ? Math.max(0, r - thickness.get()) : -1;
        Vec3d eye = mc.player.getEyePos();
        double reach = placeRange.get();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (keepOwnColumn.get() && dx == 0 && dz == 0) continue;

                double dist = round.get() ? Math.sqrt(dx * dx + dz * dz) : Math.max(Math.abs(dx), Math.abs(dz));
                if (dist > r + (round.get() ? 0.5 : 0)) continue;
                if (shape.get() == Shape.Ring && dist <= inner) continue;

                for (int l = 0; l < layers.get(); l++) {
                    BlockPos pos = new BlockPos(px + dx, py + l, pz + dz);
                    if (recent.containsKey(pos.asLong())) continue;
                    if (!BlockPlacer.canPlace(pos, Blocks.OBSIDIAN)) continue;
                    if (eye.distanceTo(Vec3d.ofCenter(pos)) > reach) continue;
                    if (occupied(pos)) continue;
                    pending.add(pos);
                }
            }
        }

        Vec3d me = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        pending.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(me)));
    }

    private boolean place(BlockPos pos) {
        if (BlockPlacer.find(pos, false) != null) return BlockPlacer.place(pos, false, rotate.get());
        if (!airPlace.get()) return false;

        Vec3d center = Vec3d.ofCenter(pos);
        if (rotate.get()) BlockPlacer.rotateTo(center);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(center, Direction.UP, pos, false));
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private boolean occupied(BlockPos pos) {
        for (Entity e : mc.world.getOtherEntities(null, new Box(pos))) {
            if (!e.isSpectator() && e.isAlive() && !(e instanceof ItemEntity)) return true;
        }
        return false;
    }

    private int blockSlot() {
        FindItemResult res = InvUtils.findInHotbar(s -> s.getItem() instanceof BlockItem bi && blocks.get().contains(bi.getBlock()));
        if (res.found()) return res.slot();
        if (autoRefill.get()) {
            FindItemResult inv = InvUtils.find(s -> s.getItem() instanceof BlockItem bi && blocks.get().contains(bi.getBlock()));
            if (inv.found()) {
                InvUtils.move().from(inv.slot()).toHotbar(8);
                return 8;
            }
        }
        return -1;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (BlockPos p : pending) event.renderer.box(p, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}
