package net.users.tracking;

import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.users.OriginsPowerfulPowers;
import net.users.compat.sn.NicknameCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-viewer masks on players:
 *   OBFUSCATE: the player's username and Styled Nicknames nickname show as magic text (§k) to that viewer, wherever
 *              they appear in text sent to them (PlayerMaskPackets), in the tab list, and on the name tag.
 *   TAB_HIDE:  the player's row is left out of that viewer's tab list. Nothing else changes.
 *
 * Rules come from powers (refreshed while active, dropped after a short ttl), and from commands and actions (the
 * MANUAL source, one per player per kind). Who each rule applies to is worked out per viewer every REFRESH_TICKS, or
 * sooner after a change, and kept in a ViewerState that the packet hook reads. A player never masks themselves.
 *
 * The name tag uses a team that exists only on that viewer's client, with its color set to OBFUSCATED: the client
 * formats a player's name with their team's color, and that "color" is the magic-text format. It copies the
 * options of the player's real team, and real team packets naming the player are filtered for that viewer.
 */
public final class PlayerMaskTracker {
	public enum Kind {
		OBFUSCATE, TAB_HIDE
	}

	/** Whether the rule applies for this viewer. holder is the masked player, online. */
	@FunctionalInterface
	public interface ViewerTest {
		boolean test(ServerPlayer viewer, ServerPlayer holder);
	}

	/** The source key commands and actions share: one manual rule per player per kind. */
	public static final Object MANUAL = "manual";

	private static final int REFRESH_TICKS = 10;
	/** Shortest nickname matched in free text, so a one- or two-letter nickname doesn't scramble ordinary words. */
	private static final int MIN_NICKNAME_LENGTH = 3;
	private static final String TEAM_PREFIX = "opp_obf_";

	private record Key(Kind kind, UUID target, Object source) {
	}

	private record Rule(ViewerTest viewers, long expiresAt) {
	}

	/** What one viewer sees right now. Read by PlayerMaskPackets for every packet sent to them. */
	public static final class ViewerState {
		final Set<UUID> obfuscated = new HashSet<>();
		final Set<UUID> hidden = new HashSet<>();
		/** Usernames and nicknames of the obfuscated players, lowercase, and a pattern matching any of them. */
		Set<String> names = Set.of();
		Pattern pattern;
		/** Obfuscated players by username, lowercase, for team packets. */
		final Map<String, UUID> byName = new HashMap<>();
		/** Players whose client-side team this viewer has, and the real team options it copied. */
		final Map<UUID, String> teamsSent = new HashMap<>();
		boolean teamsDirty;

		public boolean isObfuscated(UUID player) {
			return obfuscated.contains(player);
		}

		public boolean isHidden(UUID player) {
			return hidden.contains(player);
		}

		public Pattern pattern() {
			return pattern;
		}

		public boolean isEmpty() {
			return obfuscated.isEmpty() && hidden.isEmpty();
		}

		/** The obfuscated player with this username, or null. */
		public UUID obfuscatedNamed(String name) {
			return byName.get(name.toLowerCase(java.util.Locale.ROOT));
		}

		public void markTeamsDirty() {
			teamsDirty = true;
		}
	}

	private static final Map<Key, Rule> RULES = new HashMap<>();
	private static final Map<UUID, ViewerState> VIEWERS = new HashMap<>();
	private static MinecraftServer server;
	private static boolean changed;

	private PlayerMaskTracker() {
	}

	/** True when any mask exists; the packet hook does nothing otherwise. */
	public static boolean active() {
		return !RULES.isEmpty() || !VIEWERS.isEmpty();
	}

	/** Adds or replaces a rule. ticks 0 or less: until it's removed. */
	public static void put(Kind kind, UUID target, Object source, ViewerTest viewers, int ticks) {
		long now = server == null ? 0 : server.getTickCount();
		RULES.put(new Key(kind, target, source), new Rule(viewers, ticks > 0 ? now + ticks : Long.MAX_VALUE));
		changed = true;
	}

	/** Keeps a power's rule alive for ttl more ticks without forcing a refresh. */
	public static void refreshPower(Kind kind, UUID target, Object power, ViewerTest viewers, int ttl) {
		long now = server == null ? 0 : server.getTickCount();
		Rule had = RULES.put(new Key(kind, target, power), new Rule(viewers, now + ttl));
		if (had == null) {
			changed = true;
		}
	}

	public static boolean remove(Kind kind, UUID target, Object source) {
		boolean removed = RULES.remove(new Key(kind, target, source)) != null;
		changed |= removed;
		return removed;
	}

	/**
	 * The viewer's current state, worked out now if they're still joining (not in the player list yet), so their first
	 * tab-list packet comes out masked. An online player without one waits for tick: a state holds what their client
	 * has been told, and working one out here would make tick think the client already had it and send nothing.
	 */
	public static ViewerState stateFor(ServerPlayer viewer) {
		ViewerState state = VIEWERS.get(viewer.getUUID());
		if (state == null && server != null && !RULES.isEmpty() && server.getPlayerList().getPlayer(viewer.getUUID()) == null) {
			state = new ViewerState();
			try {
				compute(viewer, state);
			} catch (RuntimeException e) {
				OriginsPowerfulPowers.LOGGER.warn("[mask] couldn't work out masks for {}: {}", viewer.getGameProfile().getName(), e.toString());
			}
			VIEWERS.put(viewer.getUUID(), state);
		}
		return state;
	}

	public static void tick(MinecraftServer current) {
		server = current;
		long now = current.getTickCount();
		changed |= RULES.values().removeIf(rule -> rule.expiresAt <= now);
		boolean dirtyTeams = VIEWERS.values().stream().anyMatch(state -> state.teamsDirty);
		if (!changed && !dirtyTeams && now % REFRESH_TICKS != 0) {
			return;
		}
		changed = false;
		if (RULES.isEmpty() && VIEWERS.isEmpty()) {
			return;
		}
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer viewer : current.getPlayerList().getPlayers()) {
			online.add(viewer.getUUID());
			ViewerState old = VIEWERS.get(viewer.getUUID());
			ViewerState next = new ViewerState();
			try {
				compute(viewer, next);
			} catch (RuntimeException e) {
				OriginsPowerfulPowers.LOGGER.warn("[mask] couldn't work out masks for {}: {}", viewer.getGameProfile().getName(), e.toString());
				continue;
			}
			if (old != null) {
				next.teamsSent.putAll(old.teamsSent);
				next.teamsDirty = old.teamsDirty;
			}
			VIEWERS.put(viewer.getUUID(), next);
			sendChanges(viewer, old, next);
		}
		VIEWERS.keySet().retainAll(online);
		if (RULES.isEmpty()) {
			// Everything has been undone on every client; drop the states so the packet hook goes idle.
			VIEWERS.clear();
		}
	}

	public static void clear() {
		RULES.clear();
		VIEWERS.clear();
		server = null;
		changed = false;
	}

	private static void compute(ServerPlayer viewer, ViewerState state) {
		for (Map.Entry<Key, Rule> entry : RULES.entrySet()) {
			Key key = entry.getKey();
			if (key.target.equals(viewer.getUUID())) {
				continue;
			}
			ServerPlayer holder = server.getPlayerList().getPlayer(key.target);
			if (holder == null) {
				continue;
			}
			Set<UUID> set = key.kind == Kind.OBFUSCATE ? state.obfuscated : state.hidden;
			if (!set.contains(key.target) && entry.getValue().viewers.test(viewer, holder)) {
				set.add(key.target);
			}
		}
		Set<String> names = new HashSet<>();
		for (UUID id : state.obfuscated) {
			ServerPlayer holder = server.getPlayerList().getPlayer(id);
			String username = holder.getGameProfile().getName();
			names.add(username.toLowerCase(java.util.Locale.ROOT));
			state.byName.put(username.toLowerCase(java.util.Locale.ROOT), id);
			String nickname = NicknameCompat.nickname(holder);
			if (nickname != null && nickname.length() >= MIN_NICKNAME_LENGTH) {
				names.add(nickname.toLowerCase(java.util.Locale.ROOT));
			}
		}
		state.names = names;
		state.pattern = names.isEmpty() ? null : pattern(names);
	}

	/** Any of the names as a whole word (not inside a longer name or word), ignoring case. */
	private static Pattern pattern(Set<String> names) {
		List<String> sorted = new ArrayList<>(names);
		sorted.sort(Comparator.comparingInt(String::length).reversed());
		StringBuilder regex = new StringBuilder("(?<![\\p{L}\\p{N}_])(?:");
		for (int i = 0; i < sorted.size(); i++) {
			regex.append(i == 0 ? "" : "|").append(Pattern.quote(sorted.get(i)));
		}
		regex.append(")(?![\\p{L}\\p{N}_])");
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	/**
	 * Brings the viewer's client up to date. Tab-list packets go through PlayerMaskPackets on the way out, so
	 * re-sending a player's display name or listed flag is enough for it to come out masked or back to normal.
	 */
	private static void sendChanges(ServerPlayer viewer, ViewerState old, ViewerState next) {
		Set<UUID> oldObfuscated = old == null ? Set.of() : old.obfuscated;
		Set<UUID> oldHidden = old == null ? Set.of() : old.hidden;
		Set<String> oldNames = old == null ? Set.of() : old.names;
		boolean namesChanged = !oldNames.equals(next.names);

		List<ServerPlayer> displayNames = new ArrayList<>();
		List<ServerPlayer> listed = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			if (oldObfuscated.contains(id) != next.obfuscated.contains(id) || namesChanged && next.obfuscated.contains(id)) {
				displayNames.add(player);
			}
			if (oldHidden.contains(id) != next.hidden.contains(id)) {
				listed.add(player);
			}
		}
		if (!displayNames.isEmpty()) {
			viewer.connection.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), displayNames));
		}
		if (!listed.isEmpty()) {
			viewer.connection.send(new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), listed));
		}
		syncTeams(viewer, next);
	}

	/** Gives each obfuscated player a client-side team with the magic-text color, and takes it away again after. */
	private static void syncTeams(ServerPlayer viewer, ViewerState state) {
		Scoreboard real = server.getScoreboard();
		for (UUID id : new ArrayList<>(state.teamsSent.keySet())) {
			ServerPlayer holder = server.getPlayerList().getPlayer(id);
			if (!state.obfuscated.contains(id) || holder == null) {
				state.teamsSent.remove(id);
				PlayerTeam fake = new PlayerTeam(new Scoreboard(), teamName(id));
				viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(fake));
				// Back to the team they're really on, which the client lost when they moved into the fake one.
				if (holder != null) {
					PlayerTeam team = real.getPlayersTeam(holder.getScoreboardName());
					if (team != null) {
						viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, holder.getScoreboardName(),
							ClientboundSetPlayerTeamPacket.Action.ADD));
					}
				}
			}
		}
		for (UUID id : state.obfuscated) {
			ServerPlayer holder = server.getPlayerList().getPlayer(id);
			PlayerTeam team = real.getPlayersTeam(holder.getScoreboardName());
			String signature = team == null ? "" : team.getName() + "|" + team.packOptions() + "|" + team.getNameTagVisibility().name
				+ "|" + team.getCollisionRule().name + "|" + team.getPlayerPrefix().getString() + "|" + team.getPlayerSuffix().getString();
			if (!state.teamsDirty && signature.equals(state.teamsSent.get(id))) {
				continue;
			}
			PlayerTeam fake = new PlayerTeam(new Scoreboard(), teamName(id));
			if (team != null) {
				fake.setDisplayName(team.getDisplayName());
				fake.setPlayerPrefix(team.getPlayerPrefix());
				fake.setPlayerSuffix(team.getPlayerSuffix());
				fake.setNameTagVisibility(team.getNameTagVisibility());
				fake.setDeathMessageVisibility(team.getDeathMessageVisibility());
				fake.setCollisionRule(team.getCollisionRule());
				fake.unpackOptions(team.packOptions());
			}
			fake.setColor(ChatFormatting.OBFUSCATED);
			boolean exists = state.teamsSent.containsKey(id);
			viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(fake, !exists));
			// Joining the fake team takes the player out of their real one on this client only.
			viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(fake, holder.getScoreboardName(),
				ClientboundSetPlayerTeamPacket.Action.ADD));
			state.teamsSent.put(id, signature);
		}
		state.teamsDirty = false;
	}

	public static boolean isMaskTeam(String name) {
		return name.startsWith(TEAM_PREFIX);
	}

	private static String teamName(UUID id) {
		return TEAM_PREFIX + id.toString().substring(0, 8);
	}
}
