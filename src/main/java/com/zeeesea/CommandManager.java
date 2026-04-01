package com.zeeesea;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class CommandManager {
    private static final String COMMANDS_FILE = "datapackcommands.json";
    private final File file;
    private final Gson gson = new Gson();
    private final Map<String, String> commands = new HashMap<>();
    private boolean feedbackEnabled = false;

    public CommandManager(File worldDir) {
        this.file = new File(worldDir, COMMANDS_FILE);
        load();
    }

    private static class ConfigData {
        Map<String, String> commands = new HashMap<>();
        boolean feedbackEnabled = false;
    }

    public void load() {
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            ConfigData data = gson.fromJson(reader, ConfigData.class);
            if (data != null) {
                if (data.commands != null) commands.putAll(data.commands);
                this.feedbackEnabled = data.feedbackEnabled;
            }
        } catch (IOException e) {
            DatapackCommands.LOGGER.error("Failed to load commands: ", e);
        }
    }

    public void save() {
        ConfigData data = new ConfigData();
        data.commands = this.commands;
        data.feedbackEnabled = this.feedbackEnabled;
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            DatapackCommands.LOGGER.error("Failed to save commands: ", e);
        }
    }

    /**
     * Registers a new command mapping.
     * Returns false if a mapping for that name already exists.
     */
    public boolean registerCommand(String name, String function) {
        if (commands.containsKey(name)) return false;
        commands.put(name, function);
        save();
        return true;
    }

    /**
     * Removes a command mapping.
     * Returns false if no mapping with that name exists.
     */
    public boolean removeCommand(String name) {
        if (!commands.containsKey(name)) return false;
        commands.remove(name);
        save();
        return true;
    }

    public boolean isFeedbackEnabled() {
        return feedbackEnabled;
    }

    public void setFeedback(boolean enabled) {
        this.feedbackEnabled = enabled;
        save();
    }

    public Map<String, String> getCommands() {
        return commands;
    }

    public String getFunction(String name) {
        return commands.get(name);
    }
}