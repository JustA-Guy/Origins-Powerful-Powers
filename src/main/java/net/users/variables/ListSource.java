package net.users.variables;

/**
 * A power that code can use as a list: a stored origins-powerful-powers:list, or a computed one like
 * origins-powerful-powers:power_list.
 */
public interface ListSource {
	/** The name code uses for the list. */
	String listName();

	/** A stored list returns the same ListData every time; a computed one returns a fresh snapshot. */
	ListData data();

	/** Push the list to the client if it changed. Computed lists never change. */
	void syncIfDirty();
}
