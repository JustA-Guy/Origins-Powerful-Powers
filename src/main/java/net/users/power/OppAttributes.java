package net.users.power;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.users.OriginsPowerfulPowers;
import net.users.compat.hcr.HcrCompat;
import net.users.compat.svc.SvcCompat;
import net.users.mixin.FoodDataAccessor;
import net.users.mixin.LivingEntityAccessor;
import net.users.tracking.AttackReachTracker;
import net.users.tracking.BroadcastTracker;
import net.users.tracking.CrouchDepthTracker;
import net.users.tracking.DamageHistoryTracker;
import net.users.tracking.DeafenTracker;
import net.users.tracking.KnockbackTracker;
import net.users.tracking.LastDamageTracker;
import net.users.tracking.MealTracker;
import net.users.tracking.MovementStateTracker;
import net.users.tracking.MuteTracker;
import net.users.tracking.VibrationCooldownTracker;
import net.users.tracking.VoiceChatTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Read-only attributes mirroring the OPP conditions, so "/attribute @s get origins-powerful-powers:<id>" reads them with
 * no power. They're synced onto the player every tick, which overwrites any base value set by hand.
 *
 * DRAG is the exception: settable, on every living entity, and never synced.
 */
public final class OppAttributes {
	public static final List<Entry> ENTRIES = new ArrayList<>();

	public static final Attribute ATTACK_COOLDOWN_PROGRESS = define("attack_cooldown_progress", 0.0, 0.0, 1.0,
		player -> player.getAttackStrengthScale(0.0F));
	public static final Attribute HURT_TIME = define("hurt_time", 0.0, 0.0, 40.0,
		player -> player.hurtTime);
	public static final Attribute INVULNERABLE_TICKS = define("invulnerable_ticks", 0.0, 0.0, 40.0,
		player -> player.invulnerableTime);
	public static final Attribute VELOCITY_X = define("velocity_x", 0.0, -16.0, 16.0,
		player -> player.getDeltaMovement().x);
	public static final Attribute VELOCITY_Y = define("velocity_y", 0.0, -16.0, 16.0,
		player -> player.getDeltaMovement().y);
	public static final Attribute VELOCITY_Z = define("velocity_z", 0.0, -16.0, 16.0,
		player -> player.getDeltaMovement().z);
	public static final Attribute LOOK_PITCH = define("look_pitch", 0.0, -90.0, 90.0,
		Player::getXRot);
	public static final Attribute LOOK_YAW = define("look_yaw", 0.0, -180.0, 180.0,
		Player::getYRot);
	public static final Attribute IS_JUMPING = define("is_jumping", 0.0, 0.0, 1.0,
		player -> ((LivingEntityAccessor) player).opp$isJumping() ? 1.0 : 0.0);
	public static final Attribute CROUCH_DEPTH = define("crouch_depth", 0.0, 0.0, 1.0,
		CrouchDepthTracker::get);
	public static final Attribute EXHAUSTION = define("exhaustion", 0.0, 0.0, 40.0,
		player -> ((FoodDataAccessor) player.getFoodData()).opp$getExhaustionLevel());
	public static final Attribute XP_PROGRESS = define("xp_progress", 0.0, 0.0, 1.0,
		player -> player.experienceProgress);
	public static final Attribute BIOME_TEMPERATURE = define("biome_temperature", 0.0, -2.0, 2.0,
		player -> player.level().getBiome(player.blockPosition()).value().getBaseTemperature());
	public static final Attribute REGIONAL_DIFFICULTY = define("regional_difficulty", 0.0, 0.0, 6.75,
		player -> player.level().getCurrentDifficultyAt(player.blockPosition()).getEffectiveDifficulty());
	public static final Attribute LIGHT_AT_EYES = define("light_at_eyes", 0.0, 0.0, 15.0,
		player -> player.level().getMaxLocalRawBrightness(BlockPos.containing(player.getEyePosition())));
	public static final Attribute LAST_ATTACK_REACH = define("last_attack_reach", 0.0, 0.0, 32.0,
		AttackReachTracker::get);
	public static final Attribute RECENT_DAMAGE_TAKEN = define("recent_damage_taken", 0.0, 0.0, 2048.0,
		player -> DamageHistoryTracker.sumWithin(player, 60, player.level().getGameTime(), false));
	public static final Attribute RECENT_DAMAGE_BLOCKED = define("recent_damage_blocked", 0.0, 0.0, 2048.0,
		player -> DamageHistoryTracker.sumWithin(player, 60, player.level().getGameTime(), true));
	public static final Attribute VIBRATION_COOLDOWN = define("vibration_cooldown", 0.0, 0.0, 2400.0,
		VibrationCooldownTracker::get);
	public static final Attribute VIBRATION_ON_COOLDOWN = define("vibration_on_cooldown", 0.0, 0.0, 1.0,
		player -> VibrationCooldownTracker.get(player) > 0 ? 1.0 : 0.0);
	public static final Attribute IS_BLOCKING = define("is_blocking", 0.0, 0.0, 1.0,
		player -> player.isBlocking() ? 1.0 : 0.0);
	public static final Attribute CRITICAL_HIT_ELIGIBLE = define("critical_hit_eligible", 0.0, 0.0, 1.0,
		player -> player.fallDistance > 0.0F && !player.onGround() && !player.isPassenger() && !player.isSprinting() ? 1.0 : 0.0);
	public static final Attribute LAST_DAMAGE_AMOUNT = define("last_damage_amount", 0.0, 0.0, 2048.0,
		LastDamageTracker::getAmount);
	public static final Attribute RECENT_KNOCKBACK = define("recent_knockback", 0.0, 0.0, 16.0,
		KnockbackTracker::get);
	public static final Attribute TIME_AIRBORNE = define("time_airborne", 0.0, 0.0, 72000.0,
		MovementStateTracker::getAirborneTicks);
	public static final Attribute SPRINT_TICKS = define("sprint_ticks", 0.0, 0.0, 72000.0,
		MovementStateTracker::getSprintTicks);
	public static final Attribute SUBMERSION_DEPTH = define("submersion_depth", 0.0, 0.0, 16.0,
		OppEntityConditions::submersionDepth);
	public static final Attribute GLIDE_RATIO = define("glide_ratio", 0.0, 0.0, 1000.0,
		player -> player.isFallFlying() ? OppEntityConditions.glideRatio(player) : 0.0);
	public static final Attribute TIME_SINCE_LAST_MEAL = define("time_since_last_meal", 0.0, 0.0, 1.0E9,
		player -> Math.min(1.0E9, MealTracker.ticksSinceLastMeal(player, player.level().getGameTime())));
	public static final Attribute DISTANCE_TO_NEAREST_PLAYER = define("distance_to_nearest_player", 0.0, 0.0, 1.0E6,
		OppEntityConditions::nearestOtherPlayerDistance);
	public static final Attribute LAST_DEATH_DISTANCE = define("last_death_distance", -1.0, -1.0, 1.0E8,
		OppEntityConditions::lastDeathDistance);
	public static final Attribute REDSTONE_POWER = define("redstone_power", 0.0, 0.0, 15.0,
		OppEntityConditions::redstonePowerAtFeet);
	public static final Attribute MOON_PHASE = define("moon_phase", 0.0, 0.0, 7.0,
		player -> player.level().dimensionType().moonPhase(player.level().getDayTime()));
	public static final Attribute FRICTION = define("friction", 0.6, 0.0, 2.0,
		OppEntityConditions::friction);
	public static final Attribute IS_MOVING_LOUDLY = define("is_moving_loudly", 0.0, 0.0, 1.0,
		player -> OppEntityConditions.isMovingLoudly(player) ? 1.0 : 0.0);
	public static final Attribute MAINHAND_COOLDOWN = define("mainhand_cooldown", 0.0, 0.0, 1.0E9,
		player -> OppItemCooldowns.ticksRemaining(player, player.getMainHandItem().getItem()));
	public static final Attribute VOICE_INSTALLED = define("voice_installed", 0.0, 0.0, 1.0,
		player -> SvcCompat.isLoaded() && VoiceChatTracker.isInstalled(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute VOICE_MIC_DISABLED = define("voice_mic_disabled", 0.0, 0.0, 1.0,
		player -> SvcCompat.isLoaded() && VoiceChatTracker.isMicDisabled(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute IS_TALKING = define("is_talking", 0.0, 0.0, 1.0,
		player -> SvcCompat.isLoaded() && VoiceChatTracker.isTalking(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute IS_WHISPERING_VOICE = define("is_whispering_voice", 0.0, 0.0, 1.0,
		player -> SvcCompat.isLoaded() && VoiceChatTracker.isWhispering(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute IN_VOICE_GROUP = define("in_voice_group", 0.0, 0.0, 1.0,
		player -> SvcCompat.isLoaded() && VoiceChatTracker.isInGroup(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute IS_BROADCASTING_VOICE = define("is_broadcasting_voice", 0.0, 0.0, 1.0,
		player -> BroadcastTracker.isActive(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute IS_MUTED_VOICE = define("is_muted_voice", 0.0, 0.0, 1.0,
		player -> MuteTracker.isMuted(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute IS_DEAFENED_VOICE = define("is_deafened_voice", 0.0, 0.0, 1.0,
		player -> DeafenTracker.isDeafened(player.getUUID()) ? 1.0 : 0.0);
	public static final Attribute IS_DOWNED = define("is_downed", 0.0, 0.0, 1.0,
		player -> HcrCompat.isLoaded() && HcrCompat.isDowned(player) ? 1.0 : 0.0);

	/**
	 * Settable: it's left out of ENTRIES, so sync() never overwrites it. Every living entity has it
	 * (LivingEntityAttributesMixin), and LivingEntityDragMixin uses it in place of vanilla's 0.98 fall drag. The default
	 * is that same 0.98, so an untouched value changes nothing.
	 */
	public static final Attribute DRAG = new RangedAttribute(
		"attribute." + OriginsPowerfulPowers.MOD_ID + ".drag", 0.98, 0.0, 1.0
	).setSyncable(true);

	private OppAttributes() {
	}

	private static Attribute define(String path, double defaultValue, double min, double max, ToDoubleFunction<Player> valueFn) {
		Attribute attribute = new RangedAttribute("attribute." + OriginsPowerfulPowers.MOD_ID + "." + path, defaultValue, min, max)
			.setSyncable(true);
		ENTRIES.add(new Entry(attribute, valueFn));
		return attribute;
	}

	public static void register() {
		for (Entry entry : ENTRIES) {
			Registry.register(BuiltInRegistries.ATTRIBUTE, OriginsPowerfulPowers.id(idOf(entry.attribute)), entry.attribute);
		}
		Registry.register(BuiltInRegistries.ATTRIBUTE, OriginsPowerfulPowers.id("drag"), DRAG);
	}

	private static String idOf(Attribute attribute) {
		String descriptionId = attribute.getDescriptionId();
		return descriptionId.substring(("attribute." + OriginsPowerfulPowers.MOD_ID + ".").length());
	}

	public static void sync(Player player) {
		for (Entry entry : ENTRIES) {
			AttributeInstance instance = player.getAttribute(entry.attribute);
			if (instance != null) {
				instance.setBaseValue(entry.valueFn.applyAsDouble(player));
			}
		}
	}

	public static final class Entry {
		public final Attribute attribute;
		public final ToDoubleFunction<Player> valueFn;

		Entry(Attribute attribute, ToDoubleFunction<Player> valueFn) {
			this.attribute = attribute;
			this.valueFn = valueFn;
		}
	}
}
