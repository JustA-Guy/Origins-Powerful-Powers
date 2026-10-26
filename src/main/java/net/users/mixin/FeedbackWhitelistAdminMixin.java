package net.users.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.users.command.FeedbackWhitelist;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** whitelistFeedback: the gray "[Steve: …]" copy sent to operators only reaches those on the list (or all, when empty). */
@Mixin(CommandSourceStack.class)
public abstract class FeedbackWhitelistAdminMixin {
	@Redirect(method = "broadcastToAdmins", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ServerPlayer;sendSystemMessage(Lnet/minecraft/network/chat/Component;)V"))
	private void opp$whitelistAdminFeedback(ServerPlayer player, Component message) {
		if (FeedbackWhitelist.allows(player)) {
			player.sendSystemMessage(message);
		}
	}
}
