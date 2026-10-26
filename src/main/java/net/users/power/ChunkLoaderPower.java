package net.users.power;

import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.factory.PowerFactory;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataTypes;
import net.minecraft.core.Registry;
import net.minecraft.world.entity.LivingEntity;
import net.users.OriginsPowerfulPowers;
import net.users.tracking.ChunkLoadTracker;

/**
 * origins-powerful-powers:chunk_loader: while active, the chunks within "radius" of the holder stay loaded like
 * /forceload, following them. Going inactive (condition false), revoking the power (onLost) and death release them.
 * Logging out keeps them: onLost only runs on a revoke, and the tracker keeps an offline player's last chunks.
 */
public class ChunkLoaderPower extends Power {
	private final int radius;

	public ChunkLoaderPower(PowerType<?> type, LivingEntity entity, SerializableData.Instance data) {
		super(type, entity);
		this.radius = Math.max(0, data.getInt("radius"));
	}

	public static void register() {
		PowerFactory<ChunkLoaderPower> factory = new PowerFactory<ChunkLoaderPower>(
			OriginsPowerfulPowers.id("chunk_loader"),
			new SerializableData().add("radius", SerializableDataTypes.INT, 1),
			data -> (type, entity) -> new ChunkLoaderPower(type, entity, data)
		).allowCondition();
		Registry.register(io.github.apace100.apoli.registry.ApoliRegistries.POWER_FACTORY, factory.getSerializerId(), factory);
	}

	@Override
	public boolean shouldTick() {
		return true;
	}

	@Override
	public boolean shouldTickWhenInactive() {
		return true;
	}

	@Override
	public void tick() {
		if (entity.level().isClientSide) {
			return;
		}
		// A dead player still ticks on the death screen; starting again then would undo the release at death.
		if (isActive() && entity.isAlive()) {
			ChunkLoadTracker.start(entity, source(), radius, 0);
		} else {
			ChunkLoadTracker.stop(entity.getUUID(), source());
		}
	}

	@Override
	public void onLost() {
		if (!entity.level().isClientSide) {
			ChunkLoadTracker.stop(entity.getUUID(), source());
		}
	}

	private String source() {
		return "power " + type.getIdentifier();
	}
}
