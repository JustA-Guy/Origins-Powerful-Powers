package net.users.tracking;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.users.OriginsPowerfulPowers;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skins for fake-player fake entities, looked up by username the way the server looks up any account: the profile cache
 * first (usercache.json), then Mojang. The lookup runs off the server thread, so a fake entity spawned before the answer
 * arrives wears the default skin and is re-sent to its viewers once the real one is known.
 *
 * An offline-mode server, or no internet, simply never gets a skin; the fake entity stays on the default one.
 */
public final class FakeSkins {
	/** Lowercased name -> the textures property, or an empty profile when the lookup found nothing. */
	private static final Map<String, GameProfile> CACHE = new ConcurrentHashMap<>();
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

	private FakeSkins() {
	}

	/** The cached profile for a username, or null while it's still being looked up (or if it failed). */
	public static GameProfile cached(String name) {
		return CACHE.get(name.toLowerCase(java.util.Locale.ROOT));
	}

	/**
	 * Makes sure a lookup for this name is running or done. onReady runs on the server thread when a lookup
	 * finds a skin, so the caller can re-send the fake entity.
	 */
	public static void request(MinecraftServer server, String name, Runnable onReady) {
		String key = name.toLowerCase(java.util.Locale.ROOT);
		if (CACHE.containsKey(key) || !PENDING.add(key)) {
			return;
		}
		Util.backgroundExecutor().execute(() -> {
			GameProfile found = lookUp(server, name);
			CACHE.put(key, found == null ? new GameProfile(UUID.randomUUID(), name) : found);
			PENDING.remove(key);
			if (found != null) {
				server.execute(onReady);
			}
		});
	}

	/** Runs off the server thread: the cache, then Mojang for the textures. */
	private static GameProfile lookUp(MinecraftServer server, String name) {
		try {
			Optional<GameProfile> known = server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name);
			if (known.isEmpty()) {
				OriginsPowerfulPowers.LOGGER.warn("[fake entity] no account named {}, so no skin", name);
				return null;
			}
			// Signed: the client loads other players' skins with requireSecure, so it throws away unsigned textures and
			// falls back to a default skin. The signature covers only the textures value, so it survives the copy onto
			// the fake entity's random-id profile.
			GameProfile filled = server.getSessionService().fillProfileProperties(known.get(), true);
			if (filled == null || filled.getProperties().get("textures").isEmpty()) {
				OriginsPowerfulPowers.LOGGER.warn("[fake entity] found {} but Mojang returned no skin for it", name);
				return null;
			}
			return filled;
		} catch (RuntimeException e) {
			OriginsPowerfulPowers.LOGGER.warn("[fake entity] couldn't look up the skin for {}: {}", name, e.toString());
			return null;
		}
	}

	/** Copies the looked-up skin onto a profile that keeps its own id and name. */
	public static void applySkin(GameProfile target, GameProfile source) {
		target.getProperties().removeAll("textures");
		for (Property property : source.getProperties().get("textures")) {
			target.getProperties().put("textures", property);
		}
	}
}
