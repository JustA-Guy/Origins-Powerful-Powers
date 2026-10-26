package net.users.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.users.power.OppAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds DRAG to createLivingAttributes(), the base every living entity type's attributes build on, so mobs and
 * players both get it.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityAttributesMixin {
	@Inject(method = "createLivingAttributes", at = @At("RETURN"))
	private static void opp$addDrag(CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
		cir.getReturnValue().add(OppAttributes.DRAG);
	}
}
