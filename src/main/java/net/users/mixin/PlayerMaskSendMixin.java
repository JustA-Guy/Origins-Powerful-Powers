package net.users.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.users.tracking.PlayerMaskPackets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Name obfuscation and tab-list hiding (PlayerMaskTracker): every packet a player is sent can be swapped for a
 * rewritten copy here. Swapping the argument keeps the copy from passing through the rewrite a second time.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerMaskSendMixin {
	@Shadow
	public ServerPlayer player;

	@ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
		at = @At("HEAD"), argsOnly = true)
	private Packet<?> opp$maskPlayers(Packet<?> packet) {
		return PlayerMaskPackets.rewrite(player, packet);
	}
}
