package net.users.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The whitelistFeedback gamerule: a list of usernames. While it has any, only
 * those players are sent command feedback, both their own ("Set the time to 1000") and the gray "[Steve: …]" copies
 * sent to operators. Everyone else gets none. Empty (the default, or "none") leaves vanilla's behavior alone. Error
 * messages aren't feedback and still reach whoever ran the command, like with sendCommandFeedback.
 *
 * Checked in Entity#acceptsSuccess and CommandSourceStack#broadcastToAdmins (FeedbackWhitelistMixin).
 */
public final class FeedbackWhitelist {
	/** The word that empties the list, since the command can't take an empty value. */
	private static final String CLEAR = "none";

	public static final GameRules.Type<NameList> TYPE = new GameRules.Type<>(
		StringArgumentType::greedyString,
		NameList::new,
		(server, value) -> {
		},
		(visitor, key, type) -> visitor.visit(key, type));

	public static final GameRules.Key<NameList> KEY = GameRuleRegistry.register("whitelistFeedback", GameRules.Category.CHAT, TYPE);

	private FeedbackWhitelist() {
	}

	public static void register() {
		// Loads the class, which registers the rule.
	}

	/** False when the list is in use and this player isn't on it. */
	public static boolean allows(ServerPlayer player) {
		NameList list = player.server.getGameRules().getRule(KEY);
		return list.lowercase.isEmpty() || list.lowercase.contains(player.getGameProfile().getName().toLowerCase(Locale.ROOT));
	}

	/** Usernames separated by spaces (commas work too), in the order given. Saved in level.dat like any gamerule. */
	public static final class NameList extends GameRules.Value<NameList> {
		private List<String> names = List.of();
		private Set<String> lowercase = Set.of();

		public NameList(GameRules.Type<NameList> type) {
			super(type);
		}

		public List<String> names() {
			return Collections.unmodifiableList(names);
		}

		@Override
		protected void updateFromArgument(CommandContext<CommandSourceStack> context, String argument) {
			parse(StringArgumentType.getString(context, argument));
		}

		@Override
		protected void deserialize(String value) {
			parse(value);
		}

		private void parse(String value) {
			List<String> parsed = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (String name : value.trim().split("[\\s,]+")) {
				if (!name.isEmpty() && !name.equalsIgnoreCase(CLEAR) && seen.add(name.toLowerCase(Locale.ROOT))) {
					parsed.add(name);
				}
			}
			names = List.copyOf(parsed);
			lowercase = Set.copyOf(seen);
		}

		@Override
		public String serialize() {
			return String.join(" ", names);
		}

		@Override
		public String toString() {
			return names.isEmpty() ? CLEAR : serialize();
		}

		/** What /gamerule returns: how many names are on the list. */
		@Override
		public int getCommandResult() {
			return names.size();
		}

		@Override
		protected NameList getSelf() {
			return this;
		}

		@Override
		protected NameList copy() {
			NameList copy = new NameList(type);
			copy.names = names;
			copy.lowercase = lowercase;
			return copy;
		}

		@Override
		public void setFrom(NameList other, MinecraftServer server) {
			names = other.names;
			lowercase = other.lowercase;
			onChanged(server);
		}
	}
}
