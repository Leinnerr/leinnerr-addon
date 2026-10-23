package com.leinnerr.addon.modules;

import com.leinnerr.addon.LeinnerrAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

// esto sirve para el crawltrap en bedrock cuando se juega en bedrock
public class BedrockESP extends Module {
    public enum Above { Directo, EnLaColumna }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks").description("Bloques q resaltar")
        .defaultValue(Blocks.DEEPSLATE, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.DRIPSTONE_BLOCK).build());

    private final Setting<Above> above = sgGeneral.add(new EnumSetting.Builder<Above>()
        .name("bedrock-above").description("La bedrock esta justo encima de este bloque")
        .defaultValue(Above.Directo).build());

    private final Setting<Integer> bedrockSearch = sgGeneral.add(new IntSetting.Builder()
        .name("bedrock-search").description("Cuantos bloques hacia arriba se busca")
        .defaultValue(4).min(1).sliderMax(10).visible(() -> above.get() == Above.EnLaColumna).build());

    private final Setting<Integer> hRange = sgGeneral.add(new IntSetting.Builder()
        .name("horizontal-range").description("Radio horizontal de busqueda")
        .defaultValue(24).min(4).sliderMax(64).build());

    private final Setting<Integer> vRange = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-range").description("Rango vertical de busqueda")
        .defaultValue(12).min(2).sliderMax(40).build());

    private final Setting<Integer> scanDelay = sgGeneral.add(new IntSetting.Builder()
        .name("scan-delay").description("Ticks entre cada escaneo (mas alto = menos lag)")
        .defaultValue(10).min(1).sliderMax(60).build());

    private final Setting<Integer> maxBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks").description("Maximo de bloques dibujados")
        .defaultValue(400).min(10).sliderMax(2000).build());

    private final Setting<Boolean> highlightExposed = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-exposed").description("Usa otro color si debajo de la bedrock hay otro bloque y si es picado el enemigo cae")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Como se dibujan")
        .defaultValue(ShapeMode.Both).build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Color de caras")
        .defaultValue(new SettingColor(120, 60, 200, 50)).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Color de lineas.")
        .defaultValue(new SettingColor(180, 120, 255, 200)).build());

    private final Setting<SettingColor> exposedSide = sgRender.add(new ColorSetting.Builder()
        .name("exposed-side-color").description("Color de caras con hueco debajo")
        .defaultValue(new SettingColor(255, 40, 80, 60)).visible(highlightExposed::get).build());

    private final Setting<SettingColor> exposedLine = sgRender.add(new ColorSetting.Builder()
        .name("exposed-line-color").description("Color de lineas con hueco debajo")
        .defaultValue(new SettingColor(255, 60, 100, 220)).visible(highlightExposed::get).build());

    private record Hit(BlockPos pos, boolean exposed) {}

    private volatile List<Hit> hits = List.of();
    private int timer;

    public BedrockESP() {
        super(LeinnerrAddon.CATEGORY, "bedrock-esp",
            "Resalta los bloques que estan debajo de bedrock y si los picas y tu enemigo esta en burrow, cae y lo trapea");
    }

    @Override
    public void onActivate() {
        timer = 0;
        hits = List.of();
    }

    @Override
    public void onDeactivate() {
        hits = List.of();
    }

    @Override
    public String getInfoString() {
        return String.valueOf(hits.size());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (timer-- > 0) return;
        timer = scanDelay.get();

        BlockPos me = mc.player.getBlockPos();
        int h = hRange.get(), v = vRange.get();
        int minY = Math.max(mc.world.getBottomY(), me.getY() - v);
        int maxY = Math.min(mc.world.getBottomY() + mc.world.getHeight() - 1, me.getY() + v);
        List<Block> list = blocks.get();
        List<Hit> found = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();

        outer:
        for (int x = me.getX() - h; x <= me.getX() + h; x++) {
            for (int z = me.getZ() - h; z <= me.getZ() + h; z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(x, y, z);
                    BlockState s = mc.world.getBlockState(m);
                    if (!list.contains(s.getBlock())) continue;
                    if (!bedrockAbove(m)) continue;

                    boolean exposed = highlightExposed.get()
                        && mc.world.getBlockState(m.down()).isReplaceable();
                    found.add(new Hit(m.toImmutable(), exposed));
                    if (found.size() >= maxBlocks.get()) break outer;
                }
            }
        }
        hits = found;
    }

    private boolean bedrockAbove(BlockPos pos) {
        if (above.get() == Above.Directo) return mc.world.getBlockState(pos.up()).isOf(Blocks.BEDROCK);
        for (int i = 1; i <= bedrockSearch.get(); i++) {
            if (mc.world.getBlockState(pos.up(i)).isOf(Blocks.BEDROCK)) return true;
        }
        return false;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        for (Hit hit : hits) {
            if (hit.exposed()) event.renderer.box(hit.pos(), exposedSide.get(), exposedLine.get(), shapeMode.get(), 0);
            else event.renderer.box(hit.pos(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
