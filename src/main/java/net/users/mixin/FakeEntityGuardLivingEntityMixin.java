package net.users.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.users.tracking.FakeEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a fake entity ticks, nothing gets hurt and no real entity is pushed. A fake entity itself can never be hurt, even by
 * a power action or /kill: dying would drop real loot.
 */
@Mixin(LivingEntity.class)
public abstract class FakeEntityGuardLivingEntityMixin {
	@Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!self.level().isClientSide() && (FakeEntityTracker.TICKING || FakeEntityTracker.isFakeEntity(self))) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityPushing(CallbackInfo ci) {
		if (FakeEntityTracker.TICKING && !((LivingEntity) (Object) this).level().isClientSide()) {
			ci.cancel();
		}
	}
}
