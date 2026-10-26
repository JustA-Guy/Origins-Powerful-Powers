package net.users.variables;

import io.github.apace100.apoli.power.InventoryPower;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.PowerTypeRegistry;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.users.power.OppEntityConditions;
import net.users.power.OppItemCooldowns;
import net.users.power.VanillaSlot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Variables and lists of real entities: self (the power holder, or the actor in bientity code) and
 * other (the bientity target). Remembers what changed so flush() can sync it to clients once per run.
 */
final class EntityContext extends Context {
	/** A variable is either in a variables power (power) or, from a global_variables power, in the global store (globalKey). */
	private record Slot(Entity entity, VariablesPower power, String globalKey, VarType globalType) {
	}

	private final Entity self;
	private final Entity other;
	private final Map<String, Slot> slots = new HashMap<>();
	/** Per run: the list's source (for syncing) and the data code sees, so a computed list is one snapshot per run. */
	private final Map<String, ListSource> listSources = new HashMap<>();
	private final Map<String, ListData> listData = new HashMap<>();
	private final Map<Entity, Set<VariablesPower>> changedPowers = new LinkedHashMap<>();
	private final Map<Entity, Set<String>> changedNames = new HashMap<>();
	/** Global lists code touched this run, so flush() can save the ones that changed. */
	private final Set<ListData> globalLists = new LinkedHashSet<>();

	EntityContext(Entity self, Entity other) {
		this.self = self;
		this.other = other;
	}

	private Entity entity(Node.Scope scope) {
		return scope == Node.Scope.SELF ? self : other;
	}

	private static String describe(Node.Scope scope) {
		return scope == Node.Scope.SELF ? "this entity" : "the other entity";
	}

	private Slot slot(Node.Scope scope, String name) {
		String key = scope + ":" + name;
		Slot slot = slots.get(key);
		if (slot == null) {
			Entity entity = entity(scope);
			VariablesPower power = entity == null ? null : VariableStore.find(entity, name);
			GlobalVariablesPower global = entity == null ? null : VariableStore.findGlobal(entity, name);
			boolean isList = entity != null && VariableStore.findList(entity, name) != null;
			if (power != null && global != null) {
				throw new ScriptException(name + " is declared by both a variables and a global_variables power on " + describe(scope));
			}
			if ((power != null || global != null) && isList) {
				throw new ScriptException(name + " is declared as both a variable and a list on " + describe(scope));
			}
			if (isList) {
				throw new ScriptException(name + " is a list; use " + name + ".get(i), " + name + ".size() and the other list methods, or loop over it with for_each");
			}
			if (global != null) {
				slot = new Slot(entity, null, global.key(GlobalStore.of(entity), name), global.typeOf(name));
				slots.put(key, slot);
				return slot;
			}
			if (power == null) {
				throw new ScriptException("cannot find variable " + name + " on " + describe(scope)
					+ " (no origins-powerful-powers:variables, global_variables or list power it holds declares it)");
			}
			slot = new Slot(entity, power, null, null);
			slots.put(key, slot);
		}
		return slot;
	}

	@Override
	ListData list(Node.Scope scope, String name) {
		String key = scope + ":" + name;
		ListData data = listData.get(key);
		if (data == null) {
			Entity entity = entity(scope);
			ListSource source = entity == null ? null : VariableStore.findList(entity, name);
			if (source == null) {
				return null;
			}
			if (VariableStore.find(entity, name) != null) {
				throw new ScriptException(name + " is declared as both a variable and a list on " + describe(scope));
			}
			data = source.data();
			listSources.put(key, source);
			listData.put(key, data);
		}
		return data;
	}

	@Override
	Object read(Node.Scope scope, String name) {
		Slot slot = slot(scope, name);
		return slot.globalKey() != null ? GlobalStore.of(slot.entity()).get(slot.globalKey()) : slot.power().get(name);
	}

	@Override
	VarType typeOf(Node.Scope scope, String name) {
		Slot slot = slot(scope, name);
		return slot.globalKey() != null ? slot.globalType() : slot.power().typeOf(name);
	}

	@Override
	void write(Node.Scope scope, String name, Object value) {
		Slot slot = slot(scope, name);
		if (slot.globalKey() != null) {
			GlobalStore store = GlobalStore.of(slot.entity());
			if (!value.equals(store.get(slot.globalKey()))) {
				store.set(slot.globalKey(), value, self);
			}
			return;
		}
		if (value.equals(slot.power().get(name))) {
			return;
		}
		slot.power().set(name, value);
		changedPowers.computeIfAbsent(slot.entity(), entity -> new LinkedHashSet<>()).add(slot.power());
		changedNames.computeIfAbsent(slot.entity(), entity -> new HashSet<>()).add(name);
	}

	/** Scores live on the world's scoreboard; with no holder, this entity's own score (its scoreboard name). */
	@Override
	int score(String holder, String objectiveName) {
		Scoreboard board = self.level().getScoreboard();
		Objective objective = board.getObjective(objectiveName);
		if (objective == null) {
			throw new ScriptException("unknown scoreboard objective " + objectiveName
				+ (self.level().isClientSide() ? " (the client may only know objectives shown in a display slot)" : ""));
		}
		String name = holder == null ? self.getScoreboardName() : holder;
		if (!board.hasPlayerScore(name, objective)) {
			throw new ScriptException(name + " has no score in " + objectiveName + "; check with Scoreboard.has first");
		}
		return board.getOrCreatePlayerScore(name, objective).getScore();
	}

	@Override
	boolean hasScore(String holder, String objectiveName) {
		Scoreboard board = self.level().getScoreboard();
		Objective objective = board.getObjective(objectiveName);
		return objective != null && board.hasPlayerScore(holder == null ? self.getScoreboardName() : holder, objective);
	}

	private Entity entityOrFail(Node.Scope scope) {
		Entity entity = entity(scope);
		if (entity == null) {
			throw new ScriptException("there is no other entity here to read items from");
		}
		return entity;
	}

	/** The stack in one slot of an origins:inventory power. Errors name the power, as the command does. */
	private ItemStack stack(Node.Scope scope, String powerId, int slot) {
		Entity target = entityOrFail(scope);
		ResourceLocation id = ResourceLocation.tryParse(powerId);
		if (id == null) {
			throw new ScriptException("'" + powerId + "' isn't a power id; write it as namespace:path, like mypack:satchel");
		}
		if (!PowerTypeRegistry.contains(id)) {
			throw new ScriptException("unknown power " + powerId);
		}
		// PowerType#get returns null for entities that don't have the power, and for non-living entities.
		Power power = PowerTypeRegistry.get(id).get(target);
		if (power == null) {
			throw new ScriptException(describe(scope) + " doesn't have the power " + powerId);
		}
		if (!(power instanceof InventoryPower inventory)) {
			throw new ScriptException(powerId + " isn't an origins:inventory power");
		}
		if (slot < 0 || slot >= inventory.getContainerSize()) {
			throw new ScriptException("slot " + slot + " is outside " + powerId + ", which has slots 0 to " + (inventory.getContainerSize() - 1));
		}
		return inventory.getItem(slot);
	}

	/** A vanilla slot by the names /item uses (see VanillaSlot). */
	private ItemStack vanillaStack(Node.Scope scope, String slotName) {
		Entity target = entityOrFail(scope);
		VanillaSlot slot;
		try {
			slot = VanillaSlot.parse(slotName);
		} catch (IllegalArgumentException e) {
			throw new ScriptException(e.getMessage());
		}
		ItemStack stack = slot.stack(target);
		if (stack == null) {
			throw new ScriptException(slot.equipment() != null ? describe(scope) + " has no " + slot.name() + " slot"
				: "only players have " + slot.group() + " slots");
		}
		return stack;
	}

	/**
	 * Item.cooldown: the vanilla cooldown ticks left on an item, named by id (minecraft:ender_pearl) or by
	 * the slot holding it (weapon.mainhand). A name that parses as a slot is a slot; anything else is an item id.
	 */
	@Override
	int itemCooldown(Node.Scope scope, String itemOrSlot) {
		Entity target = entityOrFail(scope);
		Item item;
		VanillaSlot slot = null;
		try {
			slot = VanillaSlot.parse(itemOrSlot);
		} catch (IllegalArgumentException notASlot) {
			// fall through to an item id
		}
		if (slot != null) {
			item = vanillaStack(scope, itemOrSlot).getItem();
		} else {
			ResourceLocation id = ResourceLocation.tryParse(itemOrSlot.trim());
			if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
				throw new ScriptException("'" + itemOrSlot + "' is neither an item id (like minecraft:ender_pearl) nor a slot "
					+ "(weapon.mainhand, weapon.offhand, armor.head, armor.chest, armor.legs, armor.feet, hotbar.0-8, inventory.0-26 or enderchest.0-26)");
			}
			item = BuiltInRegistries.ITEM.get(id);
		}
		return target instanceof Player player ? OppItemCooldowns.ticksRemaining(player, item) : 0;
	}

	/**
	 * Death.*: the player's last death location, the one the recovery compass points to. Non-players never have
	 * one. x/y/z/dimension throw without one; distance is -1 without one or when it's in another dimension.
	 */
	@Override
	Object lastDeath(Node.Scope scope, String method) {
		Entity target = entity(scope);
		if (target == null) {
			throw new ScriptException("there is no other entity here to read a death location from");
		}
		Optional<GlobalPos> death = target instanceof Player player ? player.getLastDeathLocation() : Optional.empty();
		return switch (method) {
			case "has" -> Boolean.valueOf(death.isPresent());
			case "distance" -> Double.valueOf(target instanceof Player player ? OppEntityConditions.lastDeathDistance(player) : -1.0);
			default -> {
				GlobalPos pos = death.orElseThrow(() -> new ScriptException(describe(scope)
					+ " has no death location; check with Death.has() first"));
				yield switch (method) {
					case "x" -> Integer.valueOf(pos.pos().getX());
					case "y" -> Integer.valueOf(pos.pos().getY());
					case "z" -> Integer.valueOf(pos.pos().getZ());
					default -> pos.dimension().location().toString();
				};
			}
		};
	}

	@Override
	Object global(String method, Object[] args, Node.Scope bindScope) {
		GlobalStore store = GlobalStore.of(self);
		String key = (String) args[0];
		return switch (method) {
			case "has" -> Boolean.valueOf(store.has(key));
			case "get" -> store.get(key);
			case "set" -> store.set(key, args[1], self);
			case "delete" -> Boolean.valueOf(store.delete(key, self));
			case "bind" -> {
				Entity target = entity(bindScope);
				if (target == null) {
					throw new ScriptException("there is no other entity here to bind global " + key + " to");
				}
				store.bind(key, target, self);
				yield null;
			}
			case "lock" -> {
				store.lock(key, (String) args[1], self);
				yield null;
			}
			case "unlock" -> {
				store.unlock(key, self);
				yield null;
			}
			default -> {
				VarType elementType = null;
				if (args.length > 1) {
					elementType = VarType.byKeyword((String) args[1]);
					if (elementType == null) {
						throw new ScriptException("'" + args[1] + "' isn't an element type; use int, long, float, double, boolean or String");
					}
				}
				ListData list = store.list(key, elementType, self);
				globalLists.add(list);
				yield list;
			}
		};
	}

	@Override
	Object globalAssign(String key, String op, Object value) {
		GlobalStore store = GlobalStore.of(self);
		if (op == null) {
			return store.set(key, value, self);
		}
		VarType type = store.typeOf(key);
		if (type == null) {
			throw new ScriptException(store.has(key) ? "global " + GlobalStore.normalize(key) + " is a list" : "there's no global " + GlobalStore.normalize(key) + " to change; use the set operation first");
		}
		return store.set(key, Ops.compound(op, store.get(key), value, type, "global " + GlobalStore.normalize(key)), self);
	}

	@Override
	void checkGlobalListWrite(ListData list) {
		GlobalStore.of(self).checkListWritable(list, self);
	}

	/**
	 * Stat.get: stats only exist on the server, so the client and non-players read 0. The name is a scoreboard
	 * criterion (minecraft.mined:minecraft.stone); a name that isn't a statistic is an error.
	 */
	@Override
	int stat(Node.Scope scope, String name) {
		Entity target = entity(scope);
		if (target == null) {
			throw new ScriptException("there is no other entity here to read a statistic from");
		}
		Stat<?> stat = ObjectiveCriteria.byName(name.trim()).orElse(null) instanceof Stat<?> found ? found : null;
		if (stat == null) {
			throw new ScriptException("'" + name + "' isn't a statistic; name it like a scoreboard criterion, "
				+ "such as minecraft.custom:minecraft.jump or minecraft.mined:minecraft.stone");
		}
		return target instanceof ServerPlayer player ? player.getStats().getValue(stat) : 0;
	}

	/** An empty slot reads as minecraft:air, the item vanilla keeps there. */
	@Override
	String itemId(Node.Scope scope, String powerId, int slot) {
		return BuiltInRegistries.ITEM.getKey(stack(scope, powerId, slot).getItem()).toString();
	}

	@Override
	int itemCount(Node.Scope scope, String powerId, int slot) {
		return stack(scope, powerId, slot).getCount();
	}

	@Override
	String slotItemId(Node.Scope scope, String slot) {
		return BuiltInRegistries.ITEM.getKey(vanillaStack(scope, slot).getItem()).toString();
	}

	@Override
	int slotItemCount(Node.Scope scope, String slot) {
		return vanillaStack(scope, slot).getCount();
	}

	void flush() {
		if (!globalLists.isEmpty()) {
			GlobalStore store = GlobalStore.of(self);
			for (ListData list : globalLists) {
				if (list.takeDirty()) {
					store.listChanged(list);
				}
			}
		}
		changedPowers.forEach((entity, powers) -> VariableStore.sync(entity, powers, changedNames.get(entity)));
		for (ListSource list : listSources.values()) {
			list.syncIfDirty();
		}
	}
}
