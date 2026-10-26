package net.users.mixin;

import net.minecraft.world.entity.Entity;
import net.users.tracking.FakeEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A ticking fake entity (see FakeEntityTracker) never touches the blocks it's inside: no pressure plates, tripwire,
 * portals or cactus. FakeEntityTracker.TICKING is only set on the server thread during a fake entity's own tick.
 */
@Mixin(Entity.class)
public abstract class FakeEntityGuardEntityMixin {
	@Inject(method = "checkInsideBlocks", at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityBlockContact(CallbackInfo ci) {
		if (FakeEntityTracker.TICKING && !((Entity) (Object) this).level().isClientSide()) {
			ci.cancel();
		}
	}
}
