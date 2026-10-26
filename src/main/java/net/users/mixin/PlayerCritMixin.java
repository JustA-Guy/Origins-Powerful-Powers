package net.users.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.users.tracking.CritTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records melee criticals for last_hit_was_crit and last_damage_was_crit. Vanilla calls Player#crit only for a real
 * critical, after the damage has landed, so the attack is bracketed: it starts as "not a crit" and crit() sets it.
 *
 * Server-side only: the client runs attack() too when it predicts a hit, and in singleplayer both threads share the
 * tracker's maps.
 */
@Mixin(Player.class)
public abstract class PlayerCritMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void opp$startAttack(Entity target, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide()) {
			CritTracker.beginAttack(self);
		}
	}

	@Inject(method = "attack", at = @At("TAIL"))
	private void opp$finishAttack(Entity target, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide()) {
			CritTracker.endAttack(self);
		}
	}

	@Inject(method = "crit", at = @At("HEAD"))
	private void opp$markCrit(Entity target, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide()) {
			CritTracker.markCrit(self, target);
		}
	}
}
