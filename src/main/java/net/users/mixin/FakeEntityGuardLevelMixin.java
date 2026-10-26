package net.users.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.users.tracking.FakeEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A ticking fake entity can't change blocks: trampling farmland, pressing plates, stepping on turtle eggs, and so on. */
@Mixin(Level.class)
public abstract class FakeEntityGuardLevelMixin {
	@Shadow
	public abstract boolean isClientSide();

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityBlockChange(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
		if (FakeEntityTracker.TICKING && !isClientSide()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
		at = @At("HEAD"), cancellable = true)
	private void opp$noFakeEntityBlockBreak(BlockPos pos, boolean drop, Entity breaker, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
		if (FakeEntityTracker.TICKING && !isClientSide()) {
			cir.setReturnValue(false);
		}
	}
}
