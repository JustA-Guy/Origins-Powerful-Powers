package net.users.tracking;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.users.mixin.PlayerInfoEntriesAccessor;
import net.users.mixin.TeamPacketInvoker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites what one viewer is sent so PlayerMaskTracker's masks hold (PlayerMaskSendMixin calls rewrite for every
 * packet). Packets are often shared between viewers (broadcasts), so a changed packet is always a new one.
 *
 * Text: a component whose hover shows an obfuscated player (vanilla's display name, used in chat, death, join and
 * advancement messages) becomes magic text as a whole, which also covers styled nicknames split into many parts.
 * Anywhere else, the player's username or nickname as a whole word is cut out into its own magic-text part. Click
 * actions and shift-click insertions that would give the name away are dropped.
 */
public final class PlayerMaskPackets {
	private static final Style MAGIC = Style.EMPTY.withObfuscated(true);

	private PlayerMaskPackets() {
	}

	public static Packet<?> rewrite(ServerPlayer viewer, Packet<?> packet) {
		if (!PlayerMaskTracker.active()) {
			return packet;
		}
		PlayerMaskTracker.ViewerState state = PlayerMaskTracker.stateFor(viewer);
		if (state == null) {
			return packet;
		}
		if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
			return playerInfo(info, state);
		}
		if (packet instanceof ClientboundSetPlayerTeamPacket team) {
			return team(viewer, team, state);
		}
		if (state.obfuscated.isEmpty()) {
			return packet;
		}
		if (packet instanceof ClientboundSystemChatPacket chat) {
			Component content = text(chat.content(), state);
			return content == chat.content() ? packet : new ClientboundSystemChatPacket(content, chat.overlay());
		}
		if (packet instanceof ClientboundPlayerChatPacket chat) {
			ChatType.BoundNetwork type = bound(chat.chatType(), state);
			Component shown = chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
			Component rewritten = text(shown, state);
			if (type == chat.chatType() && rewritten == shown) {
				return packet;
			}
			// The signed text stays as sent; the client shows the unsigned copy, like any server-side chat styling.
			return new ClientboundPlayerChatPacket(chat.sender(), chat.index(), chat.signature(), chat.body(),
				rewritten == shown ? chat.unsignedContent() : rewritten, chat.filterMask(), type);
		}
		if (packet instanceof ClientboundDisguisedChatPacket chat) {
			Component message = text(chat.message(), state);
			ChatType.BoundNetwork type = bound(chat.chatType(), state);
			return message == chat.message() && type == chat.chatType() ? packet : new ClientboundDisguisedChatPacket(message, type);
		}
		if (packet instanceof ClientboundSetTitleTextPacket title) {
			Component text = text(title.getText(), state);
			return text == title.getText() ? packet : new ClientboundSetTitleTextPacket(text);
		}
		if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
			Component text = text(subtitle.getText(), state);
			return text == subtitle.getText() ? packet : new ClientboundSetSubtitleTextPacket(text);
		}
		if (packet instanceof ClientboundSetActionBarTextPacket bar) {
			Component text = text(bar.getText(), state);
			return text == bar.getText() ? packet : new ClientboundSetActionBarTextPacket(text);
		}
		if (packet instanceof ClientboundTabListPacket tab) {
			Component header = text(tab.getHeader(), state);
			Component footer = text(tab.getFooter(), state);
			return header == tab.getHeader() && footer == tab.getFooter() ? packet : new ClientboundTabListPacket(header, footer);
		}
		return packet;
	}

	private static ChatType.BoundNetwork bound(ChatType.BoundNetwork type, PlayerMaskTracker.ViewerState state) {
		Component name = text(type.name(), state);
		Component target = type.targetName() == null ? null : text(type.targetName(), state);
		return name == type.name() && target == type.targetName() ? type : new ChatType.BoundNetwork(type.chatType(), name, target);
	}

	/** Hidden players go unlisted; obfuscated players get a magic-text display name (their username if they had none). */
	private static Packet<?> playerInfo(ClientboundPlayerInfoUpdatePacket info, PlayerMaskTracker.ViewerState state) {
		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = info.actions();
		boolean names = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
			|| actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
		boolean listing = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED);
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(info.entries().size());
		boolean changed = false;
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
			UUID id = entry.profileId();
			boolean listed = entry.listed();
			Component displayName = entry.displayName();
			if (listing && listed && state.isHidden(id)) {
				listed = false;
			}
			if (names && state.isObfuscated(id)) {
				displayName = magic(displayName != null ? displayName
					: Component.literal(entry.profile() != null ? entry.profile().getName() : ""));
			}
			if (listed != entry.listed() || displayName != entry.displayName()) {
				changed = true;
				entry = new ClientboundPlayerInfoUpdatePacket.Entry(id, entry.profile(), listed, entry.latency(), entry.gameMode(),
					displayName, entry.chatSession());
			}
			entries.add(entry);
		}
		if (!changed) {
			return info;
		}
		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> newActions = EnumSet.copyOf(actions);
		if (names) {
			// ADD_PLAYER alone doesn't carry the display name; the entry's display name is only sent with this.
			newActions.add(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
		}
		ClientboundPlayerInfoUpdatePacket copy = new ClientboundPlayerInfoUpdatePacket(newActions, List.of());
		((PlayerInfoEntriesAccessor) copy).opp$setEntries(entries);
		return copy;
	}

	/**
	 * An obfuscated player sits in this viewer's client-side magic-text team, so real team packets that add or remove
	 * them are left without their name (a removal from a team the client doesn't have them in would disconnect the
	 * viewer). Changes to their real team are copied over on the next refresh.
	 */
	private static Packet<?> team(ServerPlayer viewer, ClientboundSetPlayerTeamPacket packet, PlayerMaskTracker.ViewerState state) {
		if (state.obfuscated.isEmpty() || PlayerMaskTracker.isMaskTeam(packet.getName())) {
			return packet;
		}
		for (UUID id : state.obfuscated) {
			ServerPlayer holder = viewer.server.getPlayerList().getPlayer(id);
			PlayerTeam team = holder == null ? null : viewer.server.getScoreboard().getPlayersTeam(holder.getScoreboardName());
			if (team != null && team.getName().equals(packet.getName())) {
				state.markTeamsDirty();
			}
		}
		Collection<String> players = packet.getPlayers();
		if (players.isEmpty()) {
			return packet;
		}
		List<String> kept = new ArrayList<>();
		for (String name : players) {
			if (state.obfuscatedNamed(name) == null) {
				kept.add(name);
			}
		}
		if (kept.size() == players.size()) {
			return packet;
		}
		state.markTeamsDirty();
		// An add or remove left with nobody in it is harmless: the client just loops over no names.
		int method = ((TeamPacketInvoker) packet).opp$getMethod();
		return TeamPacketInvoker.opp$create(packet.getName(), method, packet.getParameters(), kept);
	}

	/** The component with the viewer's obfuscated names masked, or the same instance when nothing needed it. */
	public static Component text(Component component, PlayerMaskTracker.ViewerState state) {
		if (component == null || state.obfuscated.isEmpty()) {
			return component;
		}
		return walk(component, state);
	}

	private static Component walk(Component component, PlayerMaskTracker.ViewerState state) {
		Style style = component.getStyle();
		HoverEvent hover = style.getHoverEvent();
		if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_ENTITY) {
			HoverEvent.EntityTooltipInfo entity = hover.getValue(HoverEvent.Action.SHOW_ENTITY);
			if (entity != null && state.isObfuscated(entity.id)) {
				return magic(component);
			}
		}
		Pattern pattern = state.pattern();
		boolean changed = false;
		Style newStyle = scrub(style, pattern, state);
		if (newStyle != style) {
			changed = true;
		}
		ComponentContents contents = component.getContents();
		MutableComponent out = null;
		if (contents instanceof LiteralContents literal && pattern != null) {
			out = split(literal.text(), pattern);
		} else if (contents instanceof TranslatableContents translatable) {
			Object[] args = translatable.getArgs();
			Object[] newArgs = args.clone();
			boolean argsChanged = false;
			for (int i = 0; i < args.length; i++) {
				if (args[i] instanceof Component arg) {
					newArgs[i] = walk(arg, state);
				} else if (args[i] instanceof String string && pattern != null) {
					MutableComponent split = split(string, pattern);
					newArgs[i] = split != null ? split : string;
				}
				argsChanged |= newArgs[i] != args[i];
			}
			if (argsChanged) {
				out = MutableComponent.create(new TranslatableContents(translatable.getKey(), translatable.getFallback(), newArgs));
			}
		}
		changed |= out != null;
		List<Component> siblings = component.getSiblings();
		List<Component> newSiblings = new ArrayList<>(siblings.size());
		for (Component sibling : siblings) {
			Component walked = walk(sibling, state);
			changed |= walked != sibling;
			newSiblings.add(walked);
		}
		if (!changed) {
			return component;
		}
		if (out == null) {
			out = MutableComponent.create(contents);
		}
		out.setStyle(newStyle);
		newSiblings.forEach(out::append);
		return out;
	}

	/** The text with each name cut out into a magic-text part, or null when no name is in it. */
	private static MutableComponent split(String text, Pattern pattern) {
		Matcher matcher = pattern.matcher(text);
		if (!matcher.find()) {
			return null;
		}
		MutableComponent out = Component.empty();
		int last = 0;
		do {
			if (matcher.start() > last) {
				out.append(Component.literal(text.substring(last, matcher.start())));
			}
			out.append(Component.literal(matcher.group()).setStyle(MAGIC));
			last = matcher.end();
		} while (matcher.find());
		if (last < text.length()) {
			out.append(Component.literal(text.substring(last)));
		}
		return out;
	}

	/** Drops click actions and insertions that carry a name, and masks names in hover text. */
	private static Style scrub(Style style, Pattern pattern, PlayerMaskTracker.ViewerState state) {
		Style out = style;
		ClickEvent click = style.getClickEvent();
		if (click != null && pattern != null && pattern.matcher(click.getValue()).find()) {
			out = out.withClickEvent(null);
		}
		String insertion = style.getInsertion();
		if (insertion != null && pattern != null && pattern.matcher(insertion).find()) {
			out = out.withInsertion(null);
		}
		HoverEvent hover = style.getHoverEvent();
		if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
			Component shown = hover.getValue(HoverEvent.Action.SHOW_TEXT);
			Component walked = shown == null ? null : walk(shown, state);
			if (walked != shown) {
				out = out.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, walked));
			}
		}
		return out;
	}

	/** The whole component as magic text, every part of it, with nothing to click, insert or hover that names them. */
	private static MutableComponent magic(Component component) {
		MutableComponent out = MutableComponent.create(component.getContents());
		out.setStyle(component.getStyle().withObfuscated(true).withClickEvent(null).withInsertion(null).withHoverEvent(null));
		for (Component sibling : component.getSiblings()) {
			out.append(magic(sibling));
		}
		return out;
	}
}
