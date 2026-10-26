package net.users.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.users.tracking.KnockbackTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityKnockbackMixin {
	@Inject(method = "knockback", at = @At("HEAD"))
	private void opp$recordKnockback(double strength, double x, double z, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		KnockbackTracker.record(self, strength);
	}
}
