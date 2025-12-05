package dev.latvian.mods.packsync;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.latvian.mods.packsync.repackaged.nbt.NBTCompoundTag;
import dev.latvian.mods.packsync.repackaged.nbt.NBTList;
import dev.latvian.mods.packsync.repackaged.nbt.NBTString;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

public class PackSync implements IModFileCandidateLocator {
	private static final Logger LOGGER = LogUtils.getLogger();

	public static String getPlatform() {
		String s = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		if (s.contains("win")) {
			return "windows";
		} else if (s.contains("mac")) {
			return "mac";
		} else if (s.contains("solaris") || s.contains("sunos")) {
			return "solaris";
		} else if (s.contains("linux")) {
			return "linux";
		} else {
			return s.contains("unix") ? "linux" : "unknown";
		}
	}

	public static long size(Path path) {
		try {
			return Files.size(path);
		} catch (IOException e) {
			return 0L;
		}
	}

	private static InputStream gzip(InputStream in, boolean gzip) throws IOException {
		in = new BufferedInputStream(in);

		if (gzip) {
			in = new BufferedInputStream(new GZIPInputStream(in));
		}

		return in;
	}

	private static void fetch(HttpClient httpClient, HttpRequest.Builder requestBuilderBase, IDiscoveryPipeline pipeline, String fileName, long size, String uri, boolean gzip, Consumer<InputStream> callback) {
		try {
			LOGGER.info("Fetching " + fileName + " from " + uri + (size > 0L ? " [%,d bytes]...".formatted(size) : "..."));
			var response = httpClient.send(requestBuilderBase.copy().uri(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() / 100 != 2) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to update %s! Error code %d", fileName, response.statusCode()));
			}

			try (var in = gzip(response.body(), gzip)) {
				callback.accept(in);
			}
		} catch (Exception ex) {
			pipeline.addIssue(ModLoadingIssue.error("Failed to update %s!", fileName).withCause(ex));
		}
	}

	private static boolean download(HttpClient httpClient, HttpRequest.Builder requestBuilderBase, IDiscoveryPipeline pipeline, Path path, String fileName, long size, String uri, boolean gzip) {
		var actualFileName = fileName.isEmpty() ? path.getFileName().toString() : fileName;

		try {
			LOGGER.info("Downloading " + actualFileName + " from " + uri + (size > 0L ? " [%,d bytes]...".formatted(size) : "..."));
			var response = httpClient.send(requestBuilderBase.copy().uri(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() / 100 != 2) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to update %s! Error code %d", actualFileName, response.statusCode()).withAffectedPath(path));
				return false;
			}

			var parent = path.getParent();

			if (Files.notExists(parent)) {
				Files.createDirectories(parent);
			}

			try (var in = gzip(response.body(), gzip); var out = new BufferedOutputStream(Files.newOutputStream(path))) {
				in.transferTo(out);
				return true;
			}
		} catch (Exception ex) {
			pipeline.addIssue(ModLoadingIssue.error("Failed to update %s!", actualFileName).withCause(ex).withAffectedPath(path));
			return false;
		}
	}

	private static boolean delete(Path path, String fileName, IDiscoveryPipeline pipeline) {
		var actualFileName = fileName.isEmpty() ? path.getFileName().toString() : fileName;

		try {
			LOGGER.info("Deleting " + actualFileName + " [%,d bytes]...".formatted(Files.exists(path) ? Files.size(path) : 0L));
			Files.deleteIfExists(path);
			return true;
		} catch (Exception ex) {
			pipeline.addIssue(ModLoadingIssue.error("Failed to delete %s!", actualFileName).withCause(ex).withAffectedPath(path));
			return false;
		}
	}

	public static void findMods(Executor executor, HttpClient httpClient, IDiscoveryPipeline pipeline) throws Exception {
		var errors = new AtomicInteger(0);
		var gameDir = FMLPaths.GAMEDIR.get();
		long startTime = System.currentTimeMillis();
		var gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
		var futures = new ArrayList<CompletableFuture<Void>>();

		var configFile = FMLPaths.MODSDIR.get().resolve("pack-sync.json");

		if (Files.notExists(configFile)) {
			pipeline.addIssue(ModLoadingIssue.error("Pack Sync config file not found!").withAffectedPath(configFile));
			return;
		}

		JsonObject config;

		try (var configReader = Files.newBufferedReader(configFile)) {
			config = gson.fromJson(configReader, JsonObject.class);
		} catch (Exception ex) {
			pipeline.addIssue(ModLoadingIssue.error("Failed to read Pack Sync config file!").withCause(ex).withAffectedPath(configFile));
			return;
		}

		var infoFile = FMLPaths.GAMEDIR.get().resolve("pack-sync-info.json");
		JsonObject info;
		boolean updateInfo = false;

		if (Files.notExists(infoFile)) {
			info = new JsonObject();
		} else {
			try (var infoReader = Files.newBufferedReader(infoFile)) {
				info = gson.fromJson(infoReader, JsonObject.class);
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to read Pack Sync info file!").withCause(ex).withAffectedPath(infoFile));
				return;
			}
		}

		if (!info.has("version")) {
			info.addProperty("version", "");
			updateInfo = true;
		}

		if (!info.has("auth")) {
			info.addProperty("auth", "%PACK_SYNC_TOKEN%");
			updateInfo = true;
		}

		if (!info.has("pause_updates")) {
			info.addProperty("pause_updates", false);
			updateInfo = true;
		}

		if (!info.has("ignored_mods")) {
			info.add("ignored_mods", new JsonArray());
			updateInfo = true;
		}

		if (!info.has("mods")) {
			info.add("mods", new JsonArray());
			updateInfo = true;
		}

		var repositoryEnv = Optional.ofNullable(System.getenv("PACK_SYNC_REPO_DIRECTORY")).orElse("");
		var repository = repositoryEnv.isEmpty() ? Path.of(System.getProperty("user.home")).resolve(".latvian.dev").resolve("pack-sync") : Path.of(repositoryEnv);

		if (Files.notExists(repository) || !Files.isDirectory(repository)) {
			try {
				Files.createDirectories(repository);
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to create Pack Sync repository directory!").withCause(ex).withAffectedPath(repository));
				return;
			}
		}

		var repositoryFiles = new ConcurrentHashMap<String, RepositoryFile>();

		try (var listStream = Files.walk(repository)) {
			listStream.filter(Files::isRegularFile).forEach(file -> {
				var filename = file.getFileName().toString();

				if (!filename.endsWith(".meta.json")) {
					futures.add(CompletableFuture.runAsync(() -> {
						try {
							var i = filename.lastIndexOf('.');
							var checksum = i == -1 ? filename : filename.substring(0, i);
							var metaPath = file.resolveSibling(checksum + ".meta.json");

							if (Files.exists(metaPath) && Files.isRegularFile(metaPath)) {
								try (var reader = Files.newBufferedReader(metaPath)) {
									var json = gson.fromJson(reader, JsonObject.class);
									var repositoryFile = new RepositoryFile(file, json);
									repositoryFiles.put(repositoryFile.fileInfo().checksum(), repositoryFile);
								}
							} else {
								pipeline.addIssue(ModLoadingIssue.warning("Failed to load metadata file of Pack Sync repository file %s!", filename).withAffectedPath(metaPath));
							}
						} catch (Exception ex) {
							pipeline.addIssue(ModLoadingIssue.warning("Failed to load Pack Sync repository file %s!", filename).withCause(ex).withAffectedPath(file));
						}
					}, executor));
				}
			});
		}

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		futures.clear();

		var now = System.currentTimeMillis();
		LOGGER.info("Found %,d local files in %,d ms".formatted(repositoryFiles.size(), now - startTime));
		startTime = now;

		var packVersion = info.get("version").getAsString();
		var api = config.get("api").getAsString();

		while (api.endsWith("/")) {
			api = api.substring(0, api.length() - 1);
		}

		var packCode = config.get("pack_code").getAsString();
		var auth = info.get("auth").getAsString();

		if (auth.startsWith("%") && auth.endsWith("%")) {
			auth = Optional.ofNullable(System.getenv(auth.substring(1, auth.length() - 1))).orElse("");
		}

		var requestBuilderBase = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30L)).header("User-Agent", "dev.latvian.mods.packsync/1.0");

		if (!auth.isEmpty()) {
			requestBuilderBase.header("Authorization", "Bearer " + auth);
		}

		String newVersion;

		try {
			var versionRequest = httpClient.send(requestBuilderBase.copy().uri(URI.create(api + "/version/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8))).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (versionRequest.statusCode() / 100 != 2) {
				pipeline.addIssue(ModLoadingIssue.warning("Failed to update the modpack with error %d - %s!", versionRequest.statusCode(), versionRequest.body()));
				return;
			}

			newVersion = versionRequest.body().trim();
		} catch (HttpTimeoutException | ConnectException ex) {
			pipeline.addIssue(ModLoadingIssue.warning("Pack Sync update server timed out!").withCause(ex));
			return;
		}

		var ignoredMods = new HashSet<String>();

		for (var e : info.get("ignored_mods").getAsJsonArray()) {
			ignoredMods.add(e.getAsString());
		}

		if (info.get("pause_updates").getAsBoolean()) {
			LOGGER.info("Pack updates are paused ('" + packVersion + "')!");
			loadMods(repositoryFiles, info, ignoredMods, pipeline);

			if (updateInfo) {
				try (var writer = Files.newBufferedWriter(infoFile)) {
					gson.toJson(info, writer);
				}
			}

			return;
		}

		if (newVersion.equals(packVersion)) {
			LOGGER.info("Pack is up to date ('" + packVersion + "')!");
			loadMods(repositoryFiles, info, ignoredMods, pipeline);

			if (updateInfo) {
				try (var writer = Files.newBufferedWriter(infoFile)) {
					gson.toJson(info, writer);
				}
			}

			return;
		}

		LOGGER.info("Update found! '" + packVersion + "' -> '" + newVersion + "'");

		for (int i = 0; i < 256; i++) {
			var dir = repository.resolve("%02x".formatted(i));

			if (Files.notExists(dir) || !Files.isDirectory(dir)) {
				try {
					Files.createDirectory(dir);
				} catch (Exception ex) {
					pipeline.addIssue(ModLoadingIssue.error("Failed to create Pack Sync repository directory!").withCause(ex).withAffectedPath(dir));
					return;
				}
			}
		}

		var requestJson = new JsonObject();
		requestJson.addProperty("pack_version", packVersion);
		requestJson.addProperty("mc_version", FMLLoader.versionInfo().mcVersion());
		requestJson.addProperty("loader_version", FMLLoader.versionInfo().fmlVersion());
		requestJson.addProperty("loader_api_version", FMLLoader.versionInfo().neoForgeVersion());
		requestJson.addProperty("platform", getPlatform());
		requestJson.addProperty("dev", !FMLLoader.isProduction());
		requestJson.addProperty("server", FMLLoader.getDist().isDedicatedServer());
		requestJson.addProperty("gzip", true);

		var syncRequest = httpClient.send(requestBuilderBase.copy().uri(URI.create(api + "/sync/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8))).POST(HttpRequest.BodyPublishers.ofString(requestJson.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (syncRequest.statusCode() / 100 != 2) {
			pipeline.addIssue(ModLoadingIssue.warning("Failed to update the modpack with error %d - %s!", syncRequest.statusCode(), syncRequest.body()));
			loadMods(repositoryFiles, info, ignoredMods, pipeline);
			return;
		}

		var syncJson = gson.fromJson(syncRequest.body(), JsonObject.class);

		if (syncJson.has("warnings")) {
			for (var entry : syncJson.get("warnings").getAsJsonArray()) {
				pipeline.addIssue(ModLoadingIssue.warning(entry.getAsString()));
			}
		}

		if (syncJson.has("errors")) {
			for (var entry : syncJson.get("errors").getAsJsonArray()) {
				pipeline.addIssue(ModLoadingIssue.error(entry.getAsString()));
			}

			return;
		}

		if (syncJson.has("mods")) {
			var newModList = new JsonArray();

			for (var entry : syncJson.get("mods").getAsJsonArray()) {
				var remoteFile = new RemoteFile(entry.getAsJsonObject());
				var checksum = remoteFile.fileInfo().checksum();
				var filename = remoteFile.fileInfo().filename();

				var repositoryFile = repositoryFiles.get(checksum);

				if (repositoryFile == null || !repositoryFile.fileInfo().equals(remoteFile.fileInfo())) {
					futures.add(CompletableFuture.runAsync(() -> {
						var exti = filename.lastIndexOf('.');
						var ext = exti == -1 ? "" : filename.substring(exti);
						var downloadPath = repository.resolve(checksum.substring(0, 2)).resolve(checksum + ext);

						if (repositoryFile == null || download(httpClient, requestBuilderBase, pipeline, downloadPath, filename + " (" + checksum + ")", remoteFile.fileInfo().size(), remoteFile.url(), remoteFile.gzip())) {
							var file = new RepositoryFile(downloadPath, remoteFile.fileInfo());
							repositoryFiles.put(file.fileInfo().checksum(), file);

							var json = new JsonObject();
							file.fileInfo().toJson(json);

							var metaPath = downloadPath.resolveSibling(checksum + ".meta.json");

							try (var writer = Files.newBufferedWriter(metaPath)) {
								gson.toJson(json, writer);
							} catch (Exception ex) {
								pipeline.addIssue(ModLoadingIssue.error("Failed to save Pack Sync file %s metadata!", filename).withCause(ex).withAffectedPath(metaPath));
								errors.incrementAndGet();
							}
						}
					}, executor));
				}

				var json = new JsonObject();
				remoteFile.fileInfo().toJson(json);
				newModList.add(json);
			}

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			futures.clear();

			info.add("mods", newModList);
		}

		if (errors.get() > 0) {
			return;
		}

		if (syncJson.has("extra_files")) {
			for (var entry : syncJson.get("extra_files").getAsJsonArray()) {
				var file = new RemoteFile(entry.getAsJsonObject());

				futures.add(CompletableFuture.runAsync(() -> {
					var path = gameDir.resolve(file.path());

					if (!path.startsWith(gameDir)) {
						pipeline.addIssue(ModLoadingIssue.error("Pack Sync attempted to update file outside game directory!").withAffectedPath(path));
						errors.incrementAndGet();
					} else if (file.replace(path, pipeline)) {
						var relPath = gameDir.relativize(path);

						if (file.fileInfo().size() == 0L && file.fileInfo().filename().equals("deleted")) {
							delete(path, relPath.toString(), pipeline);
						} else {
							download(httpClient, requestBuilderBase, pipeline, path, relPath.toString(), file.fileInfo().size(), file.url(), file.gzip());
						}
					}
				}, executor));
			}
		}

		if (errors.get() > 0) {
			return;
		}

		if (syncJson.has("servers")) {
			var file = new RemoteFile(syncJson.get("servers").getAsJsonObject());

			futures.add(CompletableFuture.runAsync(() -> fetch(httpClient, requestBuilderBase, pipeline, "servers.dat", file.fileInfo().size(), file.url(), file.gzip(), in -> {
				var path = gameDir.resolve("servers.dat");

				try {
					var remoteNbt = NBTCompoundTag.readFully(in);
					var nbt = Files.exists(path) ? NBTCompoundTag.read(path) : new NBTCompoundTag();
					var serverMap = new LinkedHashMap<String, NBTCompoundTag>();

					if (nbt.get("servers") instanceof NBTList list) {
						for (var entry : list) {
							if (entry instanceof NBTCompoundTag tag && tag.get("name") instanceof NBTString(String name) && !name.isEmpty()) {
								serverMap.put(name, tag);
							}
						}
					}

					if (remoteNbt.get("servers") instanceof NBTList list) {
						for (var entry : list) {
							if (entry instanceof NBTCompoundTag tag && tag.get("name") instanceof NBTString(String name) && !name.isEmpty()) {
								if (tag.get("ip") instanceof NBTString(String ip) && !ip.isEmpty()) {
									serverMap.put(name, tag);
								} else {
									serverMap.remove(name);
								}
							}
						}
					}

					nbt.put("servers", new NBTList(serverMap.values()));
					nbt.write(path);
				} catch (Exception ex) {
					pipeline.addIssue(ModLoadingIssue.error("Failed to merge pack-sync-servers.dat into servers.dat!").withCause(ex).withAffectedPath(path));
					errors.incrementAndGet();
				}
			}), executor));
		}

		if (errors.get() > 0) {
			return;
		}

		if (syncJson.has("server_icon")) {
			var file = new RemoteFile(syncJson.get("server_icon").getAsJsonObject());

			futures.add(CompletableFuture.runAsync(() -> {
				var path = gameDir.resolve("server-icon.png");

				if (file.replace(path, pipeline)) {
					download(httpClient, requestBuilderBase, pipeline, path, "server-icon.png", file.fileInfo().size(), file.url(), file.gzip());
				}
			}, executor));
		}

		if (syncJson.has("options")) {
			var options = new LinkedHashMap<String, String>();
			var path = gameDir.resolve("options.txt");
			boolean changed = false;

			if (Files.exists(path)) {
				for (var line : Files.readAllLines(path)) {
					var parts = line.split(":", 2);

					if (parts.length == 2) {
						options.put(parts[0], parts[1]);
					}
				}
			} else {
				options.put("version", "4325");
			}

			for (var entry : syncJson.get("options").getAsJsonArray()) {
				var json = entry.getAsJsonObject();
				var key = json.get("key").getAsString();
				var value = json.get("value").getAsString();
				var force = json.has("force") && json.get("force").getAsBoolean();

				if (!options.containsKey(key) || force && !options.get(key).equals(value)) {
					options.put(key, value);
					changed = true;
				}
			}

			if (changed) {
				futures.add(CompletableFuture.runAsync(() -> {
					LOGGER.info("Updating options.txt...");

					try {
						Files.writeString(path, options.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining("\n")));
					} catch (Exception ex) {
						pipeline.addIssue(ModLoadingIssue.warning("Failed to update options.txt!").withCause(ex).withAffectedPath(path));
					}
				}, executor));
			}
		}

		if (syncJson.has("server_properties")) {
			var properties = new Properties();
			var path = gameDir.resolve("server.properties");
			boolean changed = false;

			if (Files.exists(path)) {
				try (var in = Files.newInputStream(path)) {
					properties.load(in);
				}
			}

			for (var entry : syncJson.get("server_properties").getAsJsonArray()) {
				var json = entry.getAsJsonObject();
				var key = json.get("key").getAsString();
				var value = json.get("value").getAsString();
				var force = json.has("force") && json.get("force").getAsBoolean();

				if (!properties.containsKey(key) || force && !properties.get(key).equals(value)) {
					properties.setProperty(key, value);
					changed = true;
				}
			}

			if (changed) {
				futures.add(CompletableFuture.runAsync(() -> {
					LOGGER.info("Updating server.properties...");

					try (var out = Files.newOutputStream(path)) {
						properties.store(out, "Minecraft server properties");
					} catch (Exception ex) {
						pipeline.addIssue(ModLoadingIssue.warning("Failed to update server.properties!").withCause(ex).withAffectedPath(path));
					}
				}, executor));
			}
		}

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		futures.clear();

		info.addProperty("version", newVersion);

		try (var writer = Files.newBufferedWriter(infoFile)) {
			gson.toJson(info, writer);
		}

		LOGGER.info("Pack updated '" + packVersion + "' -> '" + newVersion + "'!");
		loadMods(repositoryFiles, info, ignoredMods, pipeline);
	}

	private static void loadMods(Map<String, RepositoryFile> repositoryFiles, JsonObject info, Set<String> ignoredMods, IDiscoveryPipeline pipeline) {
		var filesToLoad = new ArrayList<RepositoryFile>();

		for (var entry : info.get("mods").getAsJsonArray()) {
			String filename = "";

			try {
				var fileInfo = new FileInfo(entry.getAsJsonObject());
				filename = fileInfo.filename();
				var artifact = fileInfo.artifact();

				if (!artifact.isEmpty() && ignoredMods.contains(artifact)) {
					LOGGER.info("Skipping ignored mod '" + filename + "' (" + artifact + ")");
					continue;
				}

				var repositoryFile = repositoryFiles.get(fileInfo.checksum());

				if (repositoryFile != null) {
					filesToLoad.add(repositoryFile);
					LOGGER.info("Loaded mod '" + filename + "' (" + artifact + ":" + fileInfo.version() + "/" + fileInfo.checksum() + ")");
				} else {
					pipeline.addIssue(ModLoadingIssue.error("Pack Sync mod %s not found!", fileInfo.filename()));
				}
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Pack Sync error loading mod %s!", filename.isEmpty() ? entry.toString() : filename).withCause(ex));
			}
		}

		filesToLoad.sort((a, b) -> a.fileInfo().filename().compareToIgnoreCase(b.fileInfo().filename()));

		for (var file : filesToLoad) {
			pipeline.addPath(file.path(), ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
		}
	}

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		long startTime = System.currentTimeMillis();
		LOGGER.info("Loading Pack Sync...");

		try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15L)).followRedirects(HttpClient.Redirect.ALWAYS).build()) {
			findMods(executor, httpClient, pipeline);
		} catch (HttpTimeoutException ex) {
			pipeline.addIssue(ModLoadingIssue.warning("Pack Sync update server timed out!").withCause(ex));
		} catch (Exception ex) {
			pipeline.addIssue(ModLoadingIssue.error("Pack Sync Crashed!").withCause(ex));
		}

		var now = System.currentTimeMillis();
		LOGGER.info("Finished loading Pack Sync in " + (now - startTime) + " ms!");
	}

	@Override
	public String toString() {
		return "PackSync";
	}
}
