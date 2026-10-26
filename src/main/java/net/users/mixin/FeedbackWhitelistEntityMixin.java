package net.users.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.users.command.FeedbackWhitelist;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** whitelistFeedback: a player's own command feedback only reaches them if they're on the list (or it's empty). */
@Mixin(Entity.class)
public abstract class FeedbackWhitelistEntityMixin {
	@Inject(method = "acceptsSuccess", at = @At("RETURN"), cancellable = true)
	private void opp$whitelistFeedback(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && (Object) this instanceof ServerPlayer player && !FeedbackWhitelist.allows(player)) {
			cir.setReturnValue(false);
		}
	}
}
