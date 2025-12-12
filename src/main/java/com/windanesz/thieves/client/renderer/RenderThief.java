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

	public static final ResourceLocation TEXTURE = new ResourceLocation(Thieves.MODID, "textures/entity/thief_0.png");

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
		return TEXTURE;
	}

	@Override
	public void doRender(EntityThief entity, double x, double y, double z, float entityYaw, float partialTicks) {
		this.mainModel.setLivingAnimations(entity, entity.limbSwing, entity.limbSwingAmount, partialTicks);
		super.doRender(entity, x, y, z, entityYaw, partialTicks);
	}
}