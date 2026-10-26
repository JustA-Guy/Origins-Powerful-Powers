package net.users.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.users.tracking.AttackReachTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerAttackReachMixin {
	@Inject(method = "attack", at = @At("HEAD"))
	private void opp$captureAttackReach(Entity target, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		AttackReachTracker.record(self, self.distanceTo(target));
	}
}
