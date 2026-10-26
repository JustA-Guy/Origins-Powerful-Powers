package net.users.mixin;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;
import java.util.Optional;

/**
 * Lets PlayerMaskPackets rebuild a team packet with some player names left out. The constructor that takes the
 * method and player list is private; the public factories can't express "the same packet minus these names".
 */
@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface TeamPacketInvoker {
	@Accessor("method")
	int opp$getMethod();

	@Invoker("<init>")
	static ClientboundSetPlayerTeamPacket opp$create(String name, int method,
			Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters, Collection<String> players) {
		throw new AssertionError();
	}
}
