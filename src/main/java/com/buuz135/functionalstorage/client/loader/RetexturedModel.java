package com.buuz135.functionalstorage.client.loader;

import com.buuz135.functionalstorage.block.FramedDrawerBlock;
import com.buuz135.functionalstorage.client.model.FramedDrawerModelData;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gson.*;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Class from Mantle {@url https://github.com/SlimeKnights/Mantle/blob/1.18.2/src/main/java/slimeknights/mantle/client/model/RetexturedModel.java}
 * <p>
 * Model that dynamically retextures a list of textures based on data from {@link RetexturedHelper}.
 */
@SuppressWarnings("WeakerAccess")

public class RetexturedModel implements IUnbakedGeometry<RetexturedModel> {
    private final SimpleBlockModel model;
    private final Set<String> retextured;

    public RetexturedModel(SimpleBlockModel model, Set<String> retextured) {
        this.model = model;
        this.retextured = retextured;
    }

    /**
     * Gets a list of all names to retexture based on the block model texture references
     * @param owner        Model config instance
     * @param model        Model fallback
     * @param originalSet  Original list of names to retexture
     * @return Set of textures including parent textures
     */
    public static Set<String> getAllRetextured(IGeometryBakingContext owner, SimpleBlockModel model, Set<String> originalSet) {
        Set<String> retextured = Sets.newHashSet(originalSet);
        for (Map<String, Either<Material, String>> textures : ModelTextureIteratable.of(model)) {
            textures.forEach((name, either) ->
                    either.ifRight(parent -> {
                        if (retextured.contains(parent)) {
                            retextured.add(name);
                        }
                    })
            );
        }
        return ImmutableSet.copyOf(retextured);
    }

    @Override
    public Collection<Material> getMaterials(IGeometryBakingContext owner, Function<ResourceLocation, UnbakedModel> modelGetter, Set<Pair<String, String>> missingTextureErrors) {
        return model.getMaterials(owner, modelGetter, missingTextureErrors);
    }

    @Override
    public BakedModel bake(IGeometryBakingContext owner, ModelBakery bakery, Function<Material, TextureAtlasSprite> spriteGetter, ModelState transform, ItemOverrides overrides, ResourceLocation location) {
        // bake the model and return
        BakedModel baked = model.bakeModel(owner, transform, overrides, spriteGetter, location);
        return new Baked(baked, owner, model, transform, getAllRetextured(owner, this.model, retextured), spriteGetter);
    }

    /** Registered model loader instance registered */
    public static class Loader implements IGeometryLoader<RetexturedModel>, ResourceManagerReloadListener {
        public static final Loader INSTANCE = new Loader();

        private Loader() {
        }

        @Override
        public void onResourceManagerReload(ResourceManager resourceManager) {
        }

        @Override
        public RetexturedModel read(JsonObject json, JsonDeserializationContext context) {
            // get base model
            SimpleBlockModel model = SimpleBlockModel.deserialize(context, json);

            // get list of textures to retexture
            Set<String> retextured = getRetextured(json);

            // return retextured model
            return new RetexturedModel(model, retextured);
        }

        /**
         * Gets the list of retextured textures from the model
         * @param json  Model json
         * @return  List of textures
         */
        public static Set<String> getRetextured(JsonObject json) {
            if (json.has("retextured")) {
                // if an array, set from each texture in array
                JsonElement retextured = json.get("retextured");
                if (retextured.isJsonArray()) {
                    JsonArray array = retextured.getAsJsonArray();
                    if (array.size() == 0) {
                        throw new JsonSyntaxException("Must have at least one texture in retextured");
                    }
                    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
                    for (int i = 0; i < array.size(); i++) {
                        builder.add(GsonHelper.convertToString(array.get(i), "retextured[" + i + "]"));
                    }
                    return builder.build();
                }
                // if string, single texture
                if (retextured.isJsonPrimitive()) {
                    return ImmutableSet.of(retextured.getAsString());
                }
            }
            // if neither or missing, error
            throw new JsonSyntaxException("Missing retextured, expected to find a String or a JsonArray");
        }
    }

    /** Baked variant of the model, used to swap out quads based on the texture */
    public static class Baked extends DynamicBakedWrapper<BakedModel> {
        /**
         * Cache of texture name to baked model
         */
        private final Map<String, BakedModel> cache = new ConcurrentHashMap<>();
        /* Properties for rebaking */
        private final IGeometryBakingContext owner;
        private final SimpleBlockModel model;
        private final ModelState transform;
        /**
         * List of texture names that are retextured
         */
        private final Set<String> retextured;
        private final Function<Material, TextureAtlasSprite> spriteGetter;

        public Baked(BakedModel baked, IGeometryBakingContext owner, SimpleBlockModel model, ModelState transform, Set<String> retextured, Function<Material, TextureAtlasSprite> spriteGetter) {
            super(baked);
            this.model = model;
            this.owner = owner;
            this.transform = transform;
            this.retextured = retextured;
            this.spriteGetter = spriteGetter;
        }

        /**
         * Gets the model with the given texture applied
         * @param framedDrawerModelData  Texture location
         * @return  Retextured model
         */
        private BakedModel getRetexturedModel(FramedDrawerModelData framedDrawerModelData) {
            return model.bakeDynamic(new RetexturedConfiguration(owner, retextured, framedDrawerModelData), transform, spriteGetter);
        }

        /**
         * Gets a cached retextured model, computing it if missing from the cache
         * @param framedDrawerModelData  Block determining the texture
         * @return  Retextured model
         */
        private BakedModel getCachedModel(FramedDrawerModelData framedDrawerModelData) {
            return cache.computeIfAbsent(framedDrawerModelData.getCode(), (s) -> this.getRetexturedModel(framedDrawerModelData));
        }

        @Override
        public TextureAtlasSprite getParticleIcon(ModelData data) {
            // if particle is retextured, fetch particle from the cached model
            if (retextured.contains("particle")) {
                FramedDrawerModelData framedDrawerModelData = data.get(FramedDrawerModelData.FRAMED_PROPERTY);
                if (framedDrawerModelData != null) {
                    return getCachedModel(framedDrawerModelData).getParticleIcon(data);
                }
            }
            return originalModel.getParticleIcon(data);
        }

        @Nonnull
        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction direction, RandomSource random, ModelData data, RenderType renderType) {
            FramedDrawerModelData framedDrawerModelData = data.get(FramedDrawerModelData.FRAMED_PROPERTY);
            if (framedDrawerModelData == null) {
                return originalModel.getQuads(state, direction, random, data, renderType);
            }
            return getCachedModel(framedDrawerModelData).getQuads(state, direction, random, data, renderType);
        }

        @Override
        public ItemOverrides getOverrides() {
            return RetexturedOverride.INSTANCE;
        }
    }

    /**
     * Model configuration wrapper to retexture the block
     */
    public static class RetexturedConfiguration extends ModelConfigurationWrapper {
        /** List of textures to retexture */
        private final Set<String> retextured;
        /** Replacement texture */
        private final HashMap<String, Material> texture;

        /**
         * Creates a new configuration wrapper
         *
         * @param base       Original model configuration
         * @param retextured Set of textures that should be retextured
         * @param texture    New texture to replace those in the set
         */
        public RetexturedConfiguration(IGeometryBakingContext base, Set<String> retextured, FramedDrawerModelData texture) {
            super(base);
            this.retextured = retextured;
            this.texture = new HashMap<>();
            texture.getDesign().forEach((s, item) -> {
                this.texture.put(s, new Material(TextureAtlas.LOCATION_BLOCKS, ModelHelper.getParticleTexture(item)));
            });
        }

        @Override
        public boolean hasMaterial(String name) {
            if (retextured.contains(name) && texture.containsKey(name)) {
                return !MissingTextureAtlasSprite.getLocation().equals(texture.get(name).texture());
            }
            return super.hasMaterial(name);
        }

        @Override
        public Material getMaterial(String name) {
            if (retextured.contains(name) && texture.containsKey(name)) {
                return texture.get(name);
            }
            return super.getMaterial(name);
        }
    }

    /** Override list to swap the texture in from NBT */
    private static class RetexturedOverride extends ItemOverrides {
        private static final RetexturedOverride INSTANCE = new RetexturedOverride();

        @Nullable
        @Override
        public BakedModel resolve(BakedModel originalModel, ItemStack stack, @Nullable ClientLevel world, @Nullable LivingEntity entity, int pSeed) {
            if (stack.isEmpty() || !stack.hasTag()) {
                return originalModel;
            }

            // get the block first, ensuring its valid
            FramedDrawerModelData data = FramedDrawerBlock.getDrawerModelData(stack);
            if (data == null) {
                return originalModel;
            }

            // if valid, use the block
            return ((Baked)originalModel).getCachedModel(data);
        }
    }
}
