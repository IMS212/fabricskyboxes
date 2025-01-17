package io.github.amerebagatelle.fabricskyboxes.skyboxes;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.Codec;
import io.github.amerebagatelle.fabricskyboxes.SkyboxManager;
import io.github.amerebagatelle.fabricskyboxes.mixin.skybox.WorldRendererAccess;
import io.github.amerebagatelle.fabricskyboxes.util.JsonObjectWrapper;
import io.github.amerebagatelle.fabricskyboxes.util.Utils;
import io.github.amerebagatelle.fabricskyboxes.util.object.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * All classes that implement {@link AbstractSkybox} should
 * have a default constructor as it is required when checking
 * the type of the skybox.
 */
public abstract class AbstractSkybox {
    /**
     * The current alpha for the skybox. Expects all skyboxes extending this to accommodate this.
     * This variable is responsible for fading in/out skyboxes.
     */
    public transient float alpha;

    // ! These are the options variables.  Do not mess with these.
    protected Fade fade = Fade.ZERO;
    protected float maxAlpha = 1f;
    protected float transitionSpeed = 1;
    protected boolean changeFog = false;
    protected RGBA fogColors = RGBA.ZERO;
    protected boolean shouldRotate = false;
    protected List<String> weather = new ArrayList<>();
    protected List<Identifier> biomes = new ArrayList<>();
    protected Decorations decorations = Decorations.DEFAULT;
    /**
     * Stores identifiers of <b>worlds</b>, not dimension types.
     */
    protected List<Identifier> worlds = new ArrayList<>();
    protected List<HeightEntry> heightRanges = Lists.newArrayList();

    /**
     * The main render method for a skybox.
     * Override this if you are creating a skybox from this one.
     *
     * @param worldRendererAccess Access to the worldRenderer as skyboxes often require it.
     * @param matrices            The current MatrixStack.
     * @param tickDelta           The current tick delta.
     */
    public abstract void render(WorldRendererAccess worldRendererAccess, MatrixStack matrices, float tickDelta);

    /**
     * Specifies the codec that should be used to decode the skybox. This is used
     * only when the {@code schemaVersion} key in the skybox json is {@code 2} or above.
     *
     * @param schemaVersion the schema version, as specified in the {@code schemaVersion} key
     * @return The Codec that should be used to decode this skybox
     */
    public abstract Codec<? extends AbstractSkybox> getCodec(int schemaVersion);

    protected AbstractSkybox() {
    }

    protected AbstractSkybox(DefaultProperties properties, Conditions conditions, Decorations decorations) {
        this.fade = properties.getFade();
        this.maxAlpha = properties.getMaxAlpha();
        this.transitionSpeed = properties.getTransitionSpeed();
        this.changeFog = properties.isChangeFog();
        this.fogColors = properties.getFogColors();
        this.shouldRotate = properties.isShouldRotate();
        this.weather = conditions.getWeathers().stream().map(Weather::toString).distinct().collect(Collectors.toList());
        this.biomes = conditions.getBiomes();
        this.worlds = conditions.getWorlds();
        this.heightRanges = conditions.getHeights();
        this.decorations = decorations;
    }

    /**
     * Calculates the alpha value for the current time and conditions and returns it.
     *
     * @return The new alpha value.
     */
    public final float getAlpha() {
        if (!fade.isAlwaysOn()) {
            int currentTime = (int) Objects.requireNonNull(MinecraftClient.getInstance().world).getTimeOfDay() % 24000; // modulo so that it's bound to 24000
            int duration = Utils.getTicksBetween(this.fade.getStartFadeIn(), this.fade.getEndFadeIn());
            int phase = 0; // default not showing
            if (this.fade.getStartFadeIn() < currentTime && this.fade.getEndFadeIn() >= currentTime) {
                phase = 1; // fading out
            } else if (this.fade.getEndFadeIn() < currentTime && this.fade.getStartFadeOut() >= currentTime) {
                phase = 3; // fully faded in
            } else if (this.fade.getStartFadeOut() < currentTime && this.fade.getEndFadeOut() >= currentTime) {
                phase = 2; // fading in
            }

            float maxPossibleAlpha;
            switch (phase) {
                case 1:
                    maxPossibleAlpha = 1f - (((float) (this.fade.getStartFadeIn() + duration - currentTime)) / duration);
                    break;

                case 2:
                    maxPossibleAlpha = (float) (this.fade.getEndFadeOut() - currentTime) / duration;
                    break;

                case 3:
                    maxPossibleAlpha = 1f;
                    break;

                default:
                    maxPossibleAlpha = 0f;
            }
            maxPossibleAlpha *= maxAlpha;
            if (checkBiomes() && checkHeights() && checkWeather()) { // check if environment is invalid
                if (alpha >= maxPossibleAlpha) {
                    alpha = maxPossibleAlpha;
                } else {
                    alpha += transitionSpeed;
                    if (alpha > maxPossibleAlpha) alpha = maxPossibleAlpha;
                }
            } else {
                if (alpha > 0f) {
                    alpha -= transitionSpeed;
                    if (alpha < 0f) alpha = 0f;
                } else {
                    alpha = 0f;
                }
            }

            if (alpha > 0.1 && changeFog) {
                SkyboxManager.shouldChangeFog = true;
                SkyboxManager.fogRed = this.fogColors.getRed();
                SkyboxManager.fogBlue = this.fogColors.getBlue();
                SkyboxManager.fogGreen = this.fogColors.getGreen();
            }
        } else {
            alpha = 1f;
        }

        return alpha;
    }

    /**
     * @return Whether the current biomes and dimensions are valid for this skybox.
     */
    protected boolean checkBiomes() {
        MinecraftClient client = MinecraftClient.getInstance();
        Objects.requireNonNull(client.world);
        if (worlds.isEmpty()|| worlds.contains(client.world.getRegistryKey().getValue())) {
            return biomes.isEmpty()|| biomes.contains(client.world.getRegistryManager().get(Registry.BIOME_KEY).getId(client.world.getBiome(client.player.getBlockPos())));
        }
        return false;
    }

    /**
     * @return Whether the current heights are valid for this skybox.
     */
    protected boolean checkHeights() {
        double playerHeight = Objects.requireNonNull(MinecraftClient.getInstance().player).getY();
        boolean inRange = false;
        for (HeightEntry heightRange : this.heightRanges) {
            inRange = heightRange.getMin() < playerHeight && heightRange.getMax() > playerHeight;
            if (inRange) break;
        }
        return this.heightRanges.isEmpty() || inRange;
    }

    /**
     * @return Whether the current weather is valid for this skybox.
     */
    protected boolean checkWeather() {
        ClientWorld world = Objects.requireNonNull(MinecraftClient.getInstance().world);
        ClientPlayerEntity player = Objects.requireNonNull(MinecraftClient.getInstance().player);
        Biome.Precipitation precipitation = world.getBiome(player.getBlockPos()).getPrecipitation();
        if (weather.size() > 0) {
            if (weather.contains("thunder") && world.isThundering()) {
                return true;
            } else if (weather.contains("snow") && world.isRaining() && precipitation == Biome.Precipitation.SNOW) {
                return true;
            } else if (weather.contains("rain") && world.isRaining() && !world.isThundering()) {
                return true;
            } else return weather.contains("clear");
        } else {
            return true;
        }
    }

    public void renderDecorations(WorldRendererAccess worldRendererAccess, MatrixStack matrices, float tickDelta, BufferBuilder bufferBuilder, float alpha) {
        if (!SkyboxManager.getInstance().hasRenderedDecorations()) {
            RenderSystem.enableTexture();
            matrices.push();
            ClientWorld world = MinecraftClient.getInstance().world;
            assert world != null;
            RenderSystem.enableTexture();
            RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE, GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ZERO);
            matrices.multiply(Vector3f.POSITIVE_Y.getDegreesQuaternion(-90.0F));
            matrices.multiply(Vector3f.POSITIVE_X.getDegreesQuaternion(world.getSkyAngle(tickDelta) * 360.0F));
            float r = 1.0F - world.getRainGradient(tickDelta);
            // sun
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, alpha);
            Matrix4f matrix4f2 = matrices.peek().getModel();
            float s = 30.0F;
            if (decorations.isSunEnabled()) {
                worldRendererAccess.getTextureManager().bindTexture(this.decorations.getSunTexture());
                bufferBuilder.begin(7, VertexFormats.POSITION_TEXTURE);
                bufferBuilder.vertex(matrix4f2, -s, 100.0F, -s).texture(0.0F, 0.0F).next();
                bufferBuilder.vertex(matrix4f2, s, 100.0F, -s).texture(1.0F, 0.0F).next();
                bufferBuilder.vertex(matrix4f2, s, 100.0F, s).texture(1.0F, 1.0F).next();
                bufferBuilder.vertex(matrix4f2, -s, 100.0F, s).texture(0.0F, 1.0F).next();
                bufferBuilder.end();
                BufferRenderer.draw(bufferBuilder);
            }
            // moon
            s = 20.0F;
            if (decorations.isMoonEnabled()) {
                worldRendererAccess.getTextureManager().bindTexture(this.decorations.getMoonTexture());
                int t = world.getMoonPhase();
                int u = t % 4;
                int v = t / 4 % 2;
                float w = (float) (u) / 4.0F;
                float o = (float) (v) / 2.0F;
                float p = (float) (u + 1) / 4.0F;
                float q = (float) (v + 1) / 2.0F;
                bufferBuilder.begin(7, VertexFormats.POSITION_TEXTURE);
                bufferBuilder.vertex(matrix4f2, -s, -100.0F, s).texture(p, q).next();
                bufferBuilder.vertex(matrix4f2, s, -100.0F, s).texture(w, q).next();
                bufferBuilder.vertex(matrix4f2, s, -100.0F, -s).texture(w, o).next();
                bufferBuilder.vertex(matrix4f2, -s, -100.0F, -s).texture(p, o).next();
                bufferBuilder.end();
                BufferRenderer.draw(bufferBuilder);
            }
            // stars
            if (decorations.isStarsEnabled()) {
                RenderSystem.disableTexture();
                float aa = world.method_23787(tickDelta) * r;
                if (aa > 0.0F) {
                    RenderSystem.color4f(aa, aa, aa, aa);
                    worldRendererAccess.getStarsBuffer().bind();
                    worldRendererAccess.getSkyVertexFormat().startDrawing(0L);
                    worldRendererAccess.getStarsBuffer().draw(matrices.peek().getModel(), 7);
                    VertexBuffer.unbind();
                    worldRendererAccess.getSkyVertexFormat().endDrawing();
                }
            }
            RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
            RenderSystem.enableAlphaTest();
            RenderSystem.enableFog();
            matrices.multiply(Vector3f.NEGATIVE_X.getDegreesQuaternion(world.getSkyAngle(tickDelta) * 360.0F));
            matrices.multiply(Vector3f.NEGATIVE_Y.getDegreesQuaternion(-90.0F));
            matrices.pop();
        }
    }

    /**
     * @return A string identifying your skybox type to be used in json parsing.
     */
    public abstract String getType();

    /**
     * Method for option parsing by json. Override and extend this if your skybox has options of its own.
     * This is called only when a schemaVersion lower than two is used.
     */
    public void parseJson(JsonObjectWrapper jsonObjectWrapper) {
        try {
            this.fade = new Fade(
                    jsonObjectWrapper.get("startFadeIn").getAsInt(),
                    jsonObjectWrapper.get("endFadeIn").getAsInt(),
                    jsonObjectWrapper.get("startFadeOut").getAsInt(),
                    jsonObjectWrapper.get("endFadeOut").getAsInt(),
                    false
            );
        } catch (NullPointerException e) {
            throw new JsonParseException("Could not get a required field for skybox of type " + getType());
        }
        // alpha changing
        maxAlpha = jsonObjectWrapper.getOptionalFloat("maxAlpha", 1f);
        transitionSpeed = jsonObjectWrapper.getOptionalFloat("transitionSpeed", 1f);
        // rotation
        shouldRotate = jsonObjectWrapper.getOptionalBoolean("shouldRotate", false);
        // decorations
        decorations = Decorations.DEFAULT;
        // fog
        changeFog = jsonObjectWrapper.getOptionalBoolean("changeFog", false);
        this.fogColors = new RGBA(
                jsonObjectWrapper.getOptionalFloat("fogRed", 0f),
                jsonObjectWrapper.getOptionalFloat("fogGreen", 0f),
                jsonObjectWrapper.getOptionalFloat("fogBlue", 0f)
        );
        // environment specifications
        JsonElement element;
        element = jsonObjectWrapper.getOptionalValue("weather").orElse(null);
        if (element != null) {
            if (element.isJsonArray()) {
                for (JsonElement jsonElement : element.getAsJsonArray()) {
                    weather.add(jsonElement.getAsString());
                }
            } else if (JsonHelper.isString(element)) {
                weather.add(element.getAsString());
            }
        }
        element = jsonObjectWrapper.getOptionalValue("biomes").orElse(null);
        this.processIds(element, biomes);
        element = jsonObjectWrapper.getOptionalValue("dimensions").orElse(null);
        this.processIds(element, worlds);
        element = jsonObjectWrapper.getOptionalValue("heightRanges").orElse(null);
        if (element != null) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement jsonElement : array) {
                JsonArray insideArray = jsonElement.getAsJsonArray();
                float low = insideArray.get(0).getAsFloat();
                float high = insideArray.get(1).getAsFloat();
                this.heightRanges.add(new HeightEntry(low, high));
            }
        }
    }

    private void processIds(JsonElement element, List<Identifier> list) {
        if (element != null) {
            if (element.isJsonArray()) {
                for (JsonElement jsonElement : element.getAsJsonArray()) {
                    list.add(new Identifier(jsonElement.getAsString()));
                }
            } else if (JsonHelper.isString(element)) {
                list.add(new Identifier(element.getAsString()));
            }
        }
    }

    public Fade getFade() {
        return this.fade;
    }

    public float getMaxAlpha() {
        return this.maxAlpha;
    }

    public float getTransitionSpeed() {
        return this.transitionSpeed;
    }

    public boolean isChangeFog() {
        return this.changeFog;
    }

    public RGBA getFogColors() {
        return this.fogColors;
    }

    public boolean isShouldRotate() {
        return this.shouldRotate;
    }

    public Decorations getDecorations() {
        return this.decorations;
    }

    public List<String> getWeather() {
        return this.weather;
    }

    public List<Identifier> getBiomes() {
        return this.biomes;
    }

    public List<Identifier> getWorlds() {
        return this.worlds;
    }

    public List<HeightEntry> getHeightRanges() {
        return this.heightRanges;
    }

    public DefaultProperties getDefaultProperties() {
        return DefaultProperties.ofSkybox(this);
    }

    public Conditions getConditions() {
        return Conditions.ofSkybox(this);
    }
}
