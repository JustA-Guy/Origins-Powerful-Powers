package net.users.compat.svc;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Soft-dependency bridge to Simple Voice Chat. Only isLoaded() is safe to call without the mod; call the rest after
 * it returns true. The JVM resolves SVC's classes when a method naming them first runs, so loading this class
 * without the mod is fine.
 *
 * OPP leaves SVC group membership to players and the SVC group UI. Voice broadcast relays audio straight to the
 * chosen listeners (OppVoicechatPlugin), on top of whatever group or proximity audio they already hear.
 */
public final class SvcCompat {
	private static volatile VoicechatServerApi serverApi;

	private SvcCompat() {
	}

	public static boolean isLoaded() {
		return FabricLoader.getInstance().isModLoaded("voicechat");
	}

	static void setServerApi(VoicechatServerApi api) {
		serverApi = api;
	}

	static VoicechatServerApi getServerApi() {
		return serverApi;
	}

	public static double getVoiceChatDistance() {
		VoicechatServerApi api = serverApi;
		return api == null ? 0.0 : api.getVoiceChatDistance();
	}
}
