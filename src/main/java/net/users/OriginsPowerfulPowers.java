package net.users;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

import net.users.command.ChunkLoadCommand;
import net.users.command.FakeBlockCommand;
import net.users.command.FeedbackWhitelist;
import net.users.command.GlobalCommand;
import net.users.command.IllusionCommand;
import net.users.command.PlayerMaskCommand;
import net.users.command.PowerInvCommand;
import net.users.command.PowerTagCommand;
import net.users.command.ShieldCommand;
import net.users.command.OppvcCommand;
import net.users.command.WithCommand;
import net.users.power.OppAttributes;
import net.users.power.OppBiEntityConditions;
import net.users.power.ChunkLoaderPower;
import net.users.power.FakeBlocksPower;
import net.users.power.OppBlockActions;
import net.users.power.OppBlockConditions;
import net.users.power.OppChatPowers;
import net.users.power.OppDamageConditions;
import net.users.power.OppSignals;
import net.users.power.OppEntityActions;
import net.users.power.OppEntityConditions;
import net.users.power.FakeEntityPowers;
import net.users.power.PlayerMaskPowers;
import net.users.power.OppEventPowers;
import net.users.power.OppHardcoreRevivalConditions;
import net.users.power.PowerTags;
import net.users.power.OppVariables;
import net.users.power.OppVoicechatConditions;
import net.users.tracking.BlockCrackTracker;
import net.users.tracking.BroadcastTracker;
import net.users.tracking.ChunkLoadTracker;
import net.users.tracking.CrouchDepthTracker;
import net.users.tracking.DisguiseTracker;
import net.users.tracking.FakeBlockTracker;
import net.users.tracking.FakeEntityTracker;
import net.users.tracking.PlayerMaskTracker;
import net.users.tracking.PlayerExpiry;
import net.users.tracking.TrackerCleanup;
import net.users.tracking.MealTracker;
import net.users.tracking.MovementStateTracker;
import net.users.tracking.VibrationCooldownTracker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OriginsPowerfulPowers implements ModInitializer {
	public static final String MOD_ID = "origins-powerful-powers";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		OppEntityConditions.register();
		OppBlockConditions.register();
		OppDamageConditions.register();
		OppBiEntityConditions.register();
		OppEntityActions.register();
		OppBlockActions.register();
		OppVoicechatConditions.register();
		OppHardcoreRevivalConditions.register();
		OppVariables.register();
		OppAttributes.register();
		OppEventPowers.register();
		FakeEntityPowers.register();
		OppChatPowers.register();
		OppSignals.register();
		FakeBlocksPower.register();
		ChunkLoaderPower.register();
		PlayerMaskPowers.register();
		PowerTags.register();
		PlayerExpiry.register();
		FeedbackWhitelist.register();
		TrackerCleanup.register();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			VibrationCooldownTracker.tickDown();
			BroadcastTracker.prune(server.getTickCount());
			BlockCrackTracker.tick(server);
			FakeEntityTracker.tick(server);
			FakeBlockTracker.tick(server);
			DisguiseTracker.tick(server);
			ChunkLoadTracker.tick(server);
			PlayerMaskTracker.tick(server);
			for (var level : server.getAllLevels()) {
				for (Player player : level.players()) {
					CrouchDepthTracker.tick(player);
					MovementStateTracker.tick(player);
					MealTracker.tick(player, level.getGameTime());
					if (OppEntityConditions.isMovingLoudly(player) && VibrationCooldownTracker.get(player) == 0) {
						// Mirrors vanilla's sculk sensor re-trigger cooldown (40 ticks).
						VibrationCooldownTracker.set(player, 40);
					}
					OppAttributes.sync(player);
				}
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			BlockCrackTracker.clear();
			FakeEntityTracker.clear();
			FakeBlockTracker.clearAll();
			DisguiseTracker.clear();
			ChunkLoadTracker.clear();
			PlayerMaskTracker.clear();
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			ShieldCommand.register(dispatcher);
			OppvcCommand.register(dispatcher);
			PowerInvCommand.register(dispatcher, registryAccess);
			WithCommand.register(dispatcher);
			IllusionCommand.register(dispatcher, registryAccess);
			GlobalCommand.register(dispatcher);
			FakeBlockCommand.register(dispatcher, registryAccess);
			PowerTagCommand.register(dispatcher);
			ChunkLoadCommand.register(dispatcher);
			PlayerMaskCommand.register(dispatcher);
		});

		LOGGER.info("Origins: Powerful Powers: registered Origins/Apoli power factories");
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}
}
