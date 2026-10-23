package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import com.leinnerr.addon.compat.Compat;
import com.leinnerr.addon.schematic.SchematicManager;
import com.leinnerr.addon.schematic.StateMatch;
import com.leinnerr.addon.utils.BlockPlacer;
import com.leinnerr.addon.utils.LUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class SchematicModule extends Module {
    public enum StateMode {
        Exact,
        Prefer,
        Block
    }

    public enum Order { BottomUp, Nearest }

    public enum LayerMode { All, Single, Below }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgLayer = settings.createGroup("Capas");
    private final SettingGroup sgColors = settings.createGroup("Colores");
    private final SettingGroup sgPlace = settings.createGroup("Colocar");
    private final SettingGroup sgPlaceState = settings.createGroup("Estado y rotacion");
    private final SettingGroup sgItems = settings.createGroup("Inventario");

    private final Setting<Boolean> renderOverlay = sgGeneral.add(new BoolSetting.Builder()
        .name("render").description("Dibuja el schematic sobre el mundo: lo que falta y lo que esta mal")
        .defaultValue(true).build());

    private final Setting<Boolean> placeEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("place").description("Coloca automaticamente los bloques que faltan")
        .defaultValue(false).build());

    private final Setting<Integer> renderRange = sgRender.add(new IntSetting.Builder()
        .name("render-range").description("Distancia a la que se dibuja el schematic")
        .defaultValue(32).min(4).sliderRange(4, 96).visible(renderOverlay::get).build());

    private final Setting<Integer> maxBoxes = sgRender.add(new IntSetting.Builder()
        .name("max-boxes").description("Maximo de cajas por fotograma Se dibujan primero las mas cercanas")
        .defaultValue(3000).min(100).sliderRange(100, 15000).visible(renderOverlay::get).build());

    private final Setting<Integer> updateInterval = sgRender.add(new IntSetting.Builder()
        .name("update-interval").description("Cada cuantos ticks se recalcula que faltan")
        .defaultValue(10).min(1).sliderRange(1, 40).visible(renderOverlay::get).build());

    private final Setting<Boolean> showMissing = sgRender.add(new BoolSetting.Builder()
        .name("show-missing").description("Dibuja los bloques que faltan")
        .defaultValue(true).visible(renderOverlay::get).build());

    private final Setting<Boolean> showWrong = sgRender.add(new BoolSetting.Builder()
        .name("show-wrong").description("Dibuja los bloques que estan mal")
        .defaultValue(true).visible(renderOverlay::get).build());

    private final Setting<Boolean> showCorrect = sgRender.add(new BoolSetting.Builder()
        .name("show-correct").description("Dibuja los bloques que estan bien")
        .defaultValue(false).visible(renderOverlay::get).build());

    private final Setting<Boolean> showBounds = sgRender.add(new BoolSetting.Builder()
        .name("show-bounds").description("Dibuja la caja completa del schemati")
        .defaultValue(true).visible(renderOverlay::get).build());

    private final Setting<LayerMode> layerMode = sgLayer.add(new EnumSetting.Builder<LayerMode>()
        .name("layer-mode").description("All = todo, Single = solo una capa, Below = esa capa y las de abajo")
        .defaultValue(LayerMode.All).visible(renderOverlay::get).build());

    private final Setting<Integer> layer = sgLayer.add(new IntSetting.Builder()
        .name("layer").description("Capa")
        .defaultValue(0).min(0).sliderRange(0, 256).visible(() -> renderOverlay.get() && layerMode.get() != LayerMode.All).build());

    private final Setting<Boolean> colorByBlock = sgColors.add(new BoolSetting.Builder()
        .name("color-by-block").description("Colorea los bloques que faltan con el color de mapa de su bloque")
        .defaultValue(true).visible(renderOverlay::get).build());

    private final Setting<SettingColor> missingColor = sgColors.add(new ColorSetting.Builder()
        .name("missing-color").description("Color de lo que falta")
        .defaultValue(new SettingColor(80, 140, 255, 255)).visible(renderOverlay::get).build());

    private final Setting<SettingColor> wrongColor = sgColors.add(new ColorSetting.Builder()
        .name("wrong-color").description("Color de un bloque distinto al del schematic")
        .defaultValue(new SettingColor(255, 50, 50, 255)).visible(renderOverlay::get).build());

    private final Setting<SettingColor> stateColor = sgColors.add(new ColorSetting.Builder()
        .name("wrong-state-color").description("Color de un bloque correcto pero mal orientado")
        .defaultValue(new SettingColor(255, 170, 0, 255)).visible(renderOverlay::get).build());

    private final Setting<SettingColor> correctColor = sgColors.add(new ColorSetting.Builder()
        .name("correct-color").description("Color de lo que ya esta bien")
        .defaultValue(new SettingColor(60, 255, 110, 255)).visible(renderOverlay::get).build());

    private final Setting<Integer> sideAlpha = sgColors.add(new IntSetting.Builder()
        .name("side-alpha").description("Transparencia de las caras")
        .defaultValue(45).min(0).max(255).sliderRange(0, 255).visible(renderOverlay::get).build());

    private final Setting<Integer> lineAlpha = sgColors.add(new IntSetting.Builder()
        .name("line-alpha").description("Transparencia de las lineas")
        .defaultValue(170).min(0).max(255).sliderRange(0, 255).visible(renderOverlay::get).build());

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range").description("Alcance maximo para colocar")
        .defaultValue(4.5).min(1).sliderMax(6).visible(placeEnabled::get).build());

    private final Setting<Integer> blocksPerTick = sgPlace.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("Bloques que coloca por tick")
        .defaultValue(3).min(1).sliderRange(1, 12).visible(placeEnabled::get).build());

    private final Setting<Integer> tickDelay = sgPlace.add(new IntSetting.Builder()
        .name("tick-delay").description("Ticks de espera")
        .defaultValue(0).min(0).sliderRange(0, 10).visible(placeEnabled::get).build());

    private final Setting<Order> order = sgPlace.add(new EnumSetting.Builder<Order>()
        .name("order").description("BottomUp = de abajo hacia arriba, Nearest = el mas cercano primero")
        .defaultValue(Order.BottomUp).visible(placeEnabled::get).build());

    private final Setting<Boolean> pauseOnUse = sgPlace.add(new BoolSetting.Builder()
        .name("pause-on-use").description("Se pausa mientras comes")
        .defaultValue(true).visible(placeEnabled::get).build());

    private final Setting<StateMode> stateMode = sgPlaceState.add(new EnumSetting.Builder<StateMode>()
        .name("state-mode").description("Exact = solo estados exactos, Prefer = exacto si se puede, Block = solo el tipo")
        .defaultValue(StateMode.Exact).visible(placeEnabled::get).build());

    private final Setting<Boolean> rotate = sgPlaceState.add(new BoolSetting.Builder()
        .name("rotate").description("Rota en silencio para orientar escaleras, troncos, etc")
        .defaultValue(true).visible(placeEnabled::get).build());

    private final Setting<Boolean> airPlace = sgPlaceState.add(new BoolSetting.Builder()
        .name("air-place").description("Coloca los bloques en el aire")
        .defaultValue(false).visible(placeEnabled::get).build());

    private final Setting<Boolean> swing = sgPlaceState.add(new BoolSetting.Builder()
        .name("swing").description("Mueve la mano al colocar")
        .defaultValue(true).visible(placeEnabled::get).build());

    private final Setting<Boolean> autoRefill = sgItems.add(new BoolSetting.Builder()
        .name("auto-refill").description("Pasa a la hotbar los bloques que te faltan desde el inventario")
        .defaultValue(true).visible(placeEnabled::get).build());

    private final Setting<Integer> refillSlot = sgItems.add(new IntSetting.Builder()
        .name("refill-slot").description("Ranura de la hotbar donde se pone el bloque que faltan")
        .defaultValue(9).min(1).max(9).sliderRange(1, 9).visible(placeEnabled::get).build());

    private final Setting<Boolean> creativePick = sgItems.add(new BoolSetting.Builder()
        .name("creative-pick").description("En creativo se da a si mismo los bloques que necesita")
        .defaultValue(true).visible(placeEnabled::get).build());

    private record Entry(double x, double y, double z, Color side, Color line, double dist) {}

    private volatile List<Entry> entries = new ArrayList<>();
    private int renderTimer;
    private int renderVersion = -1;
    private int missingCount;
    private int wrongCount;

    private record Plan(BlockHitResult hit, float yaw, float pitch) {}

    private static final int MAX_PLANS_PER_TICK = 24;
    private static final int FAIL_COOLDOWN_TICKS = 30;

    private final Map<Long, Integer> failedUntil = new HashMap<>();
    private final Set<Item> lacking = new LinkedHashSet<>();
    private int placeTimer;
    private int tickCounter;
    private int placeVersion = -1;
    private int pending;
    private int placedTotal;

    public SchematicModule() {
        super(LeinnerrAddon.CATEGORY, "schematic",
            "Schematic, con .schem load se carga y los schematics van en .minecraft/leinnerr/schematics");
    }

    @Override
    public void onActivate() {
        renderTimer = 0;
        renderVersion = -1;
        placeTimer = 0;
        placeVersion = -1;
        tickCounter = 0;
        placedTotal = 0;
        pending = 0;
        failedUntil.clear();
        lacking.clear();
    }

    @Override
    public String getInfoString() {
        if (!SchematicManager.get().hasSchematic()) return "sin schematic";

        StringBuilder sb = new StringBuilder();
        if (renderOverlay.get()) sb.append(missingCount).append("faltan").append(wrongCount > 0 ? " / " + wrongCount + " mal" : "");
        if (placeEnabled.get()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(pending).append(" pend.");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @EventHandler
    private void onRenderTick(TickEvent.Pre event) {
        if (!renderOverlay.get() || mc.player == null || mc.world == null) return;

        SchematicManager m = SchematicManager.get();
        if (!m.hasSchematic()) {
            entries = new ArrayList<>();
            missingCount = wrongCount = 0;
            return;
        }

        if (renderTimer > 0 && m.version() == renderVersion) {
            renderTimer--;
            return;
        }
        renderTimer = updateInterval.get();
        renderVersion = m.version();

        rebuild(m);
    }

    private void rebuild(SchematicManager m) {
        BlockPos origin = m.origin();
        BlockPos player = mc.player.getBlockPos();
        int r = renderRange.get();

        int minX = Math.max(player.getX() - r, origin.getX());
        int maxX = Math.min(player.getX() + r, origin.getX() + m.width() - 1);
        int minY = Math.max(player.getY() - r, origin.getY());
        int maxY = Math.min(player.getY() + r, origin.getY() + m.height() - 1);
        int minZ = Math.max(player.getZ() - r, origin.getZ());
        int maxZ = Math.min(player.getZ() + r, origin.getZ() + m.length() - 1);

        if (layerMode.get() != LayerMode.All) {
            int layerY = origin.getY() + layer.get();
            if (layerMode.get() == LayerMode.Single) minY = Math.max(minY, layerY);
            maxY = Math.min(maxY, layerY);
        }

        List<Entry> found = new ArrayList<>();
        int missing = 0, wrong = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        final int HARD_CAP = 40000;

        outer:
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState want = m.stateAt(x, y, z);
                    if (want == null || want.isAir() || want.getBlock() instanceof FluidBlock) continue;

                    pos.set(x, y, z);
                    BlockState have = mc.world.getBlockState(pos);

                    Color base;
                    if (have.isAir() || have.isReplaceable()) {
                        missing++;
                        if (!showMissing.get()) continue;
                        base = colorByBlock.get() ? mapColor(want, pos) : missingColor.get();
                    } else if (have.getBlock() != want.getBlock()) {
                        wrong++;
                        if (!showWrong.get()) continue;
                        base = wrongColor.get();
                    } else if (!StateMatch.sameState(want, have)) {
                        wrong++;
                        if (!showWrong.get()) continue;
                        base = stateColor.get();
                    } else {
                        if (!showCorrect.get()) continue;
                        base = correctColor.get();
                    }

                    double dx = x + 0.5 - mc.player.getX();
                    double dy = y + 0.5 - mc.player.getY();
                    double dz = z + 0.5 - mc.player.getZ();

                    found.add(new Entry(x, y, z,
                        new Color(base.r, base.g, base.b, sideAlpha.get()),
                        new Color(base.r, base.g, base.b, lineAlpha.get()),
                        dx * dx + dy * dy + dz * dz));

                    if (found.size() >= HARD_CAP) break outer;
                }
            }
        }

        found.sort(Comparator.comparingDouble(Entry::dist));
        if (found.size() > maxBoxes.get()) found = new ArrayList<>(found.subList(0, maxBoxes.get()));

        entries = found;
        missingCount = missing;
        wrongCount = wrong;
    }

    private Color mapColor(BlockState state, BlockPos pos) {
        int c = state.getMapColor(mc.world, pos).color;
        return new Color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 255);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderOverlay.get()) return;
        SchematicManager m = SchematicManager.get();
        if (!m.hasSchematic()) return;

        for (Entry e : entries) {
            event.renderer.box(e.x(), e.y(), e.z(), e.x() + 1, e.y() + 1, e.z() + 1, e.side(), e.line(), ShapeMode.Both, 0);
        }

        if (showBounds.get()) {
            BlockPos o = m.origin();
            Color c = new Color(255, 255, 255, 120);
            event.renderer.box(o.getX(), o.getY(), o.getZ(),
                o.getX() + m.width(), o.getY() + m.height(), o.getZ() + m.length(),
                c, c, ShapeMode.Lines, 0);
        }
    }

    @EventHandler
    private void onPlaceTick(TickEvent.Pre event) {
        if (!placeEnabled.get() || mc.player == null || mc.world == null || mc.interactionManager == null) return;

        SchematicManager m = SchematicManager.get();
        if (!m.hasSchematic()) return;

        tickCounter++;

        if (m.version() != placeVersion) {
            placeVersion = m.version();
            failedUntil.clear();
        }

        if (tickCounter % 100 == 0 && !lacking.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Item item : lacking) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(item.getName().getString());
            }
            if (chatFeedback) warning("Te faltan bloques: %s", sb);
            lacking.clear();
        }

        if (placeTimer > 0) {
            placeTimer--;
            return;
        }
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;

        List<BlockPos> targets = collect(m);
        pending = targets.size();
        if (targets.isEmpty()) return;

        float realYaw = mc.player.getYaw();
        float realPitch = mc.player.getPitch();
        float curYaw = realYaw;
        float curPitch = realPitch;

        int placed = 0;
        int plans = 0;
        boolean swapped = false;
        boolean refilled = false;

        for (BlockPos pos : targets) {
            if (placed >= blocksPerTick.get() || plans >= MAX_PLANS_PER_TICK) break;

            BlockState want = m.stateAt(pos.getX(), pos.getY(), pos.getZ());
            if (want == null) continue;
            Item item = want.getBlock().asItem();

            FindItemResult res = InvUtils.findInHotbar(item);
            if (!res.found()) {
                lacking.add(item);
                if (!refilled && refill(item)) refilled = true;
                continue;
            }

            Hand hand = res.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            ItemStack stack = res.isOffhand() ? mc.player.getOffHandStack() : mc.player.getInventory().getStack(res.slot());

            plans++;
            Plan plan = findPlan(pos, want, stack, hand, curYaw, curPitch);
            if (plan == null) {
                failedUntil.put(pos.asLong(), tickCounter + FAIL_COOLDOWN_TICKS);
                continue;
            }

            if (!res.isOffhand() && !res.isMainHand()) {
                InvUtils.swap(res.slot(), true);
                swapped = true;
            }

            if (plan.yaw() != curYaw || plan.pitch() != curPitch) {
                LUtils.send(Compat.moveLook(plan.yaw(), plan.pitch(), mc.player.isOnGround(), mc.player.horizontalCollision));
                curYaw = plan.yaw();
                curPitch = plan.pitch();
            }

            mc.interactionManager.interactBlock(mc.player, hand, plan.hit());
            if (swing.get()) mc.player.swingHand(hand);
            placed++;
        }

        if (swapped) InvUtils.swapBack();
        if (curYaw != realYaw || curPitch != realPitch) BlockPlacer.resync();

        if (placed > 0) {
            placedTotal += placed;
            placeTimer = tickDelay.get();
        }
    }

    private List<BlockPos> collect(SchematicManager m) {
        BlockPos origin = m.origin();
        BlockPos player = mc.player.getBlockPos();
        Vec3d eye = mc.player.getEyePos();
        double reach = placeRange.get();
        int r = (int) Math.ceil(reach) + 1;

        int minX = Math.max(player.getX() - r, origin.getX());
        int maxX = Math.min(player.getX() + r, origin.getX() + m.width() - 1);
        int minY = Math.max(player.getY() - r, origin.getY());
        int maxY = Math.min(player.getY() + r, origin.getY() + m.height() - 1);
        int minZ = Math.max(player.getZ() - r, origin.getZ());
        int maxZ = Math.min(player.getZ() + r, origin.getZ() + m.length() - 1);

        List<BlockPos> list = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState want = m.stateAt(x, y, z);
                    if (want == null || want.isAir() || !isPlaceable(want)) continue;

                    pos.set(x, y, z);

                    BlockState have = mc.world.getBlockState(pos);
                    if (!have.isAir() && !have.isReplaceable()) continue;
                    if (have.getBlock() == want.getBlock()) continue;

                    if (eye.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5) > (reach + 1.0) * (reach + 1.0)) continue;

                    Integer until = failedUntil.get(pos.asLong());
                    if (until != null && until > tickCounter) continue;

                    list.add(pos.toImmutable());
                }
            }
        }

        if (order.get() == Order.BottomUp) {
            list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> eye.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
        } else {
            list.sort(Comparator.comparingDouble(p -> eye.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
        }
        return list;
    }

    private boolean isPlaceable(BlockState want) {
        Block block = want.getBlock();
        if (block instanceof FluidBlock) return false;
        if (block.asItem() == Items.AIR) return false;

        if (want.contains(Properties.DOUBLE_BLOCK_HALF) && want.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return false;
        if (want.contains(Properties.BED_PART) && want.get(Properties.BED_PART) == BedPart.HEAD) return false;
        if (want.contains(Properties.SLAB_TYPE) && want.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) return false;
        return true;
    }

    private Plan findPlan(BlockPos pos, BlockState want, ItemStack stack, Hand hand, float curYaw, float curPitch) {
        List<BlockHitResult> hits = candidateHits(pos);
        if (hits.isEmpty()) return null;

        Block block = want.getBlock();
        StateMode mode = stateMode.get();

        float realYaw = mc.player.getYaw();
        float realPitch = mc.player.getPitch();
        Plan fallback = null;

        try {
            for (float[] rot : rotationCandidates(curYaw, curPitch)) {
                mc.player.setYaw(rot[0]);
                mc.player.setPitch(rot[1]);

                for (BlockHitResult hit : hits) {
                    ItemPlacementContext ctx = new ItemPlacementContext(mc.player, hand, stack, hit);
                    if (!ctx.canPlace() || !ctx.getBlockPos().equals(pos)) continue;

                    BlockState got = block.getPlacementState(ctx);
                    if (got == null || !got.canPlaceAt(mc.world, pos)) continue;
                    if (!mc.world.canPlace(got, pos, ShapeContext.absent())) continue;

                    if (mode == StateMode.Block || StateMatch.sameState(want, got)) {
                        return new Plan(hit, rot[0], rot[1]);
                    }
                    if (mode == StateMode.Prefer && fallback == null) {
                        fallback = new Plan(hit, rot[0], rot[1]);
                    }
                }
            }
        } finally {
            mc.player.setYaw(realYaw);
            mc.player.setPitch(realPitch);
        }

        return fallback;
    }

    private float[][] rotationCandidates(float curYaw, float curPitch) {
        if (!rotate.get()) return new float[][]{{curYaw, curPitch}};

        float[] yaws = {0f, 90f, 180f, -90f};
        float[] pitches = {0f, 90f, -90f};

        List<float[]> list = new ArrayList<>();
        list.add(new float[]{curYaw, curPitch});
        for (float pitch : pitches) {
            for (float yaw : yaws) list.add(new float[]{yaw, pitch});
        }
        return list.toArray(new float[0][]);
    }

    private List<BlockHitResult> candidateHits(BlockPos pos) {
        List<BlockHitResult> list = new ArrayList<>();
        Vec3d eye = mc.player.getEyePos();
        double reach = placeRange.get();

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbor);

            if (state.isAir() || state.isReplaceable()) continue;
            if (!mc.player.isSneaking() && BlockPlacer.isClickable(state.getBlock())) continue;

            addFaceHits(list, neighbor, dir.getOpposite(), eye, reach);
        }

        if (airPlace.get()) {
            for (Direction dir : Direction.values()) addFaceHits(list, pos, dir, eye, reach);
        }

        list.sort(Comparator.comparingDouble(h -> eye.squaredDistanceTo(h.getPos())));
        return list;
    }

    private void addFaceHits(List<BlockHitResult> list, BlockPos blockPos, Direction side, Vec3d eye, double reach) {
        Vec3d center = Vec3d.ofCenter(blockPos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        double[] dys = side.getAxis().isHorizontal() ? new double[]{-0.25, 0.25} : new double[]{0.0};

        for (double dy : dys) {
            Vec3d hit = center.add(0, dy, 0);
            if (eye.distanceTo(hit) > reach) continue;
            list.add(new BlockHitResult(hit, side, blockPos, false));
        }
    }

    private boolean refill(Item item) {
        int slot = refillSlot.get() - 1;

        if (mc.player.isCreative() && creativePick.get()) {
            mc.interactionManager.clickCreativeStack(new ItemStack(item, 64), 36 + slot);
            return true;
        }

        if (!autoRefill.get()) return false;

        FindItemResult inv = InvUtils.find(item);
        if (!inv.found() || inv.isHotbar()) return false;

        InvUtils.move().from(inv.slot()).toHotbar(slot);
        return true;
    }
}
