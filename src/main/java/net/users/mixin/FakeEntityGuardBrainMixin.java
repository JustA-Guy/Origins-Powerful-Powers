package net.users.mixin;

import net.minecraft.world.entity.ai.Brain;
import net.users.tracking.FakeEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Brain-based mobs (piglins, villagers, wardens, ...) act through their brain; a fake entity skips it entirely. */
@Mixin(Brain.class)
public abstract class FakeEntityGuardBrainMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityBrain(CallbackInfo ci) {
		if (FakeEntityTracker.TICKING) {
			ci.cancel();
		}
	}
}
