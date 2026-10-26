package net.users.variables;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.power.Power;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.users.OriginsPowerfulPowers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The global store: typed values and lists addressed by a namespaced key, saved with the world in
 * data/origins-powerful-powers_global.dat. Server only.
 *
 * - A key's type is fixed when it's created (first set, a global_variables declaration, or Global.list with a type).
 * - A locked key has an owner, a namespace (mypack) or a power id (mypack:bond). Only an entity holding a power in
 *   that namespace, or that exact power, can change it; reading is open. Commands (writer null) can always write.
 * - A key bound to entities is deleted once every bound entity is gone: non-players when killed or discarded, players
 *   when PlayerExpiry finds them offline past oppPlayerExpiryDays.
 */
public final class GlobalStore extends SavedData {
	private static final String DATA_NAME = "origins-powerful-powers_global";
	private static final int LIST_MAX_SIZE = 1024;

	public static final class Entry {
		final String key;
		final VarType type;
		/** Non-null for a list; then value is unused. */
		final ListData list;
		Object value;
		long lastWritten;
		String owner;
		final Set<UUID> bound = new LinkedHashSet<>();

		Entry(String key, VarType type, ListData list, Object value) {
			this.key = key;
			this.type = type;
			this.list = list;
			this.value = value;
		}

		public String key() {
			return key;
		}

		public boolean isList() {
			return list != null;
		}

		public VarType type() {
			return type;
		}

		public String owner() {
			return owner;
		}

		public long lastWritten() {
			return lastWritten;
		}

		public Set<UUID> bound() {
			return bound;
		}

		/** The value as text for commands: the scalar, or [a, b, c]. */
		public String describe() {
			if (list == null) {
				return type == VarType.STRING ? "\"" + value + "\"" : String.valueOf(value);
			}
			return list.values().toString();
		}

		public Object value() {
			return value;
		}
	}

	private final Map<String, Entry> entries = new TreeMap<>();
	private final Map<UUID, Set<String>> byEntity = new HashMap<>();
	private MinecraftServer server;

	private GlobalStore() {
	}

	public static GlobalStore get(MinecraftServer server) {
		GlobalStore store = server.overworld().getDataStorage().computeIfAbsent(GlobalStore::load, GlobalStore::new, DATA_NAME);
		store.server = server;
		return store;
	}

	/** The store for code running on this entity; throws on the client, where globals don't exist. */
	static GlobalStore of(Entity entity) {
		MinecraftServer server = entity.getServer();
		if (server == null || entity.level().isClientSide()) {
			throw new ScriptException("global values only exist on the server, so they can't be read here (for example in a hud_render condition)");
		}
		return get(server);
	}

	/** "bond/a/b" becomes origins-powerful-powers:bond/a/b; a key must be a valid namespaced id. */
	public static String normalize(String key) {
		String trimmed = key.trim();
		String full = trimmed.indexOf(':') < 0 ? OriginsPowerfulPowers.MOD_ID + ":" + trimmed : trimmed;
		ResourceLocation id = ResourceLocation.tryParse(full);
		if (id == null || trimmed.isEmpty()) {
			throw new ScriptException("'" + key + "' isn't a valid global key; use lowercase letters, digits and _ - . / like mypack:bond/alice");
		}
		return id.toString();
	}

	/** A value typed in a command: a Java literal or constant expression, like 5, 2.5f, true or "text". */
	public static Object literal(String source) {
		return Parser.valueExpression(source, false).eval(new Context.Initializers(Map.of()));
	}

	/** Entry lookup for commands; null when missing. Lists go through list(). */
	public ListData listForCommand(String key) {
		Entry entry = entries.get(normalize(key));
		return entry == null ? null : entry.list;
	}

	public Collection<Entry> entries() {
		return entries.values();
	}

	public Entry entry(String key) {
		return entries.get(normalize(key));
	}

	boolean has(String key) {
		return entries.containsKey(normalize(key));
	}

	Object get(String key) {
		Entry entry = existing(key);
		if (entry.isList()) {
			throw new ScriptException("global " + entry.key + " is a list; use Global.list(\"" + entry.key + "\").get(i) and the other list methods");
		}
		return entry.value;
	}

	VarType typeOf(String key) {
		Entry entry = entries.get(normalize(key));
		return entry == null || entry.isList() ? null : entry.type;
	}

	/** Sets a scalar. A new key takes the value's type; an existing one converts it with Java's assignment rules. */
	public Object set(String key, Object value, Entity writer) {
		String id = normalize(key);
		Entry entry = entries.get(id);
		if (entry == null) {
			entry = new Entry(id, VarType.of(value), null, value);
			entries.put(id, entry);
		} else {
			checkWritable(entry, writer);
			if (entry.isList()) {
				throw new ScriptException("global " + id + " is a list, so it can't be set to a single value");
			}
			entry.value = Ops.assign(value, entry.type, "global " + id);
		}
		touch(entry);
		return entry.value;
	}

	/** Creates a declared key (global_variables) if it's missing. owner, if not null, locks a newly created key. */
	void declare(String key, VarType type, Object initial, String owner) {
		String id = normalize(key);
		Entry entry = entries.get(id);
		if (entry == null) {
			entry = new Entry(id, type, null, initial);
			entry.owner = owner;
			entries.put(id, entry);
			touch(entry);
		} else if (entry.isList() || entry.type != type) {
			throw new ScriptException("global " + id + " already exists as " + describeType(entry) + ", but a global_variables power declares it as " + type.keyword());
		}
	}

	public boolean delete(String key, Entity writer) {
		String id = normalize(key);
		Entry entry = entries.get(id);
		if (entry == null) {
			return false;
		}
		checkWritable(entry, writer);
		remove(entry);
		return true;
	}

	/** The list at key. With an element type, a missing key is created as an empty list of that type. */
	ListData list(String key, VarType elementType, Entity writer) {
		String id = normalize(key);
		Entry entry = entries.get(id);
		if (entry == null) {
			if (elementType == null) {
				throw new ScriptException("there's no global list " + id + "; create it with Global.list(\"" + id + "\", \"int\") (or another element type)");
			}
			entry = new Entry(id, elementType, new ListData(id, elementType, LIST_MAX_SIZE, false, List.of()), null);
			entries.put(id, entry);
			touch(entry);
		}
		if (!entry.isList()) {
			throw new ScriptException("global " + id + " is a single " + entry.type.keyword() + ", not a list");
		}
		if (elementType != null && entry.type != elementType) {
			throw new ScriptException("global list " + id + " holds " + entry.type.keyword() + ", not " + elementType.keyword());
		}
		return entry.list;
	}

	/** Called after code changed a global list, so the change is saved. */
	void listChanged(ListData list) {
		Entry entry = entries.get(list.name());
		if (entry != null && entry.list == list) {
			touch(entry);
		}
	}

	/** Changing a locked list's contents needs the same permission as setting it. */
	void checkListWritable(ListData list, Entity writer) {
		Entry entry = entries.get(list.name());
		if (entry != null) {
			checkWritable(entry, writer);
		}
	}

	void bind(String key, Entity entity, Entity writer) {
		Entry entry = existing(key);
		checkWritable(entry, writer);
		if (entry.bound.add(entity.getUUID())) {
			byEntity.computeIfAbsent(entity.getUUID(), id -> new LinkedHashSet<>()).add(entry.key);
			setDirty();
		}
	}

	/** Locks a key to owner (a namespace or power id). The writer must be allowed to change the key and must hold that owner. */
	public void lock(String key, String owner, Entity writer) {
		Entry entry = existing(key);
		checkWritable(entry, writer);
		String cleaned = owner.trim();
		if (cleaned.isEmpty() || ResourceLocation.tryParse(cleaned.indexOf(':') >= 0 ? cleaned : cleaned + ":x") == null) {
			throw new ScriptException("'" + owner + "' isn't a namespace (mypack) or a power id (mypack:bond)");
		}
		if (writer != null && !holds(writer, cleaned)) {
			throw new ScriptException("can't lock global " + entry.key + " to " + cleaned + ": this entity holds no power " + (cleaned.indexOf(':') >= 0 ? cleaned : "in " + cleaned));
		}
		entry.owner = cleaned;
		setDirty();
	}

	public void unlock(String key, Entity writer) {
		Entry entry = existing(key);
		checkWritable(entry, writer);
		entry.owner = null;
		setDirty();
	}

	/** An entity is gone for good: unbind it everywhere, and delete keys that have no bound entities left. */
	public void entityGone(UUID id) {
		Set<String> keys = byEntity.remove(id);
		if (keys == null) {
			return;
		}
		for (String key : keys) {
			Entry entry = entries.get(key);
			if (entry != null && entry.bound.remove(id) && entry.bound.isEmpty()) {
				remove(entry);
			}
		}
		setDirty();
	}

	/** Deletes keys starting with prefix (null for any) and not written for at least olderThan ticks (0 for any age). */
	public int prune(String prefix, long olderThan) {
		long now = gameTime();
		String normalizedPrefix = prefix == null ? null : prefix.indexOf(':') < 0 ? OriginsPowerfulPowers.MOD_ID + ":" + prefix : prefix;
		List<Entry> doomed = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if ((normalizedPrefix == null || entry.key.startsWith(normalizedPrefix)) && now - entry.lastWritten >= olderThan) {
				doomed.add(entry);
			}
		}
		doomed.forEach(this::remove);
		return doomed.size();
	}

	public long gameTime() {
		return server == null ? 0 : server.overworld().getGameTime();
	}

	private Entry existing(String key) {
		String id = normalize(key);
		Entry entry = entries.get(id);
		if (entry == null) {
			throw new ScriptException("there's no global " + id + "; check with Global.has first");
		}
		return entry;
	}

	private void checkWritable(Entry entry, Entity writer) {
		if (entry.owner != null && writer != null && !holds(writer, entry.owner)) {
			throw new ScriptException("global " + entry.key + " is locked to " + entry.owner + "; only an entity with "
				+ (entry.owner.indexOf(':') >= 0 ? "the power " + entry.owner : "a power in " + entry.owner) + " can change it");
		}
	}

	private static boolean holds(Entity entity, String owner) {
		boolean powerId = owner.indexOf(':') >= 0;
		return PowerHolderComponent.KEY.maybeGet(entity).map(component -> {
			for (Power power : component.getPowers()) {
				ResourceLocation id = power.getType().getIdentifier();
				if (powerId ? id.toString().equals(owner) : id.getNamespace().equals(owner)) {
					return true;
				}
			}
			return false;
		}).orElse(false);
	}

	private void touch(Entry entry) {
		entry.lastWritten = gameTime();
		setDirty();
	}

	private void remove(Entry entry) {
		entries.remove(entry.key);
		for (UUID id : entry.bound) {
			Set<String> keys = byEntity.get(id);
			if (keys != null) {
				keys.remove(entry.key);
				if (keys.isEmpty()) {
					byEntity.remove(id);
				}
			}
		}
		setDirty();
	}

	private static String describeType(Entry entry) {
		return entry.isList() ? "a list of " + entry.type.keyword() : entry.type.keyword();
	}

	private static GlobalStore load(CompoundTag tag) {
		GlobalStore store = new GlobalStore();
		CompoundTag saved = tag.getCompound("entries");
		for (String key : saved.getAllKeys()) {
			CompoundTag data = saved.getCompound(key);
			VarType type = VarType.byKeyword(data.getString("type"));
			if (type == null) {
				continue;
			}
			Entry entry;
			if (data.getBoolean("list")) {
				List<Object> values = new ArrayList<>();
				if (data.get("values") instanceof ListTag savedValues) {
					for (Tag element : savedValues) {
						Object value = VariablesPower.fromTag(element, type);
						if (value != null) {
							values.add(value);
						}
					}
				}
				ListData list = new ListData(key, type, LIST_MAX_SIZE, false, List.of());
				list.load(values);
				entry = new Entry(key, type, list, null);
			} else {
				Object value = VariablesPower.fromTag(data.get("value"), type);
				entry = new Entry(key, type, null, value == null ? type.defaultValue() : value);
			}
			entry.lastWritten = data.getLong("written");
			entry.owner = data.contains("owner") ? data.getString("owner") : null;
			for (Tag bound : data.getList("bound", Tag.TAG_STRING)) {
				try {
					UUID id = UUID.fromString(bound.getAsString());
					entry.bound.add(id);
					store.byEntity.computeIfAbsent(id, uuid -> new LinkedHashSet<>()).add(key);
				} catch (IllegalArgumentException ignored) {
					// not a UUID; skip it
				}
			}
			store.entries.put(key, entry);
		}
		return store;
	}

	@Override
	public CompoundTag save(CompoundTag tag) {
		CompoundTag saved = new CompoundTag();
		for (Entry entry : entries.values()) {
			CompoundTag data = new CompoundTag();
			data.putString("type", entry.type.keyword());
			data.putBoolean("list", entry.isList());
			if (entry.isList()) {
				ListTag values = new ListTag();
				for (Object value : entry.list.values()) {
					values.add(VariablesPower.toTag(entry.type, value));
				}
				data.put("values", values);
			} else {
				data.put("value", VariablesPower.toTag(entry.type, entry.value));
			}
			data.putLong("written", entry.lastWritten);
			if (entry.owner != null) {
				data.putString("owner", entry.owner);
			}
			ListTag bound = new ListTag();
			for (UUID id : entry.bound) {
				bound.add(StringTag.valueOf(id.toString()));
			}
			data.put("bound", bound);
			saved.put(entry.key, data);
		}
		tag.put("entries", saved);
		return tag;
	}
}
