package com.leinnerr.addon.commands;

import com.leinnerr.addon.bots.BotManager;
import com.leinnerr.addon.modules.BotJoiner;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

public class BotsCommand extends Command {
    public BotsCommand() {
        super("bots", "Controla los bots del modulo bot-joiner");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("join").executes(ctx -> {
            Modules.get().get(BotJoiner.class).joinBots();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("leave")
            .executes(ctx -> {
                BotManager.get().leaveAll();
                return SINGLE_SUCCESS;
            })
            .then(argument("name", StringArgumentType.word()).executes(ctx -> {
                BotManager.get().leave(StringArgumentType.getString(ctx, "name"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("say")
            .then(argument("target", StringArgumentType.word())
                .then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                    String target = StringArgumentType.getString(ctx, "target");
                    String text = StringArgumentType.getString(ctx, "text");
                    BotManager manager = BotManager.get();

                    if (!target.equalsIgnoreCase("all") && !manager.has(target)) {
                        error("No hay ningun bot llamado %s. Mira .bots list", target);
                        return SINGLE_SUCCESS;
                    }
                    manager.say(target, text);
                    info("Enviando a %s: %s (el resultado sale en la consola de bots)", target, text);
                    return SINGLE_SUCCESS;
                }))));

        builder.then(literal("farm")
            .then(argument("target", StringArgumentType.word()).executes(ctx -> {
                Modules.get().get(BotJoiner.class).startFarm(StringArgumentType.getString(ctx, "target"));
                return SINGLE_SUCCESS;
            })));

        builder.then(literal("list").executes(ctx -> {
            List<String> names = BotManager.get().names();
            info(names.isEmpty() ? "No hay bots conectados, conectalos mierda" : "Bots: " + String.join(", ", names));
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("console").executes(ctx -> {
            Modules.get().get(BotJoiner.class).openConsole();
            return SINGLE_SUCCESS;
        }));
    }
}
