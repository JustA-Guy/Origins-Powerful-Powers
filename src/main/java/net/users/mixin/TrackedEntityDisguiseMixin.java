package net.users.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.users.tracking.DisguiseTracker;
import net.users.tracking.OppTrackedEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Every update about a tracked entity goes through broadcast(), which sends it to each viewer. For a disguised
 * entity, disguised viewers only get the packets DisguiseTracker.passes allows; everyone else gets them all.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityDisguiseMixin implements OppTrackedEntity {
	@Shadow
	@Final
	Entity entity;

	@Shadow
	@Final
	private Set<ServerPlayerConnection> seenBy;

	@Shadow
	public abstract void removePlayer(ServerPlayer player);

	@Shadow
	public abstract void updatePlayer(ServerPlayer player);

	@Inject(method = "broadcast", at = @At("HEAD"), cancellable = true)
	private void opp$filterForDisguise(Packet<?> packet, CallbackInfo ci) {
		if (!DisguiseTracker.isDisguised(entity)) {
			return;
		}
		for (ServerPlayerConnection connection : seenBy) {
			if (!DisguiseTracker.appliesTo(entity, connection.getPlayer()) || DisguiseTracker.passes(entity, packet)) {
				connection.send(packet);
			}
		}
		ci.cancel();
	}

	@Override
	public List<ServerPlayer> opp$viewers() {
		List<ServerPlayer> viewers = new ArrayList<>();
		for (ServerPlayerConnection connection : seenBy) {
			viewers.add(connection.getPlayer());
		}
		return viewers;
	}

	@Override
	public void opp$resend(ServerPlayer player) {
		removePlayer(player);
		updatePlayer(player);
	}
}
