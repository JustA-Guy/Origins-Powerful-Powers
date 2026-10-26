package net.users.variables;

/** One declared variable. value is its initial value, already converted to type. */
public record FieldDecl(String name, VarType type, Object value) {
}
