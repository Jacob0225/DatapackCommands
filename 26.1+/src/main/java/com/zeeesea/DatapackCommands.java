package com.zeeesea;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.storage.LevelResource;
import java.lang.reflect.Field;
import java.util.Map;

public class DatapackCommands implements ModInitializer {
    public static final String MOD_ID = "datapackcommands";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);
    private static CommandManager commandManager;
    private static MinecraftServer cachedServer;
    private static com.mojang.brigadier.CommandDispatcher<CommandSourceStack> cachedDispatcher;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            cachedServer = server;
            java.io.File worldDir = server.getWorldPath(LevelResource.ROOT).toFile();
            commandManager = new CommandManager(worldDir);
            LOGGER.info("[CGEN DEBUG] Server started, loading commands from world folder");
            registerAllCustomCommands(cachedDispatcher);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            commandManager = null;
            cachedServer = null;
            LOGGER.info("[CGEN DEBUG] Server stopped, cache cleared");
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            cachedDispatcher = dispatcher;
            registerCgenCommand(dispatcher);
            if (commandManager != null) {
                LOGGER.info("[CGEN DEBUG] Re-registering custom commands after reload");
                registerAllCustomCommands(dispatcher);
            }
        });

        LOGGER.info("DatapackCommands initialized!");
    }

    private void registerCgenCommand(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                net.minecraft.commands.Commands.literal("cgen")
                        .then(net.minecraft.commands.Commands.literal("create")
                                .then(net.minecraft.commands.Commands.argument("\"<command>\" <namespace:function>",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String raw = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "\"<command>\" <namespace:function>").trim();
                                            CommandSourceStack source = ctx.getSource();

                                            String cmdPath;
                                            String func;

                                            if (raw.startsWith("\"")) {
                                                int closing = raw.indexOf('"', 1);
                                                if (closing == -1) {
                                                    source.sendFailure(Component.literal("Missing closing quote in command name."));
                                                    return 0;
                                                }
                                                cmdPath = raw.substring(1, closing).trim();
                                                func = raw.substring(closing + 1).trim();
                                            } else {
                                                int lastSpace = raw.lastIndexOf(' ');
                                                if (lastSpace == -1) {
                                                    source.sendFailure(Component.literal("Usage: /cgen create \"<command> [subcommand]\" <namespace:function>"));
                                                    return 0;
                                                }
                                                cmdPath = raw.substring(0, lastSpace).trim();
                                                func = raw.substring(lastSpace + 1).trim();
                                            }

                                            if (cmdPath.isEmpty() || func.isEmpty()) {
                                                source.sendFailure(Component.literal("Usage: /cgen create \"<command> [subcommand]\" <namespace:function>"));
                                                return 0;
                                            }

                                            LOGGER.info("[CGEN DEBUG] Creating command /{} -> {}", cmdPath, func);

                                            if (!commandManager.registerCommand(cmdPath, func)) {
                                                source.sendFailure(Component.literal("Command already exists: /" + cmdPath));
                                                return 0;
                                            }

                                            registerCustomCommand(cachedDispatcher, cmdPath, func);
                                            source.sendSuccess(() -> Component.literal("Created command /" + cmdPath + " -> " + func), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(net.minecraft.commands.Commands.literal("remove")
                                .then(net.minecraft.commands.Commands.argument("commandname",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> {
                                            commandManager.getCommands().keySet().forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String cmd = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "commandname").trim();
                                            LOGGER.info("[CGEN DEBUG] remove: cmd='{}'", cmd);

                                            if (!commandManager.removeCommand(cmd)) {
                                                ctx.getSource().sendFailure(Component.literal("Command not found: /" + cmd));
                                                return 0;
                                            }

                                            unregisterCommand(dispatcher, cmd.split(" ")[0]);
                                            ctx.getSource().sendSuccess(() -> Component.literal("Command /" + cmd + " removed!"), true);
                                            return 1;
                                        })
                                )
                        )
                        .then(net.minecraft.commands.Commands.literal("list")
                                .executes(ctx -> {
                                    var commands = commandManager.getCommands();
                                    if (commands.isEmpty()) {
                                        ctx.getSource().sendSuccess(() -> Component.literal("No commands registered."), false);
                                        return 1;
                                    }
                                    MutableComponent msg = Component.literal("Registered commands:\n");
                                    commands.forEach((k, v) -> {
                                        MutableComponent entry = Component.literal("  /" + k);
                                        MutableComponent rest = Component.literal(" -> " + v + "\n");
                                        msg.append(entry).append(rest);
                                    });
                                    ctx.getSource().sendSuccess(() -> msg, false);
                                    return 1;
                                })
                        )
                        .then(net.minecraft.commands.Commands.literal("feedback")
                                .then(net.minecraft.commands.Commands.literal("on")
                                        .executes(ctx -> {
                                            commandManager.setFeedback(true);
                                            ctx.getSource().sendSuccess(() -> Component.literal("Command feedback enabled."), true);
                                            return 1;
                                        })
                                )
                                .then(net.minecraft.commands.Commands.literal("off")
                                        .executes(ctx -> {
                                            commandManager.setFeedback(false);
                                            ctx.getSource().sendSuccess(() -> Component.literal("Command feedback disabled."), true);
                                            return 1;
                                        })
                                )
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Feedback is currently: " + (commandManager.isFeedbackEnabled() ? "ON" : "OFF")
                                    ), false);
                                    return 1;
                                })
                        )
                        .then(net.minecraft.commands.Commands.literal("check")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(() -> Component.literal("1"), false);
                                    return 1;
                                })
                        )
        );
    }

    private void registerAllCustomCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher == null || commandManager == null) return;
        LOGGER.info("[CGEN DEBUG] Auto-registering {} saved commands", commandManager.getCommands().size());
        commandManager.getCommands().forEach((cmd, func) -> registerCustomCommand(dispatcher, cmd, func));
    }

    private void registerCustomCommand(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, String cmdPath, String func) {
        if (dispatcher == null) return;
        LOGGER.info("[CGEN DEBUG] Registering: /{} -> {}", cmdPath, func);
        String[] tokens = cmdPath.split(" ");

        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> innermost =
                net.minecraft.commands.Commands.literal(tokens[tokens.length - 1])
                        .requires(source -> true)
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            CommandSourceStack source = ctx.getSource();
                            String execCmd = "function " + func;

                            // Elevate to server-level permissions so /function always succeeds,
                            // keeping the player's world/position context.
                            CommandSourceStack elevated = server.createCommandSourceStack()
                                    .withLevel((ServerLevel) source.getLevel())
                                    .withPosition(source.getPosition())
                                    .withRotation(source.getRotation());
                            CommandSourceStack execSource = commandManager.isFeedbackEnabled()
                                    ? elevated : elevated.withSuppressedOutput();

                            LOGGER.info("[CGEN DEBUG] Executing /{} -> {} as '{}'", cmdPath, func, source.getDisplayName().getString());

                            com.mojang.brigadier.ParseResults<CommandSourceStack> parseResults =
                                    server.getCommands().getDispatcher().parse(execCmd, execSource);
                            server.getCommands().performCommand(parseResults, execCmd);

                            if (commandManager.isFeedbackEnabled()) {
                                source.sendSuccess(() -> Component.literal("Executed function: " + func), false);
                            }
                            return 1;
                        });

        if (tokens.length == 1) {
            dispatcher.register(innermost);
        } else {
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> node = innermost;
            for (int i = tokens.length - 2; i >= 1; i--) {
                node = net.minecraft.commands.Commands.literal(tokens[i])
                        .requires(source -> true)
                        .then(node);
            }
            dispatcher.register(
                    net.minecraft.commands.Commands.literal(tokens[0])
                            .requires(source -> true)
                            .then(node)
            );
        }
        syncCommandsToAllPlayers();
    }

    @SuppressWarnings("unchecked")
    private void unregisterCommand(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher, String cmd) {
        if (dispatcher == null) return;
        try {
            com.mojang.brigadier.tree.RootCommandNode<CommandSourceStack> root = dispatcher.getRoot();
            Field childrenField = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("children");
            Field literalsField = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("literals");
            Field argumentsField = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("arguments");

            childrenField.setAccessible(true);
            literalsField.setAccessible(true);
            argumentsField.setAccessible(true);

            ((Map<String, ?>) childrenField.get(root)).remove(cmd);
            ((Map<String, ?>) literalsField.get(root)).remove(cmd);
            ((Map<String, ?>) argumentsField.get(root)).remove(cmd);

            LOGGER.info("[CGEN DEBUG] Unregistered /{} from dispatcher via reflection", cmd);
        } catch (Exception e) {
            LOGGER.error("[CGEN DEBUG] Failed to unregister /{} from dispatcher: {}", cmd, e.getMessage());
        }
        syncCommandsToAllPlayers();
    }

    private void syncCommandsToAllPlayers() {
        if (cachedServer == null) {
            LOGGER.info("[CGEN DEBUG] Sync skipped — server not cached yet");
            return;
        }
        int count = cachedServer.getPlayerList().getPlayerCount();
        LOGGER.info("[CGEN DEBUG] Syncing command tree to {} players", count);
        cachedServer.getPlayerList().getPlayers().forEach(player ->
                cachedServer.getCommands().sendCommands(player)
        );
    }

    public static CommandManager getCommandManager() {
        return commandManager;
    }
}
