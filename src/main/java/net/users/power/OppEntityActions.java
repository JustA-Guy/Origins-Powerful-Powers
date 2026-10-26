package net.users.power;

import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.factory.action.ActionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.users.OriginsPowerfulPowers;
import net.users.tracking.BroadcastTracker;
import net.users.tracking.DisguiseTracker;
import net.users.tracking.FakeBlockTracker;
import net.users.tracking.ChunkLoadTracker;
import net.users.tracking.FakeEntityTracker;
import net.users.tracking.VibrationCooldownTracker;

public final class OppEntityActions {
	private OppEntityActions() {
	}

	public static void register() {
		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("set_vibration_cooldown"),
			new SerializableData().add("ticks", SerializableDataTypes.INT),
			(data, entity) -> VibrationCooldownTracker.set(entity, data.getInt("ticks"))
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("set_item_cooldown"),
			new SerializableData()
				.add("item", SerializableDataTypes.ITEM, null)
				.add("slot", VanillaSlot.DATA_TYPE, VanillaSlot.MAINHAND)
				.add("ticks", SerializableDataTypes.INT),
			(data, entity) -> {
				if (entity instanceof Player player) {
					Item item = OppItemCooldowns.resolve(player, data.get("item"), data.get("slot"));
					if (item != null) {
						OppItemCooldowns.set(player, item, data.getInt("ticks"));
					}
				}
			}
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("start_broadcast_voice"),
			new SerializableData()
				.add("listeners", ApoliDataTypes.BIENTITY_CONDITION)
				.add("ticks", SerializableDataTypes.INT, -1),
			(data, entity) -> {
				int ticks = data.getInt("ticks");
				net.minecraft.server.MinecraftServer server = entity.getServer();
				long now = server == null ? 0 : server.getTickCount();
				long expiry = ticks <= 0 ? Long.MAX_VALUE : now + ticks;
				BroadcastTracker.start(entity, data.get("listeners"), expiry);
			}
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("stop_broadcast_voice"),
			new SerializableData(),
			(data, entity) -> BroadcastTracker.stop(entity)
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("set_rotation"),
			stepData()
				.add("yaw", SerializableDataTypes.FLOAT, null)
				.add("pitch", SerializableDataTypes.FLOAT, null)
				.add("relative", SerializableDataTypes.BOOLEAN, false),
			(data, entity) -> {
				Float yaw = data.get("yaw");
				Float pitch = data.get("pitch");
				if (data.getBoolean("relative")) {
					yaw = yaw == null ? null : entity.getYRot() + yaw;
					pitch = pitch == null ? null : entity.getXRot() + pitch;
				}
				OppRotation.turn(entity, yaw, pitch, data.get("max_step"), data.getFloat("min_step"));
			}
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("look_at"),
			stepData()
				.add("x", SerializableDataTypes.DOUBLE)
				.add("y", SerializableDataTypes.DOUBLE)
				.add("z", SerializableDataTypes.DOUBLE)
				.add("relative_to", RELATIVE_TO, RelativeTo.WORLD),
			(data, entity) -> {
				Vec3 target = new Vec3(data.getDouble("x"), data.getDouble("y"), data.getDouble("z"));
				if (data.get("relative_to") == RelativeTo.SELF) {
					target = target.add(entity.position());
				}
				lookAt(entity, target, data);
			}
		));

		registerBiEntity(new ActionFactory<>(
			OriginsPowerfulPowers.id("look_at"),
			stepData().add("anchor", ANCHOR, Anchor.EYES),
			(data, pair) -> {
				Entity target = pair.getB();
				Vec3 point = switch ((Anchor) data.get("anchor")) {
					case EYES -> target.getEyePosition();
					case FEET -> target.position();
					case CENTER -> target.getBoundingBox().getCenter();
				};
				lookAt(pair.getA(), point, data);
			}
		));

		registerIllusions();
		registerFakeBlocks();
		registerChunkLoading();
	}

	/** Registered from register(): fake entities and disguises (see FakeEntityTracker and DisguiseTracker). */
	private static void registerIllusions() {
		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("spawn_fake_entity"),
			new SerializableData()
				.add("entity_type", SerializableDataTypes.ENTITY_TYPE)
				.add("x", SerializableDataTypes.DOUBLE, 0.0)
				.add("y", SerializableDataTypes.DOUBLE, 0.0)
				.add("z", SerializableDataTypes.DOUBLE, 0.0)
				.add("relative_to", RELATIVE_TO, RelativeTo.SELF)
				.add("behavior", BEHAVIOR, null)
				.add("lifetime", SerializableDataTypes.INT)
				.add("viewer_condition", ApoliDataTypes.BIENTITY_CONDITION, null)
				.add("recheck", SerializableDataTypes.BOOLEAN, true)
				.add("id", SerializableDataTypes.INT, null)
				.add("player", SerializableDataTypes.STRING, null)
				.add("nbt", SerializableDataTypes.NBT, null),
			(data, entity) -> {
				Vec3 pos = new Vec3(data.getDouble("x"), data.getDouble("y"), data.getDouble("z"));
				if (data.get("relative_to") == RelativeTo.SELF) {
					pos = pos.add(entity.position());
				}
				FakeEntityTracker.spawn(data.get("entity_type"), entity, pos, data.get("behavior"), data.get("viewer_condition"),
					data.getBoolean("recheck"), data.getInt("lifetime"), data.get("id"), data.get("player"), data.get("nbt"));
			}
		));

		// No id: every fake entity this entity spawned. Ids are server-wide, so despawning one by id works from anywhere.
		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("despawn_fake_entity"),
			new SerializableData().add("id", SerializableDataTypes.INT, null),
			(data, entity) -> {
				Integer id = data.get("id");
				if (id == null) {
					FakeEntityTracker.despawnAllOwnedBy(entity.getUUID());
				} else {
					FakeEntityTracker.despawn(id);
				}
			}
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("disguise"),
			new SerializableData()
				.add("entity_type", SerializableDataTypes.ENTITY_TYPE)
				.add("duration", SerializableDataTypes.INT, 0)
				.add("viewer_condition", ApoliDataTypes.BIENTITY_CONDITION, null)
				.add("recheck", SerializableDataTypes.BOOLEAN, true),
			(data, entity) -> DisguiseTracker.disguise(entity, data.get("entity_type"), data.get("viewer_condition"),
				data.getBoolean("recheck"), data.getInt("duration"))
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("undisguise"),
			new SerializableData(),
			(data, entity) -> DisguiseTracker.undisguise(entity)
		));
	}

	/** Fake blocks from an entity action. The position is worked out once, here, and then stays put. */
	private static void registerFakeBlocks() {
		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("set_fake_block"),
			positionData()
				.add("block", SerializableDataTypes.BLOCK_STATE)
				.add("viewer_condition", ApoliDataTypes.BIENTITY_CONDITION, null)
				.add("recheck", SerializableDataTypes.BOOLEAN, true)
				.add("duration", SerializableDataTypes.INT, 0),
			(data, entity) -> {
				if (entity.level() instanceof ServerLevel level) {
					FakeBlockTracker.place(level, blockPos(data, entity), data.get("block"), entity, data.get("viewer_condition"),
						null, null, data.getBoolean("recheck"), data.getInt("duration"));
				}
			}
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("clear_fake_block"),
			positionData(),
			(data, entity) -> {
				if (entity.level() instanceof ServerLevel level) {
					FakeBlockTracker.clear(level, blockPos(data, entity));
				}
			}
		));
	}

	/** Entity-tied chunk loading (see ChunkLoadTracker). One action tie per entity: running it again replaces it. */
	private static void registerChunkLoading() {
		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("load_chunks"),
			new SerializableData()
				.add("radius", SerializableDataTypes.INT, 1)
				.add("duration", SerializableDataTypes.INT, 0),
			(data, entity) -> ChunkLoadTracker.start(entity, ChunkLoadTracker.ACTION_SOURCE, data.getInt("radius"), data.getInt("duration"))
		));

		register(new ActionFactory<>(
			OriginsPowerfulPowers.id("stop_loading_chunks"),
			new SerializableData(),
			(data, entity) -> ChunkLoadTracker.stop(entity.getUUID(), ChunkLoadTracker.ACTION_SOURCE)
		));
	}

	private static SerializableData positionData() {
		return new SerializableData()
			.add("x", SerializableDataTypes.DOUBLE, 0.0)
			.add("y", SerializableDataTypes.DOUBLE, 0.0)
			.add("z", SerializableDataTypes.DOUBLE, 0.0)
			.add("relative_to", RELATIVE_TO, RelativeTo.SELF);
	}

	private static BlockPos blockPos(SerializableData.Instance data, Entity entity) {
		Vec3 pos = new Vec3(data.getDouble("x"), data.getDouble("y"), data.getDouble("z"));
		if (data.get("relative_to") == RelativeTo.SELF) {
			pos = pos.add(entity.position());
		}
		return BlockPos.containing(pos);
	}

	private static final SerializableDataType<FakeEntityTracker.Behavior> BEHAVIOR = SerializableDataType.enumValue(FakeEntityTracker.Behavior.class);

	public enum RelativeTo {
		WORLD, SELF
	}

	public enum Anchor {
		EYES, FEET, CENTER
	}

	private static final SerializableDataType<RelativeTo> RELATIVE_TO = SerializableDataType.enumValue(RelativeTo.class);
	private static final SerializableDataType<Anchor> ANCHOR = SerializableDataType.enumValue(Anchor.class);

	/** max_step and min_step, shared by set_rotation and both look_at actions. */
	private static SerializableData stepData() {
		return new SerializableData()
			.add("max_step", SerializableDataTypes.FLOAT, null)
			.add("min_step", SerializableDataTypes.FLOAT, 0.0F);
	}

	private static void lookAt(Entity entity, Vec3 target, SerializableData.Instance data) {
		float[] angles = OppRotation.anglesTo(entity, target);
		OppRotation.turn(entity, angles[0], angles[1], data.get("max_step"), data.getFloat("min_step"));
	}

	private static void register(ActionFactory<Entity> factory) {
		Registry.register(ApoliRegistries.ENTITY_ACTION, factory.getSerializerId(), factory);
	}

	private static void registerBiEntity(ActionFactory<Tuple<Entity, Entity>> factory) {
		Registry.register(ApoliRegistries.BIENTITY_ACTION, factory.getSerializerId(), factory);
	}
}
