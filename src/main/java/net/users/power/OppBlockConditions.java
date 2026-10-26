package net.users.power;

import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.apoli.util.Comparison;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.users.OriginsPowerfulPowers;

/**
 * Registers "type": "origins-powerful-powers:..." block conditions, usable anywhere Apoli takes a block
 * condition: on_block, in_block, block_in_radius, block_collision, action_on_block_use, raycasts, and so on.
 */
public final class OppBlockConditions {
	/** The three redstone reads vanilla has, all on SignalGetter. */
	public enum RedstoneMode {
		/** The strongest signal reaching the block from any side: what lamps, pistons and doors react to. */
		RECEIVED,
		/** The strongest strong power into the block: what lets a solid block pass power on. */
		STRONG,
		/** What the block itself sends out toward one side (wire, torches, levers, powered solid blocks). */
		EMITTED
	}

	private static final SerializableDataType<RedstoneMode> REDSTONE_MODE = SerializableDataType.enumValue(RedstoneMode.class);

	private OppBlockConditions() {
	}

	public static void register() {
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("redstone_power"),
			new SerializableData()
				.add("mode", REDSTONE_MODE, RedstoneMode.RECEIVED)
				.add("direction", SerializableDataTypes.DIRECTION, null)
				.add("comparison", ApoliDataTypes.COMPARISON)
				.add("compare_to", SerializableDataTypes.INT),
			(data, block) -> ((Comparison) data.get("comparison")).compare(
				redstonePower(block.getLevel(), block.getPos(), data.get("mode"), data.get("direction")),
				data.getInt("compare_to"))
		));
	}

	/** 0-15. For EMITTED, direction is the side the signal leaves through; null takes the strongest side. */
	public static int redstonePower(SignalGetter signals, BlockPos pos, RedstoneMode mode, Direction direction) {
		return switch (mode) {
			case RECEIVED -> signals.getBestNeighborSignal(pos);
			case STRONG -> signals.getDirectSignalTo(pos);
			case EMITTED -> {
				if (direction != null) {
					// getSignal(pos, d) is what pos sends to its neighbour on the side opposite d.
					yield signals.getSignal(pos, direction.getOpposite());
				}
				int strongest = 0;
				for (Direction side : Direction.values()) {
					strongest = Math.max(strongest, signals.getSignal(pos, side));
				}
				yield strongest;
			}
		};
	}

	private static void register(ConditionFactory<BlockInWorld> factory) {
		Registry.register(ApoliRegistries.BLOCK_CONDITION, factory.getSerializerId(), factory);
	}
}
