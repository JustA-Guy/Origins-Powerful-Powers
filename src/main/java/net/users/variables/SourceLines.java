package net.users.variables;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

/**
 * "fields", "code" and "expression" take one string or an array of strings. An array is joined with
 * line breaks, so each element is a line: a // comment ends with its element, and error positions
 * say "line N" for element N.
 */
public final class SourceLines {
	private SourceLines() {
	}

	/** A JSON number or boolean is taken as its literal text, so modify_variable can say "value": 10 or "value": true. */
	public static String join(JsonElement json) {
		if (json.isJsonPrimitive()) {
			return json.getAsString();
		}
		if (json.isJsonArray()) {
			List<String> lines = new ArrayList<>();
			for (JsonElement line : json.getAsJsonArray()) {
				if (!line.isJsonPrimitive() || !line.getAsJsonPrimitive().isString()) {
					throw new JsonSyntaxException("expected every element of the array to be a string (one line of code), found " + line);
				}
				lines.add(line.getAsString());
			}
			return String.join("\n", lines);
		}
		throw new JsonSyntaxException("expected a string or an array of strings (one line of code each), found " + json);
	}
}
