package net.users.compat.sn;

import eu.pb4.stylednicknames.NicknameHolder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Soft-dependency bridge to Styled Nicknames (1.2.1+1.20). nickname() is safe to call unconditionally: it checks
 * isLoaded() first, and the method that names a Styled Nicknames type only runs after that. Mirrors
 * net.users.compat.hcr.HcrCompat.
 */
public final class NicknameCompat {
	private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("styled-nicknames");

	private NicknameCompat() {
	}

	public static boolean isLoaded() {
		return LOADED;
	}

	/** The player's nickname as plain text, or null when they have none (or the mod isn't installed). */
	public static String nickname(ServerPlayer player) {
		if (!LOADED) {
			return null;
		}
		try {
			return fromMod(player);
		} catch (LinkageError | RuntimeException e) {
			// A different Styled Nicknames version with another API: behave as if it weren't there.
			return null;
		}
	}

	private static String fromMod(ServerPlayer player) {
		Component parsed = NicknameHolder.of(player).styledNicknames$getParsed();
		if (parsed == null) {
			return null;
		}
		String text = parsed.getString().trim();
		return text.isEmpty() ? null : text;
	}
}
