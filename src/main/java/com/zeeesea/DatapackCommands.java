package com.zeeesea;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.WorldSavePath;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import java.lang.reflect.Field;
import java.util.Map;

public class DatapackCommands implements ModInitializer {
    public static final String MOD_ID = "datapackcommands";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);
    private static CommandManager commandManager;
    private static MinecraftServer cachedServer;
    private static com.mojang.brigadier.CommandDispatcher<ServerCommandSource> cachedDispatcher;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            cachedServer = server;
            java.io.File worldDir = server.getSavePath(WorldSavePath.ROOT).toFile();
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

        LOGGER.info("DatapackCommands initialized!");    }

    private void registerCgenCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("cgen")
                        .requires(source -> {
                            try {
                                java.lang.reflect.Method m = source.getClass().getMethod("getPermissionLevel");
                                int level = (int) m.invoke(source);
                                return level >= 4;
                            } catch (Exception ex) {
                                return true;
                            }
                        })
                        .then(net.minecraft.server.command.CommandManager.literal("create")
                                .then(net.minecraft.server.command.CommandManager.argument("\"<command>\" <namespace:function>",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String raw = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "\"<command>\" <namespace:function>").trim();
                                            ServerCommandSource source = ctx.getSource();

                                            // Split into "tp creative" and "example:func" on the last space-separated token after the last quote block
                                            // Format: "command path" namespace:function  OR  singleword namespace:function
                                            String cmdPath;
                                            String func;

                                            if (raw.startsWith("\"")) {
                                                int closing = raw.indexOf('"', 1);
                                                if (closing == -1) {
                                                    source.sendError(Text.literal("Missing closing quote in command name."));
                                                    return 0;
                                                }
                                                cmdPath = raw.substring(1, closing).trim();
                                                func = raw.substring(closing + 1).trim();
                                            } else {
                                                int lastSpace = raw.lastIndexOf(' ');
                                                if (lastSpace == -1) {
                                                    source.sendError(Text.literal("Usage: /cgen create \"<command> [subcommand]\" <namespace:function>"));
                                                    return 0;
                                                }
                                                cmdPath = raw.substring(0, lastSpace).trim();
                                                func = raw.substring(lastSpace + 1).trim();
                                            }

                                            if (cmdPath.isEmpty() || func.isEmpty()) {
                                                source.sendError(Text.literal("Usage: /cgen create \"<command> [subcommand]\" <namespace:function>"));
                                                return 0;
                                            }

                                            LOGGER.info("[CGEN DEBUG] Creating command /{} -> {}", cmdPath, func);

                                            if (!commandManager.registerCommand(cmdPath, func)) {
                                                source.sendError(Text.literal("Command already exists: /" + cmdPath));
                                                return 0;
                                            }

                                            registerCustomCommand(cachedDispatcher, cmdPath, func);

                                            source.sendMessage(Text.literal("Created command /" + cmdPath + " -> " + func));
                                            return 1;
                                        })
                                )
                        )
                        .then(net.minecraft.server.command.CommandManager.literal("remove")
                                .then(net.minecraft.server.command.CommandManager.argument("commandname",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> {
                                            commandManager.getCommands().keySet().forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String cmd = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "commandname").trim();
                                            LOGGER.info("[CGEN DEBUG] remove: cmd='{}'", cmd);

                                            if (!commandManager.removeCommand(cmd)) {
                                                ctx.getSource().sendError(Text.literal("Command not found: /" + cmd));
                                                return 0;
                                            }

                                            // Only unregister the root token from Brigadier
                                            unregisterCommand(dispatcher, cmd.split(" ")[0]);
                                            ctx.getSource().sendMessage(Text.literal("Command /" + cmd + " removed!"));
                                            return 1;
                                        })
                                )
                        )
                        .then(net.minecraft.server.command.CommandManager.literal("list")
                                .executes(ctx -> {
                                    var commands = commandManager.getCommands();
                                    if (commands.isEmpty()) {
                                        ctx.getSource().sendMessage(Text.literal("No commands registered."));
                                        return 1;
                                    }
                                    MutableText msg = Text.literal("Registered commands:\n");
                                    commands.forEach((k, v) -> {
                                        MutableText entry = Text.literal("  /" + k);
                                        MutableText rest = Text.literal(" -> " + v + "\n");
                                        msg.append(entry).append(rest);
                                    });
                                    ctx.getSource().sendMessage(msg);
                                    return 1;
                                })
                        )
                        .then(net.minecraft.server.command.CommandManager.literal("feedback")
                                .then(net.minecraft.server.command.CommandManager.literal("on")
                                        .executes(ctx -> {
                                            commandManager.setFeedback(true);
                                            ctx.getSource().sendMessage(Text.literal("Command feedback enabled."));
                                            return 1;
                                        })
                                )
                                .then(net.minecraft.server.command.CommandManager.literal("off")
                                        .executes(ctx -> {
                                            commandManager.setFeedback(false);
                                            ctx.getSource().sendMessage(Text.literal("Command feedback disabled."));
                                            return 1;
                                        })
                                )
                                .executes(ctx -> {
                                    ctx.getSource().sendMessage(Text.literal(
                                            "Feedback is currently: " + (commandManager.isFeedbackEnabled() ? "ON" : "OFF")
                                    ));
                                    return 1;
                                })
                        )
                        .then(net.minecraft.server.command.CommandManager.literal("check")
                                .executes(ctx -> {
                                    ctx.getSource().sendMessage(Text.literal("1"));
                                    return 1;
                                })
                        )
        );
    }

    private void registerAllCustomCommands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        LOGGER.info("[CGEN DEBUG] Auto-registering {} saved commands", commandManager.getCommands().size());
        commandManager.getCommands().forEach((cmd, func) -> registerCustomCommand(dispatcher, cmd, func));
    }

    private void registerCustomCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher, String cmdPath, String func) {
        LOGGER.info("[CGEN DEBUG] Registering: /{} -> {}", cmdPath, func);
        String[] tokens = cmdPath.split(" ");

        // Build the innermost executes node
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> innermost =
                net.minecraft.server.command.CommandManager.literal(tokens[tokens.length - 1])
                        .requires(source -> true)
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            ServerCommandSource source = ctx.getSource();
                            String execCmd = "function " + func;

                            ServerCommandSource execSource = commandManager.isFeedbackEnabled()
                                    ? source : source.withSilent();

                            LOGGER.info("[CGEN DEBUG] Executing /{} -> {} as '{}'", cmdPath, func, source.getName());

                            com.mojang.brigadier.ParseResults<ServerCommandSource> parseResults =
                                    server.getCommandManager().getDispatcher().parse(execCmd, execSource);
                            server.getCommandManager().execute(parseResults, execCmd);

                            if (commandManager.isFeedbackEnabled()) {
                                source.sendMessage(Text.literal("Executed function: " + func));
                            }
                            return 1;
                        });

        // If only one token, register directly
        if (tokens.length == 1) {
            dispatcher.register(innermost);
        } else {
            // Chain from outermost down: tokens[0].then(tokens[1].then(...innermost))
            com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> node = innermost;
            for (int i = tokens.length - 2; i >= 1; i--) {
                node = net.minecraft.server.command.CommandManager.literal(tokens[i])
                        .requires(source -> true)
                        .then(node);
            }
            dispatcher.register(
                    net.minecraft.server.command.CommandManager.literal(tokens[0])
                            .requires(source -> true)
                            .then(node)
            );
        }
        syncCommandsToAllPlayers();
    }

    @SuppressWarnings("unchecked")
    private void unregisterCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher, String cmd) {
        try {
            com.mojang.brigadier.tree.RootCommandNode<ServerCommandSource> root = dispatcher.getRoot();
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
        int count = cachedServer.getPlayerManager().getCurrentPlayerCount();
        LOGGER.info("[CGEN DEBUG] Syncing command tree to {} players", count);
        cachedServer.getPlayerManager().getPlayerList().forEach(player ->
                cachedServer.getCommandManager().sendCommandTree(player)
        );
    }

    private boolean isValidCommandName(String name) {
        return name.matches("[a-zA-Z0-9_]{1,32}");
    }

    private boolean isValidFunctionName(String name) {
        return name.matches("[a-z0-9_\\-]+:[a-z0-9_\\-./]+");
    }

    public static CommandManager getCommandManager() {
        return commandManager;
    }
}