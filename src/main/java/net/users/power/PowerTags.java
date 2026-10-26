package net.users.power;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.power.Power;
import io.github.apace100.apoli.power.PowerType;
import io.github.apace100.apoli.power.PowerTypeRegistry;
import io.github.apace100.calio.data.SerializableData;
import io.github.apace100.calio.data.SerializableDataType;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.users.OriginsPowerfulPowers;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Power tags. Each pack puts one file at data/&lt;namespace&gt;/opp_power_tags.json:
 *   { "mypack:movement": ["mypack:fly", "gravity-shifter:*"], "unsilenceable": ["mypack:heartbeat"] }
 * A tag lists power ids, and * matches any run of characters, so "gravity-shifter:*" is every power in that pack.
 * A tag name without a namespace gets the file's own namespace, so "unsilenceable" in data/mypack/ is mypack:unsilenceable;
 * write the full name to add to another pack's tag.
 * Files from every pack are merged, so several packs can add to the same tag.
 *
 * Tags are matched against power ids as text, so a tag can name powers from a pack that isn't installed (they just
 * match nothing) and no load order matters.
 */
public final class PowerTags {
	private static final String FILE = "opp_power_tags.json";
	/** tag -> the id patterns in it, in the order they were read. */
	private static final Map<String, List<Pattern>> TAGS = new LinkedHashMap<>();
	/** tag -> the patterns as written, for the command. */
	private static final Map<String, List<String>> SOURCES = new LinkedHashMap<>();

	private PowerTags() {
	}

	/**
	 * A tag name in a power's JSON. A name without a namespace gets the namespace of the pack the power came from
	 * (Apoli sets Calio's CURRENT_NAMESPACE while it reads power files), so "magic" written in mypack means
	 * mypack:magic. Write the full name to use another pack's tag. The normalized name is what travels to clients.
	 */
	public static final SerializableDataType<String> TAG_NAME = new SerializableDataType<>(
		String.class,
		(buf, tag) -> buf.writeUtf(tag),
		buf -> buf.readUtf(),
		json -> qualify(json.getAsString(), SerializableData.CURRENT_NAMESPACE)
	);

	/** name without a namespace becomes namespace:name; with one, or without a namespace to use, it's unchanged. */
	static String qualify(String name, String namespace) {
		String trimmed = name.trim();
		return trimmed.indexOf(':') >= 0 || namespace == null || namespace.isEmpty() ? trimmed : namespace + ":" + trimmed;
	}

	public static void register() {
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return OriginsPowerfulPowers.id("power_tags");
			}

			@Override
			public void onResourceManagerReload(ResourceManager manager) {
				load(manager);
			}
		});
	}

	static void load(ResourceManager manager) {
		TAGS.clear();
		SOURCES.clear();
		for (String namespace : manager.getNamespaces()) {
			ResourceLocation id = new ResourceLocation(namespace, FILE);
			for (Resource resource : manager.getResourceStack(id)) {
				try (BufferedReader reader = resource.openAsReader()) {
					read(id, JsonParser.parseReader(reader));
				} catch (IOException | RuntimeException e) {
					OriginsPowerfulPowers.LOGGER.error("[power tags] couldn't read {}: {}", id, e.getMessage());
				}
			}
		}
		if (!TAGS.isEmpty()) {
			OriginsPowerfulPowers.LOGGER.info("Origins: Powerful Powers: loaded {} power tag(s)", TAGS.size());
		}
	}

	private static void read(ResourceLocation file, JsonElement json) {
		if (!(json instanceof JsonObject object)) {
			throw new IllegalArgumentException("expected an object of \"tag\": [\"power\", ...] entries");
		}
		for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
			if (!(entry.getValue() instanceof JsonArray values)) {
				OriginsPowerfulPowers.LOGGER.error("[power tags] {}: the tag {} isn't a list of power ids", file, entry.getKey());
				continue;
			}
			String tag = qualify(entry.getKey(), file.getNamespace());
			List<Pattern> patterns = TAGS.computeIfAbsent(tag, name -> new ArrayList<>());
			List<String> sources = SOURCES.computeIfAbsent(tag, name -> new ArrayList<>());
			for (JsonElement value : values) {
				String written = value.getAsString().trim();
				patterns.add(toPattern(written));
				sources.add(written);
			}
		}
	}

	/** Like signals: * matches any run of characters, everything else is literal. */
	private static Pattern toPattern(String value) {
		List<String> quoted = new ArrayList<>();
		for (String part : value.split("\\*", -1)) {
			quoted.add(Pattern.quote(part));
		}
		return Pattern.compile(String.join(".*", quoted));
	}

	public static boolean exists(String tag) {
		return TAGS.containsKey(tag);
	}

	public static Collection<String> tags() {
		return TAGS.keySet();
	}

	public static List<String> entriesOf(String tag) {
		return SOURCES.getOrDefault(tag, List.of());
	}

	/** Whether this power id is in the tag. */
	public static boolean matches(String tag, ResourceLocation powerId) {
		String id = powerId.toString();
		for (Pattern pattern : TAGS.getOrDefault(tag, List.of())) {
			if (pattern.matcher(id).matches()) {
				return true;
			}
		}
		return false;
	}

	/** Every registered power the tag covers, for the command. */
	public static List<String> powersIn(String tag) {
		List<String> powers = new ArrayList<>();
		PowerTypeRegistry.identifiers().sorted(java.util.Comparator.comparing(ResourceLocation::toString)).forEach(id -> {
			if (matches(tag, id)) {
				powers.add(id.toString());
			}
		});
		return powers;
	}

	/** Every tag on one power, for the command. */
	public static Collection<String> tagsOf(ResourceLocation powerId) {
		Collection<String> found = new TreeSet<>();
		for (String tag : TAGS.keySet()) {
			if (matches(tag, powerId)) {
				found.add(tag);
			}
		}
		return found;
	}

	/** Whether the entity holds a power with this tag. Hidden powers count; inactive ones only with includeInactive. */
	public static boolean has(Entity entity, String tag, boolean includeInactive) {
		return PowerHolderComponent.KEY.maybeGet(entity).map(component -> {
			for (Power power : component.getPowers()) {
				PowerType<?> type = power.getType();
				if ((includeInactive || power.isActive()) && matches(tag, type.getIdentifier())) {
					return true;
				}
			}
			return false;
		}).orElse(false);
	}
}
