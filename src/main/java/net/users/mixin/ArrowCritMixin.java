package net.users.mixin;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import net.users.tracking.CritTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arrow criticals (a fully drawn bow) for last_hit_was_crit and last_damage_was_crit. Injected at the tail, after the
 * arrow's damage, because hurt() resets the victim's flag as the damage starts.
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowCritMixin {
	@Inject(method = "onHitEntity", at = @At("TAIL"))
	private void opp$recordArrowCrit(EntityHitResult result, CallbackInfo ci) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (!self.level().isClientSide()) {
			CritTracker.recordArrowHit(self.getOwner(), result.getEntity(), self.isCritArrow());
		}
	}
}
