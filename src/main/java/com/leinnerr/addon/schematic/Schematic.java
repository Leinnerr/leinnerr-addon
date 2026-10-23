package com.leinnerr.addon.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

public final class Schematic {
    public final String name;
    public final int sx, sy, sz;
    final BlockState[] palette;
    final int[] data;

    public final int blockCount;
    public final int unknownTypes;

    Schematic(String name, int sx, int sy, int sz, BlockState[] palette, int[] data, int unknownTypes) {
        this.name = name;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.palette = palette;
        this.data = data;
        this.unknownTypes = unknownTypes;

        boolean[] air = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) air[i] = palette[i].isAir();
        int n = 0;
        for (int idx : data) if (!air[idx]) n++;
        this.blockCount = n;
    }

    public BlockState[] palette() {
        return palette;
    }

    public int indexAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return -1;
        return data[x + z * sx + y * sx * sz];
    }

    public BlockState get(int x, int y, int z) {
        int i = indexAt(x, y, z);
        return i < 0 ? Blocks.AIR.getDefaultState() : palette[i];
    }
}
