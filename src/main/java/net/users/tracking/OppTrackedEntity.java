package net.users.tracking;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Added to vanilla's ChunkMap.TrackedEntity (package-private) by TrackedEntityDisguiseMixin. */
public interface OppTrackedEntity {
	/** Players whose client currently has this entity. */
	List<ServerPlayer> opp$viewers();

	/** Removes the entity from the player's client and sends it again, so a disguise change shows. */
	void opp$resend(ServerPlayer player);
}
