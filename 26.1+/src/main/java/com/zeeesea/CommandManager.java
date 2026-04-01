package com.zeeesea;

import com.google.gson.Gson;

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

    public CommandManager(File configDir) {
        this.file = new File(configDir, COMMANDS_FILE);
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
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            DatapackCommands.LOGGER.error("Failed to save commands: ", e);
        }
    }

    public boolean registerCommand(String name, String function) {
        if (commands.containsKey(name)) return false;
        commands.put(name, function);
        save();
        return true;
    }

    public boolean removeCommand(String name) {
        if (!commands.containsKey(name)) return false;
        commands.remove(name);
        save();
        return true;
    }

    private boolean feedbackEnabled = true;

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