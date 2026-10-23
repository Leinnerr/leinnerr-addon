package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.utils.BlockPlacer;
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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaceWater extends Module {
    public enum Mode { Agresivo, Inteligente }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Objetivo");
    private final SettingGroup sgSmart = settings.createGroup("Inteligente");
    private final SettingGroup sgSponge = settings.createGroup("Solo esponjas");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Agresivo: esponjas por todos lados. Inteligente: esponja a la altura de la cabeza + obsidiana debajo; el cristal rompe la esponja mojada y se repone una seca")
        .defaultValue(Mode.Inteligente).build());

    private final Setting<Boolean> placeObsidian = sgGeneral.add(new BoolSetting.Builder()
        .name("place-obsidian").description("Pone la obsidiana junto a la esponja en agresivo, ")
        .defaultValue(true).build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range").description("Alcance maximo para colocar")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks de espera entre cada combo")
        .defaultValue(2).min(0).sliderMax(20).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio hacia el bloque antes de colocar")
        .defaultValue(true).build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Mueve la mano al colocar")
        .defaultValue(true).build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("No se pone mientras comes")
        .defaultValue(true).build());

    private final Setting<Boolean> autoRefill = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-refill").description("Pasa esponjas y obsidiana a la hotbar")
        .defaultValue(true).build());

    private final Setting<Double> targetRange = sgTarget.add(new DoubleSetting.Builder()
        .name("target-range").description("Distancia maxima al oponente")
        .defaultValue(8.0).min(1).sliderMax(20).build());

    private final Setting<Double> crystalRadius = sgTarget.add(new DoubleSetting.Builder()
        .name("crystal-radius").description("Distancia maxima entre el oponente y la obsidiana donde ira el cristal")
        .defaultValue(3.0).min(1).sliderMax(6).build());

    private final Setting<SortPriority> priority = sgTarget.add(new EnumSetting.Builder<SortPriority>()
        .name("priority").description("Como ordenar los objetivos")
        .defaultValue(SortPriority.ClosestAngle).build());

    private final Setting<Boolean> ignoreFriends = sgTarget.add(new BoolSetting.Builder()
        .name("ignore-friends").description("Ignora tus compas")
        .defaultValue(true).build());

    private final Setting<Integer> smartDelay = sgSmart.add(new IntSetting.Builder()
        .name("smart-delay").description("Ticks de espera entre acciones")
        .defaultValue(1).min(0).sliderMax(10).visible(() -> mode.get() == Mode.Inteligente).build());

    private final Setting<Boolean> diagonals = sgSmart.add(new BoolSetting.Builder()
        .name("allow-diagonals").description("Permite columnas en diagonal")
        .defaultValue(false).visible(() -> mode.get() == Mode.Inteligente).build());

    private final Setting<Integer> replaceCooldown = sgSmart.add(new IntSetting.Builder()
        .name("replace-cooldown").description("Ticks minimos antes de volver a poner la esponja tras romperse")
        .defaultValue(1).min(0).sliderMax(10).visible(() -> mode.get() == Mode.Inteligente).build());

    private final Setting<Integer> spongeRadius = sgSponge.add(new IntSetting.Builder()
        .name("sponge-radius").description("Radio alrededor del oponente donde buscar agua que absorber")
        .defaultValue(4).min(1).sliderMax(8).visible(() -> mode.get() == Mode.Agresivo && !placeObsidian.get()).build());

    private final Setting<Integer> spongesPerTick = sgSponge.add(new IntSetting.Builder()
        .name("sponges-per-tick").description("Esponjas por tick.")
        .defaultValue(3).min(1).sliderMax(8).visible(() -> mode.get() == Mode.Agresivo && !placeObsidian.get()).build());

    private final Setting<Integer> spongeDelay = sgSponge.add(new IntSetting.Builder()
        .name("sponge-delay").description("Ticks de espera entre esponjas")
        .defaultValue(0).min(0).sliderMax(10).visible(() -> mode.get() == Mode.Agresivo && !placeObsidian.get()).build());

    private final Setting<Integer> spongeSpacing = sgSponge.add(new IntSetting.Builder()
        .name("sponge-spacing").description("Separacion minima entre esponjas ")
        .defaultValue(3).min(1).sliderMax(7).visible(() -> mode.get() == Mode.Agresivo && !placeObsidian.get()).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Dibuja donde se coloco")
        .defaultValue(true).build());

    private final Setting<SettingColor> spongeColor = sgRender.add(new ColorSetting.Builder()
        .name("sponge-color").description("Color de la esponja")
        .defaultValue(new SettingColor(240, 220, 60, 120)).visible(render::get).build());

    private final Setting<SettingColor> obsidianColor = sgRender.add(new ColorSetting.Builder()
        .name("obsidian-color").description("Color de la obsi")
        .defaultValue(new SettingColor(120, 60, 200, 120)).visible(() -> render.get() && placeObsidian.get()).build());

    private record Combo(BlockPos sponge, BlockPos obsidian, double score) {}

    private PlayerEntity target;
    private int timer;
    private BlockPos lastSponge;
    private BlockPos lastObsidian;
    private long lastStamp;
    private int combos;
    private int tickCounter;
    private BlockPos smartCol;
    private int spongeGoneAt = -1;
    private final Map<Long, Integer> recent = new HashMap<>();
    private final List<BlockPos> lastBatch = new ArrayList<>();

    public PlaceWater() {
        super(LeinnerrAddon.CATEGORY, "place-water",
            "Sirve para atacar con cristales estando dentro del agua");
    }

    @Override
    public void onActivate() {
        target = null;
        timer = 0;
        combos = 0;
        lastSponge = lastObsidian = null;
        recent.clear();
        lastBatch.clear();
        smartCol = null;
        spongeGoneAt = -1;
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString() + " (" + combos + ")";
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        target = null;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;

        target = findTarget();
        if (target == null) return;

        tickCounter++;
        if (mode.get() == Mode.Inteligente) {
            smart();
            return;
        }
        if (!placeObsidian.get()) {
            spongeOnly();
            return;
        }

        Combo combo = findCombo();
        if (combo == null) return;

        int spongeSlot = slotFor(Items.SPONGE, 7);
        int obsidianSlot = slotFor(Items.OBSIDIAN, 8);

        boolean needSponge = !isSponge(mc.world.getBlockState(combo.sponge()));
        boolean needObsidian = !mc.world.getBlockState(combo.obsidian()).isOf(Blocks.OBSIDIAN);

        if (needSponge && spongeSlot == -1) return;
        if (needObsidian && obsidianSlot == -1) return;

        boolean swapped = false;

        if (needSponge) {
            InvUtils.swap(spongeSlot, true);
            swapped = true;
            airPlace(combo.sponge());
        }
        if (needObsidian) {
            InvUtils.swap(obsidianSlot, true);
            swapped = true;
            airPlace(combo.obsidian());
        }
        if (swapped) InvUtils.swapBack();
        if (rotate.get()) BlockPlacer.resync();

        lastSponge = combo.sponge();
        lastObsidian = combo.obsidian();
        lastStamp = System.currentTimeMillis();
        combos++;
        timer = delay.get();
    }

    private void smart() {
        BlockPos col = pickColumn();
        if (col == null) return;
        smartCol = col;

        BlockPos s = col.up();
        BlockPos o = col.down();
        BlockState sState = mc.world.getBlockState(s);
        BlockState oState = mc.world.getBlockState(o);

        boolean spongePresent = isSponge(sState);
        if (spongePresent) spongeGoneAt = -1;
        else if (spongeGoneAt == -1) spongeGoneAt = tickCounter;

        boolean needSponge = !spongePresent && sState.isReplaceable() && !occupied(s)
            && !recent.containsKey(s.asLong()) && tickCounter - spongeGoneAt >= replaceCooldown.get();
        boolean needObsidian = placeObsidian.get() && oState.isReplaceable() && !occupied(o)
            && !recent.containsKey(o.asLong());

        recent.values().removeIf(t -> t < tickCounter);

        if (!needSponge && !needObsidian) return;

        int spongeSlot = needSponge ? slotFor(Items.SPONGE, 7) : -1;
        int obsidianSlot = needObsidian ? slotFor(Items.OBSIDIAN, 8) : -1;
        if (needSponge && spongeSlot == -1) needSponge = false;
        if (needObsidian && obsidianSlot == -1) needObsidian = false;
        if (!needSponge && !needObsidian) return;

        boolean swapped = false;
        lastSponge = null;
        lastObsidian = null;
        
        if (needSponge) {
            InvUtils.swap(spongeSlot, true);
            swapped = true;
            airPlace(s);
            recent.put(s.asLong(), tickCounter + 3);
            lastSponge = s;
        }
        if (needObsidian) {
            InvUtils.swap(obsidianSlot, true);
            swapped = true;
            airPlace(o);
            recent.put(o.asLong(), tickCounter + 3);
            lastObsidian = o;
        }
        if (swapped) InvUtils.swapBack();
        if (rotate.get()) BlockPlacer.resync();

        lastStamp = System.currentTimeMillis();
        combos++;
        timer = smartDelay.get();
    }

    private BlockPos pickColumn() {
        Vec3d eye = mc.player.getEyePos();
        double reach = placeRange.get();
        int fx = net.minecraft.util.math.MathHelper.floor(target.getX());
        int fy = net.minecraft.util.math.MathHelper.floor(target.getY() + 0.001);
        int fz = net.minecraft.util.math.MathHelper.floor(target.getZ());

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (!diagonals.get() && dx != 0 && dz != 0) continue;

                BlockPos c = new BlockPos(fx + dx, fy, fz + dz);
                BlockPos s = c.up();
                BlockPos o = c.down();

                BlockState cState = mc.world.getBlockState(c);
                if (!cState.isReplaceable()) continue;
                BlockState sState = mc.world.getBlockState(s);
                if (!isSponge(sState) && !sState.isReplaceable()) continue;
                BlockState oState = mc.world.getBlockState(o);
                boolean oSolid = oState.isOf(Blocks.OBSIDIAN) || oState.isOf(Blocks.BEDROCK);
                if (!oSolid && !oState.isReplaceable()) continue;
                if (!oSolid && !placeObsidian.get()) continue;

                if (eye.distanceTo(Vec3d.ofCenter(s)) > reach) continue;
                if (placeObsidian.get() && !oSolid && eye.distanceTo(Vec3d.ofCenter(o)) > reach) continue;

                if (!isSponge(sState) && !nearWater(c) && !nearWater(s) && !nearWater(o)) continue;

                double score = eye.distanceTo(Vec3d.ofCenter(c));
                if (isSponge(sState)) score -= 50;
                if (oSolid) score -= 25;
                if (c.equals(smartCol)) score -= 10;
                if (score < bestScore) { bestScore = score; best = c; }
            }
        }
        return best;
    }

    private void spongeOnly() {
        int slot = slotFor(Items.SPONGE, 7);
        if (slot == -1) return;

        recent.values().removeIf(t -> t < tickCounter);

        Vec3d eye = mc.player.getEyePos();
        double reach = placeRange.get();
        BlockPos base = target.getBlockPos();
        int r = spongeRadius.get();
        Vec3d t = new Vec3d(target.getX(), target.getY(), target.getZ());

        List<BlockPos> cands = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = base.add(x, y, z);
                    if (recent.containsKey(p.asLong())) continue;
                    if (!mc.world.getBlockState(p).isReplaceable()) continue;
                    if (eye.distanceTo(Vec3d.ofCenter(p)) > reach) continue;
                    if (!nearWater(p)) continue;
                    if (occupied(p)) continue;
                    cands.add(p);
                }
            }
        }
        if (cands.isEmpty()) return;

        cands.sort((a, b) -> Double.compare(Vec3d.ofCenter(a).squaredDistanceTo(t), Vec3d.ofCenter(b).squaredDistanceTo(t)));

        List<BlockPos> chosen = new ArrayList<>();
        int spacing = spongeSpacing.get();
        for (BlockPos p : cands) {
            boolean ok = true;
            for (BlockPos c : chosen) {
                if (Math.abs(c.getX() - p.getX()) + Math.abs(c.getY() - p.getY()) + Math.abs(c.getZ() - p.getZ()) < spacing) { ok = false; break; }
            }
            if (ok) chosen.add(p);
            if (chosen.size() >= spongesPerTick.get()) break;
        }
        if (chosen.isEmpty()) return;

        InvUtils.swap(slot, true);
        lastBatch.clear();
        for (BlockPos p : chosen) {
            airPlace(p);
            recent.put(p.asLong(), tickCounter + 6);
            lastBatch.add(p);
            combos++;
        }
        InvUtils.swapBack();
        if (rotate.get()) BlockPlacer.resync();

        lastSponge = null;
        lastStamp = System.currentTimeMillis();
        timer = spongeDelay.get();
    }

    private boolean nearWater(BlockPos p) {
        if (mc.world.getFluidState(p).isIn(FluidTags.WATER)) return true;
        for (Direction d : Direction.values()) {
            if (mc.world.getFluidState(p.offset(d)).isIn(FluidTags.WATER)) return true;
        }
        return false;
    }

    private void airPlace(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        if (rotate.get()) BlockPlacer.rotateTo(center);

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(center, Direction.UP, pos, false));
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
    }

    private Combo findCombo() {
        Vec3d eye = mc.player.getEyePos();
        double reach = placeRange.get();
        Vec3d t = new Vec3d(target.getX(), target.getY(), target.getZ());
        BlockPos base = target.getBlockPos();
        int r = (int) Math.ceil(crystalRadius.get()) + 1;

        Combo best = null;

        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 1; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos s = base.add(x, y, z);

                    double dx = t.x - (s.getX() + 0.5);
                    double dz = t.z - (s.getZ() + 0.5);
                    if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) continue;

                    Direction dir = Math.abs(dx) >= Math.abs(dz)
                        ? (dx > 0 ? Direction.EAST : Direction.WEST)
                        : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                    BlockPos o = s.offset(dir);

                    Vec3d topCenter = new Vec3d(o.getX() + 0.5, o.getY() + 1.0, o.getZ() + 0.5);
                    double dist = topCenter.distanceTo(new Vec3d(t.x, t.y + 0.9, t.z));
                    if (dist > crystalRadius.get()) continue;

                    if (eye.distanceTo(Vec3d.ofCenter(s)) > reach || eye.distanceTo(Vec3d.ofCenter(o)) > reach) continue;

                    if (!worthIt(s, o)) continue;
                    if (best == null || dist < best.score()) best = new Combo(s, o, dist);
                }
            }
        }
        return best;
    }

    private boolean worthIt(BlockPos s, BlockPos o) {
        BlockState sState = mc.world.getBlockState(s);
        BlockState oState = mc.world.getBlockState(o);

        boolean spongeOk = isSponge(sState) || sState.isReplaceable();
        boolean obsidianOk = oState.isOf(Blocks.OBSIDIAN) || oState.isReplaceable();
        if (!spongeOk || !obsidianOk) return false;

        if (!isSponge(sState) && occupied(s)) return false;
        if (!oState.isOf(Blocks.OBSIDIAN) && occupied(o)) return false;

        boolean water = mc.world.getFluidState(o).isIn(FluidTags.WATER)
            || mc.world.getFluidState(o.up()).isIn(FluidTags.WATER);
        if (!water) return false;

        return !(isSponge(sState) && oState.isOf(Blocks.OBSIDIAN));
    }

    private boolean isSponge(BlockState state) {
        return state.isOf(Blocks.SPONGE) || state.isOf(Blocks.WET_SPONGE);
    }

    private boolean occupied(BlockPos pos) {
        Box box = new Box(pos);
        for (Entity e : mc.world.getOtherEntities(null, box)) {
            if (!e.isSpectator()) return true;
        }
        return false;
    }

    private int slotFor(net.minecraft.item.Item item, int preferredSlot) {
        FindItemResult res = InvUtils.findInHotbar(item);
        if (res.found()) return res.slot();

        if (autoRefill.get()) {
            FindItemResult inv = InvUtils.find(item);
            if (inv.found()) {
                InvUtils.move().from(inv.slot()).toHotbar(preferredSlot);
                return preferredSlot;
            }
        }
        return -1;
    }

    private PlayerEntity findTarget() {
        Entity e = TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity p) || p == mc.player) return false;
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) return false;
            if (p.distanceTo(mc.player) > targetRange.get()) return false;
            return !(ignoreFriends.get() && Friends.get().isFriend(p));
        }, priority.get());
        return e instanceof PlayerEntity p ? p : null;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        if (System.currentTimeMillis() - lastStamp > 400) return;
        if (mode.get() == Mode.Agresivo && !placeObsidian.get()) {
            for (BlockPos p : lastBatch) event.renderer.box(p, spongeColor.get(), spongeColor.get(), ShapeMode.Both, 0);
            return;
        }
        if (lastSponge != null) event.renderer.box(lastSponge, spongeColor.get(), spongeColor.get(), ShapeMode.Both, 0);
        if (lastObsidian != null) event.renderer.box(lastObsidian, obsidianColor.get(), obsidianColor.get(), ShapeMode.Both, 0);
    }
}
