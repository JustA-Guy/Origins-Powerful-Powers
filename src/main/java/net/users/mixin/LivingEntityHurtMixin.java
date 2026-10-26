package net.users.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.users.tracking.CritTracker;
import net.users.tracking.DamageHistoryTracker;
import net.users.tracking.LastDamageTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the recent damage windows from LivingEntity#hurt, since Fabric API for 1.20.1 has no AFTER_DAMAGE event.
 * isDamageSourceBlocked() only reads, so calling it again here is safe.
 *
 * Server-side only: the client runs hurt() when predicting damage, and in singleplayer both threads share the
 * tracker maps.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {
	@Inject(method = "hurt", at = @At("HEAD"))
	private void opp$trackIncomingDamage(DamageSource damageSource, float amount, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		boolean blocked = amount > 0.0F && self.isDamageSourceBlocked(damageSource);
		DamageHistoryTracker.record(self, amount, blocked, self.level().getGameTime());
		LastDamageTracker.record(self, damageSource, amount);
		// Every hit starts as "not a critical"; Player#crit (melee) or ArrowCritMixin sets it right after this.
		CritTracker.recordTaken(self, false);
	}
}
