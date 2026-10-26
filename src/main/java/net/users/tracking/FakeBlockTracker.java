package net.users.tracking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Fake blocks: chosen players see a block that isn't really there. The world is untouched; each player is sent a
 * different block state for that position.
 *
 * Fakes come from two places: placed ones (set_fake_block, /opp fakeblock) and ones a fake_blocks power recomputes
 * while held. Every tick the tracker works out what each online player should see and sends only the differences.
 * FakeBlockSendMixin keeps vanilla from undoing them: a chunk or section resend is followed by the fakes in it, and a
 * block update that just repeats the real block is swapped for the fake. A block update carrying a different real
 * block means the real block changed, so that fake is dropped: placed fakes are deleted, and power fakes are left for
 * the power's next recompute.
 *
 * Not saved: placed fakes end on restart. They're tied to positions, so a player who relogs sees them again.
 */
public final class FakeBlockTracker {
	private static final int RECHECK_TICKS = 10;

	/** A fake someone placed. Exactly one of the viewer fields is used; all null means every player. */
	private static final class Placed {
		final ResourceKey<Level> dimension;
		final BlockPos pos;
		final BlockState state;
		final BlockState real;
		final Entity setter;
		final Predicate<Tuple<Entity, Entity>> bientityViewer;
		final Predicate<Entity> entityViewer;
		final Set<UUID> fixedViewers;
		final boolean recheck;
		final long expiry;
		final Map<UUID, Boolean> allowed = new HashMap<>();

		Placed(ResourceKey<Level> dimension, BlockPos pos, BlockState state, BlockState real, Entity setter,
				Predicate<Tuple<Entity, Entity>> bientityViewer, Predicate<Entity> entityViewer, Set<UUID> fixedViewers, boolean recheck, long expiry) {
			this.dimension = dimension;
			this.pos = pos;
			this.state = state;
			this.real = real;
			this.setter = setter;
			this.bientityViewer = bientityViewer;
			this.entityViewer = entityViewer;
			this.fixedViewers = fixedViewers;
			this.recheck = recheck;
			this.expiry = expiry;
		}

		boolean visibleTo(ServerPlayer player) {
			if (fixedViewers != null) {
				return fixedViewers.contains(player.getUUID());
			}
			if (bientityViewer == null && entityViewer == null) {
				return true;
			}
			return allowed.computeIfAbsent(player.getUUID(), id -> entityViewer != null ? entityViewer.test(player)
				: setter != null && !setter.isRemoved() && bientityViewer.test(new Tuple<>(player, setter)));
		}
	}

	/** What a fake_blocks power wants shown right now, and to whom. */
	public record Contribution(ResourceKey<Level> dimension, Map<Long, BlockState> blocks, Set<UUID> viewers, long updatedTick, long ttl) {
	}

	/** A fake a player's client has, and the real block it covers. */
	private record Shown(BlockState fake, BlockState base) {
	}

	private static final class View {
		final ServerPlayer player;
		final ResourceKey<Level> dimension;
		final Map<Long, Shown> shown = new ConcurrentHashMap<>();

		View(ServerPlayer player) {
			this.player = player;
			this.dimension = player.level().dimension();
		}
	}

	private static final List<Placed> PLACED = new ArrayList<>();
	private static final Map<Object, Contribution> CONTRIBUTIONS = new IdentityHashMap<>();
	private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();
	private static int age;

	private FakeBlockTracker() {
	}

	/**
	 * Places a fake at pos, replacing any placed fake there. The real block is remembered now, so the position and what it
	 * covers stay fixed even if the setter moves. Does nothing in an unloaded chunk. duration <= 0 lasts until cleared.
	 */
	public static void place(ServerLevel level, BlockPos pos, BlockState state, Entity setter, Predicate<Tuple<Entity, Entity>> bientityViewer,
			Predicate<Entity> entityViewer, Set<UUID> fixedViewers, boolean recheck, int duration) {
		if (!level.isLoaded(pos)) {
			return;
		}
		BlockPos fixed = pos.immutable();
		clear(level, fixed);
		long expiry = duration > 0 ? level.getGameTime() + duration : -1;
		PLACED.add(new Placed(level.dimension(), fixed, state, level.getBlockState(fixed), setter, bientityViewer, entityViewer,
			fixedViewers, recheck, expiry));
	}

	/** Removes placed fakes at pos. Returns how many. Viewers get the real block on the next tick. */
	public static int clear(ServerLevel level, BlockPos pos) {
		int before = PLACED.size();
		PLACED.removeIf(placed -> placed.dimension == level.dimension() && placed.pos.equals(pos));
		return before - PLACED.size();
	}

	public static void contribute(Object source, Contribution contribution) {
		CONTRIBUTIONS.put(source, contribution);
	}

	public static void withdraw(Object source) {
		CONTRIBUTIONS.remove(source);
	}

	public static void tick(MinecraftServer server) {
		age++;
		long now = server.overworld().getGameTime();
		boolean recheck = age % RECHECK_TICKS == 0;
		Iterator<Placed> placedIterator = PLACED.iterator();
		while (placedIterator.hasNext()) {
			Placed placed = placedIterator.next();
			ServerLevel level = server.getLevel(placed.dimension);
			if (level == null || placed.expiry >= 0 && level.getGameTime() >= placed.expiry
				|| level.isLoaded(placed.pos) && level.getBlockState(placed.pos) != placed.real) {
				placedIterator.remove();
			} else if (recheck && placed.recheck) {
				placed.allowed.clear();
			}
		}
		CONTRIBUTIONS.values().removeIf(contribution -> now - contribution.updatedTick() > contribution.ttl());

		VIEWS.entrySet().removeIf(entry -> server.getPlayerList().getPlayer(entry.getKey()) == null);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			View view = VIEWS.get(player.getUUID());
			if (view == null || view.player != player || view.dimension != player.level().dimension()) {
				// A new login, a respawn or a dimension change: the client dropped its chunks, so start from nothing.
				view = new View(player);
				VIEWS.put(player.getUUID(), view);
			}
			update(view, desired(player));
		}
	}

	public static void clearAll() {
		PLACED.clear();
		CONTRIBUTIONS.clear();
		VIEWS.clear();
	}

	private static Map<Long, BlockState> desired(ServerPlayer player) {
		Map<Long, BlockState> wanted = new HashMap<>();
		ResourceKey<Level> dimension = player.level().dimension();
		for (Placed placed : PLACED) {
			if (placed.dimension == dimension && placed.visibleTo(player)) {
				wanted.put(placed.pos.asLong(), placed.state);
			}
		}
		for (Contribution contribution : CONTRIBUTIONS.values()) {
			if (contribution.dimension() == dimension && contribution.viewers().contains(player.getUUID())) {
				wanted.putAll(contribution.blocks());
			}
		}
		return wanted;
	}

	private static void update(View view, Map<Long, BlockState> wanted) {
		ServerLevel level = view.player.serverLevel();
		Iterator<Map.Entry<Long, Shown>> iterator = view.shown.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Long, Shown> entry = iterator.next();
			if (!wanted.containsKey(entry.getKey())) {
				iterator.remove();
				BlockPos pos = BlockPos.of(entry.getKey());
				if (level.isLoaded(pos)) {
					view.player.connection.send(new ClientboundBlockUpdatePacket(pos, level.getBlockState(pos)));
				}
			}
		}
		for (Map.Entry<Long, BlockState> entry : wanted.entrySet()) {
			BlockPos pos = BlockPos.of(entry.getKey());
			if (!level.isLoaded(pos)) {
				continue;
			}
			Shown current = view.shown.get(entry.getKey());
			if (current == null || current.fake() != entry.getValue()) {
				view.shown.put(entry.getKey(), new Shown(entry.getValue(), level.getBlockState(pos)));
				view.player.connection.send(new ClientboundBlockUpdatePacket(pos, entry.getValue()));
			}
		}
	}

	// ---- used by FakeBlockSendMixin, on whatever thread sends the packet ----

	/**
	 * A block update about to go to player. Returns the state to send instead, or null to send it unchanged.
	 * A repeat of the real block becomes the fake; a different real block means it changed, so the fake is dropped.
	 */
	public static BlockState replaceUpdate(ServerPlayer player, BlockPos pos, BlockState state) {
		View view = VIEWS.get(player.getUUID());
		Shown shown = view == null || view.player != player ? null : view.shown.get(pos.asLong());
		if (shown == null || state == shown.fake()) {
			return null;
		}
		if (state == shown.base()) {
			return shown.fake();
		}
		view.shown.remove(pos.asLong());
		return null;
	}

	/** After a section update: the same check for each position in it, then the fakes in that section are sent again. */
	public static void afterSectionUpdate(ServerPlayer player, SectionPos section, List<Tuple<BlockPos, BlockState>> changes) {
		View view = VIEWS.get(player.getUUID());
		if (view == null || view.player != player) {
			return;
		}
		for (Tuple<BlockPos, BlockState> change : changes) {
			Shown shown = view.shown.get(change.getA().asLong());
			if (shown != null && change.getB() != shown.fake() && change.getB() != shown.base()) {
				view.shown.remove(change.getA().asLong());
			}
		}
		resend(view, pos -> SectionPos.of(pos).equals(section));
	}

	/** After a whole chunk was sent: send the fakes in it again. */
	public static void afterChunk(ServerPlayer player, int chunkX, int chunkZ) {
		View view = VIEWS.get(player.getUUID());
		if (view != null && view.player == player) {
			resend(view, pos -> pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ);
		}
	}

	private static void resend(View view, Predicate<BlockPos> inside) {
		for (Map.Entry<Long, Shown> entry : view.shown.entrySet()) {
			BlockPos pos = BlockPos.of(entry.getKey());
			if (inside.test(pos)) {
				view.player.connection.send(new ClientboundBlockUpdatePacket(pos, entry.getValue().fake()));
			}
		}
	}
}
