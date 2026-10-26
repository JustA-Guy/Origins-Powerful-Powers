package net.users.variables;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON of an Origins action or condition whose string values contain ${expression} placeholders,
 * for origins-powerful-powers:with_variables. The placeholders are parsed when the pack loads; fill()
 * evaluates them for one entity and returns plain JSON for Apoli to read.
 *
 *   "${speed}"          the whole string is one placeholder: becomes a JSON number/boolean/string,
 *                       so it can fill numeric and boolean fields
 *   "say ${coins} gold" inside text: Java's string conversion ("" + value)
 *   "$${"               a literal "${"
 */
public final class Template {
	private record Placeholder(String source, Node expression) {
	}

	private static final int CACHE_SIZE = 64;
	/** fillText's parsed commands, since a function can run the same one every tick. */
	private static final Map<String, List<Object>> TEXT = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<Object>> eldest) {
			return size() > 256;
		}
	};

	private final JsonElement json;
	/** Each distinct string value that contains placeholders, split into literal Strings and Placeholders. */
	private final Map<String, List<Object>> parts = new HashMap<>();
	/** Filled-in JSON -> what Apoli made of it, so repeated values don't re-parse the action every run. */
	private final Map<String, Object> loaded = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
			return size() > CACHE_SIZE;
		}
	};

	private Template(JsonElement json) {
		this.json = json;
	}

	public static Template parse(JsonElement json, boolean bientity) {
		if (!json.isJsonObject()) {
			throw new ScriptException("expected an action or condition object, found " + json);
		}
		Template template = new Template(json);
		template.collect(json, bientity);
		return template;
	}

	public String source() {
		return json.toString();
	}

	/** Throws ScriptException when a placeholder can't be evaluated (unknown variable, void value, ...). */
	public JsonElement fill(Context context) {
		return fill(json, context);
	}

	/** The cached result of loading this filled-in JSON, if any. */
	Object cached(String filledJson) {
		synchronized (loaded) {
			return loaded.get(filledJson);
		}
	}

	void cache(String filledJson, Object result) {
		synchronized (loaded) {
			loaded.put(filledJson, result);
		}
	}

	private void collect(JsonElement element, boolean bientity) {
		if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				collect(entry.getValue(), bientity);
			}
		} else if (element.isJsonArray()) {
			for (JsonElement child : element.getAsJsonArray()) {
				collect(child, bientity);
			}
		} else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String text = element.getAsString();
			if (text.contains("${") && !parts.containsKey(text)) {
				parts.put(text, split(text, bientity));
			}
		}
	}

	private static List<Object> split(String text, boolean bientity) {
		List<Object> result = new ArrayList<>();
		StringBuilder literal = new StringBuilder();
		int i = 0;
		while (i < text.length()) {
			if (text.startsWith("$${", i)) {
				literal.append("${");
				i += 3;
				continue;
			}
			if (!text.startsWith("${", i)) {
				literal.append(text.charAt(i++));
				continue;
			}
			int end = closingBrace(text, i + 2);
			if (end < 0) {
				throw new ScriptException("unclosed ${ in \"" + text + "\"");
			}
			String source = text.substring(i + 2, end);
			Node expression;
			try {
				expression = Parser.valueExpression(source, bientity);
			} catch (ScriptException e) {
				throw new ScriptException("in ${" + source + "}: " + e.getMessage());
			}
			if (literal.length() > 0) {
				result.add(literal.toString());
				literal.setLength(0);
			}
			result.add(new Placeholder(source, expression));
			i = end + 1;
		}
		if (literal.length() > 0) {
			result.add(literal.toString());
		}
		return result;
	}

	/** The } that ends a placeholder, skipping any inside "string literals". */
	private static int closingBrace(String text, int from) {
		boolean inString = false;
		for (int i = from; i < text.length(); i++) {
			char c = text.charAt(i);
			if (inString && c == '\\') {
				i++;
			} else if (c == '"') {
				inString = !inString;
			} else if (c == '}' && !inString) {
				return i;
			}
		}
		return -1;
	}

	private JsonElement fill(JsonElement element, Context context) {
		if (element.isJsonObject()) {
			JsonObject filled = new JsonObject();
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				filled.add(entry.getKey(), fill(entry.getValue(), context));
			}
			return filled;
		}
		if (element.isJsonArray()) {
			JsonArray filled = new JsonArray();
			for (JsonElement child : element.getAsJsonArray()) {
				filled.add(fill(child, context));
			}
			return filled;
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return element;
		}
		List<Object> pieces = parts.get(element.getAsString());
		if (pieces == null) {
			return element;
		}
		if (pieces.size() == 1 && pieces.get(0) instanceof Placeholder whole) {
			return toJson(evaluate(whole, context));
		}
		return new JsonPrimitive(join(pieces, context));
	}

	/**
	 * Plain text with placeholders, like a command for /opp with. Always text, so every placeholder uses
	 * Java string conversion. Throws ScriptException for a bad placeholder, when parsing or evaluating.
	 */
	static String fillText(String text, Context context) {
		if (!text.contains("${")) {
			return text;
		}
		List<Object> pieces;
		synchronized (TEXT) {
			pieces = TEXT.get(text);
		}
		if (pieces == null) {
			pieces = split(text, false);
			synchronized (TEXT) {
				TEXT.put(text, pieces);
			}
		}
		return join(pieces, context);
	}

	private static String join(List<Object> pieces, Context context) {
		StringBuilder text = new StringBuilder();
		for (Object piece : pieces) {
			text.append(piece instanceof Placeholder placeholder ? String.valueOf(evaluate(placeholder, context)) : piece);
		}
		return text.toString();
	}

	private static Object evaluate(Placeholder placeholder, Context context) {
		try {
			Object value = placeholder.expression().eval(context);
			VarType.of(value);
			return value;
		} catch (ScriptException e) {
			throw new ScriptException("in ${" + placeholder.source() + "}: " + e.getMessage());
		}
	}

	private static JsonElement toJson(Object value) {
		if (value instanceof Number number) {
			return new JsonPrimitive(number);
		}
		if (value instanceof Boolean bool) {
			return new JsonPrimitive(bool);
		}
		return new JsonPrimitive((String) value);
	}
}
