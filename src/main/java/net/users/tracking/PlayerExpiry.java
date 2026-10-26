package net.users.tracking;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Forgets players who have been offline too long. The gamerule oppPlayerExpiryDays (default 30, 0 = never) is in
 * real days, since game time doesn't pass while the server is off. When a player last seen longer ago than that is
 * found (checked once a minute), every listener gets their UUID: TrackerCleanup drops their tracker entries and the
 * global store unbinds them from its keys. Last-seen times are saved with the world.
 */
public final class PlayerExpiry {
	public static final GameRules.Key<GameRules.IntegerValue> EXPIRY_DAYS =
		GameRuleRegistry.register("oppPlayerExpiryDays", GameRules.Category.PLAYER, GameRuleFactory.createIntRule(30, 0));

	private static final String DATA_NAME = "origins-powerful-powers_players";
	private static final int SWEEP_TICKS = 1200;
	private static final long DAY_MILLIS = 86_400_000L;
	private static final List<BiConsumer<MinecraftServer, UUID>> LISTENERS = new ArrayList<>();

	private PlayerExpiry() {
	}

	/** Called with the server and the UUID of each player who expired. */
	public static void onExpired(BiConsumer<MinecraftServer, UUID> listener) {
		LISTENERS.add(listener);
	}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			data(server).lastSeen.put(handler.player.getUUID(), System.currentTimeMillis());
			data(server).setDirty();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (data(server).lastSeen.remove(handler.player.getUUID()) != null) {
				data(server).setDirty();
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % SWEEP_TICKS == 0) {
				sweep(server);
			}
		});
	}

	private static void sweep(MinecraftServer server) {
		int days = server.getGameRules().getInt(EXPIRY_DAYS);
		if (days <= 0) {
			return;
		}
		long cutoff = System.currentTimeMillis() - days * DAY_MILLIS;
		Data data = data(server);
		List<UUID> expired = new ArrayList<>();
		Iterator<Map.Entry<UUID, Long>> iterator = data.lastSeen.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Long> entry = iterator.next();
			if (entry.getValue() < cutoff && server.getPlayerList().getPlayer(entry.getKey()) == null) {
				expired.add(entry.getKey());
				iterator.remove();
			}
		}
		if (!expired.isEmpty()) {
			data.setDirty();
			for (UUID id : expired) {
				for (BiConsumer<MinecraftServer, UUID> listener : LISTENERS) {
					listener.accept(server, id);
				}
			}
		}
	}

	private static Data data(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(Data::load, Data::new, DATA_NAME);
	}

	private static final class Data extends SavedData {
		final Map<UUID, Long> lastSeen = new HashMap<>();

		static Data load(CompoundTag tag) {
			Data data = new Data();
			CompoundTag seen = tag.getCompound("last_seen");
			for (String key : seen.getAllKeys()) {
				try {
					data.lastSeen.put(UUID.fromString(key), seen.getLong(key));
				} catch (IllegalArgumentException ignored) {
					// not a UUID; skip it
				}
			}
			return data;
		}

		@Override
		public CompoundTag save(CompoundTag tag) {
			CompoundTag seen = new CompoundTag();
			lastSeen.forEach((id, millis) -> seen.putLong(id.toString(), millis));
			tag.put("last_seen", seen);
			return tag;
		}
	}
}
