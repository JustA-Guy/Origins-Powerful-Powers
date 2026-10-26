package net.users.power;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.data.ApoliDataTypes;
import io.github.apace100.apoli.power.ModifySlipperinessPower;
import io.github.apace100.apoli.power.factory.condition.ConditionFactory;
import io.github.apace100.apoli.registry.ApoliRegistries;
import io.github.apace100.apoli.util.Comparison;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.users.OriginsPowerfulPowers;
import net.users.mixin.EntityAccessor;
import net.users.mixin.FoodDataAccessor;
import net.users.mixin.LivingEntityAccessor;
import net.users.tracking.AttackReachTracker;
import net.users.tracking.CritTracker;
import net.users.tracking.CrouchDepthTracker;
import net.users.tracking.DamageHistoryTracker;
import net.users.tracking.KnockbackTracker;
import net.users.tracking.LastDamageTracker;
import net.users.tracking.MealTracker;
import net.users.tracking.MovementStateTracker;
import net.users.tracking.VibrationCooldownTracker;

/**
 * Entity conditions, usable anywhere Apoli takes one. Numeric ones use Apoli's "comparison" and "compare_to".
 *
 * Conditions that only repeated an Origins type were removed in 2.1.0: fall_distance, oxygen_remaining, is_gliding,
 * potion_amplifier, sky_visible, wall_collision and weather_intensity.
 */
public final class OppEntityConditions {
	public enum Axis {
		X, Y, Z
	}

	public enum LookComponent {
		PITCH, YAW
	}

	private static final SerializableDataType<Axis> AXIS = SerializableDataType.enumValue(Axis.class);
	private static final SerializableDataType<LookComponent> LOOK_COMPONENT = SerializableDataType.enumValue(LookComponent.class);

	private OppEntityConditions() {
	}

	public static void register() {
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("attack_cooldown_progress"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> entity instanceof Player player
				&& ((Comparison) data.get("comparison")).compare(player.getAttackStrengthScale(0.0F), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("invulnerable_ticks"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(entity.invulnerableTime, data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("hurt_time"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> entity instanceof LivingEntity living
				&& ((Comparison) data.get("comparison")).compare(living.hurtTime, data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("recent_damage_taken"),
			new SerializableData()
				.add("window_ticks", SerializableDataTypes.INT, 60)
				.add("comparison", ApoliDataTypes.COMPARISON)
				.add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> entity instanceof LivingEntity living
				&& ((Comparison) data.get("comparison")).compare(
					DamageHistoryTracker.sumWithin(living, data.getInt("window_ticks"), living.level().getGameTime(), false),
					data.getFloat("compare_to")
				)
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("velocity"),
			new SerializableData().add("axis", AXIS).add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.DOUBLE),
			(data, entity) -> {
				var velocity = entity.getDeltaMovement();
				double value = switch ((Axis) data.get("axis")) {
					case X -> velocity.x;
					case Y -> velocity.y;
					case Z -> velocity.z;
				};
				return ((Comparison) data.get("comparison")).compare(value, data.getDouble("compare_to"));
			}
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("look_angle"),
			new SerializableData().add("component", LOOK_COMPONENT).add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> {
				float value = data.get("component") == LookComponent.PITCH ? entity.getXRot() : entity.getYRot();
				return ((Comparison) data.get("comparison")).compare(value, data.getFloat("compare_to"));
			}
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_jumping"),
			new SerializableData(),
			(data, entity) -> entity instanceof LivingEntity living && ((LivingEntityAccessor) living).opp$isJumping()
		));

		// Stats live on the server only; the client never has them unless the statistics screen asked, so it reads false.
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("stat"),
			new SerializableData()
				.add("stat", SerializableDataTypes.STAT)
				.add("comparison", ApoliDataTypes.COMPARISON)
				.add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> entity instanceof ServerPlayer player
				&& ((Comparison) data.get("comparison")).compare(player.getStats().getValue((Stat<?>) data.get("stat")), data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("has_power_tag"),
			new SerializableData()
				.add("tag", PowerTags.TAG_NAME)
				.add("include_inactive", SerializableDataTypes.BOOLEAN, false),
			(data, entity) -> PowerTags.has(entity, data.getString("tag"), data.getBoolean("include_inactive"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_sleeping"),
			new SerializableData(),
			(data, entity) -> entity instanceof LivingEntity living && living.isSleeping()
		));

		// Set as the hit resolves, so during the hit itself (modify_damage_dealt, action_on_hit) these still read the previous one.
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("last_hit_was_crit"),
			new SerializableData(),
			(data, entity) -> CritTracker.dealtCrit(entity)
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("last_damage_was_crit"),
			new SerializableData(),
			(data, entity) -> CritTracker.tookCrit(entity)
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_blocking"),
			new SerializableData(),
			(data, entity) -> entity instanceof LivingEntity living && living.isBlocking()
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("crouch_depth"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> entity instanceof LivingEntity living
				&& ((Comparison) data.get("comparison")).compare(CrouchDepthTracker.get(living), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("exhaustion"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> {
				if (!(entity instanceof Player player)) {
					return false;
				}
				FoodData foodData = player.getFoodData();
				return ((Comparison) data.get("comparison")).compare(((FoodDataAccessor) foodData).opp$getExhaustionLevel(), data.getFloat("compare_to"));
			}
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("xp_progress"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> entity instanceof Player player
				&& ((Comparison) data.get("comparison")).compare(player.experienceProgress, data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("biome_temperature"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> ((Comparison) data.get("comparison"))
				.compare(entity.level().getBiome(entity.blockPosition()).value().getBaseTemperature(), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("regional_difficulty"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> ((Comparison) data.get("comparison"))
				.compare(entity.level().getCurrentDifficultyAt(entity.blockPosition()).getEffectiveDifficulty(), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("has_attack_target"),
			new SerializableData(),
			(data, entity) -> entity instanceof Mob mob && hasAttackTarget(mob)
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("light_at_eyes"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> {
				BlockPos eyePos = BlockPos.containing(entity.getEyePosition());
				return ((Comparison) data.get("comparison")).compare(entity.level().getMaxLocalRawBrightness(eyePos), data.getInt("compare_to"));
			}
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("last_attack_reach"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.DOUBLE),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(AttackReachTracker.get(entity), data.getDouble("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("recent_damage_blocked"),
			new SerializableData()
				.add("window_ticks", SerializableDataTypes.INT, 60)
				.add("comparison", ApoliDataTypes.COMPARISON)
				.add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> entity instanceof LivingEntity living
				&& ((Comparison) data.get("comparison")).compare(
					DamageHistoryTracker.sumWithin(living, data.getInt("window_ticks"), living.level().getGameTime(), true),
					data.getFloat("compare_to")
				)
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("vibration_on_cooldown"),
			new SerializableData(),
			(data, entity) -> VibrationCooldownTracker.get(entity) > 0
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("critical_hit_eligible"),
			new SerializableData(),
			(data, entity) -> entity instanceof Player player
				&& player.fallDistance > 0.0F
				&& !player.onGround()
				&& !player.isPassenger()
				&& !player.isSprinting()
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("projectile_age"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(entity.tickCount, data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("last_damage_amount"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(LastDamageTracker.getAmount(entity), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("last_damage_type"),
			new SerializableData().add("value", SerializableDataTypes.STRING),
			(data, entity) -> LastDamageTracker.getType(entity).equals(data.getString("value"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("recent_knockback"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.DOUBLE),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(KnockbackTracker.get(entity), data.getDouble("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("time_airborne"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> entity instanceof Player player
				&& ((Comparison) data.get("comparison")).compare(MovementStateTracker.getAirborneTicks(player), data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("sprint_ticks"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> entity instanceof Player player
				&& ((Comparison) data.get("comparison")).compare(MovementStateTracker.getSprintTicks(player), data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("submersion_depth"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(submersionDepth(entity), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("glide_ratio"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> entity instanceof LivingEntity living && living.isFallFlying()
				&& ((Comparison) data.get("comparison")).compare(glideRatio(living), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("time_since_last_meal"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> entity instanceof Player player
				&& ((Comparison) data.get("comparison")).compare(
					MealTracker.ticksSinceLastMeal(player, player.level().getGameTime()),
					data.getInt("compare_to")
				)
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("distance_to_nearest_player"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.DOUBLE),
			(data, entity) -> {
				double nearest = nearestOtherPlayerDistance(entity);
				return ((Comparison) data.get("comparison")).compare(nearest, data.getDouble("compare_to"));
			}
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("redstone_power"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(redstonePowerAtFeet(entity), data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("moon_phase"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.INT),
			(data, entity) -> ((Comparison) data.get("comparison"))
				.compare(entity.level().dimensionType().moonPhase(entity.level().getDayTime()), data.getInt("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("friction"),
			new SerializableData().add("comparison", ApoliDataTypes.COMPARISON).add("compare_to", SerializableDataTypes.FLOAT),
			(data, entity) -> ((Comparison) data.get("comparison")).compare(friction(entity), data.getFloat("compare_to"))
		));

		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("is_moving_loudly"),
			new SerializableData(),
			(data, entity) -> isMovingLoudly(entity)
		));

		// Without a comparison: is the item on its vanilla cooldown at all. With one: compare the ticks left.
		register(new ConditionFactory<>(
			OriginsPowerfulPowers.id("item_on_cooldown"),
			new SerializableData()
				.add("item", SerializableDataTypes.ITEM, null)
				.add("slot", VanillaSlot.DATA_TYPE, VanillaSlot.MAINHAND)
				.add("comparison", ApoliDataTypes.COMPARISON, null)
				.add("compare_to", SerializableDataTypes.INT, 0),
			(data, entity) -> {
				if (!(entity instanceof Player player)) {
					return false;
				}
				Item item = OppItemCooldowns.resolve(player, data.get("item"), data.get("slot"));
				int ticks = item == null ? 0 : OppItemCooldowns.ticksRemaining(player, item);
				Comparison comparison = data.get("comparison");
				return comparison == null ? ticks > 0 : comparison.compare(ticks, data.getInt("compare_to"));
			}
		));
	}

	/**
	 * Goal-based mobs (zombies, skeletons, ...) keep their target in Mob#getTarget; brain-based ones
	 * (piglins, hoglins, wardens, ...) keep it in the ATTACK_TARGET memory. Apoli's origins:attack_target
	 * uses getTarget, so check both.
	 */
	public static boolean hasAttackTarget(Mob mob) {
		return mob.getTarget() != null || mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).isPresent();
	}

	/**
	 * The friction the entity gets from the block it moves on: that block's slipperiness (0.6 for most
	 * blocks, 0.8 slime, 0.98 ice, 0.989 blue ice), then any origins:modify_slipperiness powers that apply
	 * to it. Same block position and same modifier call as Apoli's LivingEntityMixin#modifySlipperiness.
	 */
	public static float friction(Entity entity) {
		BlockPos pos = ((EntityAccessor) entity).opp$getBlockPosBelowThatAffectsMyMovement();
		float slipperiness = entity.level().getBlockState(pos).getBlock().getFriction();
		return PowerHolderComponent.modify(entity, ModifySlipperinessPower.class, slipperiness,
			power -> power.doesApply(entity.level(), pos));
	}

	public static boolean isMovingLoudly(Entity entity) {
		if (entity.isCrouching()) {
			return false;
		}
		var velocity = entity.getDeltaMovement();
		double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		return horizontalSpeed > 0.15 && entity.onGround();
	}

	public static double nearestOtherPlayerDistance(Entity entity) {
		double nearest = Double.MAX_VALUE;
		for (Player other : entity.level().players()) {
			if (other != entity) {
				nearest = Math.min(nearest, other.distanceTo(entity));
			}
		}
		return nearest;
	}

	/** The redstone signal the block at the entity's feet receives, 0-15. */
	public static int redstonePowerAtFeet(Entity entity) {
		return OppBlockConditions.redstonePower(entity.level(), entity.blockPosition(), OppBlockConditions.RedstoneMode.RECEIVED, null);
	}

	/**
	 * Blocks from the player's position to the centre of the block they last died in (the recovery compass's
	 * target). -1 when they have never died or died in another dimension.
	 */
	public static double lastDeathDistance(Player player) {
		return player.getLastDeathLocation()
			.filter(death -> death.dimension() == player.level().dimension())
			.map(death -> Math.sqrt(death.pos().distToCenterSqr(player.position())))
			.orElse(-1.0);
	}

	public static float glideRatio(LivingEntity living) {
		var velocity = living.getDeltaMovement();
		double horizontalSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		double verticalSpeed = Math.abs(velocity.y);
		return verticalSpeed < 1.0E-4 ? (float) horizontalSpeed * 1000.0F : (float) (horizontalSpeed / verticalSpeed);
	}

	public static float submersionDepth(Entity entity) {
		if (!entity.isInWater()) {
			return 0.0F;
		}
		BlockPos.MutableBlockPos mutable = BlockPos.containing(entity.getEyePosition()).mutable();
		int depth = 0;
		while (depth < 16 && !entity.level().getFluidState(mutable).isEmpty()) {
			mutable.move(0, 1, 0);
			depth++;
		}
		return (float) depth;
	}

	private static void register(ConditionFactory<net.minecraft.world.entity.Entity> factory) {
		Registry.register(ApoliRegistries.ENTITY_CONDITION, factory.getSerializerId(), factory);
	}
}
