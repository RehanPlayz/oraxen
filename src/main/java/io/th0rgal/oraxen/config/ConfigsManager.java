package io.th0rgal.oraxen.config;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.font.Glyph;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.items.ItemParser;
import io.th0rgal.oraxen.utils.logs.Logs;
import net.kyori.adventure.text.minimessage.Template;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

public class ConfigsManager {

    private final JavaPlugin plugin;
    private final YamlConfiguration defaultSettings;
    private final YamlConfiguration defaultFont;
    private final YamlConfiguration defaultSound;
    private final YamlConfiguration defaultLanguage;
    private YamlConfiguration settings;
    private YamlConfiguration font;
    private YamlConfiguration sound;
    private YamlConfiguration language;
    private File itemsFolder;
    private File glyphsFolder;

    public ConfigsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        defaultSettings = extractDefault("settings.yml");
        defaultFont = extractDefault("font.yml");
        defaultSound = extractDefault("sound.yml");
        defaultLanguage = extractDefault("languages/english.yml");
    }

    public YamlConfiguration getSettings() {
        return settings != null ? settings : defaultSettings;
    }

    public YamlConfiguration getLanguage() {
        return language != null ? language : defaultLanguage;
    }

    public YamlConfiguration getFont() {
        return font != null ? font : defaultFont;
    }

    public YamlConfiguration getSound() {
        return sound != null ? sound : defaultSound;
    }

    private YamlConfiguration extractDefault(String source) {
        InputStreamReader inputStreamReader = new InputStreamReader(plugin.getResource(source));
        try {
            return YamlConfiguration.loadConfiguration(inputStreamReader);
        } finally {
            try {
                inputStreamReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean validatesConfig() {
        ResourcesManager resourcesManager = new ResourcesManager(OraxenPlugin.get());
        settings = validate(resourcesManager, "settings.yml", defaultSettings);
        font = validate(resourcesManager, "font.yml", defaultFont);
        sound = validate(resourcesManager, "sound.yml", defaultSound);
        File languagesFolder = new File(plugin.getDataFolder(), "languages");
        languagesFolder.mkdir();
        String languageFile = "languages/" + settings.getString(Settings.PLUGIN_LANGUAGE.getPath()) + ".yml";
        language = validate(resourcesManager, languageFile, defaultLanguage);

        // check itemsFolder
        itemsFolder = new File(plugin.getDataFolder(), "items");
        if (!itemsFolder.exists()) {
            itemsFolder.mkdirs();
            new ResourcesManager(plugin).extractConfigsInFolder("items", "yml");
        }

        // check glyphsFolder
        glyphsFolder = new File(plugin.getDataFolder(), "glyphs");
        if (!glyphsFolder.exists()) {
            glyphsFolder.mkdirs();
            new ResourcesManager(plugin).extractConfigsInFolder("glyphs", "yml");
        }

        return true; // todo : return false when an error is detected + prints a detailed error
    }

    private YamlConfiguration validate(ResourcesManager resourcesManager, String configName, YamlConfiguration defaultConfiguration) {
        File configurationFile = resourcesManager.extractConfiguration(configName);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configurationFile);
        boolean updated = false;
        for (String key : defaultConfiguration.getKeys(true))
            if (configuration.get(key) == null) {
                updated = true;
                Message.UPDATING_CONFIG.log(Template.of("option", key));
                configuration.set(key, defaultConfiguration.get(key));
            }
        if (updated)
            try {
                configuration.save(configurationFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        return configuration;
    }

    public Collection<Glyph> parseGlyphConfigs() {
        List<Glyph> output = new ArrayList<>();
        List<File> configs = Arrays
                .stream(getGlyphsFiles())
                .filter(file -> file.getName().endsWith(".yml"))
                .collect(Collectors.toList());
        for (File file : configs) {
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            boolean fileChanged = false;
            for (String key : configuration.getKeys(false)) {
                Glyph glyph = new Glyph(key, configuration.getConfigurationSection(key));
                if (glyph.isFileChanged())
                    fileChanged = true;
                output.add(glyph);
            }
            if (fileChanged && Settings.AUTOMATICALLY_SET_GLYPH_CODE.toBool())
                try {
                    configuration.save(file);
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
        return output;
    }

    private File[] getGlyphsFiles() {
        File[] glyphConfigs = glyphsFolder.listFiles();
        Arrays.sort(glyphConfigs);
        return glyphConfigs;
    }

    public Map<File, Map<String, ItemBuilder>> parseItemConfigs() {
        Map<File, Map<String, ItemBuilder>> parseMap = new LinkedHashMap<>();
        List<File> configs = Arrays
                .stream(getItemsFiles())
                .filter(file -> file.getName().endsWith(".yml"))
                .collect(Collectors.toList());
        for (File file : configs)
            parseMap.put(file, parseItemConfigs(YamlConfiguration.loadConfiguration(file), file));
        return parseMap;
    }

    public Map<String, ItemBuilder> parseItemConfigs(YamlConfiguration config, File itemFile) {
        Map<String, ItemParser> parseMap = new LinkedHashMap<>();
        ItemParser errorItem = new ItemParser(Settings.ERROR_ITEM.toConfigSection());
        for (String itemSectionName : config.getKeys(false)) {
            if (!config.isConfigurationSection(itemSectionName))
                continue;
            ConfigurationSection itemSection = config.getConfigurationSection(itemSectionName);
            parseMap.put(itemSectionName, new ItemParser(itemSection));
        }
        boolean configUpdated = false;
        // because we must have parse all the items before building them to be able to
        // use available models
        Map<String, ItemBuilder> map = new LinkedHashMap<>();
        for (Map.Entry<String, ItemParser> entry : parseMap.entrySet()) {
            ItemParser itemParser = entry.getValue();
            try {
                map.put(entry.getKey(), itemParser.buildItem());
            } catch (Exception e) {
                map
                        .put(entry.getKey(),
                                errorItem
                                        .buildItem(String.valueOf(ChatColor.DARK_RED) + ChatColor.BOLD
                                                + e.getClass().getSimpleName() + ": " + ChatColor.RED + entry.getKey()));
                Logs.logError("ERROR BUILDING ITEM \"" + entry.getKey() + "\"");
                e.printStackTrace();
            }
            if (itemParser.isConfigUpdated())
                configUpdated = true;
        }
        if (configUpdated)
            try {
                config.save(itemFile);
            } catch (IOException e) {
                e.printStackTrace();
            }

        return map;
    }

    private File[] getItemsFiles() {
        File[] itemConfigs = itemsFolder.listFiles();
        Arrays.sort(itemConfigs);
        return itemConfigs;
    }

}
