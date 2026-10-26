package net.users.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.state.BlockState;
import net.users.tracking.FakeBlockTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps fake blocks (FakeBlockTracker) on a player's screen. Every block packet a player gets passes through here:
 * a block update repeating the real block is swapped for that player's fake, and after a chunk or section update the
 * fakes inside it are sent again, since those packets carry the real blocks.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class FakeBlockSendMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
		at = @At("HEAD"), cancellable = true)
	private void opp$swapInFakeBlock(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
		if (packet instanceof ClientboundBlockUpdatePacket update) {
			BlockState fake = FakeBlockTracker.replaceUpdate(player, update.getPos(), update.getBlockState());
			if (fake != null) {
				ci.cancel();
				player.connection.send(new ClientboundBlockUpdatePacket(update.getPos(), fake));
			}
		}
	}

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
		at = @At("TAIL"))
	private void opp$resendFakeBlocks(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
		if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
			List<Tuple<BlockPos, BlockState>> changes = new ArrayList<>();
			section.runUpdates((pos, state) -> changes.add(new Tuple<>(pos.immutable(), state)));
			FakeBlockTracker.afterSectionUpdate(player, changes.isEmpty() ? null : SectionPos.of(changes.get(0).getA()), changes);
		} else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
			FakeBlockTracker.afterChunk(player, chunk.getX(), chunk.getZ());
		}
	}
}
