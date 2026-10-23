package com.leinnerr.addon.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SchematicLoader {
    private static final long MAX_VOLUME = 120_000_000L;

    private SchematicLoader() {}

    public static Schematic load(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        String name = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        if (lower.endsWith(".schematic")) {
            throw new IOException("El formato es incumpatible, exportalo como .litematic o .schem");
        }

        Map<String, Object> root = Nbt.read(file);

        if (lower.endsWith(".litematic")) return litematic(name, root);
        if (lower.endsWith(".schem")) return sponge(name, root);
        if (lower.endsWith(".nbt")) return structure(name, root);

        throw new IOException("Formato no soportado: " + fileName + " usa .litematic, .schem o .nbt");
    }


    private static final class Builder {
        final List<BlockState> palette = new ArrayList<>();
        final Map<BlockState, Integer> index = new HashMap<>();
        final Set<String> unknown = new LinkedHashSet<>();

        Builder() {
            add(Blocks.AIR.getDefaultState());
        }

        int add(BlockState state) {
            Integer i = index.get(state);
            if (i != null) return i;
            int n = palette.size();
            palette.add(state);
            index.put(state, n);
            return n;
        }

        int parseAndAdd(String blockName, Map<String, String> props) {
            return add(parseState(blockName, props, unknown));
        }

        Schematic build(String name, int sx, int sy, int sz, int[] data) {
            return new Schematic(name, sx, sy, sz, palette.toArray(new BlockState[0]), data, unknown.size());
        }
    }

    private static void checkVolume(long sx, long sy, long sz) throws IOException {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IOException("El schematic esta vacio");
        if (sx * sy * sz > MAX_VOLUME) throw new IOException("El schematic es demasiado grande (" + sx + "x" + sy + "x" + sz + ").");
    }


    private static Schematic litematic(String name, Map<String, Object> root) throws IOException {
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        if (regions == null || regions.isEmpty()) throw new IOException("El litematic no tiene regiones");

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Object o : regions.values()) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> region = (Map<String, Object>) o;
            int[] box = regionBox(region);
            minX = Math.min(minX, box[0]);
            minY = Math.min(minY, box[1]);
            minZ = Math.min(minZ, box[2]);
            maxX = Math.max(maxX, box[0] + box[3]);
            maxY = Math.max(maxY, box[1] + box[4]);
            maxZ = Math.max(maxZ, box[2] + box[5]);
        }

        int sx = maxX - minX, sy = maxY - minY, sz = maxZ - minZ;
        checkVolume(sx, sy, sz);

        int[] data = new int[sx * sy * sz];
        Builder b = new Builder();

        for (Object o : regions.values()) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> region = (Map<String, Object>) o;

            int[] box = regionBox(region);
            int ax = box[3], ay = box[4], az = box[5];

            List<Object> paletteTag = Nbt.list(region, "BlockStatePalette");
            Object statesTag = region.get("BlockStates");
            if (paletteTag == null || !(statesTag instanceof long[] states)) continue;

            int[] map = new int[paletteTag.size()];
            for (int i = 0; i < map.length; i++) {
                map[i] = paletteEntry(paletteTag.get(i), b);
            }

            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, map.length - 1)));
            long mask = (1L << bits) - 1L;

            long totalBits = (long) ax * ay * az * bits;
            if ((totalBits + 63) / 64 > states.length) throw new IOException("Los datos de bloques de la region estan atoradosxd");

            int offX = box[0] - minX, offY = box[1] - minY, offZ = box[2] - minZ;

            for (int y = 0; y < ay; y++) {
                for (int z = 0; z < az; z++) {
                    for (int x = 0; x < ax; x++) {
                        long index = ((long) y * az + z) * ax + x;
                        int v = (int) readPacked(states, index, bits, mask);
                        if (v >= map.length) v = 0;

                        int g = map[v];
                        if (g != 0) data[(offX + x) + (offZ + z) * sx + (offY + y) * sx * sz] = g;
                    }
                }
            }
        }

        return b.build(name, sx, sy, sz, data);
    }

    private static int[] regionBox(Map<String, Object> region) throws IOException {
        Map<String, Object> pos = Nbt.compound(region, "Position");
        Map<String, Object> size = Nbt.compound(region, "Size");
        if (pos == null || size == null) throw new IOException("Una region del litematic no tiene Position/Size");

        int px = Nbt.integer(pos, "x", 0), py = Nbt.integer(pos, "y", 0), pz = Nbt.integer(pos, "z", 0);
        int rx = Nbt.integer(size, "x", 0), ry = Nbt.integer(size, "y", 0), rz = Nbt.integer(size, "z", 0);

        int x0 = rx < 0 ? px + rx + 1 : px;
        int y0 = ry < 0 ? py + ry + 1 : py;
        int z0 = rz < 0 ? pz + rz + 1 : pz;
        return new int[]{x0, y0, z0, Math.abs(rx), Math.abs(ry), Math.abs(rz)};
    }

    private static long readPacked(long[] arr, long index, int bits, long mask) {
        long startBit = index * bits;
        int startArr = (int) (startBit >>> 6);
        int endArr = (int) (((index + 1L) * bits - 1L) >>> 6);
        int startOffset = (int) (startBit & 63L);

        if (startArr == endArr) return (arr[startArr] >>> startOffset) & mask;

        int endOffset = 64 - startOffset;
        return ((arr[startArr] >>> startOffset) | (arr[endArr] << endOffset)) & mask;
    }

    @SuppressWarnings("unchecked")
    private static int paletteEntry(Object entry, Builder b) {
        if (!(entry instanceof Map)) return 0;
        Map<String, Object> tag = (Map<String, Object>) entry;

        String blockName = Nbt.string(tag, "Name");
        if (blockName == null) return 0;

        Map<String, String> props = new LinkedHashMap<>();
        Map<String, Object> propTag = Nbt.compound(tag, "Properties");
        if (propTag != null) {
            for (Map.Entry<String, Object> e : propTag.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return b.parseAndAdd(blockName, props);
    }


    private static Schematic sponge(String name, Map<String, Object> root) throws IOException {
        Map<String, Object> v3 = Nbt.compound(root, "Schematic");
        Map<String, Object> m = v3 != null ? v3 : root;

        int sx = Nbt.integer(m, "Width", 0), sy = Nbt.integer(m, "Height", 0), sz = Nbt.integer(m, "Length", 0);
        checkVolume(sx, sy, sz);

        Map<String, Object> paletteTag;
        Object blockData;
        if (v3 != null) {
            Map<String, Object> blocks = Nbt.compound(m, "Blocks");
            if (blocks == null) throw new IOException("El .schem no tiene la seccion Blocks");
            paletteTag = Nbt.compound(blocks, "Palette");
            blockData = blocks.get("Data");
        } else {
            paletteTag = Nbt.compound(m, "Palette");
            blockData = m.get("BlockData");
        }
        if (paletteTag == null || !(blockData instanceof byte[] bytes)) throw new IOException("El schem no tiene los datos de bloques");

        Builder b = new Builder();
        int max = 0;
        for (Object v : paletteTag.values()) max = Math.max(max, Nbt.toInt(v));
        int[] map = new int[max + 1];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            map[Nbt.toInt(e.getValue())] = parseStateString(e.getKey(), b);
        }

        int[] data = new int[sx * sy * sz];
        int pos = 0;
        for (int i = 0; i < data.length; i++) {
            int value = 0, shift = 0;
            while (true) {
                if (pos >= bytes.length) throw new IOException("Los datos del schem estan atoradosxd");
                int cur = bytes[pos++] & 0xFF;
                value |= (cur & 0x7F) << shift;
                if ((cur & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) throw new IOException("Varint invalido en el .schem");
            }
            data[i] = value < map.length ? map[value] : 0;
        }

        return b.build(name, sx, sy, sz, data);
    }

    private static int parseStateString(String s, Builder b) {
        String blockName = s;
        Map<String, String> props = new LinkedHashMap<>();

        int bracket = s.indexOf('[');
        if (bracket >= 0 && s.endsWith("]")) {
            blockName = s.substring(0, bracket);
            String inner = s.substring(bracket + 1, s.length() - 1);
            if (!inner.isEmpty()) {
                for (String pair : inner.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
        return b.parseAndAdd(blockName, props);
    }


    @SuppressWarnings("unchecked")
    private static Schematic structure(String name, Map<String, Object> root) throws IOException {
        List<Object> size = Nbt.list(root, "size");
        if (size == null || size.size() < 3) throw new IOException("El .nbt no tiene size");

        int sx = Nbt.toInt(size.get(0)), sy = Nbt.toInt(size.get(1)), sz = Nbt.toInt(size.get(2));
        checkVolume(sx, sy, sz);

        List<Object> paletteTag = Nbt.list(root, "palette");
        if (paletteTag == null) {
            List<Object> palettes = Nbt.list(root, "palettes");
            if (palettes != null && !palettes.isEmpty() && palettes.get(0) instanceof List) paletteTag = (List<Object>) palettes.get(0);
        }
        List<Object> blocks = Nbt.list(root, "blocks");
        if (paletteTag == null || blocks == null) throw new IOException("El .nbt no tiene palette o blocks");

        Builder b = new Builder();
        int[] map = new int[paletteTag.size()];
        for (int i = 0; i < map.length; i++) map[i] = paletteEntry(paletteTag.get(i), b);

        int[] data = new int[sx * sy * sz];
        for (Object o : blocks) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> block = (Map<String, Object>) o;

            List<Object> p = Nbt.list(block, "pos");
            if (p == null || p.size() < 3) continue;

            int x = Nbt.toInt(p.get(0)), y = Nbt.toInt(p.get(1)), z = Nbt.toInt(p.get(2));
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) continue;

            int state = Nbt.integer(block, "state", 0);
            data[x + z * sx + y * sx * sz] = state >= 0 && state < map.length ? map[state] : 0;
        }

        return b.build(name, sx, sy, sz, data);
    }


    static BlockState parseState(String blockName, Map<String, String> props, Set<String> unknown) {
        Identifier id = Identifier.tryParse(blockName);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            unknown.add(blockName);
            return Blocks.AIR.getDefaultState();
        }

        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();

        for (Map.Entry<String, String> e : props.entrySet()) {
            Property<?> property = block.getStateManager().getProperty(e.getKey());
            if (property != null) state = withValue(state, property, e.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.parse(value);
        return parsed.isPresent() ? state.with(property, parsed.get()) : state;
    }
}
