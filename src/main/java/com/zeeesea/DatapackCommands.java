package com.zeeesea;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.Identifier;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.arguments.IdentifierArgument;

// 26.1: NameAndId is the type that PlayerList.isOp() now accepts (replaces GameProfile).
// Package: net.minecraft.server.players.NameAndId
import net.minecraft.server.players.NameAndId;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class DatapackCommands implements ModInitializer {
    public static final String MOD_ID = "datapackcommands";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

    private static CommandManager commandManager;
    private static MinecraftServer cachedServer;
    private static com.mojang.brigadier.CommandDispatcher<CommandSourceStack> cachedDispatcher;
    private final Set<String> registeredCustomRoots = new HashSet<>();

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
     * 26.1 OP check.
     *
     * - Console / non-entity sources: always permitted (no entity = system source).
     * - Players: PlayerList.isOp() now takes NameAndId (a record with UUID + name string),
     *   NOT GameProfile. We construct NameAndId from the player's UUID and name.
     *   NameAndId is a record: NameAndId(UUID id, String name).
     * - Non-player entities: denied.
     */
    private static boolean isOperator(CommandSourceStack source) {
        if (source.getEntity() == null) return true;

        if (source.getEntity() instanceof ServerPlayer player) {
            // 26.1: NameAndId(UUID, String) — construct from the player directly.
            NameAndId nameAndId = new NameAndId(
                    player.getUUID(),
                    player.getName().getString()
            );
            return source.getServer().getPlayerList().isOp(nameAndId);
        }

        return false;
    }

    private static final SuggestionProvider<CommandSourceStack> FUNCTION_SUGGESTIONS =
            (ctx, builder) -> {
                MinecraftServer server = ctx.getSource().getServer();
                if (server == null) return builder.buildFuture();
                try {
                    Object manager = server.getFunctions();
                    Method found = null;
                    for (String candidate : new String[]{
                            "getFunctionNames", "getAvailableFunctions", "getFunctions", "listFunctions", "getAll", "values"
                    }) {
                        try {
                            found = manager.getClass().getMethod(candidate);
                            break;
                        } catch (NoSuchMethodException ignored) {}
                    }
                    if (found != null) {
                        Object result = found.invoke(manager);
                        suggestFunctionIds(result, builder);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[CGEN DEBUG] Could not populate function suggestions: {}", e.getMessage());
                }
                return builder.buildFuture();
            };

    private static void suggestFunctionIds(Object result, SuggestionsBuilder builder) {
        if (result instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (key != null) builder.suggest(key.toString());
            }
            return;
        }

        if (result instanceof Stream<?> stream) {
            List<?> collected = stream.toList();
            for (Object entry : collected) {
                suggestFunctionEntry(entry, builder);
            }
            return;
        }

        if (result instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                suggestFunctionEntry(entry, builder);
            }
        }
    }

    private static void suggestFunctionEntry(Object entry, SuggestionsBuilder builder) {
        if (entry == null) return;
        if (entry instanceof Identifier id) {
            builder.suggest(id.toString());
            return;
        }

        try {
            Method idMethod = entry.getClass().getMethod("id");
            Object id = idMethod.invoke(entry);
            if (id != null) {
                builder.suggest(id.toString());
                return;
            }
        } catch (Exception ignored) {}

        try {
            Method getIdMethod = entry.getClass().getMethod("getId");
            Object id = getIdMethod.invoke(entry);
            if (id != null) {
                builder.suggest(id.toString());
                return;
            }
        } catch (Exception ignored) {}

        builder.suggest(entry.toString());
    }

    private void registerCgenCommand(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("cgen")
                        .requires(DatapackCommands::isOperator)

                        .then(Commands.literal("create")
                                .then(Commands.argument(
                                                "commandname",
                                                com.mojang.brigadier.arguments.StringArgumentType.string())
                                        .then(Commands.argument(
                                                        "function",
                                                        IdentifierArgument.id())
                                                .suggests(FUNCTION_SUGGESTIONS)
                                                .executes(ctx -> {
                                                    CommandSourceStack source = ctx.getSource();
                                                    String cmdPath = com.mojang.brigadier.arguments.StringArgumentType
                                                            .getString(ctx, "commandname");
                                                    String func = IdentifierArgument
                                                            .getId(ctx, "function").toString();
                                                    cmdPath = normalizeCommandPath(cmdPath);

                                                    if (cmdPath.isEmpty() || func.isEmpty()) {
                                                        source.sendFailure(Component.literal(
                                                                "Usage: /cgen create <n> <namespace:function> (quote names with spaces)"));
                                                        return 0;
                                                    }
                                                    if (!isValidCommandPath(cmdPath)) {
                                                        source.sendFailure(Component.literal(
                                                                "Invalid command name '" + cmdPath + "'. Use letters, numbers, underscore, max 32 chars per token."));
                                                        return 0;
                                                    }
                                                    if (isConflictingRoot(dispatcher, cmdPath)) {
                                                        String root = cmdPath.split(" ")[0];
                                                        source.sendFailure(Component.literal(
                                                                "Cannot use root '/" + root + "' because it already exists."));
                                                        return 0;
                                                    }
                                                    if (!isValidFunctionName(func)) {
                                                        source.sendFailure(Component.literal(
                                                                "Invalid function name '" + func + "'. Expected format: namespace:path"));
                                                        return 0;
                                                    }

                                                    LOGGER.info("[CGEN DEBUG] Creating command /{} -> {}", cmdPath, func);

                                                    if (!commandManager.registerCommand(cmdPath, func)) {
                                                        source.sendFailure(Component.literal("Command already exists: /" + cmdPath));
                                                        return 0;
                                                    }

                                                    registerCustomCommand(cachedDispatcher, cmdPath, func, true);
                                                    source.sendSystemMessage(Component.literal("Created command /" + cmdPath + " -> " + func));
                                                    return 1;
                                                })
                                        )
                                )
                        )

                        .then(Commands.literal("remove")
                                .then(Commands.argument(
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
                                                ctx.getSource().sendFailure(Component.literal("Command not found: /" + cmd));
                                                return 0;
                                            }
                                            rebuildCustomCommands(dispatcher);
                                            ctx.getSource().sendSystemMessage(Component.literal("Command /" + cmd + " removed!"));
                                            return 1;
                                        })
                                )
                        )

                        .then(Commands.literal("list")
                                .executes(ctx -> {
                                    var commands = commandManager.getCommands();
                                    if (commands.isEmpty()) {
                                        ctx.getSource().sendSystemMessage(Component.literal("No commands registered."));
                                        return 1;
                                    }
                                    MutableComponent msg = Component.literal("Registered commands:\n");
                                    commands.forEach((k, v) ->
                                            msg.append(Component.literal("  /" + k))
                                                    .append(Component.literal(" -> " + v + "\n"))
                                    );
                                    ctx.getSource().sendSystemMessage(msg);
                                    return 1;
                                })
                        )

                        .then(Commands.literal("feedback")
                                .then(Commands.literal("on")
                                        .executes(ctx -> {
                                            commandManager.setFeedback(true);
                                            ctx.getSource().sendSystemMessage(Component.literal("Command feedback enabled."));
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("off")
                                        .executes(ctx -> {
                                            commandManager.setFeedback(false);
                                            ctx.getSource().sendSystemMessage(Component.literal("Command feedback disabled."));
                                            return 1;
                                        })
                                )
                                .executes(ctx -> {
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "Feedback is currently: " + (commandManager.isFeedbackEnabled() ? "ON" : "OFF")));
                                    return 1;
                                })
                        )

                        .then(Commands.literal("check")
                                .executes(ctx -> {
                                    ctx.getSource().sendSystemMessage(Component.literal("CmdGen is running."));
                                    return 1;
                                })
                        )
        );
    }

    private void registerAllCustomCommands(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        if (commandManager == null || dispatcher == null) return;
        LOGGER.info("[CGEN DEBUG] Auto-registering {} saved commands",
                commandManager.getCommands().size());
        commandManager.getCommands().forEach((cmd, func) ->
                registerCustomCommand(dispatcher, cmd, func, false));
        syncCommandsToAllPlayers();
    }

    private void rebuildCustomCommands(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        clearDynamicRoots(dispatcher);
        registerAllCustomCommands(dispatcher);
    }

    private void registerCustomCommand(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
            String cmdPath,
            String func,
            boolean syncAfter) {
        LOGGER.info("[CGEN DEBUG] Registering: /{} -> {}", cmdPath, func);
        String[] tokens = cmdPath.split(" ");
        registeredCustomRoots.add(tokens[0]);

        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> innermost =
                Commands.literal(tokens[tokens.length - 1])
                        .requires(source -> true)
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            CommandSourceStack source = ctx.getSource();
                            String execCmd = "function " + func;

                            CommandSourceStack execSource = server.createCommandSourceStack()
                                    .withPosition(source.getPosition())
                                    .withRotation(source.getRotation())
                                    .withLevel(source.getLevel());

                            if (source.getEntity() != null) {
                                execSource = execSource.withEntity(source.getEntity());
                            }

                            if (!commandManager.isFeedbackEnabled()) {
                                execSource = execSource.withSuppressedOutput();
                            }

                            LOGGER.info("[CGEN DEBUG] Executing /{} -> {} as '{}'",
                                    cmdPath, func, source.getTextName());

                            com.mojang.brigadier.ParseResults<CommandSourceStack> parseResults =
                                    server.getCommands().getDispatcher().parse(execCmd, execSource);
                            server.getCommands().performCommand(parseResults, execCmd);

                            if (commandManager.isFeedbackEnabled()) {
                                source.sendSystemMessage(Component.literal("Executed function: " + func));
                            }
                            return 1;
                        });

        if (tokens.length == 1) {
            dispatcher.register(innermost);
        } else {
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> node = innermost;
            for (int i = tokens.length - 2; i >= 1; i--) {
                node = Commands.literal(tokens[i])
                        .requires(source -> true)
                        .then(node);
            }
            dispatcher.register(
                    Commands.literal(tokens[0])
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
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            com.mojang.brigadier.tree.RootCommandNode<CommandSourceStack> root = dispatcher.getRoot();
            Field childrenField  = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("children");
            Field literalsField  = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("literals");
            Field argumentsField = com.mojang.brigadier.tree.CommandNode.class.getDeclaredField("arguments");

            childrenField.setAccessible(true);
            literalsField.setAccessible(true);
            argumentsField.setAccessible(true);

            Map<String, ?> children  = (Map<String, ?>) childrenField.get(root);
            Map<String, ?> literals  = (Map<String, ?>) literalsField.get(root);
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
        int count = cachedServer.getPlayerList().getPlayerCount();
        LOGGER.info("[CGEN DEBUG] Syncing command tree to {} players", count);
        cachedServer.getPlayerList().getPlayers().forEach(player ->
                cachedServer.getCommands().sendCommands(player)
        );
    }

    private boolean isValidFunctionName(String name) {
        return name.matches("[a-z0-9_\\-]+:[a-z0-9_\\-./]+");
    }

    private String normalizeCommandPath(String path) {
        if (path == null) return "";
        return Arrays.stream(path.trim().split("\\s+"))
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
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
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
