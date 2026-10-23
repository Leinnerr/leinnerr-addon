package com.leinnerr.addon.schematic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class SchematicManager {
    private static final SchematicManager INSTANCE = new SchematicManager();

    private static final BlockRotation[] ROTATIONS = {
        BlockRotation.NONE, BlockRotation.CLOCKWISE_90, BlockRotation.CLOCKWISE_180, BlockRotation.COUNTERCLOCKWISE_90
    };

    private volatile Schematic schematic;
    private volatile BlockPos origin = BlockPos.ORIGIN;
    private volatile int rotation;
    private volatile BlockState[][] rotatedPalettes = new BlockState[4][];

    private volatile int version;

    private SchematicManager() {}

    public static SchematicManager get() {
        return INSTANCE;
    }


    public Path folder() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("leinnerr").resolve("schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    public List<String> listFiles() {
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder())) {
            s.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> {
                    String l = n.toLowerCase(Locale.ROOT);
                    return l.endsWith(".litematic") || l.endsWith(".schem") || l.endsWith(".nbt");
                })
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(names::add);
        } catch (IOException ignored) {
        }
        return names;
    }

    public Path resolve(String name) {
        Path folder = folder();
        for (String f : listFiles()) {
            String noExt = f.contains(".") ? f.substring(0, f.lastIndexOf('.')) : f;
            if (f.equalsIgnoreCase(name) || noExt.equalsIgnoreCase(name)) return folder.resolve(f);
        }
        return null;
    }


    public boolean hasSchematic() {
        return schematic != null;
    }

    public Schematic schematic() {
        return schematic;
    }

    public BlockPos origin() {
        return origin;
    }

    public int rotation() {
        return rotation;
    }

    public int version() {
        return version;
    }

    public void set(Schematic s, BlockPos newOrigin) {
        this.schematic = s;
        this.origin = newOrigin;
        this.rotation = 0;
        this.rotatedPalettes = new BlockState[4][];
        version++;
    }

    public void unload() {
        this.schematic = null;
        this.rotatedPalettes = new BlockState[4][];
        version++;
    }

    public void setOrigin(BlockPos pos) {
        this.origin = pos.toImmutable();
        version++;
    }

    public void move(int dx, int dy, int dz) {
        this.origin = origin.add(dx, dy, dz);
        version++;
    }

    public void rotate(int quarterTurns) {
        this.rotation = Math.floorMod(rotation + quarterTurns, 4);
        version++;
    }

    public int width() {
        Schematic s = schematic;
        if (s == null) return 0;
        return (rotation & 1) == 0 ? s.sx : s.sz;
    }

    public int height() {
        Schematic s = schematic;
        return s == null ? 0 : s.sy;
    }

    public int length() {
        Schematic s = schematic;
        if (s == null) return 0;
        return (rotation & 1) == 0 ? s.sz : s.sx;
    }

    public BlockState stateAt(int wx, int wy, int wz) {
        Schematic s = schematic;
        if (s == null) return null;

        int rx = wx - origin.getX();
        int ry = wy - origin.getY();
        int rz = wz - origin.getZ();
        if (ry < 0 || ry >= s.sy) return null;

        int lx, lz;
        switch (rotation) {
            case 1 -> { lz = s.sz - 1 - rx; lx = rz; }
            case 2 -> { lx = s.sx - 1 - rx; lz = s.sz - 1 - rz; }
            case 3 -> { lz = rx; lx = s.sx - 1 - rz; }
            default -> { lx = rx; lz = rz; }
        }

        int idx = s.indexAt(lx, ry, lz);
        if (idx < 0) return null;

        BlockState[] palette = rotatedPalette(s);
        return palette[idx];
    }

    private BlockState[] rotatedPalette(Schematic s) {
        BlockState[][] cache = rotatedPalettes;
        BlockState[] p = cache[rotation];
        if (p != null) return p;

        BlockState[] base = s.palette();
        p = new BlockState[base.length];
        BlockRotation rot = ROTATIONS[rotation];
        for (int i = 0; i < base.length; i++) {
            BlockState st = base[i];
            p[i] = st == null ? Blocks.AIR.getDefaultState() : st.rotate(rot);
        }
        cache[rotation] = p;
        return p;
    }
}
