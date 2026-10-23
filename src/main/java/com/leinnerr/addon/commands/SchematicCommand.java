package com.leinnerr.addon.commands;

import com.leinnerr.addon.schematic.Schematic;
import com.leinnerr.addon.schematic.SchematicLoader;
import com.leinnerr.addon.schematic.SchematicManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class SchematicCommand extends Command {
    public SchematicCommand() {
        super("schematic", "Carga schematics (.litematic, .schem, .nbt) para el modulo schematic", "schem");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        SchematicManager mgr = SchematicManager.get();

        builder.then(literal("list").executes(ctx -> {
            List<String> files = mgr.listFiles();
            if (files.isEmpty()) {
                info("No hay schematics. Pon tus archivos en: %s", mgr.folder());
            } else {
                info("Schematics (%d):", files.size());
                for (String f : files) info(" - %s", f);
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("folder").executes(ctx -> {
            info("Carpeta de schematics: %s", mgr.folder());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("load").then(argument("name", StringArgumentType.greedyString())
            .suggests((ctx, b) -> CommandSource.suggestMatching(mgr.listFiles(), b))
            .executes(ctx -> {
                load(StringArgumentType.getString(ctx, "name").trim());
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("unload").executes(ctx -> {
            mgr.unload();
            info("Schematic descargado");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("origin")
            .executes(ctx -> {
                if (!requireLoaded() || mc.player == null) return SINGLE_SUCCESS;
                mgr.setOrigin(mc.player.getBlockPos());
                info("Origen: %s", fmt(mgr.origin()));
                return SINGLE_SUCCESS;
            })
            .then(literal("look").executes(ctx -> {
                if (!requireLoaded()) return SINGLE_SUCCESS;
                if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                    mgr.setOrigin(hit.getBlockPos().offset(hit.getSide()));
                    info("Origen: %s", fmt(mgr.origin()));
                } else {
                    error("No estas mirando ningun bloque");
                }
                return SINGLE_SUCCESS;
            }))
            .then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                    .then(argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                        if (!requireLoaded()) return SINGLE_SUCCESS;
                        mgr.setOrigin(new BlockPos(
                            IntegerArgumentType.getInteger(ctx, "x"),
                            IntegerArgumentType.getInteger(ctx, "y"),
                            IntegerArgumentType.getInteger(ctx, "z")));
                        info("Origen: %s", fmt(mgr.origin()));
                        return SINGLE_SUCCESS;
                    })))));

        builder.then(literal("move").then(argument("x", IntegerArgumentType.integer())
            .then(argument("y", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                    if (!requireLoaded()) return SINGLE_SUCCESS;
                    mgr.move(
                        IntegerArgumentType.getInteger(ctx, "x"),
                        IntegerArgumentType.getInteger(ctx, "y"),
                        IntegerArgumentType.getInteger(ctx, "z"));
                    info("Origen: %s", fmt(mgr.origin()));
                    return SINGLE_SUCCESS;
                })))));

        builder.then(literal("rotate")
            .executes(ctx -> {
                rotate(90);
                return SINGLE_SUCCESS;
            })
            .then(argument("degrees", IntegerArgumentType.integer()).executes(ctx -> {
                rotate(IntegerArgumentType.getInteger(ctx, "degrees"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("info").executes(ctx -> {
            Schematic s = mgr.schematic();
            if (s == null) {
                info("No hay ningun schematic cargado. Usa .schem load <nombre>.");
                return SINGLE_SUCCESS;
            }
            info("%s: %dx%dx%d, %d bloques", s.name, mgr.width(), mgr.height(), mgr.length(), s.blockCount);
            info("Origen: %s, rotacion: %d grados", fmt(mgr.origin()), mgr.rotation() * 90);
            if (s.unknownTypes > 0) info("ABIZO: %d tipos de bloque no existen en esta version y se ignoran", s.unknownTypes);
            return SINGLE_SUCCESS;
        }));
    }

    private boolean requireLoaded() {
        if (SchematicManager.get().hasSchematic()) return true;
        error("No hay ningun schematic cargado Usa .schem load <nombre>");
        return false;
    }

    private void rotate(int degrees) {
        if (!requireLoaded()) return;
        if (degrees % 90 != 0) {
            error("Los grados tienen que ser multiplo de 90");
            return;
        }
        SchematicManager.get().rotate(degrees / 90);
        info("Rotacion: %d grados", SchematicManager.get().rotation() * 90);
    }

    private void load(String name) {
        SchematicManager mgr = SchematicManager.get();

        Path path = mgr.resolve(name);
        if (path == null) {
            error("No encontre '%s'. Usa .schem list (carpeta: %s)", name, mgr.folder());
            return;
        }

        BlockPos at = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
        info("Cargando %s...", path.getFileName());

        Thread thread = new Thread(() -> {
            try {
                Schematic s = SchematicLoader.load(path);
                mc.execute(() -> {
                    mgr.set(s, at);
                    info("Cargado '%s': %dx%dx%d, %d bloques. Origen en %s",
                        s.name, s.sx, s.sy, s.sz, s.blockCount, fmt(at));
                    if (s.unknownTypes > 0) info("Aviso: %d tipos de bloque no existen en esta version y se ignoran", s.unknownTypes);
                    info("Activa el modulo schematic y marca render (ver) y/o place (construir)");
                });
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                mc.execute(() -> error("No se pudo cargar el schematic: %s", msg));
            }
        }, "leinnerr-schematic-loader");
        thread.setDaemon(true);
        thread.start();
    }

    private static String fmt(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }
}
