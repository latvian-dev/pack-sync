package dev.latvian.mods.packsync.platform;

import java.nio.file.Path;

public interface PackSyncPlatformContext {
	void addIssue(Issue issue);

	String getMinecraftVersion();

	String getLoaderVersion();

	String getDataVersion();

	boolean isDev();

	boolean isServer();

	Path getGameDirectory();

	default Path getModsDirectory() {
		return getGameDirectory().resolve("mods");
	}

	default Path getLocalDirectory() {
		return getGameDirectory().resolve("local");
	}

	void addPath(Path path);
}
