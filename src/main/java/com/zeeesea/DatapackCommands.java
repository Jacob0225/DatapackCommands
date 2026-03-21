package com.zeeesea;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import java.nio.file.Paths;
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
        commandManager = new CommandManager(Paths.get("config").toFile());

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            cachedServer = server;
            LOGGER.info("[CGEN DEBUG] Server cached");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            cachedServer = null;
            LOGGER.info("[CGEN DEBUG] Server stopped, cache cleared");
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            cachedDispatcher = dispatcher;
            registerCgenCommand(dispatcher);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerAllCustomCommands(dispatcher);
        });

        LOGGER.info("DatapackCommands initialized!");
    }

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
                                .then(net.minecraft.server.command.CommandManager.argument("commandname",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .then(net.minecraft.server.command.CommandManager.argument("function",
                                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    String cmd = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "commandname");
                                                    String func = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "function").trim();
                                                     MinecraftServer server = ctx.getSource().getServer();
                                                     ServerCommandSource source = ctx.getSource();

                                                    ServerCommandSource execSource = commandManager.isFeedbackEnabled()
                                                            ? source
                                                            : source.withSilent();

                                                    String execCmd = "function " + func;
                                                    LOGGER.info("[CGEN DEBUG] Executing /{} -> {} as '{}'", cmd, func, source.getName());

                                                    com.mojang.brigadier.ParseResults<ServerCommandSource> parseResults =
                                                            server.getCommandManager().getDispatcher().parse(execCmd, execSource);
                                                    server.getCommandManager().execute(parseResults, execCmd);

                                                    if (commandManager.isFeedbackEnabled()) {
                                                        source.sendMessage(Text.literal("Executed: " + func));
                                                    }
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .then(net.minecraft.server.command.CommandManager.literal("remove")
                                .then(net.minecraft.server.command.CommandManager.argument("commandname",
                                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(ctx -> {
                                            String cmd = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "commandname");
                                            LOGGER.info("[CGEN DEBUG] remove: cmd='{}'", cmd);

                                            if (!commandManager.removeCommand(cmd)) {
                                                ctx.getSource().sendError(Text.literal("Command not found: /" + cmd));
                                                return 0;
                                            }

                                            unregisterCommand(dispatcher, cmd);
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
                                    StringBuilder sb = new StringBuilder("Registered commands:\n");
                                    commands.forEach((k, v) -> sb.append("  /").append(k).append(" -> ").append(v).append("\n"));
                                    ctx.getSource().sendMessage(Text.literal(sb.toString()));
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

    private void registerCustomCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher, String cmd, String func) {
        LOGGER.info("[CGEN DEBUG] Registering: /{} -> {}", cmd, func);
        dispatcher.register(
                net.minecraft.server.command.CommandManager.literal(cmd)
                        .requires(source -> true)
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            ServerCommandSource source = ctx.getSource();
                            String execCmd = "function " + func;

                            LOGGER.info("[CGEN DEBUG] Executing /{} -> {} as '{}'", cmd, func, source.getName());

                            com.mojang.brigadier.ParseResults<ServerCommandSource> parseResults =
                                    server.getCommandManager().getDispatcher().parse(execCmd, source);
                            server.getCommandManager().execute(parseResults, execCmd);

                            if (commandManager.isFeedbackEnabled()) {
                                source.sendMessage(Text.literal("Executed function: " + func));
                            }
                            return 1;
                        })
        );
        syncCommandsToAllPlayers();
    }

    @SuppressWarnings("unchecked")
    private void unregisterCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher, String cmd) {
        try {
            // Reflection auf den Root-CommandNode um in die literals-Map zu greifen
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