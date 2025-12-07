package com.windanesz.thieves.client.renderer;

import com.windanesz.thieves.Thieves;
import com.windanesz.thieves.client.model.ModelGoblin;
import com.windanesz.thieves.entity.EntityThief;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

public class RenderThief extends RenderBiped<EntityThief> {

	public static final ResourceLocation TEXTURE = new ResourceLocation(Thieves.MODID, "textures/entity/thief.png");

	public RenderThief(RenderManager rendermanagerIn) {
		super(rendermanagerIn, new ModelGoblin(), 0.2F);
		LayerBipedArmor layerbipedarmor = new LayerBipedArmor(this) {
			protected void initArmor() {
				this.modelLeggings = new ModelGoblin();
				this.modelArmor = new ModelGoblin();
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
		// Ensure setLivingAnimations is called before render
		((ModelGoblin) this.mainModel).setLivingAnimations(entity, entity.limbSwing, entity.limbSwingAmount, partialTicks);
		super.doRender(entity, x, y, z, entityYaw, partialTicks);
	}

	@Override
	protected void preRenderCallback(EntityThief entitylivingbaseIn, float partialTickTime) {
		super.preRenderCallback(entitylivingbaseIn, partialTickTime);
	}
}