package net.users.tracking;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timestamps each rise in a player's food level, checked every tick. FoodData#eat doesn't know its player, so there's
 * no eating hook; any rise counts as a meal, whatever caused it.
 */
public final class MealTracker {
	private static final Map<UUID, Integer> LAST_FOOD_LEVEL = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_MEAL_TIME = new ConcurrentHashMap<>();

	private MealTracker() {
	}

	public static void tick(Player player, long gameTime) {
		UUID id = player.getUUID();
		int foodLevel = player.getFoodData().getFoodLevel();
		int previous = LAST_FOOD_LEVEL.getOrDefault(id, foodLevel);
		if (foodLevel > previous) {
			LAST_MEAL_TIME.put(id, gameTime);
		}
		LAST_FOOD_LEVEL.put(id, foodLevel);
	}

	public static long ticksSinceLastMeal(Player player, long gameTime) {
		Long lastMeal = LAST_MEAL_TIME.get(player.getUUID());
		return lastMeal == null ? Long.MAX_VALUE : gameTime - lastMeal;
	}

	/** Drops everything kept for this entity (TrackerCleanup). */
	public static void forget(UUID id) {
		LAST_FOOD_LEVEL.remove(id);
		LAST_MEAL_TIME.remove(id);
	}
}
