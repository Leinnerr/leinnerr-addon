package com.leinnerr.addon.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class Nbt {
    private Nbt() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(Path file) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            raw.mark(2);
            int b1 = raw.read();
            int b2 = raw.read();
            raw.reset();

            boolean gzip = b1 == 0x1f && b2 == 0x8b;
            try (DataInputStream in = new DataInputStream(gzip ? new BufferedInputStream(new GZIPInputStream(raw), 1 << 16) : raw)) {
                int type = in.readUnsignedByte();
                if (type != 10) throw new IOException("El archivo no empieza con un compound NBT (tipo " + type + ")");
                in.readUTF();
                return (Map<String, Object>) readPayload(in, 10);
            }
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                int len = in.readInt();
                byte[] arr = new byte[len];
                in.readFully(arr);
                return arr;
            }
            case 8: return in.readUTF();
            case 9: {
                int elementType = in.readUnsignedByte();
                int len = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, len));
                for (int i = 0; i < len; i++) list.add(readPayload(in, elementType));
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = in.readUTF();
                    map.put(name, readPayload(in, t));
                }
                return map;
            }
            case 11: {
                int len = in.readInt();
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) arr[i] = in.readInt();
                return arr;
            }
            case 12: {
                int len = in.readInt();
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) arr[i] = in.readLong();
                return arr;
            }
            default:
                throw new IOException("Tipo NBT desconocido: " + type);
        }
    }


    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> map, String key) {
        Object o = map.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {
        Object o = map.get(key);
        return o instanceof List ? (List<Object>) o : null;
    }

    public static int integer(Map<String, Object> map, String key, int def) {
        Object o = map.get(key);
        return o instanceof Number n ? n.intValue() : def;
    }

    public static String string(Map<String, Object> map, String key) {
        Object o = map.get(key);
        return o instanceof String s ? s : null;
    }

    public static int toInt(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
