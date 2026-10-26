package net.users.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Lets OPP put its own entries in a player-info packet. Vanilla only builds them from real ServerPlayers, which always
 * come out "listed", and a fake-player fake entity must stay out of the tab list.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoEntriesAccessor {
	@Mutable
	@Accessor("entries")
	void opp$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
