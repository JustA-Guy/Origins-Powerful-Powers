package net.users.mixin;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.users.tracking.FakeEntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A client attacking a fake entity sends its (fake) entity id; vanilla finds no entity and ignores it. This passes
 * the attack to FakeEntityTracker first. handleInteract is first called on the network thread and re-queued onto
 * the server thread, so only the server-thread call is used.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class FakeEntityAttackMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleInteract", at = @At("HEAD"))
	private void opp$attackFakeEntity(ServerboundInteractPacket packet, CallbackInfo ci) {
		if (!player.server.isSameThread()) {
			return;
		}
		int entityId = ((ServerboundInteractPacketAccessor) packet).opp$getEntityId();
		packet.dispatch(new ServerboundInteractPacket.Handler() {
			@Override
			public void onInteraction(InteractionHand hand) {
			}

			@Override
			public void onInteraction(InteractionHand hand, Vec3 location) {
			}

			@Override
			public void onAttack() {
				FakeEntityTracker.onAttacked(entityId, player);
			}
		});
	}
}
