package net.users.tracking;

import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.users.OriginsPowerfulPowers;
import net.users.power.FakeEntityPowers;
import net.users.mixin.PlayerInfoEntriesAccessor;

import java.util.ArrayList;
import com.mojang.authlib.GameProfile;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Fake entities: entities that exist only on the clients of the players allowed to see them. Each is an Entity made with
 * EntityType#create and never added to its level, so vanilla's tracker, saving and entity lookups never see it. OPP
 * sends its spawn, movement and removal packets itself.
 *
 * Mobs tick here with their goals removed and their brain skipped (FakeEntityGuardMixin). OPP drives their navigation:
 * hostile chases the nearest viewer, neutral wanders, passive wanders until a viewer hits it and then chases them. A
 * chaser stops within melee reach and keeps facing its target. While a fake entity ticks, TICKING is set and
 * FakeEntityGuardMixin cancels anything that would reach the real world: block changes, new entities, damage, sounds,
 * game events, pushing and block contact.
 */
public final class FakeEntityTracker {
	public enum Behavior {
		HOSTILE, NEUTRAL, PASSIVE, STATIONARY
	}

	/** True only on the server thread while a fake entity's tick() runs. */
	public static boolean TICKING = false;

	/** How often a live viewer_condition is re-checked, in ticks. */
	private static final int RECHECK_TICKS = 10;
	/** How often a chasing fake entity re-paths to its target, in ticks. */
	private static final int REPATH_TICKS = 10;

	private static final class FakeEntity {
		/** This fake entity's number, unique across the server: chosen by the pack, or assigned when it wasn't given one. */
		final int id;
		final Entity entity;
		final ServerLevel level;
		final Entity owner;
		final UUID ownerId;
		/** Fake players are sent as players and never tick; everything else is a normal (never added) entity. */
		final boolean fakePlayer;
		/**
		 * The mob that's steered and ticked: the fake entity itself, or for a fake player an unseen husk walking in its
		 * place, whose position and rotation the fake player copies. Null for fake entities that aren't mobs.
		 */
		final Mob mover;
		/** The username whose skin it wears, for fake players. */
		final String skin;
		final Behavior behavior;
		final Predicate<Tuple<Entity, Entity>> viewerCondition;
		final boolean recheck;
		int ticksLeft;
		/** Players whose client has this fake entity right now, and the level they had it in. */
		final Map<ServerPlayer, ResourceKey<Level>> shownTo = new IdentityHashMap<>();
		/** viewer_condition results: kept for good without recheck, refreshed every RECHECK_TICKS with it. */
		final Map<UUID, Boolean> allowed = new HashMap<>();
		/** The armor and held items viewers last got, so a change (from fake_entity_action or /opp fakeentity run) is sent on. */
		final Map<EquipmentSlot, ItemStack> sentEquipment = new EnumMap<>(EquipmentSlot.class);
		/** Passive fake entities that were hit, and hostile ones, chase this player. */
		ServerPlayer target;
		boolean angry;
		int age;

		FakeEntity(int id, Entity entity, Mob mover, ServerLevel level, Entity owner, boolean fakePlayer, String skin, Behavior behavior,
				Predicate<Tuple<Entity, Entity>> viewerCondition, boolean recheck, int lifetime) {
			this.id = id;
			this.mover = mover;
			this.ownerId = owner == null ? null : owner.getUUID();
			this.fakePlayer = fakePlayer;
			this.skin = skin;
			this.entity = entity;
			this.level = level;
			this.owner = owner;
			this.behavior = behavior;
			this.viewerCondition = viewerCondition;
			this.recheck = recheck;
			this.ticksLeft = lifetime;
		}
	}

	private static final Map<Integer, FakeEntity> FAKE_ENTITIES = new HashMap<>();
	/** Fake entity ids are unique across the server: spawning one with an id that's in use replaces the old fake entity. */
	private static final Map<Integer, FakeEntity> BY_ID = new HashMap<>();

	private FakeEntityTracker() {
	}

	public static boolean isFakeEntity(Entity entity) {
		return FAKE_ENTITIES.containsKey(entity.getId()) && FAKE_ENTITIES.get(entity.getId()).entity == entity;
	}

	/**
	 * Spawns a fake entity of the type at pos in the owner's level, facing the way the owner faces. id null assigns
	 * the next free one. behavior null is stationary for fake players and neutral for everything else. nbt, if given, is
	 * merged onto the new entity as-is, like /summon's: checking it is the pack's job. Returns the fake entity's id, or 0 when
	 * the type can't be a fake entity (players, and types that can't be created on their own).
	 */
	public static int spawn(EntityType<?> type, Entity owner, Vec3 pos, Behavior behavior,
			Predicate<Tuple<Entity, Entity>> viewerCondition, boolean recheck, int lifetime, Integer id, String skin, CompoundTag nbt) {
		if (!(owner.level() instanceof ServerLevel level)) {
			return 0;
		}
		if (id != null && id < 1) {
			OriginsPowerfulPowers.LOGGER.warn("[fake entity] ids start at 1, so {} was ignored and one was assigned", id);
			id = null;
		}
		boolean fakePlayer = type == EntityType.PLAYER;
		if (behavior == null) {
			behavior = fakePlayer ? Behavior.STATIONARY : Behavior.NEUTRAL;
		}
		Entity entity = fakePlayer ? createFakePlayer(level, skin) : type.create(level);
		if (entity == null) {
			return 0;
		}
		float yaw = owner.getYRot();
		if (nbt != null) {
			applyNbt(entity, nbt);
			if (nbt.contains("Rotation")) {
				yaw = entity.getYRot();
			}
		}
		place(entity, pos, yaw);
		Mob mover = entity instanceof Mob mob ? mob : null;
		if (fakePlayer) {
			// Players have no pathfinding, so a husk (player-sized, and it doesn't burn in daylight) walks for it.
			mover = EntityType.HUSK.create(level);
			if (mover != null) {
				place(mover, pos, yaw);
			}
		}
		return track(id, entity, mover, level, owner, fakePlayer, skin, behavior, viewerCondition, recheck, lifetime);
	}

	/**
	 * Merges the pack's NBT onto the entity's own, the way /data merge does, and loads the result. The position always
	 * comes from the action (like /summon), and the entity keeps its UUID: a fake player's is already in its profile.
	 * Invulnerable, Silent, PersistenceRequired and CanPickUpLoot are set after this, so NBT can't change them.
	 */
	private static void applyNbt(Entity entity, CompoundTag nbt) {
		UUID uuid = entity.getUUID();
		try {
			CompoundTag merged = entity.saveWithoutId(new CompoundTag());
			merged.merge(nbt);
			entity.load(merged);
		} catch (RuntimeException e) {
			OriginsPowerfulPowers.LOGGER.warn("[fake entity] couldn't apply nbt {}: {}", nbt, e.toString());
		}
		entity.setUUID(uuid);
	}

	private static void place(Entity entity, Vec3 pos, float yaw) {
		entity.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
		entity.setYHeadRot(yaw);
		if (entity instanceof LivingEntity living) {
			living.setYBodyRot(yaw);
		}
	}

	/**
	 * A player-shaped fake entity: a Fabric FakePlayer that's never added to the world. FakePlayer absorbs the packets,
	 * stats and damage that break a ServerPlayer with no connection, so power actions can run on it. It moves by
	 * copying its husk (Fake entity.mover). Its profile gets a random id, so it can't clash with the real account, and the
	 * looked-up skin.
	 */
	private static Entity createFakePlayer(ServerLevel level, String skin) {
		String name = skin == null || skin.isBlank() ? "Fake entity" : skin.trim();
		GameProfile profile = new GameProfile(UUID.randomUUID(), name.length() > 16 ? name.substring(0, 16) : name);
		GameProfile known = skin == null ? null : FakeSkins.cached(skin);
		if (known != null) {
			FakeSkins.applySkin(profile, known);
		}
		try {
			return new FakeEntityPlayer(level, profile);
		} catch (RuntimeException e) {
			OriginsPowerfulPowers.LOGGER.error("[fake entity] couldn't build a fake player: {}", e.toString());
			return null;
		}
	}

	/** FakePlayer's constructor is protected so each mod makes its own; FakePlayer.get would share one per profile. */
	private static final class FakeEntityPlayer extends FakePlayer {
		FakeEntityPlayer(ServerLevel level, GameProfile profile) {
			super(level, profile);
		}
	}

	private static int track(Integer id, Entity entity, Mob mover, ServerLevel level, Entity owner, boolean fakePlayer, String skin, Behavior behavior,
			Predicate<Tuple<Entity, Entity>> viewerCondition, boolean recheck, int lifetime) {
		entity.setInvulnerable(true);
		entity.setSilent(true);
		if (mover != null) {
			mover.setInvulnerable(true);
			mover.setSilent(true);
			mover.setCanPickUpLoot(false);
			mover.setPersistenceRequired();
			((net.users.mixin.MobAccessor) mover).opp$getGoalSelector().removeAllGoals(goal -> true);
			((net.users.mixin.MobAccessor) mover).opp$getTargetSelector().removeAllGoals(goal -> true);
		}
		if (id != null) {
			despawn(id);
		}
		int assigned = id != null ? id : nextFreeId();
		FakeEntity fakeEntity = new FakeEntity(assigned, entity, mover, level, owner, fakePlayer, skin, behavior, viewerCondition, recheck, Math.max(1, lifetime));
		equipmentChanges(fakeEntity);
		FAKE_ENTITIES.put(entity.getId(), fakeEntity);
		BY_ID.put(assigned, fakeEntity);
		if (fakePlayer && skin != null && !skin.isBlank() && FakeSkins.cached(skin) == null) {
			// The skin arrives later: re-send the fake entity to whoever can see it once it's known.
			FakeSkins.request(level.getServer(), skin, () -> reskin(fakeEntity, skin));
		}
		return assigned;
	}

	/** The lowest id no live fake entity has, so ids freed by despawning are used again and stay small. */
	private static int nextFreeId() {
		int id = 1;
		while (BY_ID.containsKey(id)) {
			id++;
		}
		return id;
	}

	/** The skin finally arrived: put it on the profile and show the fake entity again to everyone who has it. */
	private static void reskin(FakeEntity fakeEntity, String skin) {
		GameProfile known = FakeSkins.cached(skin);
		if (known == null || fakeEntity.entity.isRemoved() || !FAKE_ENTITIES.containsKey(fakeEntity.entity.getId())) {
			return;
		}
		FakeSkins.applySkin(((Player) fakeEntity.entity).getGameProfile(), known);
		List<ServerPlayer> viewers = new ArrayList<>(fakeEntity.shownTo.keySet());
		for (ServerPlayer viewer : viewers) {
			hideFrom(fakeEntity, viewer);
			fakeEntity.shownTo.remove(viewer);
		}
	}

	/** Removes the fake entity with this id. Returns true if there was one. */
	public static boolean despawn(int id) {
		FakeEntity fakeEntity = BY_ID.get(id);
		if (fakeEntity == null) {
			return false;
		}
		remove(fakeEntity);
		return true;
	}

	/** The fake entity entity with this id, or null. */
	public static Entity get(int id) {
		FakeEntity fakeEntity = BY_ID.get(id);
		return fakeEntity == null ? null : fakeEntity.entity;
	}

	/** The fake entity with this id (whoever owns it), or with no id every fake entity this entity spawned, lowest id first. */
	public static List<Entity> find(Entity owner, Integer id) {
		List<Entity> found = new ArrayList<>();
		if (id != null) {
			FakeEntity fakeEntity = BY_ID.get(id);
			if (fakeEntity != null) {
				found.add(fakeEntity.entity);
			}
			return found;
		}
		for (Integer each : ids()) {
			FakeEntity fakeEntity = BY_ID.get(each);
			if (owner.getUUID().equals(fakeEntity.ownerId)) {
				found.add(fakeEntity.entity);
			}
		}
		return found;
	}

	/**
	 * Runs a power action or command on a fake entity. A failure is logged and kept from the power that ran it. A fake
	 * player's husk then takes on what the action changed (position, velocity, facing), since the next tick moves the
	 * fake player to wherever the husk is.
	 */
	public static void runOn(Entity entity, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			OriginsPowerfulPowers.LOGGER.warn("[fake entity] an action on a fake entity failed: {}", e.toString());
		}
		FakeEntity fakeEntity = FAKE_ENTITIES.get(entity.getId());
		if (fakeEntity != null && fakeEntity.entity == entity && fakeEntity.fakePlayer && fakeEntity.mover != null) {
			Mob mover = fakeEntity.mover;
			if (entity.position().distanceToSqr(mover.position()) > 1.0E-6) {
				mover.getNavigation().stop();
				mover.setPos(entity.getX(), entity.getY(), entity.getZ());
			}
			mover.setDeltaMovement(entity.getDeltaMovement());
			mover.setYRot(entity.getYRot());
			mover.setXRot(entity.getXRot());
			mover.setYHeadRot(entity.getYHeadRot());
			mover.setYBodyRot(((LivingEntity) entity).yBodyRot);
		}
	}

	/** The ids of every live fake entity, lowest first. */
	public static List<Integer> ids() {
		List<Integer> ids = new ArrayList<>(BY_ID.keySet());
		java.util.Collections.sort(ids);
		return ids;
	}

	/** Removes every fake entity this entity spawned. Returns how many. */
	public static int despawnAllOwnedBy(UUID ownerId) {
		List<FakeEntity> owned = new ArrayList<>();
		for (FakeEntity fakeEntity : FAKE_ENTITIES.values()) {
			if (ownerId.equals(fakeEntity.ownerId)) {
				owned.add(fakeEntity);
			}
		}
		owned.forEach(FakeEntityTracker::remove);
		return owned.size();
	}

	/** Every live fake entity, as "id: type at x, y, z, N ticks left, V viewer(s)", lowest id first. */
	public static List<String> describeAll() {
		List<FakeEntity> sorted = new ArrayList<>(FAKE_ENTITIES.values());
		sorted.sort(java.util.Comparator.comparingInt(fakeEntity -> fakeEntity.id));
		List<String> lines = new ArrayList<>();
		for (FakeEntity fakeEntity : sorted) {
			Entity entity = fakeEntity.entity;
			lines.add(fakeEntity.id + ": "
				+ (fakeEntity.fakePlayer ? "fake player" + (fakeEntity.skin == null ? "" : " (" + fakeEntity.skin + ")") : EntityType.getKey(entity.getType()).toString())
				+ " at " + entity.blockPosition().toShortString()
				+ ", " + fakeEntity.ticksLeft + " ticks left, " + fakeEntity.shownTo.size() + " viewer(s)");
		}
		return lines;
	}

	private static void remove(FakeEntity fakeEntity) {
		hideFromAll(fakeEntity);
		FAKE_ENTITIES.remove(fakeEntity.entity.getId());
		BY_ID.remove(fakeEntity.id, fakeEntity);
	}

	/** A viewer attacked the entity with this id. Passive fake entities get angry at them, and the owner's action_on_fake_entity_hit runs. */
	public static void onAttacked(int entityId, ServerPlayer attacker) {
		FakeEntity fakeEntity = FAKE_ENTITIES.get(entityId);
		if (fakeEntity == null || !fakeEntity.shownTo.containsKey(attacker)) {
			return;
		}
		if (fakeEntity.behavior == Behavior.PASSIVE) {
			fakeEntity.angry = true;
			fakeEntity.target = attacker;
		}
		FakeEntityPowers.onHit(fakeEntity.owner, fakeEntity.id, fakeEntity.entity, attacker);
	}

	public static void tick(MinecraftServer server) {
		Iterator<FakeEntity> iterator = FAKE_ENTITIES.values().iterator();
		while (iterator.hasNext()) {
			FakeEntity fakeEntity = iterator.next();
			fakeEntity.age++;
			boolean gone = --fakeEntity.ticksLeft <= 0 || fakeEntity.entity.isRemoved()
				|| server.getLevel(fakeEntity.level.dimension()) != fakeEntity.level;
			if (!gone) {
				updateViewers(server, fakeEntity);
				if (fakeEntity.level.isLoaded(fakeEntity.entity.blockPosition())) {
					tickFakeEntity(fakeEntity);
				}
			}
			if (gone) {
				hideFromAll(fakeEntity);
				iterator.remove();
				BY_ID.remove(fakeEntity.id, fakeEntity);
			} else {
				sendMovement(fakeEntity);
			}
		}
	}

	public static void clear() {
		FAKE_ENTITIES.clear();
		BY_ID.clear();
		TICKING = false;
	}

	private static void updateViewers(MinecraftServer server, FakeEntity fakeEntity) {
		Entity entity = fakeEntity.entity;
		double range = Math.max(16, entity.getType().clientTrackingRange() * 16);
		boolean recheckNow = fakeEntity.recheck && fakeEntity.age % RECHECK_TICKS == 1;
		if (recheckNow) {
			fakeEntity.allowed.clear();
		}
		// Players who left, died (a new ServerPlayer), or changed dimension already lost the entity client-side.
		fakeEntity.shownTo.entrySet().removeIf(entry -> entry.getKey().hasDisconnected() || entry.getKey().isRemoved()
			|| entry.getKey().level().dimension() != entry.getValue());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean show = player.level() == fakeEntity.level
				&& player.distanceToSqr(entity) <= range * range
				&& isAllowed(fakeEntity, player);
			boolean shown = fakeEntity.shownTo.containsKey(player);
			if (show && !shown) {
				showTo(fakeEntity, player);
				fakeEntity.shownTo.put(player, player.level().dimension());
			} else if (!show && shown) {
				hideFrom(fakeEntity, player);
				fakeEntity.shownTo.remove(player);
			}
		}
	}

	private static boolean isAllowed(FakeEntity fakeEntity, ServerPlayer player) {
		if (fakeEntity.viewerCondition == null) {
			return true;
		}
		return fakeEntity.allowed.computeIfAbsent(player.getUUID(), uuid -> fakeEntity.owner != null && !fakeEntity.owner.isRemoved()
			&& fakeEntity.viewerCondition.test(new Tuple<>(player, fakeEntity.owner)));
	}

	/**
	 * Steers the mob, then runs its own tick with TICKING set. A fake player has no connection, so ticking it would
	 * break: its husk ticks instead, and the fake player copies where the husk went. The client animates the walk from
	 * that movement (RemotePlayer#tick).
	 */
	private static void tickFakeEntity(FakeEntity fakeEntity) {
		Entity ticked = fakeEntity.fakePlayer ? fakeEntity.mover : fakeEntity.entity;
		if (ticked == null) {
			return;
		}
		if (fakeEntity.mover != null) {
			steer(fakeEntity, fakeEntity.mover);
		}
		ticked.setOldPosAndRot();
		ticked.tickCount++;
		TICKING = true;
		try {
			ticked.tick();
		} finally {
			TICKING = false;
		}
		if (fakeEntity.fakePlayer && fakeEntity.entity instanceof Player fake) {
			copyMover(fakeEntity.mover, fake);
		}
	}

	private static void copyMover(Mob mover, Player fake) {
		fake.setOldPosAndRot();
		fake.setPos(mover.getX(), mover.getY(), mover.getZ());
		fake.setDeltaMovement(mover.getDeltaMovement());
		fake.setYRot(mover.getYRot());
		fake.setXRot(mover.getXRot());
		fake.setYHeadRot(mover.getYHeadRot());
		fake.setYBodyRot(mover.yBodyRot);
		fake.setOnGround(mover.onGround());
	}

	private static void steer(FakeEntity fakeEntity, Mob mob) {
		ServerPlayer target = fakeEntity.target;
		if (target != null && (!fakeEntity.shownTo.containsKey(target) || !target.isAlive())) {
			target = null;
			fakeEntity.angry = false;
		}
		if (fakeEntity.behavior == Behavior.HOSTILE) {
			double followRange = mob.getAttribute(Attributes.FOLLOW_RANGE) != null ? mob.getAttributeValue(Attributes.FOLLOW_RANGE) : 16.0;
			target = nearestViewer(fakeEntity, followRange);
		}
		if (fakeEntity.behavior == Behavior.STATIONARY) {
			// Never moves; only turns to look at the nearest viewer.
			target = null;
		}
		if (fakeEntity.target != target) {
			mob.getNavigation().stop();
		}
		fakeEntity.target = target;
		if (target != null) {
			mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
			if (mob.distanceToSqr(target) <= reachSqr(mob, target)) {
				mob.getNavigation().stop();
			} else if (fakeEntity.age % REPATH_TICKS == 1 || mob.getNavigation().isDone()) {
				mob.getNavigation().moveTo(target, 1.0);
			}
			return;
		}
		// Neutral, passive until hit, and stationary: glance at nearby viewers. All but stationary wander.
		ServerPlayer near = nearestViewer(fakeEntity, 8.0);
		if (near != null) {
			mob.getLookControl().setLookAt(near, 10.0F, mob.getMaxHeadXRot());
		}
		if (fakeEntity.behavior != Behavior.STATIONARY && mob instanceof PathfinderMob walker && mob.getNavigation().isDone() && mob.getRandom().nextInt(120) == 0) {
			Vec3 spot = DefaultRandomPos.getPos(walker, 10, 7);
			if (spot != null) {
				mob.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.0);
			}
		}
	}

	private static ServerPlayer nearestViewer(FakeEntity fakeEntity, double range) {
		ServerPlayer nearest = null;
		double best = range * range;
		for (ServerPlayer player : fakeEntity.shownTo.keySet()) {
			double distance = player.distanceToSqr(fakeEntity.entity);
			if (player.isAlive() && !player.isSpectator() && distance <= best) {
				best = distance;
				nearest = player;
			}
		}
		return nearest;
	}

	/** Vanilla's melee reach (Mob#getMeleeAttackRangeSqr in later versions): twice the width, squared, plus the target's width. */
	private static double reachSqr(Mob mob, Entity target) {
		double width = mob.getBbWidth() * 2.0F;
		return width * width + target.getBbWidth();
	}

	/**
	 * A fake player needs a player-info entry before its spawn packet, or the client draws nothing. The entry is built
	 * by hand so it can be "not listed": no tab-list row. A team with its name hidden keeps the name tag off, and the
	 * team, like everything else here, only exists on that client.
	 */
	private static void showTo(FakeEntity fakeEntity, ServerPlayer viewer) {
		Entity entity = fakeEntity.entity;
		if (fakeEntity.fakePlayer && entity instanceof Player fake) {
			ClientboundPlayerInfoUpdatePacket info = new ClientboundPlayerInfoUpdatePacket(
				EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED),
				List.of());
			((PlayerInfoEntriesAccessor) info).opp$setEntries(List.of(new ClientboundPlayerInfoUpdatePacket.Entry(
				fake.getUUID(), fake.getGameProfile(), false, 0, GameType.SURVIVAL, null, null)));
			viewer.connection.send(info);
			viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(hiddenNameTeam(), true));
			viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(hiddenNameTeam(), fake.getGameProfile().getName(),
				ClientboundSetPlayerTeamPacket.Action.ADD));
		}
		for (Packet<ClientGamePacketListener> packet : spawnPackets(entity)) {
			viewer.connection.send(packet);
		}
		// Vanilla sends equipment when an entity starts being tracked (ServerEntity#sendPairingData); fake entities aren't.
		List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
		if (entity instanceof LivingEntity living) {
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = living.getItemBySlot(slot);
				if (!stack.isEmpty()) {
					equipment.add(Pair.of(slot, stack.copy()));
				}
			}
		}
		if (!equipment.isEmpty()) {
			viewer.connection.send(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
		}
	}

	/**
	 * The slots whose item changed since last time, with their new items, and remembers them as sent. Empty for
	 * fake entities that aren't living entities. Fake players read theirs from their inventory, so this works for both.
	 */
	private static List<Pair<EquipmentSlot, ItemStack>> equipmentChanges(FakeEntity fakeEntity) {
		List<Pair<EquipmentSlot, ItemStack>> changed = new ArrayList<>();
		if (!(fakeEntity.entity instanceof LivingEntity living)) {
			return changed;
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack now = living.getItemBySlot(slot);
			ItemStack sent = fakeEntity.sentEquipment.getOrDefault(slot, ItemStack.EMPTY);
			if (!ItemStack.matches(now, sent)) {
				fakeEntity.sentEquipment.put(slot, now.copy());
				changed.add(Pair.of(slot, now.copy()));
			}
		}
		return changed;
	}

	private static void hideFrom(FakeEntity fakeEntity, ServerPlayer viewer) {
		if (viewer.hasDisconnected()) {
			return;
		}
		viewer.connection.send(new ClientboundRemoveEntitiesPacket(fakeEntity.entity.getId()));
		if (fakeEntity.fakePlayer) {
			viewer.connection.send(new ClientboundPlayerInfoRemovePacket(List.of(fakeEntity.entity.getUUID())));
		}
	}

	/** A scoreboard-less team that exists only in the packets above, so fake players show no name tag. */
	private static PlayerTeam hiddenNameTeam() {
		PlayerTeam team = new PlayerTeam(new Scoreboard(), "opp_fakeentity");
		team.setNameTagVisibility(Team.Visibility.NEVER);
		return team;
	}

	private static List<Packet<ClientGamePacketListener>> spawnPackets(Entity entity) {
		List<Packet<ClientGamePacketListener>> packets = new ArrayList<>();
		packets.add(entity.getAddEntityPacket());
		List<SynchedEntityData.DataValue<?>> data = entity.getEntityData().getNonDefaultValues();
		if (data != null) {
			packets.add(new ClientboundSetEntityDataPacket(entity.getId(), data));
		}
		packets.add(new ClientboundRotateHeadPacket(entity, (byte) Mth.floor(entity.getYHeadRot() * 256.0F / 360.0F)));
		return packets;
	}

	private static void sendMovement(FakeEntity fakeEntity) {
		if (fakeEntity.shownTo.isEmpty()) {
			return;
		}
		Entity entity = fakeEntity.entity;
		List<Packet<ClientGamePacketListener>> packets = new ArrayList<>();
		packets.add(new ClientboundTeleportEntityPacket(entity));
		packets.add(new ClientboundRotateHeadPacket(entity, (byte) Mth.floor(entity.getYHeadRot() * 256.0F / 360.0F)));
		List<SynchedEntityData.DataValue<?>> dirty = entity.getEntityData().packDirty();
		if (dirty != null) {
			packets.add(new ClientboundSetEntityDataPacket(entity.getId(), dirty));
		}
		List<Pair<EquipmentSlot, ItemStack>> equipment = equipmentChanges(fakeEntity);
		if (!equipment.isEmpty()) {
			packets.add(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
		}
		for (ServerPlayer player : fakeEntity.shownTo.keySet()) {
			for (Packet<ClientGamePacketListener> packet : packets) {
				player.connection.send(packet);
			}
		}
	}

	private static void hideFromAll(FakeEntity fakeEntity) {
		for (ServerPlayer player : fakeEntity.shownTo.keySet()) {
			hideFrom(fakeEntity, player);
		}
		fakeEntity.shownTo.clear();
	}
}
