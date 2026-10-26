package net.users.compat.hcr;

import net.blay09.mods.hardcorerevival.api.HardcoreRevivalAPI;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;

/**
 * Soft-dependency bridge to Hardcore Revival. Only isLoaded() is safe to call without the mod; call the rest after
 * it returns true. The JVM resolves Hardcore Revival's classes when a method naming them first runs, so loading this
 * class without the mod is fine. Mirrors SvcCompat.
 */
public final class HcrCompat {
	private HcrCompat() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded("hardcorerevival");
	}

	public static boolean isDowned(Player player) {
		return HardcoreRevivalAPI.isKnockedOut(player);
	}
}
