package dev.latvian.mods.packsync.platform;

import dev.latvian.mods.packsync.PackSync;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.Path;

public class PackSyncModFileCandidateLocator implements IModFileCandidateLocator {
	private record NeoForgeContext(ILaunchContext context, IDiscoveryPipeline pipeline) implements PackSyncPlatformContext {
		@Override
		public void addIssue(Issue issue) {
			pipeline.addIssue((issue.warning ? ModLoadingIssue.warning(issue.message, issue.args) : ModLoadingIssue.error(issue.message, issue.args)).withAffectedPath(issue.path).withCause(issue.cause));
		}

		@Override
		public String getMinecraftVersion() {
			return context.getVersions().mcVersion();
		}

		@Override
		public String getLoaderVersion() {
			return context.getVersions().neoFormVersion() + "/" + context.getVersions().neoForgeVersion();
		}

		@Override
		public String getDataVersion() {
			// FIXME: Figure out how to get SharedConstants.getCurrentVersion().getDataVersion().getVersion()
			return "4790";
		}

		@Override
		public boolean isDev() {
			return !FMLLoader.getCurrent().isProduction();
		}

		@Override
		public boolean isServer() {
			return context.getRequiredDistribution().isDedicatedServer();
		}

		@Override
		public Path getGameDirectory() {
			return FMLPaths.GAMEDIR.get();
		}

		@Override
		public Path getModsDirectory() {
			return FMLPaths.MODSDIR.get();
		}

		@Override
		public void addPath(Path path) {
			pipeline.addPath(path, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
		}
	}

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		PackSync.load(new NeoForgeContext(context, pipeline));
	}

	@Override
	public String toString() {
		return "PackSync";
	}
}
