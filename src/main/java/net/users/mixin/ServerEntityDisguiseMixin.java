package net.users.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.users.tracking.DisguiseTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Spawn packets are built per viewer here, so a disguised viewer gets the disguise's packets instead. */
@Mixin(ServerEntity.class)
public abstract class ServerEntityDisguiseMixin {
	@Shadow
	@Final
	private Entity entity;

	@Inject(method = "sendPairingData", at = @At("HEAD"), cancellable = true)
	private void opp$sendDisguise(ServerPlayer viewer, Consumer<Packet<ClientGamePacketListener>> sender, CallbackInfo ci) {
		if (DisguiseTracker.appliesTo(entity, viewer)) {
			DisguiseTracker.sendPairing(entity, sender);
			ci.cancel();
		}
	}
}
