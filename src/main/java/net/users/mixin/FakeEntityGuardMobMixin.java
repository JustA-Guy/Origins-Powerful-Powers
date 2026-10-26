package net.users.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.users.tracking.FakeEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A fake entity zombie drowning into a drowned (and similar conversions) would add a real mob. */
@Mixin(Mob.class)
public abstract class FakeEntityGuardMobMixin {
	@Inject(method = "convertTo", at = @At("HEAD"), cancellable = true)
	private <T extends Mob> void opp$noFakeEntityConversion(EntityType<T> type, boolean keepEquipment, CallbackInfoReturnable<T> cir) {
		if (FakeEntityTracker.TICKING && !((Mob) (Object) this).level().isClientSide()) {
			cir.setReturnValue(null);
		}
	}
}
