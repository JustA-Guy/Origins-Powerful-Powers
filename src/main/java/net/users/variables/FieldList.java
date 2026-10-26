package net.users.variables;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The "fields" of a origins-powerful-powers:variables power, parsed from Java declarations when the pack loads. */
public final class FieldList {
	private final String source;
	private final Map<String, FieldDecl> fields;

	FieldList(String source, Map<String, FieldDecl> fields) {
		this.source = source;
		this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}

	public static FieldList parse(String source) {
		return new Parser(source, Parser.Mode.FIELDS, false).parseFields();
	}

	public String source() {
		return source;
	}

	public Collection<FieldDecl> all() {
		return fields.values();
	}

	public FieldDecl get(String name) {
		return fields.get(name);
	}
}
