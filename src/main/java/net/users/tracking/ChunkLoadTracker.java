package net.users.tracking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Entity-tied chunk loading: keeps the square of chunks around an entity
 * loaded the way /forceload does, and moves it with the entity. Sources are the chunk_loader power (one per power
 * type) and the load_chunks action or /opp chunkload (one per entity); the entity loads the largest radius among them.
 *
 * Chunks are held with OPP's own ticket type, one ticket key per entity, so two entities over the same chunk (or a
 * /forceload there) never release each other's. Tickets aren't saved: everything is let go when the server stops.
 *
 * It ends when the entity dies (or is discarded), when every source is gone, and nowhere else: a player who logs out
 * keeps their last chunks loaded, and an entity that changes dimension takes the loading with it.
 */
public final class ChunkLoadTracker {
	/** Same level as /forceload's FORCED tickets (33 - 2 = 31). */
	private static final int TICKET_DISTANCE = 2;
	private static final TicketType<Integer> TICKET = TicketType.create("origins-powerful-powers:entity_chunk_loader", Integer::compare);

	/** The key a load_chunks action or command uses; powers use their power type id. */
	public static final String ACTION_SOURCE = "action";

	private record Source(int radius, int ticksLeft) {
	}

	private static final class Holder {
		final UUID id;
		/** This holder's ticket key: unique so nobody else's tickets share its identity. */
		final int ticketKey;
		Entity entity;
		final Map<String, Source> sources = new LinkedHashMap<>();
		/** What's held right now. */
		ServerLevel level;
		ChunkPos center;
		int radius = -1;
		final Set<ChunkPos> loaded = new HashSet<>();

		Holder(UUID id, int ticketKey, Entity entity) {
			this.id = id;
			this.ticketKey = ticketKey;
			this.entity = entity;
		}
	}

	private static final Map<UUID, Holder> HOLDERS = new HashMap<>();
	private static int nextTicketKey = 1;

	private ChunkLoadTracker() {
	}

	/** Adds or replaces one source on the entity. ticks 0 or less: until it's stopped. */
	public static void start(Entity entity, String source, int radius, int ticks) {
		if (!(entity.level() instanceof ServerLevel) || (entity instanceof LivingEntity living && living.isDeadOrDying())) {
			return;
		}
		Holder holder = HOLDERS.computeIfAbsent(entity.getUUID(), id -> new Holder(id, nextTicketKey++, entity));
		holder.entity = entity;
		Source wanted = new Source(Math.max(0, radius), ticks > 0 ? ticks : -1);
		Source had = holder.sources.get(source);
		// Powers call this every tick; only a real change needs the chunks recomputed.
		if (had == null || had.radius != wanted.radius || wanted.ticksLeft > 0) {
			holder.sources.put(source, wanted);
			update(holder);
		}
	}

	/** Removes one source. The chunks go when the entity has none left. Returns true if it had that source. */
	public static boolean stop(UUID entity, String source) {
		Holder holder = HOLDERS.get(entity);
		if (holder == null || holder.sources.remove(source) == null) {
			return false;
		}
		if (holder.sources.isEmpty()) {
			release(holder);
			HOLDERS.remove(entity);
		} else {
			update(holder);
		}
		return true;
	}

	/** Death or discard: every source ends. A power still on a respawned player starts it again. */
	public static void entityGone(UUID entity) {
		Holder holder = HOLDERS.remove(entity);
		if (holder != null) {
			release(holder);
		}
	}

	public static boolean isLoading(Entity entity) {
		return HOLDERS.containsKey(entity.getUUID());
	}

	/** Every entity loading chunks, as "name: N chunks around x, z in dimension (sources)". */
	public static List<String> describeAll() {
		List<String> lines = new ArrayList<>();
		for (Holder holder : HOLDERS.values()) {
			String name = holder.entity != null ? holder.entity.getName().getString() : holder.id.toString();
			String where = holder.center == null ? "nowhere yet"
				: holder.center.x + ", " + holder.center.z + " in " + holder.level.dimension().location();
			List<String> sources = new ArrayList<>();
			holder.sources.forEach((key, source) -> sources.add(key + " r" + source.radius
				+ (source.ticksLeft > 0 ? " " + source.ticksLeft + "t" : "")));
			lines.add(name + ": " + holder.loaded.size() + " chunks around chunk " + where + " (" + String.join(", ", sources) + ")");
		}
		return lines;
	}

	public static void tick(MinecraftServer server) {
		Iterator<Holder> iterator = HOLDERS.values().iterator();
		while (iterator.hasNext()) {
			Holder holder = iterator.next();
			boolean changed = holder.sources.entrySet().removeIf(entry -> entry.getValue().ticksLeft == 1);
			holder.sources.replaceAll((key, source) -> source.ticksLeft > 1 ? new Source(source.radius, source.ticksLeft - 1) : source);
			if (holder.sources.isEmpty()) {
				release(holder);
				iterator.remove();
				continue;
			}
			Entity entity = resolve(server, holder);
			if (entity instanceof LivingEntity living && living.isDeadOrDying()) {
				release(holder);
				iterator.remove();
				continue;
			}
			// Not found: a player who logged out, or an entity between dimensions. Its last chunks stay put.
			if (changed || entity != null && (entity.level() != holder.level || !entity.chunkPosition().equals(holder.center))) {
				update(holder);
			}
		}
	}

	public static void clear() {
		HOLDERS.clear();
		nextTicketKey = 1;
	}

	/** The live entity: the one held, or after a relog, respawn or dimension change, the new object with its UUID. */
	private static Entity resolve(MinecraftServer server, Holder holder) {
		Entity entity = holder.entity;
		if (entity != null && !entity.isRemoved()) {
			return entity;
		}
		Entity found = server.getPlayerList().getPlayer(holder.id);
		if (found == null && !(entity instanceof Player)) {
			for (ServerLevel level : server.getAllLevels()) {
				found = level.getEntity(holder.id);
				if (found != null) {
					break;
				}
			}
		}
		if (found != null) {
			holder.entity = found;
		}
		return found;
	}

	/** Moves the held square to where the entity is now, at the largest radius among its sources. */
	private static void update(Holder holder) {
		Entity entity = holder.entity;
		if (entity == null || entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
			return;
		}
		int radius = 0;
		for (Source source : holder.sources.values()) {
			radius = Math.max(radius, source.radius);
		}
		ChunkPos center = entity.chunkPosition();
		if (level == holder.level && center.equals(holder.center) && radius == holder.radius) {
			return;
		}
		Set<ChunkPos> wanted = new HashSet<>();
		for (int x = -radius; x <= radius; x++) {
			for (int z = -radius; z <= radius; z++) {
				wanted.add(new ChunkPos(center.x + x, center.z + z));
			}
		}
		if (level != holder.level) {
			release(holder);
		} else {
			for (Iterator<ChunkPos> it = holder.loaded.iterator(); it.hasNext(); ) {
				ChunkPos pos = it.next();
				if (!wanted.contains(pos)) {
					level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_DISTANCE, holder.ticketKey);
					it.remove();
				}
			}
		}
		for (ChunkPos pos : wanted) {
			if (holder.loaded.add(pos)) {
				level.getChunkSource().addRegionTicket(TICKET, pos, TICKET_DISTANCE, holder.ticketKey);
			}
		}
		holder.level = level;
		holder.center = center;
		holder.radius = radius;
	}

	private static void release(Holder holder) {
		if (holder.level != null) {
			for (ChunkPos pos : holder.loaded) {
				holder.level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_DISTANCE, holder.ticketKey);
			}
		}
		holder.loaded.clear();
		holder.level = null;
		holder.center = null;
		holder.radius = -1;
	}
}
