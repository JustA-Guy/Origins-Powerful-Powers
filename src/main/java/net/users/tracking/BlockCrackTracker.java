package net.users.tracking;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Running play_block_crack animations. Each one owns a made-up breaker id, the key vanilla's client uses to
 * tell one crack overlay from another. Real entity ids count up from 0, so ours count down from -2 (-1 is
 * left alone). ServerLevel#destroyBlockProgress sends each update to players in that level within 32 blocks.
 *
 * Entries are removed when they finish, when the block changes, or when the chunk or level is gone.
 */
public final class BlockCrackTracker {
	public enum OnFinish {
		CLEAR, HOLD, BREAK
	}

	/** The client forgets an overlay 400 ticks after its last update, so a held crack is resent well before that. */
	private static final int RESEND_TICKS = 200;

	private record Key(ResourceKey<Level> level, long pos) {
	}

	private static final class Crack {
		final int breakerId;
		final BlockPos pos;
		final BlockState state;
		final long startTick;
		final int duration;
		final OnFinish onFinish;
		final boolean dropItems;
		int sentStage = -1;
		long sentTick;

		Crack(int breakerId, BlockPos pos, BlockState state, long startTick, int duration, OnFinish onFinish, boolean dropItems) {
			this.breakerId = breakerId;
			this.pos = pos;
			this.state = state;
			this.startTick = startTick;
			this.duration = duration;
			this.onFinish = onFinish;
			this.dropItems = dropItems;
		}
	}

	private static final Map<Key, Crack> CRACKS = new HashMap<>();
	private static int nextBreakerId = -2;

	private BlockCrackTracker() {
	}

	/** Starts a crack at pos. Does nothing while one is already running or held at that block. */
	public static void start(ServerLevel level, BlockPos pos, int duration, OnFinish onFinish, boolean dropItems) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}
		Key key = new Key(level.dimension(), pos.asLong());
		if (CRACKS.containsKey(key)) {
			return;
		}
		int breakerId = nextBreakerId;
		nextBreakerId = nextBreakerId == Integer.MIN_VALUE ? -2 : nextBreakerId - 1;
		Crack crack = new Crack(breakerId, pos.immutable(), state, level.getGameTime(), Math.max(1, duration), onFinish, dropItems);
		CRACKS.put(key, crack);
		send(level, crack, 0);
	}

	/** Removes any crack at pos and clears its overlay. */
	public static void stop(ServerLevel level, BlockPos pos) {
		Crack crack = CRACKS.remove(new Key(level.dimension(), pos.asLong()));
		if (crack != null) {
			level.destroyBlockProgress(crack.breakerId, crack.pos, -1);
		}
	}

	public static void tick(MinecraftServer server) {
		Iterator<Map.Entry<Key, Crack>> iterator = CRACKS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<Key, Crack> entry = iterator.next();
			Crack crack = entry.getValue();
			ServerLevel level = server.getLevel(entry.getKey().level());
			if (level == null || !level.isLoaded(crack.pos)) {
				iterator.remove();
				continue;
			}
			if (level.getBlockState(crack.pos) != crack.state) {
				level.destroyBlockProgress(crack.breakerId, crack.pos, -1);
				iterator.remove();
				continue;
			}
			long elapsed = level.getGameTime() - crack.startTick;
			if (elapsed < crack.duration) {
				int stage = (int) Math.min(9, elapsed * 10 / crack.duration);
				if (stage != crack.sentStage || level.getGameTime() - crack.sentTick >= RESEND_TICKS) {
					send(level, crack, stage);
				}
				continue;
			}
			switch (crack.onFinish) {
				case HOLD -> {
					if (crack.sentStage != 9 || level.getGameTime() - crack.sentTick >= RESEND_TICKS) {
						send(level, crack, 9);
					}
				}
				case CLEAR -> {
					level.destroyBlockProgress(crack.breakerId, crack.pos, -1);
					iterator.remove();
				}
				case BREAK -> {
					level.destroyBlockProgress(crack.breakerId, crack.pos, -1);
					iterator.remove();
					level.destroyBlock(crack.pos, crack.dropItems);
				}
			}
		}
	}

	/** Forgets everything when the server stops, so a singleplayer world opened later starts clean. */
	public static void clear() {
		CRACKS.clear();
	}

	private static void send(ServerLevel level, Crack crack, int stage) {
		level.destroyBlockProgress(crack.breakerId, crack.pos, stage);
		crack.sentStage = stage;
		crack.sentTick = level.getGameTime();
	}
}
