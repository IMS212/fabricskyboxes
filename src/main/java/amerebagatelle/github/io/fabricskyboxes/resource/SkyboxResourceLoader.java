package amerebagatelle.github.io.fabricskyboxes.resource;

import amerebagatelle.github.io.fabricskyboxes.SkyboxManager;
import amerebagatelle.github.io.fabricskyboxes.skyboxes.AbstractSkybox;
import amerebagatelle.github.io.fabricskyboxes.skyboxes.TexturedSkybox;
import amerebagatelle.github.io.fabricskyboxes.util.JsonObjectWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Collections;

public class SkyboxResourceLoader {
    private static final Gson gson = new Gson();
    private static final JsonObjectWrapper objectWrapper = new JsonObjectWrapper();

    public static void setupResourceLoader() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public void apply(ResourceManager manager) {
                Collection<Identifier> resources = manager.findResources("sky", (string) -> string.endsWith(".json"));

                for (Identifier id : resources) {
                    try {
                        JsonObject json = gson.fromJson(new InputStreamReader(manager.getResource(id).getInputStream()), JsonObject.class);
                        SkyboxManager.getInstance().addSkybox(parseSkyboxJson(json));
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public Collection<Identifier> getFabricDependencies() {
                return Collections.emptyList();
            }

            @Override
            public Identifier getFabricId() {
                return new Identifier("fabricskyboxes", "skybox_json");
            }
        });
    }

    private static AbstractSkybox parseSkyboxJson(JsonObject json) {
        objectWrapper.setFocusedObject(json);
        TexturedSkybox skybox;
        try {
            skybox = new TexturedSkybox(
                    objectWrapper.getJsonStringAsId("texture_north"),
                    objectWrapper.getJsonStringAsId("texture_south"),
                    objectWrapper.getJsonStringAsId("texture_east"),
                    objectWrapper.getJsonStringAsId("texture_west"),
                    objectWrapper.getJsonStringAsId("texture_top"),
                    objectWrapper.getJsonStringAsId("texture_bottom")
            );
            skybox.startFadeIn = json.get("startFadeIn").getAsInt();
            skybox.endFadeIn = json.get("endFadeIn").getAsInt();
            skybox.startFadeOut = json.get("startFadeOut").getAsInt();
            skybox.endFadeOut = json.get("endFadeOut").getAsInt();
        } catch (NullPointerException e) {
            throw new NullPointerException("Could not get a required field.");
        }
        JsonElement element = objectWrapper.getOptionalValue("maxAlpha");
        skybox.maxAlpha = element != null && JsonHelper.isNumber(element) ? element.getAsFloat() : 1f;
        element = objectWrapper.getOptionalValue("transitionSpeed");
        skybox.transitionSpeed = element != null && JsonHelper.isNumber(element) ? element.getAsFloat() : 1f;
        element = objectWrapper.getOptionalValue("biomes");
        if (element != null) {
            if (element.isJsonArray()) {
                for (JsonElement jsonElement : element.getAsJsonArray()) {
                    skybox.biomes.add(new Identifier(jsonElement.getAsString()));
                }
            } else if (JsonHelper.isString(element)) {
                skybox.biomes.add(new Identifier(element.getAsString()));
            }
        }
        if (element != null) {
            element = json.get("dimensions");
            if (element.isJsonArray()) {
                for (JsonElement jsonElement : element.getAsJsonArray()) {
                    skybox.dimensions.add(new Identifier(jsonElement.getAsString()));
                }
            } else if (JsonHelper.isString(element)) {
                skybox.dimensions.add(new Identifier(element.getAsString()));
            }
        }
        return skybox;
    }
}
