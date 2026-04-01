package com.zeeesea;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.WorldSavePath;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.util.Identifier;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.stream.StreamSupport;

public class DatapackCommands implements ModInitializer {
    public static final String MOD_ID = "datapackcommands";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);
    private static CommandManager commandManager;
    private static MinecraftServer cachedServer;
    private static com.mojang.brigadier.CommandDispatcher<ServerCommandSource> cachedDispatcher;
    private final Set<String> registeredCustomRoots = new HashSet<>();

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
            registeredCustomRoots.clear();
            LOGGER.info("[CGEN DEBUG] Server stopped, cache cleared");
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            cachedDispatcher = dispatcher;
            registerCgenCommand(dispatcher);
            if (commandManager != null) {
                LOGGER.info("[CGEN DEBUG] Re-registering custom commands after reload");
                rebuildCustomCommands(dispatcher);
            }
        });

        LOGGER.info("DatapackCommands initialized!");
    }

    /**
     * Returns true if the source has OP level >= 2.
     * - Console / non-entity sources: always permitted.
     * - Players: checked via PlayerManager.isOperator() (stable across all 1.21.x).
     * - Other entities: denied by default.
     */
    private static boolean isOperator(ServerCommandSource source) {
        if (source.getEntity() == null) return true;

        if (source.getEntity() instanceof net.minecraft.server.network.ServerPlayerEntity player) {
            return source.getServer().getPlayerManager()
                    .isOperator(player.getPlayerConfigEntry());
        }

        return false;
    }

    /**
     * Suggestion provider that lists all loaded datapack functions,
     * mirroring what /function would suggest.
     */
    private static final SuggestionProvider<ServerCommandSource> FUNCTION_SUGGESTIONS =
            (ctx, builder) -> {
                MinecraftServer server = ctx.getSource().getServer();
                if (server == null) return builder.buildFuture();
                StreamSupport.stream(
                                server.getCommandFunctionManager().getAllFunctions().spliterator(), false)
                        .map(Identifier::toString)
                        .forEach(builder::suggest);
                return builder.buildFuture();
            };

    private void registerCgenCommand(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("cgen")
                        // Only OPs (level >= 2) can see or execute /cgen
                        .requires(DatapackCommands::isOperator)

                        .then(net.minecraft.server.command.CommandManager.literal("create")
                                .then(net.minecraft.server.command.CommandManager.argument(
                                                "commandname",
                                                com.mojang.brigadier.arguments.StringArgumentType.string())
                                        .then(net.minecraft.server.command.CommandManager.argument(
                                                        "function",
                                                        IdentifierArgumentType.identifier())
                                                // Suggest functions only after command name is provided.
                                                .suggests(FUNCTION_SUGGESTIONS)
                                                .executes(ctx -> {
                                                    ServerCommandSource source = ctx.getSource();
                                                    String cmdPath = com.mojang.brigadier.arguments.StringArgumentType
                                                            .getString(ctx, "commandname");
                                                    String func = IdentifierArgumentType
                                                            .getIdentifier(ctx, "function").toString();
                                                    cmdPath = normalizeCommandPath(cmdPath);

                                                    if (cmdPath.isEmpty() || func.isEmpty()) {
                                                        source.sendError(Text.literal(
                                                                "Usage: /cgen create <name> <namespace:function> (quote names with spaces)"));
                                                        return 0;
                                                    }

                                                    if (!isValidCommandPath(cmdPath)) {
                                                        source.sendError(Text.literal(
                                                                "Invalid command name '" + cmdPath + "'. Use letters, numbers, underscore, max 32 chars per token."));
                                                        return 0;
                                                    }

                                                    if (isConflictingRoot(dispatcher, cmdPath)) {
                                                        String root = cmdPath.split(" ")[0];
                                                        source.sendError(Text.literal(
                                                                "Cannot use root '/" + root + "' because it already exists."));
                                                        return 0;
                                                    }

                                                    // Validate names before registering
                                                    if (!isValidFunctionName(func)) {
                                                        source.sendError(Text.literal(
                                                                "Invalid function name '" + func + "'. Expected format: namespace:path"));
                                                        return 0;
                                                    }

                                                    LOGGER.info("[CGEN DEBUG] Creating command /{} -> {}", cmdPath, func);

                                                    if (!commandManager.registerCommand(cmdPath, func)) {
                                                        source.sendError(Text.literal("Command already exists: /" + cmdPath));
                                                        return 0;
                                                    }

                                                    registerCustomCommand(cachedDispatcher, cmdPath, func, true);
                                                    source.sendMessage(Text.literal("Created command /" + cmdPath + " -> " + func));
                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(net.minecraft.server.command.CommandManager.literal("remove")
                                .then(net.minecraft.server.command.CommandManager.argument(
                                                "commandname",
                                                com.mojang.brigadier.arguments.StringArgumentType.string())
                                        .suggests((ctx, builder) -> {
                                            commandManager.getCommands().keySet().forEach(name -> {
                                                if (name.contains(" ")) {
                                                    builder.suggest('"' + name + '"');
                                                } else {
                                                    builder.suggest(name);
                                                }
                                            });
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String cmd = com.mojang.brigadier.arguments.StringArgumentType
                                                    .getString(ctx, "commandname");
                                            cmd = normalizeCommandPath(cmd);
                                            LOGGER.info("[CGEN DEBUG] remove: cmd='{}'", cmd);

                                            if (!commandManager.removeCommand(cmd)) {
                                                ctx.getSource().sendError(Text.literal("Command not found: /" + cmd));
                                                return 0;
                                            }

                                            rebuildCustomCommands(dispatcher);
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
                                    commands.forEach((k, v) ->
                                            msg.append(Text.literal("  /" + k))
                                                    .append(Text.literal(" -> " + v + "\n"))
                                    );
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
                                            "Feedback is currently: " + (commandManager.isFeedbackEnabled() ? "ON" : "OFF")));
                                    return 1;
                                })
                        )

                        .then(net.minecraft.server.command.CommandManager.literal("check")
                                .executes(ctx -> {
                                    ctx.getSource().sendMessage(Text.literal("CmdGen is running."));
                                    return 1;
                                })
                        )
        );
    }

    private void registerAllCustomCommands(
            com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        if (commandManager == null || dispatcher == null) return;
        LOGGER.info("[CGEN DEBUG] Auto-registering {} saved commands",
                commandManager.getCommands().size());
        commandManager.getCommands().forEach((cmd, func) ->
                registerCustomCommand(dispatcher, cmd, func, false));
        syncCommandsToAllPlayers();
    }

    private void rebuildCustomCommands(
            com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        clearDynamicRoots(dispatcher);
        registerAllCustomCommands(dispatcher);
    }

    private void registerCustomCommand(
            com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
            String cmdPath,
            String func,
            boolean syncAfter) {
        LOGGER.info("[CGEN DEBUG] Registering: /{} -> {}", cmdPath, func);
        String[] tokens = cmdPath.split(" ");
        registeredCustomRoots.add(tokens[0]);

        // Innermost node: no permission requirement — anyone can execute
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> innermost =
                net.minecraft.server.command.CommandManager.literal(tokens[tokens.length - 1])
                        .requires(source -> true)
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            ServerCommandSource source = ctx.getSource();
                            String execCmd = "function " + func;

                            ServerCommandSource execSource = server.getCommandSource()
                                    .withPosition(source.getPosition())
                                    .withRotation(source.getRotation())
                                    .withWorld(source.getWorld());

                            if (source.getEntity() != null) {
                                execSource = execSource.withEntity(source.getEntity());
                            }

                            if (!commandManager.isFeedbackEnabled()) {
                                execSource = execSource.withSilent();
                            }

                            LOGGER.info("[CGEN DEBUG] Executing /{} -> {} as '{}'",
                                    cmdPath, func, source.getName());

                            com.mojang.brigadier.ParseResults<ServerCommandSource> parseResults =
                                    server.getCommandManager().getDispatcher()
                                            .parse(execCmd, execSource);
                            server.getCommandManager().execute(parseResults, execCmd);

                            if (commandManager.isFeedbackEnabled()) {
                                source.sendMessage(Text.literal("Executed function: " + func));
                            }
                            return 1;
                        });

        if (tokens.length == 1) {
            dispatcher.register(innermost);
        } else {
            // Chain nodes from the inside out
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

        if (syncAfter) {
            syncCommandsToAllPlayers();
        }
    }

    @SuppressWarnings("unchecked")
    private void clearDynamicRoots(
            com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher) {
        try {
            com.mojang.brigadier.tree.RootCommandNode<ServerCommandSource> root = dispatcher.getRoot();
            Field childrenField  = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("children");
            Field literalsField  = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("literals");
            Field argumentsField = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("arguments");

            childrenField.setAccessible(true);
            literalsField.setAccessible(true);
            argumentsField.setAccessible(true);

            Map<String, ?> children = (Map<String, ?>) childrenField.get(root);
            Map<String, ?> literals = (Map<String, ?>) literalsField.get(root);
            Map<String, ?> arguments = (Map<String, ?>) argumentsField.get(root);

            for (String dynamicRoot : registeredCustomRoots) {
                children.remove(dynamicRoot);
                literals.remove(dynamicRoot);
                arguments.remove(dynamicRoot);
            }

            LOGGER.info("[CGEN DEBUG] Cleared {} dynamic roots", registeredCustomRoots.size());
            registeredCustomRoots.clear();
        } catch (Exception e) {
            LOGGER.error("[CGEN DEBUG] Failed to clear dynamic roots from dispatcher: {}", e.getMessage());
        }
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

    private boolean isValidFunctionName(String name) {
        return name.matches("[a-z0-9_\\-]+:[a-z0-9_\\-./]+");
    }

    private String normalizeCommandPath(String path) {
        if (path == null) return "";
        return Arrays.stream(path.trim().split("\\\\s+"))
                .filter(token -> !token.isEmpty())
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    private boolean isValidCommandPath(String path) {
        if (path.isEmpty()) return false;
        String[] tokens = path.split(" ");
        for (String token : tokens) {
            if (token.length() > 32) return false;
            if (!token.matches("[A-Za-z0-9_]+")) return false;
        }
        return true;
    }

    private boolean isConflictingRoot(
            com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
            String path) {
        String root = path.split(" ")[0];
        boolean alreadyOwnedByCgen = commandManager.getCommands().keySet().stream()
                .map(name -> name.split(" ")[0])
                .anyMatch(root::equals);
        return dispatcher.getRoot().getChild(root) != null && !alreadyOwnedByCgen;
    }

    public static CommandManager getCommandManager() {
        return commandManager;
    }
}