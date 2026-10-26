package net.users.power;

import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.users.OriginsPowerfulPowers;
import net.minecraft.server.level.ServerLevel;
import net.users.tracking.BlockCrackTracker;
import net.users.tracking.FakeBlockTracker;
import org.apache.commons.lang3.tuple.Triple;

/**
 * Registers "type": "origins-powerful-powers:..." block actions, usable anywhere Apoli takes a block action:
 * origins:block_action_at, a raycast's block_action, area_of_effect-style powers, and so on.
 */
public final class OppBlockActions {
	private static final SerializableDataType<BlockCrackTracker.OnFinish> ON_FINISH =
		SerializableDataType.enumValue(BlockCrackTracker.OnFinish.class);

	private OppBlockActions() {
	}

	public static void register() {
		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("play_block_crack"),
			new SerializableData()
				.add("duration", SerializableDataTypes.INT, 20)
				.add("on_finish", ON_FINISH, BlockCrackTracker.OnFinish.CLEAR)
				.add("drop_items", SerializableDataTypes.BOOLEAN, true)
				.add("clear", SerializableDataTypes.BOOLEAN, false),
			(data, block) -> {
				if (!(block.getLeft() instanceof ServerLevel level)) {
					return;
				}
				BlockPos pos = block.getMiddle();
				if (data.getBoolean("clear")) {
					BlockCrackTracker.stop(level, pos);
				} else {
					BlockCrackTracker.start(level, pos, data.getInt("duration"), data.get("on_finish"), data.getBoolean("drop_items"));
				}
			}
		));

		registerFakeBlocks();
	}

	/** Fake blocks from a block action: the viewer_condition here tests the viewing player alone (there's no second entity). */
	private static void registerFakeBlocks() {
		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("set_fake_block"),
			new SerializableData()
				.add("block", SerializableDataTypes.BLOCK_STATE)
				.add("viewer_condition", ApoliDataTypes.ENTITY_CONDITION, null)
				.add("recheck", SerializableDataTypes.BOOLEAN, true)
				.add("duration", SerializableDataTypes.INT, 0),
			(data, block) -> {
				if (block.getLeft() instanceof ServerLevel level) {
					FakeBlockTracker.place(level, block.getMiddle(), data.get("block"), null, null, data.get("viewer_condition"),
						null, data.getBoolean("recheck"), data.getInt("duration"));
				}
			}
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("clear_fake_block"),
			new SerializableData(),
			(data, block) -> {
				if (block.getLeft() instanceof ServerLevel level) {
					FakeBlockTracker.clear(level, block.getMiddle());
				}
			}
		));
	}

	private static void register(ActionFactory<Triple<Level, BlockPos, Direction>> factory) {
		Registry.register(ApoliRegistries.BLOCK_ACTION, factory.getSerializerId(), factory);
	}
}
