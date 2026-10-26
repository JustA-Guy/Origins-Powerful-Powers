package net.users.compat.svc;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.LeaveGroupEvent;
import de.maxhenkel.voicechat.api.events.LocationalSoundPacketEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent;
import de.maxhenkel.voicechat.api.events.StaticSoundPacketEvent;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.Entity;
import net.users.tracking.BroadcastTracker;
import net.users.tracking.DeafenTracker;
import net.users.tracking.MuteTracker;
import net.users.tracking.VoiceChatTracker;

import java.util.function.Predicate;

/**
 * Registered through the "voicechat" entrypoint in fabric.mod.json. Only Simple Voice Chat looks that entrypoint up,
 * so this class and everything it references load only when SVC is installed, and no isModLoaded() guard is needed.
 */
public class OppVoicechatPlugin implements de.maxhenkel.voicechat.api.VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "origins-powerful-powers";
	}

	@Override
	public void initialize(VoicechatApi api) {
		if (api instanceof VoicechatServerApi serverApi) {
			SvcCompat.setServerApi(serverApi);
		}
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(MicrophonePacketEvent.class, event -> {
			VoicechatConnection senderConnection = event.getSenderConnection();
			if (senderConnection == null) {
				return;
			}
			if (MuteTracker.isMuted(senderConnection.getPlayer().getUuid())) {
				// Cancelling here stops the packet before SVC relays it by proximity or group, so the mute holds
				// whatever the player's own mic toggle says.
				event.cancel();
				return;
			}
			VoiceChatTracker.onMicPacket(senderConnection.getPlayer().getUuid(), event.getPacket().isWhispering());
			relayBroadcast(event, senderConnection);
		});

		// SVC fires one of these per sender and receiver for every delivered packet (proximity, group or spectator).
		// Cancelling blocks that one receiver. SVC has no server-side deafen, so this is how a forced deafen works.
		registration.registerEvent(LocationalSoundPacketEvent.class, event -> cancelIfReceiverDeafened(event, event.getReceiverConnection()));
		registration.registerEvent(EntitySoundPacketEvent.class, event -> cancelIfReceiverDeafened(event, event.getReceiverConnection()));
		registration.registerEvent(StaticSoundPacketEvent.class, event -> cancelIfReceiverDeafened(event, event.getReceiverConnection()));

		registration.registerEvent(PlayerStateChangedEvent.class, event ->
			VoiceChatTracker.setConnectionState(
				event.getPlayerUuid(),
				event.getConnection() != null && event.getConnection().isInstalled(),
				event.isDisabled()
			)
		);

		registration.registerEvent(JoinGroupEvent.class, event ->
			VoiceChatTracker.setGroup(event.getConnection().getPlayer().getUuid(), event.getGroup().getId())
		);

		registration.registerEvent(LeaveGroupEvent.class, event ->
			VoiceChatTracker.setGroup(event.getConnection().getPlayer().getUuid(), null)
		);
	}

	private void cancelIfReceiverDeafened(Event event, VoicechatConnection receiverConnection) {
		if (receiverConnection != null && DeafenTracker.isDeafened(receiverConnection.getPlayer().getUuid())) {
			event.cancel();
		}
	}

	/**
	 * While the speaker is broadcasting (start_broadcast_voice or /oppvc broadcast start), relays their mic audio as a
	 * static sound to every listener the filter approves, at any distance. Listeners keep hearing their proximity and
	 * group audio too. sendStaticSoundPacketTo skips the cancellable sound events, so deafened listeners are skipped here.
	 */
	private void relayBroadcast(MicrophonePacketEvent event, VoicechatConnection senderConnection) {
		VoicechatServerApi api = SvcCompat.getServerApi();
		if (api == null) {
			return;
		}
		Object rawBroadcaster = senderConnection.getPlayer().getEntity();
		if (!(rawBroadcaster instanceof ServerPlayer broadcaster)) {
			return;
		}

		Predicate<Tuple<Entity, Entity>> listenerFilter = BroadcastTracker.getListenerFilter(broadcaster.getUUID());
		if (listenerFilter == null) {
			return;
		}

		MinecraftServer server = broadcaster.getServer();
		if (server == null) {
			return;
		}

		StaticSoundPacket packet = event.getPacket().staticSoundPacketBuilder().build();
		for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
			if (listener == broadcaster || DeafenTracker.isDeafened(listener.getUUID())) {
				continue;
			}
			if (!listenerFilter.test(new Tuple<>(broadcaster, listener))) {
				continue;
			}
			VoicechatConnection listenerConnection = api.getConnectionOf(listener.getUUID());
			if (listenerConnection != null && listenerConnection.isInstalled()) {
				api.sendStaticSoundPacketTo(listenerConnection, packet);
			}
		}
	}
}
