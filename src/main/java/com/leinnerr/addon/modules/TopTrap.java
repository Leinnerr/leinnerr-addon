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
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class TopTrap extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgParts = settings.createGroup("Partes");
    private final SettingGroup sgTarget = settings.createGroup("Objetivo");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks").description("Bloques q usar")
        .defaultValue(Blocks.OBSIDIAN).build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("place-range").description("Alcance maximo")
        .defaultValue(4.5).min(1).sliderMax(6).build());

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("Bloques por tick")
        .defaultValue(2).min(1).sliderMax(6).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Espera entre Ticks")
        .defaultValue(1).min(0).sliderMax(10).build());

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place").description("Coloca en el aire")
        .defaultValue(true).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio hacia el bloque")
        .defaultValue(true).build());

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing").description("Mueve la mano al colocar")
        .defaultValue(true).build());

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-use").description("No pone bloques mientras comes")
        .defaultValue(true).build());

    private final Setting<Boolean> autoRefill = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-refill").description("Pasa los bloques a la hotbar.")
        .defaultValue(true).build());

    private final Setting<Boolean> top = sgParts.add(new BoolSetting.Builder()
        .name("top").description("Bloque encima de la cabeza")
        .defaultValue(true).build());

    private final Setting<Boolean> headSides = sgParts.add(new BoolSetting.Builder()
        .name("head-sides").description("Los 4 lados a la altura de la cabeza")
        .defaultValue(true).build());

    private final Setting<Double> targetRange = sgTarget.add(new DoubleSetting.Builder()
        .name("target-range").description("Distancia maxima al oponente")
        .defaultValue(6.0).min(1).sliderMax(15).build());

    private final Setting<SortPriority> priority = sgTarget.add(new EnumSetting.Builder<SortPriority>()
        .name("priority").description("Como ordenar los objetivos.")
        .defaultValue(SortPriority.LowestDistance).build());

    private final Setting<Boolean> ignoreFriends = sgTarget.add(new BoolSetting.Builder()
        .name("ignore-friends").description("Ignora a tus panitas")
        .defaultValue(true).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Renderiza los bloques")
        .defaultValue(true).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Color.")
        .defaultValue(new SettingColor(255, 140, 0, 110)).visible(render::get).build());

    private PlayerEntity target;
    private int timer;
    private final List<BlockPos> pending = new ArrayList<>();

    public TopTrap() {
        super(LeinnerrAddon.CATEGORY, "top-trap",
            "Trapea tus enemigos");
    }

    @Override
    public void onActivate() {
        timer = 0;
        target = null;
        pending.clear();
    }

    @Override
    public String getInfoString() {
        return target == null ? null : target.getName().getString();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        pending.clear();
        target = null;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        target = findTarget();
        if (target == null) return;

        List<BlockPos> list = positions(target);
        Vec3d eye = mc.player.getEyePos();
        for (BlockPos p : list) {
            if (!mc.world.getBlockState(p).isReplaceable()) continue;
            if (eye.distanceTo(Vec3d.ofCenter(p)) > placeRange.get()) continue;
            if (occupied(p)) continue;
            pending.add(p);
        }
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
            if (place(p)) placed++;
        }
        InvUtils.swapBack();
        if (placed > 0 && rotate.get()) BlockPlacer.resync();
        timer = delay.get();
    }

    private List<BlockPos> positions(PlayerEntity p) {
        List<BlockPos> out = new ArrayList<>();
        int x = MathHelper.floor(p.getX());
        int z = MathHelper.floor(p.getZ());
        int feetY = MathHelper.floor(p.getY() + 0.001);
        int headY = MathHelper.floor(p.getBoundingBox().maxY - 0.001);

        boolean low = headY <= feetY;

        if (headSides.get() && !low) {
            out.add(new BlockPos(x + 1, headY, z));
            out.add(new BlockPos(x - 1, headY, z));
            out.add(new BlockPos(x, headY, z + 1));
            out.add(new BlockPos(x, headY, z - 1));
        }
        if (top.get()) out.add(new BlockPos(x, headY + 1, z));
        return out;
    }

    private boolean place(BlockPos pos) {
        if (BlockPlacer.find(pos, false) != null) {
            return BlockPlacer.place(pos, false, rotate.get());
        }
        if (!airPlace.get()) return false;

        Vec3d center = Vec3d.ofCenter(pos);
        if (rotate.get()) BlockPlacer.rotateTo(center);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(center, Direction.UP, pos, false));
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private boolean occupied(BlockPos pos) {
        for (Entity e : mc.world.getOtherEntities(null, new Box(pos))) {
            if (!e.isSpectator() && e.isAlive() && !(e instanceof net.minecraft.entity.ItemEntity)) return true;
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
        for (BlockPos p : pending) event.renderer.box(p, color.get(), color.get(), ShapeMode.Both, 0);
    }
}
