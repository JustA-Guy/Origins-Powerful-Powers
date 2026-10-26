package net.users.tracking;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.users.mixin.ChunkMapAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Disguises: chosen players see a real entity as another entity type. The disguise is a "shadow" entity of that
 * type (EntityType#create, never added) that lends its spawn and data packets, sent under the real entity's id
 * so movement packets still apply. Per viewer:
 * - ServerEntityDisguiseMixin replaces the spawn packets (sendPairingData runs once per viewer);
 * - TrackedEntityDisguiseMixin filters later updates, keeping only packets any entity type can take
 *   (movement, animations, passengers, and equipment when both are living). Entity data and attributes are
 *   dropped: their layout depends on the entity class, and the client disconnects on a mismatch.
 * Of the real entity, only held items and armor show through.
 */
public final class DisguiseTracker {
	private static final int RECHECK_TICKS = 10;

	private static final class Disguise {
		final Entity entity;
		final Entity shadow;
		final Predicate<Tuple<Entity, Entity>> viewerCondition;
		final boolean recheck;
		/** Ticks left, or -1 for no limit. */
		int ticksLeft;
		final Map<UUID, Boolean> allowed = new HashMap<>();
		int age;

		Disguise(Entity entity, Entity shadow, Predicate<Tuple<Entity, Entity>> viewerCondition, boolean recheck, int ticks) {
			this.entity = entity;
			this.shadow = shadow;
			this.viewerCondition = viewerCondition;
			this.recheck = recheck;
			this.ticksLeft = ticks;
		}
	}

	private static final Map<Entity, Disguise> DISGUISES = new HashMap<>();

	private DisguiseTracker() {
	}

	/** Disguises the entity, replacing any disguise it had. Returns false when the type can't be a disguise. */
	public static boolean disguise(Entity entity, EntityType<?> type, Predicate<Tuple<Entity, Entity>> viewerCondition,
			boolean recheck, int ticks) {
		if (!(entity.level() instanceof ServerLevel level) || type == EntityType.PLAYER) {
			return false;
		}
		Entity shadow = type.create(level);
		if (shadow == null) {
			return false;
		}
		shadow.setId(entity.getId());
		shadow.setUUID(entity.getUUID());
		DISGUISES.put(entity, new Disguise(entity, shadow, viewerCondition, recheck, ticks <= 0 ? -1 : ticks));
		resendToViewers(entity);
		return true;
	}

	public static void undisguise(Entity entity) {
		if (DISGUISES.remove(entity) != null) {
			resendToViewers(entity);
		}
	}

	public static void tick(MinecraftServer server) {
		Iterator<Disguise> iterator = DISGUISES.values().iterator();
		List<Entity> expired = new ArrayList<>();
		while (iterator.hasNext()) {
			Disguise disguise = iterator.next();
			if (disguise.entity.isRemoved()) {
				iterator.remove();
				continue;
			}
			if (disguise.ticksLeft > 0 && --disguise.ticksLeft == 0) {
				iterator.remove();
				expired.add(disguise.entity);
				continue;
			}
			if (disguise.recheck && disguise.viewerCondition != null && ++disguise.age % RECHECK_TICKS == 0) {
				recheck(disguise);
			}
		}
		for (Entity entity : expired) {
			resendToViewers(entity);
		}
	}

	public static void clear() {
		DISGUISES.clear();
	}

	/** Whether this viewer should see the entity disguised. */
	public static boolean appliesTo(Entity entity, ServerPlayer viewer) {
		Disguise disguise = DISGUISES.get(entity);
		return disguise != null && allowed(disguise, viewer);
	}

	public static boolean isDisguised(Entity entity) {
		return DISGUISES.containsKey(entity);
	}

	/** The spawn packets a disguised viewer gets instead of vanilla's. */
	public static void sendPairing(Entity entity, Consumer<Packet<ClientGamePacketListener>> sender) {
		Disguise disguise = DISGUISES.get(entity);
		Entity shadow = disguise.shadow;
		shadow.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
		shadow.setYHeadRot(entity.getYHeadRot());
		shadow.setDeltaMovement(entity.getDeltaMovement());
		sender.accept(shadow.getAddEntityPacket());
		List<SynchedEntityData.DataValue<?>> data = shadow.getEntityData().getNonDefaultValues();
		if (data != null) {
			sender.accept(new ClientboundSetEntityDataPacket(entity.getId(), data));
		}
		sender.accept(new ClientboundRotateHeadPacket(shadow, (byte) Mth.floor(entity.getYHeadRot() * 256.0F / 360.0F)));
		if (showsEquipment(disguise)) {
			List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = ((LivingEntity) entity).getItemBySlot(slot);
				if (!stack.isEmpty()) {
					equipment.add(Pair.of(slot, stack.copy()));
				}
			}
			if (!equipment.isEmpty()) {
				sender.accept(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
			}
		}
		if (!entity.getPassengers().isEmpty()) {
			sender.accept(new ClientboundSetPassengersPacket(entity));
		}
		if (entity.isPassenger()) {
			sender.accept(new ClientboundSetPassengersPacket(entity.getVehicle()));
		}
	}

	/** Whether a broadcast update about a disguised entity may reach a disguised viewer. */
	public static boolean passes(Entity entity, Packet<?> packet) {
		if (packet instanceof ClientboundSetEquipmentPacket) {
			Disguise disguise = DISGUISES.get(entity);
			return disguise != null && showsEquipment(disguise);
		}
		return packet instanceof ClientboundMoveEntityPacket
			|| packet instanceof ClientboundTeleportEntityPacket
			|| packet instanceof ClientboundRotateHeadPacket
			|| packet instanceof ClientboundSetEntityMotionPacket
			|| packet instanceof ClientboundAnimatePacket
			|| packet instanceof ClientboundEntityEventPacket
			|| packet instanceof ClientboundHurtAnimationPacket
			|| packet instanceof ClientboundDamageEventPacket
			|| packet instanceof ClientboundSetPassengersPacket
			|| packet instanceof ClientboundSetEntityLinkPacket
			|| packet instanceof ClientboundTakeItemEntityPacket
			|| packet instanceof ClientboundRemoveEntitiesPacket;
	}

	private static boolean showsEquipment(Disguise disguise) {
		return disguise.shadow instanceof LivingEntity && disguise.entity instanceof LivingEntity;
	}

	private static boolean allowed(Disguise disguise, ServerPlayer viewer) {
		if (disguise.viewerCondition == null) {
			return true;
		}
		return disguise.allowed.computeIfAbsent(viewer.getUUID(),
			uuid -> disguise.viewerCondition.test(new Tuple<>(viewer, disguise.entity)));
	}

	/** Re-tests viewer_condition for everyone who currently sees the entity, and resends it to anyone who changed. */
	private static void recheck(Disguise disguise) {
		OppTrackedEntity tracked = tracked(disguise.entity);
		if (tracked == null) {
			return;
		}
		Map<UUID, Boolean> before = new HashMap<>(disguise.allowed);
		disguise.allowed.clear();
		for (ServerPlayer viewer : tracked.opp$viewers()) {
			Boolean was = before.get(viewer.getUUID());
			boolean now = allowed(disguise, viewer);
			if (was != null && was != now) {
				tracked.opp$resend(viewer);
			}
		}
	}

	private static void resendToViewers(Entity entity) {
		OppTrackedEntity tracked = tracked(entity);
		if (tracked != null) {
			for (ServerPlayer viewer : tracked.opp$viewers()) {
				tracked.opp$resend(viewer);
			}
		}
	}

	private static OppTrackedEntity tracked(Entity entity) {
		if (!(entity.level() instanceof ServerLevel level)) {
			return null;
		}
		Object tracked = ((ChunkMapAccessor) level.getChunkSource().chunkMap).opp$getEntityMap().get(entity.getId());
		return tracked instanceof OppTrackedEntity t ? t : null;
	}
}
