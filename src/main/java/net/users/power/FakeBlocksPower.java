package net.users.power;

import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.Vec3;
import net.users.OriginsPowerfulPowers;
import net.users.tracking.FakeBlockTracker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * origins-powerful-powers:fake_blocks: while the holder has this power, some blocks
 * look different to them (and optionally to other players). Two modes, either or both:
 *   block_condition + radius: every real block near the holder matching the condition shows as "block" (x-ray, illusions)
 *   offsets:                  fixed positions relative to the holder, which move with them
 * Recomputed every "interval" ticks and handed to FakeBlockTracker; the world never changes.
 */
public class FakeBlocksPower extends Power {
	private static final int MAX_RADIUS = 16;
	private static final SerializableDataType<List<Vec3>> OFFSETS = SerializableDataType.list(SerializableDataTypes.VECTOR);

	private final BlockState block;
	private final Predicate<BlockInWorld> blockCondition;
	private final int radius;
	private final List<Vec3> offsets;
	private final boolean rotateWithFacing;
	private final boolean showToHolder;
	private final Predicate<Tuple<Entity, Entity>> viewerCondition;
	private final int interval;

	public FakeBlocksPower(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
		super(type, entity);
		this.block = data.get("block");
		this.blockCondition = data.get("block_condition");
		this.radius = Math.min(MAX_RADIUS, Math.max(0, data.getInt("radius")));
		this.offsets = data.get("offsets");
		this.rotateWithFacing = data.getBoolean("rotate_with_facing");
		this.showToHolder = data.getBoolean("show_to_holder");
		this.viewerCondition = data.get("viewer_condition");
		this.interval = Math.max(1, data.getInt("interval"));
	}

	public static PowerFactory<FakeBlocksPower> createFactory() {
		return new PowerFactory<FakeBlocksPower>(
			OriginsPowerfulPowers.id("fake_blocks"),
			new SerializableData()
				.add("block", SerializableDataTypes.BLOCK_STATE)
				.add("block_condition", ApoliDataTypes.BLOCK_CONDITION, null)
				.add("radius", SerializableDataTypes.INT, 8)
				.add("offsets", OFFSETS, null)
				.add("rotate_with_facing", SerializableDataTypes.BOOLEAN, false)
				.add("show_to_holder", SerializableDataTypes.BOOLEAN, true)
				.add("viewer_condition", ApoliDataTypes.BIENTITY_CONDITION, null)
				.add("interval", SerializableDataTypes.INT, 10),
			data -> (type, entity) -> new FakeBlocksPower(type, entity, data)
		).allowCondition();
	}

	public static void register() {
		PowerFactory<FakeBlocksPower> factory = createFactory();
		Registry.register(io.github.apace100.apoli.registry.ApoliRegistries.POWER_FACTORY, factory.getSerializerId(), factory);
	}

	@Override
	public boolean shouldTick() {
		return true;
	}

	@Override
	public void tick() {
		if (!(entity.level() instanceof ServerLevel level) || entity.tickCount % interval != 0) {
			return;
		}
		Map<Long, BlockState> blocks = new HashMap<>();
		if (blockCondition != null) {
			BlockPos center = entity.blockPosition();
			BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
			for (int x = -radius; x <= radius; x++) {
				for (int y = -radius; y <= radius; y++) {
					for (int z = -radius; z <= radius; z++) {
						cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
						if (level.isLoaded(cursor) && blockCondition.test(new BlockInWorld(level, cursor, false))) {
							blocks.put(cursor.asLong(), block);
						}
					}
				}
			}
		}
		if (offsets != null) {
			for (Vec3 offset : offsets) {
				blocks.put(offsetPos(offset).asLong(), block);
			}
		}
		// The ttl lets the tracker drop these if the power stops ticking (revoked, condition false, entity unloaded).
		FakeBlockTracker.contribute(this, new FakeBlockTracker.Contribution(level.dimension(), blocks, viewers(level), level.getGameTime(), interval * 2L + 20));
	}

	/** Offsets are like the ^ ^ ^ coordinates when rotated: x is left, y up, z forward. */
	private BlockPos offsetPos(Vec3 offset) {
		BlockPos base = entity.blockPosition();
		if (!rotateWithFacing) {
			return base.offset(BlockPos.containing(offset));
		}
		Direction facing = entity.getDirection();
		int forwardX = facing.getStepX();
		int forwardZ = facing.getStepZ();
		double worldX = offset.z * forwardX + offset.x * forwardZ;
		double worldZ = offset.z * forwardZ - offset.x * forwardX;
		return base.offset(BlockPos.containing(worldX, offset.y, worldZ));
	}

	private Set<UUID> viewers(ServerLevel level) {
		Set<UUID> viewers = new HashSet<>();
		if (showToHolder && entity instanceof ServerPlayer holder) {
			viewers.add(holder.getUUID());
		}
		if (viewerCondition != null) {
			for (ServerPlayer player : level.players()) {
				if (player != entity && viewerCondition.test(new Tuple<>(player, entity))) {
					viewers.add(player.getUUID());
				}
			}
		}
		return viewers;
	}

	@Override
	public void onRemoved() {
		FakeBlockTracker.withdraw(this);
	}

	@Override
	public void onLost() {
		FakeBlockTracker.withdraw(this);
	}
}
