package io.github.opencubicchunks.cubicchunks.config.reloadlisteners;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.opencubicchunks.cubicchunks.CubicChunks;
import io.github.opencubicchunks.cubicchunks.config.HeightSettingsEntry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.Util;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.StructureFeature;

public class HeightSettingsReloadListener<T> implements SimpleSynchronousResourceReloadListener {

    public static final IdentityHashMap<Feature<?>, HeightSettingsEntry> FEATURE_HEIGHT_SETTINGS = new IdentityHashMap<>();
    public static final IdentityHashMap<StructureFeature<?>, HeightSettingsEntry> STRUCTURE_HEIGHT_SETTINGS = new IdentityHashMap<>();

    private static final IdentityHashMap<String, HeightSettingsEntry> CONFIG_DEFAULT = Util.make(new IdentityHashMap<>(), (map) -> {
        map.put("modid:feature_name", HeightSettingsEntry.DEFAULT);
    });

    private final Registry<T> registry;
    private final String jsonFileTarget;
    private final IdentityHashMap<T, HeightSettingsEntry> trackedEntriesMap;
    private final Path configPath;

    public HeightSettingsReloadListener(Registry<T> registry, String jsonFileTarget, IdentityHashMap<T, HeightSettingsEntry> trackedEntriesMap) {
        this.registry = registry;
        this.jsonFileTarget = jsonFileTarget;
        this.trackedEntriesMap = trackedEntriesMap;
        this.configPath = CubicChunks.CONFIG_PATH.resolve(jsonFileTarget);
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        IdentityHashMap<T, HeightSettingsEntry> newMap = new IdentityHashMap<>();
        ResourceLocation location = new ResourceLocation(CubicChunks.MODID, this.jsonFileTarget);
        try {
            Collection<Resource> heightSettings = manager.getResources(location);
            for (Resource settings : heightSettings) {
                readAndProcess(newMap, settings.toString(), new BufferedReader(new InputStreamReader(settings.getInputStream())));
            }
        } catch (IOException e) {
            CubicChunks.LOGGER.error("Could not get resources for: " + location);
        }

        handleConfig(newMap);

        this.trackedEntriesMap.clear();
        this.trackedEntriesMap.putAll(newMap);
    }

    private void handleConfig(IdentityHashMap<T, HeightSettingsEntry> newMap) {
        if (!configPath.toFile().exists()) {
            createDefault();
        }

        try {
            readAndProcess(newMap, configPath.getFileName().toString(), new FileReader(configPath.toString()));
        } catch (FileNotFoundException e) {
            CubicChunks.LOGGER.error("Could not parse height settings for: \"" + configPath.getFileName() + "\". Entries in this file will not be added...");
        }
    }

    private void createDefault() {
        try {
            Files.write(configPath, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(CONFIG_DEFAULT).getBytes());
        } catch (IOException e) {
            CubicChunks.LOGGER.error(this.configPath.toString() + " could not be created.");
        }
    }

    private void readAndProcess(IdentityHashMap<T, HeightSettingsEntry> newMap, String fileName, Reader bufferedReader) {
        try (Reader reader = bufferedReader) {
            JsonObject jsonObject = new JsonParser().parse(reader).getAsJsonObject();

            process(newMap, jsonObject);

        } catch (IOException e) {
            CubicChunks.LOGGER.error("Could not parse height settings for: \"" + fileName + "\". Entries in this file will not be added...");
        }
    }

    private void process(IdentityHashMap<T, HeightSettingsEntry> newMap, JsonObject jsonObject) {
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            ResourceLocation entryID = new ResourceLocation(entry.getKey());
            HeightSettingsEntry.Builder heightEntry = new HeightSettingsEntry.Builder();
            if (registry.keySet().contains(entryID)) {
                processEntry(jsonObject, heightEntry);
                newMap.put(registry.get(entryID), heightEntry.build());
            } else {
                CubicChunks.LOGGER.error("\"" + entryID.toString() + "\" was not found in the \"" + registry.key().location() + "\" registry, skipping height settings...");
            }
        }
    }

    private void processEntry(JsonObject jsonObject, HeightSettingsEntry.Builder heightEntry) {
        if (jsonObject.has("min_y")) {
            String minY = jsonObject.get("min_y").getAsString().toUpperCase();
            if (HeightSettingsEntry.HeightType.HEIGHT_TYPES_NAMES.contains(minY)) {
                heightEntry.setMinHeight(HeightSettingsEntry.HeightType.valueOf(minY));
            }
        }
        if (jsonObject.has("max_y")) {
            String maxY = jsonObject.get("max_y").getAsString().toUpperCase();
            if (HeightSettingsEntry.HeightType.HEIGHT_TYPES_NAMES.contains(maxY)) {
                heightEntry.setMaxHeight(HeightSettingsEntry.HeightType.valueOf(maxY));
            }
        }
        if (jsonObject.has("bound_y")) {
            String boundY = jsonObject.get("bound_y").getAsString().toUpperCase();
            if (HeightSettingsEntry.HeightType.HEIGHT_TYPES_NAMES.contains(boundY)) {
                heightEntry.setHeightBounds(HeightSettingsEntry.HeightType.valueOf(boundY));
            }
        }
    }

    @Override public ResourceLocation getFabricId() {
        return new ResourceLocation(CubicChunks.MODID, this.jsonFileTarget.replace(".json", "_listener"));
    }

    public static void registerHeightSettingsReloadListeners() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new HeightSettingsReloadListener<>(Registry.FEATURE, "feature_height.json", FEATURE_HEIGHT_SETTINGS));
        ResourceManagerHelper.get(PackType.SERVER_DATA)
            .registerReloadListener(new HeightSettingsReloadListener<>(Registry.STRUCTURE_FEATURE, "structure_height.json", STRUCTURE_HEIGHT_SETTINGS));
    }
}