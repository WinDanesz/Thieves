package com.windanesz.thieves.client.renderer;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.client.model.ModelThief;
import com.windanesz.thieves.entity.EntityThief;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

public class RenderThief extends RenderBiped<EntityThief> {

	private static final ResourceLocation[] THIEF_TEXTURES = new ResourceLocation[] {
			new ResourceLocation(Thieves.MODID, "textures/entity/thief_0.png"),
			new ResourceLocation(Thieves.MODID, "textures/entity/thief_1.png"),
			new ResourceLocation(Thieves.MODID, "textures/entity/thief_2.png"),
			new ResourceLocation(Thieves.MODID, "textures/entity/thief_3.png"),
			new ResourceLocation(Thieves.MODID, "textures/entity/thief_4.png")
	};

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
		if (skin < 0 || skin >= THIEF_TEXTURES.length) {
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