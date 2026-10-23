package com.leinnerr.addon.bots;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Locale;
import java.util.Optional;

final class BotProtocol {
    private BotProtocol() {
    }

    static Session newSession(String host, int port, String username) {
        MinecraftProtocol protocol = new MinecraftProtocol(username);
        try {
            try {
                Class<?> factoryClass = Class.forName("org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory");
                Object factory = factoryClass.getMethod("factory").invoke(null);
                factory = callFluent(factory, "setRemoteSocketAddress", new InetSocketAddress(host, port));
                factory = callFluent(factory, "setProtocol", protocol);
                return (Session) factory.getClass().getMethod("create").invoke(factory);
            } catch (ClassNotFoundException ignored) {
                
            }

            Class<?> tcp = Class.forName("org.geysermc.mcprotocollib.network.tcp.TcpClientSession");
            for (Constructor<?> c : tcp.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[0] == String.class && p[1] == int.class) {
                    return (Session) c.newInstance(host, port, protocol);
                }
            }
            throw new IllegalStateException("No encontre como crear una sesion en esta version de MCProtocolLib");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("No pude crear la sesion: " + e, e);
        }
    }

    private static Object callFluent(Object target, String name, Object arg) throws ReflectiveOperationException {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(arg)) {
                Object result = m.invoke(target, arg);
                return result != null ? result : target;
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    static void connect(Session session) {
        Method best = null;
        for (Class<?> type : new Class<?>[]{Session.class, session.getClass()}) {
            for (Method m : type.getMethods()) {
                if (!m.getName().equals("connect")) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean allBool = true;
                for (Class<?> c : p) allBool &= (c == boolean.class);
                if (!allBool) continue;
                if (best == null || p.length < best.getParameterCount()) best = m;
            }
            if (best != null) break;
        }
        if (best == null) throw new IllegalStateException("sesion no tiene ninun metodo conectado utilizable");

        Object[] args = new Object[best.getParameterCount()];
        if (args.length >= 1) args[0] = Boolean.TRUE;
        if (args.length >= 2) args[1] = Boolean.FALSE;
        try {
            best.setAccessible(true);
            best.invoke(session, args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("connect() fallo: " + cause, cause);
        }
    }

    static void close(Session session) {
        session.disconnect("Saliendo");
    }

    static Packet message(String text) {
        if (text.startsWith("/")) return new ServerboundChatCommandPacket(text.substring(1));
        return chat(text);
    }

    private static Packet chat(String text) {
        Constructor<?> chosen = null;
        for (Constructor<?> c : ServerboundChatPacket.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 1 && p[0] == String.class
                && (chosen == null || p.length > chosen.getParameterCount())) {
                chosen = c;
            }
        }
        if (chosen == null) throw new IllegalStateException("ServerboundChatPacket: no hay constructor que empiece por String");

        Class<?>[] types = chosen.getParameterTypes();
        boolean hasInstant = Arrays.asList(types).contains(Instant.class);
        boolean firstLong = true;
        Object[] args = new Object[types.length];

        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == String.class) args[i] = i == 0 ? text : "";
            else if (t == Instant.class) args[i] = Instant.now();
            else if (t == long.class || t == Long.class) {
                args[i] = (!hasInstant && firstLong) ? System.currentTimeMillis() : 0L;
                firstLong = false;
            }
            else if (t == int.class || t == Integer.class) args[i] = 0;
            else if (t == byte.class || t == Byte.class) args[i] = (byte) 0;
            else if (t == short.class || t == Short.class) args[i] = (short) 0;
            else if (t == boolean.class || t == Boolean.class) args[i] = false;
            else if (t == BitSet.class) args[i] = new BitSet();
            else if (t == Optional.class) args[i] = Optional.empty();
            else args[i] = null;
        }

        try {
            return (Packet) chosen.newInstance(args);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new IllegalStateException("ServerboundChatPacket" + Arrays.toString(types) + " -> " + e, e);
        }
    }

    static Packet selectHotbar(int slot) {
        String[] candidates = {
            "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket",
            "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCarriedItemPacket",
        };
        for (String name : candidates) {
            try {
                return (Packet) Class.forName(name).getConstructor(int.class).newInstance(slot);
            } catch (ClassNotFoundException ignored) {
                
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("No pude crear " + name + ": " + e, e);
            }
        }
        throw new IllegalStateException("No encontre ServerboundSetCarriedItemPacket en esta version");
    }

    static Packet respawn() {
        String[] candidates = {
            "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket",
            "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundClientCommandPacket",
        };
        for (String name : candidates) {
            try {
                Class<?> cls = Class.forName(name);
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length != 1 || !p[0].isEnum()) continue;
                    for (Object constant : p[0].getEnumConstants()) {
                        if (((Enum<?>) constant).name().toUpperCase(java.util.Locale.ROOT).contains("RESPAWN")) {
                            return (Packet) c.newInstance(constant);
                        }
                    }
                }
            } catch (ClassNotFoundException ignored) {
                
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("No pude crear " + name + ": " + e, e);
            }
        }
        throw new IllegalStateException("No encontre ServerboundClientCommandPacket (respawn) en esta version");
    }

    static boolean isDeath(Packet packet) {
        String name = packet.getClass().getSimpleName();
        if (name.equals("ClientboundPlayerCombatKillPacket")) return true;
        if (name.equals("ClientboundSetHealthPacket")) {
            try {
                Object health = packet.getClass().getMethod("getHealth").invoke(packet);
                return health instanceof Number n && n.floatValue() <= 0f;
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
        return false;
    }

    static Packet useItem(int sequence) {
        return new ServerboundUseItemPacket(Hand.MAIN_HAND, sequence, 0f, 0f);
    }

    static Packet clickSlot(int containerId, int stateId, int slot) {
        return new ServerboundContainerClickPacket(
            containerId, stateId, slot,
            ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK,
            null, new Int2ObjectOpenHashMap<>());
    }


    private static final String SB = "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.";

    private static final java.util.Map<String, Class<?>> SCAN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Class<?> findClass(String... names) {
        for (String n : names) {
            try {
                return Class.forName(n);
            } catch (ClassNotFoundException ignored) {
                
            }
        }
        if (names.length == 0) return null;
        String first = names[0];
        return scanForSimpleName(first.substring(first.lastIndexOf('.') + 1));
    }

    private static Class<?> scanForSimpleName(String simple) {
        Class<?> cached = SCAN_CACHE.get(simple);
        if (cached != null) return cached;
        try {
            java.security.CodeSource source = ServerboundChatPacket.class.getProtectionDomain().getCodeSource();
            if (source == null) return null;
            java.io.File file = new java.io.File(source.getLocation().toURI());
            if (!file.isFile()) return null;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (entry.startsWith("org/geysermc/mcprotocollib/") && entry.endsWith("/" + simple + ".class")) {
                        Class<?> found = Class.forName(entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
                        SCAN_CACHE.put(simple, found);
                        return found;
                    }
                }
            }
        } catch (Exception ignored) {
            
        }
        return null;
    }

    private static int intGetter(Packet packet, String method) {
        try {
            Object v = packet.getClass().getMethod(method).invoke(packet);
            return v instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    static boolean isTeleportSync(Packet packet) {
        return packet.getClass().getSimpleName().equals("ClientboundPlayerPositionPacket");
    }

    static int teleportId(Packet packet) {
        for (String m : new String[]{"getTeleportId", "getId", "getTeleportID"}) {
            int v = intGetter(packet, m);
            if (v >= 0) return v;
        }
        for (Method m : packet.getClass().getMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            if (m.getParameterCount() != 0 || m.getReturnType() != int.class) continue;
            if (!n.contains("id") || n.contains("entity")) continue;
            try {
                Object v = m.invoke(packet);
                if (v instanceof Number num && num.intValue() >= 0) return num.intValue();
            } catch (ReflectiveOperationException ignored) {
                
            }
        }
        return -1;
    }

    static Packet playerLoaded() {
        Class<?> cls = findClass(
            SB + "player.ServerboundPlayerLoadedPacket",
            SB + "level.ServerboundPlayerLoadedPacket",
            SB + "ServerboundPlayerLoadedPacket");
        if (cls == null) return null;
        try {
            return (Packet) cls.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static int entityId(Packet loginPacket) {
        return intGetter(loginPacket, "getEntityId");
    }

    static Packet acceptTeleport(int teleportId) {
        Class<?> cls = findClass(
            SB + "level.ServerboundAcceptTeleportationPacket",
            SB + "player.ServerboundAcceptTeleportationPacket",
            SB + "ServerboundAcceptTeleportationPacket");
        if (cls == null) return null;
        try {
            return (Packet) cls.getConstructor(int.class).newInstance(teleportId);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static Packet stopSleeping(int entityId) {
        if (entityId < 0) return null;
        Class<?> cls = findClass(SB + "player.ServerboundPlayerCommandPacket", SB + "ServerboundPlayerCommandPacket");
        if (cls == null) return null;
        try {
            for (Constructor<?> c : cls.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length < 2 || p[0] != int.class || !p[1].isEnum()) continue;
                Object state = null;
                for (Object k : p[1].getEnumConstants()) {
                    if (((Enum<?>) k).name().toUpperCase(Locale.ROOT).contains("STOP_SLEEPING")) state = k;
                }
                if (state == null) continue;
                Object[] args = new Object[p.length];
                args[0] = entityId;
                args[1] = state;
                for (int i = 2; i < p.length; i++) args[i] = defaultFor(p[i]);
                return (Packet) c.newInstance(args);
            }
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    static Packet useBlock(int x, int y, int z, int sequence) {
        Class<?> cls = findClass(SB + "level.ServerboundUseItemOnPacket", SB + "player.ServerboundUseItemOnPacket");
        if (cls == null) throw new IllegalStateException("No encontre ServerboundUseItemOnPacket en esta version.");

        Constructor<?> chosen = null;
        for (Constructor<?> c : cls.getConstructors()) {
            if (chosen == null || c.getParameterCount() > chosen.getParameterCount()) chosen = c;
        }
        if (chosen == null) throw new IllegalStateException("ServerboundUseItemOnPacket sin constructores publicos.");

        Class<?>[] types = chosen.getParameterTypes();
        Object[] args = new Object[types.length];
        try {
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t.isEnum()) {
                    String want = t.getSimpleName().toLowerCase(Locale.ROOT).contains("hand") ? "MAIN_HAND" : "UP";
                    Object pick = t.getEnumConstants()[0];
                    for (Object k : t.getEnumConstants()) {
                        if (((Enum<?>) k).name().equalsIgnoreCase(want)) pick = k;
                    }
                    args[i] = pick;
                } else if (t == float.class) {
                    args[i] = 0.5f;
                } else if (t == int.class) {
                    args[i] = sequence;
                } else if (t == boolean.class) {
                    args[i] = false;
                } else {
                    args[i] = makePosition(t, x, y, z);
                }
            }
            return (Packet) chosen.newInstance(args);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            throw new IllegalStateException("ServerboundUseItemOnPacket" + Arrays.toString(types) + " -> " + e, e);
        }
    }

    private static Object makePosition(Class<?> type, int x, int y, int z) throws ReflectiveOperationException {
        try {
            return type.getMethod("from", int.class, int.class, int.class).invoke(null, x, y, z);
        } catch (NoSuchMethodException e) {
            return type.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
        }
    }

    private static Object defaultFor(Class<?> t) {
        if (t == int.class) return 0;
        if (t == boolean.class) return false;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == long.class) return 0L;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        return null;
    }

    private static volatile Constructor<?> tickEndCtor;
    private static volatile boolean tickEndSearched;

    static Packet clientTickEnd() {
        if (!tickEndSearched) {
            synchronized (BotProtocol.class) {
                if (!tickEndSearched) {
                    Class<?> cls = findClass(
                        SB + "ServerboundClientTickEndPacket",
                        SB + "level.ServerboundClientTickEndPacket",
                        SB + "player.ServerboundClientTickEndPacket");
                    try {
                        if (cls != null) tickEndCtor = cls.getConstructor();
                    } catch (ReflectiveOperationException ignored) {
                        tickEndCtor = null;
                    }
                    tickEndSearched = true;
                }
            }
        }
        if (tickEndCtor == null) return null;
        try {
            return (Packet) tickEndCtor.newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static Packet movePosRot(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        return buildMove("ServerboundMovePlayerPosRotPacket", onGround, x, y, z, yaw, pitch);
    }

    static Packet movePos(double x, double y, double z, boolean onGround) {
        return buildMove("ServerboundMovePlayerPosPacket", onGround, x, y, z, 0f, 0f);
    }

    private static Packet buildMove(String simple, boolean onGround, double x, double y, double z, float yaw, float pitch) {
        Class<?> cls = findClass(SB + "player." + simple, SB + "level." + simple, SB + simple);
        if (cls == null) return null;
        try {
            Constructor<?> chosen = null;
            for (Constructor<?> c : cls.getConstructors()) {
                if (chosen == null || c.getParameterCount() > chosen.getParameterCount()) chosen = c;
            }
            if (chosen == null) return null;

            Class<?>[] t = chosen.getParameterTypes();
            Object[] args = new Object[t.length];
            double[] ds = {x, y, z};
            float[] fs = {yaw, pitch};
            int d = 0;
            int f = 0;
            boolean firstBool = true;
            for (int i = 0; i < t.length; i++) {
                if (t[i] == boolean.class) {
                    args[i] = firstBool && onGround;
                    firstBool = false;
                } else if (t[i] == double.class && d < 3) {
                    args[i] = ds[d++];
                } else if (t[i] == float.class && f < 2) {
                    args[i] = fs[f++];
                } else {
                    args[i] = defaultFor(t[i]);
                }
            }
            return (Packet) chosen.newInstance(args);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            return null;
        }
    }

    static double[] teleportPosition(Packet packet) {
        try {
            try {
                Object relatives = packet.getClass().getMethod("getRelatives").invoke(packet);
                if (relatives instanceof java.util.Collection<?> c && !c.isEmpty()) return null;
            } catch (NoSuchMethodException ignored) {
                
            }

            Object source = packet;
            try {
                source = packet.getClass().getMethod("getPosition").invoke(packet);
            } catch (NoSuchMethodException ignored) {
            }

            double x = number(source, Double.NaN, "getX", "x");
            double y = number(source, Double.NaN, "getY", "y");
            double z = number(source, Double.NaN, "getZ", "z");
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) return null;

            double yaw = number(packet, 0, "getYRot", "getYaw");
            double pitch = number(packet, 0, "getXRot", "getPitch");
            return new double[]{x, y, z, yaw, pitch};
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static double number(Object target, double fallback, String... methods) {
        for (String m : methods) {
            try {
                Object v = target.getClass().getMethod(m).invoke(target);
                if (v instanceof Number n) return n.doubleValue();
            } catch (ReflectiveOperationException | RuntimeException ignored) {

            }
        }
        return fallback;
    }

    static String plain(Component component) {
        if (component == null) return "";
        StringBuilder sb = new StringBuilder();
        append(component, sb);
        return sb.toString();
    }

    /** la misma cosa q {@link #plain(Component)} pero acepta Componentes Stringo o otro objet */
    static String plainAny(Object reason) {
        if (reason instanceof Component c) return plain(c);
        return String.valueOf(reason);
    }

    private static void append(Component c, StringBuilder sb) {
        if (c instanceof TextComponent t) {
            sb.append(t.content());
        } else if (c instanceof TranslatableComponent tr) {
            String key = tr.key();
            java.util.List<Component> args = tr.args();
            if (key.contains("%")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("%(?:(\\d+)\\$)?s|%%").matcher(key);
                int last = 0;
                int next = 0;
                while (m.find()) {
                    sb.append(key, last, m.start());
                    if (m.group().equals("%%")) {
                        sb.append('%');
                    } else {
                        int idx = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++;
                        if (idx >= 0 && idx < args.size()) append(args.get(idx), sb);
                    }
                    last = m.end();
                }
                sb.append(key, last, key.length());
            } else {
                sb.append(key);
                for (Component arg : args) {
                    sb.append(' ');
                    append(arg, sb);
                }
            }
        }
        for (Component child : c.children()) append(child, sb);
    }
}
