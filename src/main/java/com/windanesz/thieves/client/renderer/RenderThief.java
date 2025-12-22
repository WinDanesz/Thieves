package com.windanesz.thieves.client.renderer;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.entity.EntityThief;
import com.windanesz.thieves.client.model.ModelThief;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

public class RenderThief extends RenderBiped<EntityThief> {

	private static final ResourceLocation[] THIEF_TEXTURES = new ResourceLocation[EntityThief.SKIN_VARIATION_COUNT];
	static { for (int i = 0; i < EntityThief.SKIN_VARIATION_COUNT; i++) THIEF_TEXTURES[i] = new ResourceLocation(Thieves.MODID, "textures/entity/thief_" + i + ".png"); }

	public RenderThief(RenderManager rendermanagerIn) {
		super(rendermanagerIn, new ModelThief(0, false), 0.5f);
		LayerBipedArmor layerbipedarmor = new LayerBipedArmor(this) {
			protected void initArmor() {
				this.modelLeggings =  new ModelThief(0, false);
				this.modelArmor =  new ModelThief(0, false);
			}
		};
		this.addLayer(layerbipedarmor);
	}

	@Nullable
	@Override
	protected ResourceLocation getEntityTexture(EntityThief entity) {
		int skin = entity.getSkinIndex();
		if (skin < 0 || skin >= EntityThief.SKIN_VARIATION_COUNT) {
			skin = 0;
		}
		return THIEF_TEXTURES[skin];
	}

	@Override
	public void doRender(EntityThief entity, double x, double y, double z, float entityYaw, float partialTicks) {
		this.mainModel.setLivingAnimations(entity, entity.limbSwing, entity.limbSwingAmount, partialTicks);
		super.doRender(entity, x, y, z, entityYaw, partialTicks);
	}
}