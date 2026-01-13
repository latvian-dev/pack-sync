package dev.latvian.mods.packsync;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.latvian.mods.packsync.repackaged.nbt.NBTCompoundTag;
import dev.latvian.mods.packsync.repackaged.nbt.NBTList;
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
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class PackSync implements IModFileCandidateLocator {
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(60L))
		.followRedirects(HttpClient.Redirect.ALWAYS)
		.build();

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

	private static void fetch(HttpRequest.Builder requestBuilderBase, IDiscoveryPipeline pipeline, String fileName, long size, String uri, boolean gzip, Consumer<InputStream> callback) {
		try {
			LOGGER.info("Fetching " + fileName + " from " + uri + (size > 0L ? " [%,d bytes]...".formatted(size) : "..."));
			var response = HTTP_CLIENT.send(requestBuilderBase.copy().uri(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofInputStream());

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

	private static boolean download(HttpRequest.Builder requestBuilderBase, IDiscoveryPipeline pipeline, Path path, String fileName, long size, String uri, boolean gzip) {
		var actualFileName = fileName.isEmpty() ? path.getFileName().toString() : fileName;

		try {
			LOGGER.info("Downloading " + actualFileName + " from " + uri + (size > 0L ? " [%,d bytes]...".formatted(size) : "..."));
			var response = HTTP_CLIENT.send(requestBuilderBase.copy().uri(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofInputStream());

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

	public static void findMods(Executor executor, IDiscoveryPipeline pipeline) throws Exception {
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

		try (var reader = Files.newBufferedReader(configFile)) {
			config = gson.fromJson(reader, JsonObject.class);
		} catch (Exception ex) {
			pipeline.addIssue(ModLoadingIssue.error("Failed to read Pack Sync config file!").withCause(ex).withAffectedPath(configFile));
			return;
		}

		var localPackSyncDirectory = FMLPaths.GAMEDIR.get().resolve("local").resolve("pack-sync");

		if (Files.notExists(localPackSyncDirectory)) {
			try {
				Files.createDirectories(localPackSyncDirectory);
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to create Pack Sync local directory!").withCause(ex).withAffectedPath(localPackSyncDirectory));
				return;
			}
		}

		var localConfigFile = localPackSyncDirectory.resolve("config.json");

		JsonObject localConfigJson;

		if (Files.notExists(localConfigFile)) {
			localConfigJson = new JsonObject();
		} else {
			try (var reader = Files.newBufferedReader(localConfigFile)) {
				localConfigJson = gson.fromJson(reader, JsonObject.class);
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to read Pack Sync local config file!").withCause(ex).withAffectedPath(localConfigFile));
				return;
			}
		}

		{
			boolean updateLocalConfigJson = false;

			if (!localConfigJson.has("pause_updates")) {
				localConfigJson.addProperty("pause_updates", false);
				updateLocalConfigJson = true;
			}

			if (!localConfigJson.has("disabled_artifacts")) {
				localConfigJson.add("disabled_artifacts", new JsonObject());
				updateLocalConfigJson = true;
			}

			if (localConfigJson.has("ignored_mods")) {
				var obj = localConfigJson.getAsJsonObject("disabled_artifacts");

				for (var mod : localConfigJson.get("ignored_mods").getAsJsonArray()) {
					obj.addProperty(mod.getAsString(), true);
				}

				localConfigJson.remove("ignored_mods");
				updateLocalConfigJson = true;
			}

			if (updateLocalConfigJson) {
				try (var writer = Files.newBufferedWriter(localConfigFile)) {
					gson.toJson(localConfigJson, writer);
				}
			}
		}

		var localRepository = localPackSyncDirectory.resolve("repository");

		if (Files.notExists(localRepository)) {
			try {
				Files.createDirectories(localRepository);
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to create Pack Sync local repository directory!").withCause(ex).withAffectedPath(localRepository));
				return;
			}
		}

		var repositoryEnv = Optional.ofNullable(System.getenv("PACK_SYNC_REPO_DIRECTORY")).orElse("");
		var repository = repositoryEnv.isEmpty() ? Path.of(System.getProperty("user.home")).resolve(".latvian.dev").resolve("pack-sync") : Path.of(repositoryEnv);

		if (Files.notExists(repository) || !Files.isDirectory(repository)) {
			try {
				Files.createDirectories(repository);
			} catch (AccessDeniedException ex) {
				repository = localRepository;
				LOGGER.error("Failed to create Pack Sync repository directory! Switching to local repository directory");
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to create Pack Sync repository directory!").withCause(ex).withAffectedPath(repository));
			}
		}

		var repositoryFiles = new ConcurrentHashMap<String, RepositoryFile>();

		try (var listStream = Stream.concat(Files.walk(repository), Files.walk(localRepository))) {
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

		var api0 = config.get("api").getAsString();

		while (api0.endsWith("/")) {
			api0 = api0.substring(0, api0.length() - 1);
		}

		var api = api0;

		var packCode = config.get("pack_code").getAsString();
		var packId = config.has("pack_id") ? config.get("pack_id").getAsString() : packCode;

		System.setProperty("dev.latvian.mods.packsync.id", packId);
		System.setProperty("dev.latvian.mods.packsync.code", packCode);

		var auth = localConfigJson.has("auth") ? localConfigJson.get("auth").getAsString() : "%PACK_SYNC_TOKEN%";

		while (auth.length() >= 3 && auth.startsWith("%") && auth.endsWith("%")) {
			auth = Optional.ofNullable(System.getenv(auth.substring(1, auth.length() - 1))).orElse("");
		}

		var requestBuilderBase = HttpRequest.newBuilder().timeout(Duration.ofSeconds(60L)).header("User-Agent", "dev.latvian.mods.packsync/1.0");

		if (!auth.isEmpty()) {
			requestBuilderBase.header("Authorization", "Bearer " + auth);
		}

		String sessionId;
		String newVersion;

		try {
			var versionRequest = HTTP_CLIENT.send(requestBuilderBase.copy().uri(URI.create(api + "/version/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8))).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			sessionId = versionRequest.headers().firstValue("X-Pack-Sync-Session-ID").orElse("");
			packId = versionRequest.headers().firstValue("X-Pack-Sync-Pack-ID").orElse(packId);
			System.setProperty("dev.latvian.mods.packsync.id", packId);

			if (versionRequest.statusCode() / 100 != 2) {
				pipeline.addIssue(ModLoadingIssue.warning("Failed to update the modpack with error %d - %s!", versionRequest.statusCode(), versionRequest.body()));
				return;
			}

			newVersion = versionRequest.body().trim();
		} catch (HttpTimeoutException | ConnectException ex) {
			pipeline.addIssue(ModLoadingIssue.warning("Pack Sync update server timed out!").withCause(ex));
			return;
		}

		System.setProperty("dev.latvian.mods.packsync.version", newVersion);

		if (!sessionId.isEmpty()) {
			System.setProperty("dev.latvian.mods.packsync.session", sessionId);
			requestBuilderBase.header("X-Pack-Sync-Session-ID", sessionId);
		}

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				HTTP_CLIENT.send(requestBuilderBase.copy().uri(URI.create(api + "/exit")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
				HTTP_CLIENT.close();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}, "Pack-Sync-Shutdown-Hook"));

		var versionFile = localPackSyncDirectory.resolve("version.json");

		var packVersion = "";
		var modList = new ArrayList<FileInfo>();

		if (Files.exists(versionFile)) {
			try (var reader = Files.newBufferedReader(versionFile)) {
				var versionJson = gson.fromJson(reader, JsonObject.class);
				packVersion = versionJson.has("version") ? versionJson.get("version").getAsString() : "";

				if (versionJson.get("mods") instanceof JsonArray modsJson) {
					for (var entry : modsJson) {
						try {
							modList.add(new FileInfo(entry.getAsJsonObject()));
						} catch (Exception ex) {
							pipeline.addIssue(ModLoadingIssue.error("Pack Sync error loading mod %s!", entry.toString()).withCause(ex));
						}
					}
				}
			} catch (Exception ex) {
				pipeline.addIssue(ModLoadingIssue.error("Failed to read Pack Sync version file!").withCause(ex).withAffectedPath(versionFile));
				return;
			}
		}

		var knownArtifacts = new HashSet<String>();
		var disabledArtifacts = new HashSet<String>();

		for (var e : localConfigJson.get("disabled_artifacts").getAsJsonObject().entrySet()) {
			var key = e.getKey();
			knownArtifacts.add(key);

			if (e.getValue().getAsBoolean()) {
				disabledArtifacts.add(key);
			}
		}

		if (!packVersion.isEmpty() && !checkModsExist(repositoryFiles, modList, disabledArtifacts)) {
			LOGGER.info("Found missing or broken repository files, forcing an update...");
			packVersion = "";
		}

		if (!packVersion.isEmpty() && localConfigJson.get("pause_updates").getAsBoolean()) {
			LOGGER.info("Pack updates are paused ('" + packVersion + "')!");
			loadMods(repositoryFiles, modList, disabledArtifacts, pipeline);
			return;
		}

		if (newVersion.equals(packVersion)) {
			LOGGER.info("Pack is up to date ('" + packVersion + "')!");
			loadMods(repositoryFiles, modList, disabledArtifacts, pipeline);
			return;
		}

		LOGGER.info("Update found! '" + packVersion + "' -> '" + newVersion + "'");

		var requestJson = new JsonObject();
		requestJson.addProperty("pack_version", packVersion);
		requestJson.addProperty("mc_version", FMLLoader.versionInfo().mcVersion());
		requestJson.addProperty("loader_version", FMLLoader.versionInfo().fmlVersion());
		requestJson.addProperty("loader_api_version", FMLLoader.versionInfo().neoForgeVersion());
		requestJson.addProperty("platform", getPlatform());
		requestJson.addProperty("dev", !FMLLoader.isProduction());
		requestJson.addProperty("server", FMLLoader.getDist().isDedicatedServer());

		var supportedFeatures = new JsonArray();
		supportedFeatures.add("gzip");
		supportedFeatures.add("server_list");
		supportedFeatures.add("session");

		requestJson.add("supported_features", supportedFeatures);

		var syncRequest = HTTP_CLIENT.send(requestBuilderBase.copy().uri(URI.create(api + "/sync/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8))).POST(HttpRequest.BodyPublishers.ofString(requestJson.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (syncRequest.statusCode() / 100 != 2) {
			pipeline.addIssue(ModLoadingIssue.warning("Failed to update the modpack with error %d - %s!", syncRequest.statusCode(), syncRequest.body()));
			loadMods(repositoryFiles, modList, disabledArtifacts, pipeline);
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
			modList.clear();

			for (var entry : syncJson.get("mods").getAsJsonArray()) {
				var remoteFile = new RemoteFile(entry.getAsJsonObject());
				var checksum = remoteFile.fileInfo().checksum();
				var filename = remoteFile.fileInfo().filename();

				var repositoryFile = repositoryFiles.get(checksum);

				if (repositoryFile == null || !repositoryFile.fileInfo().equals(remoteFile.fileInfo())) {
					var dir = (remoteFile.local() ? localRepository : repository).resolve(checksum.substring(0, 2));

					if (Files.notExists(dir) || !Files.isDirectory(dir)) {
						try {
							Files.createDirectory(dir);
						} catch (Exception ex) {
							pipeline.addIssue(ModLoadingIssue.error("Failed to create Pack Sync repository directory!").withCause(ex).withAffectedPath(dir));
							return;
						}
					}

					futures.add(CompletableFuture.runAsync(() -> {
						var exti = filename.lastIndexOf('.');
						var ext = exti == -1 ? "" : filename.substring(exti);
						var downloadPath = dir.resolve(checksum + ext);

						if (repositoryFile != null || download(requestBuilderBase, pipeline, downloadPath, filename + " (" + checksum + ")", remoteFile.fileInfo().size(), remoteFile.url(), remoteFile.gzip())) {
							var file = new RepositoryFile(downloadPath, remoteFile.fileInfo());
							repositoryFiles.put(file.fileInfo().checksum(), file);

							var json = new JsonObject();
							file.fileInfo().write(json);

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

				modList.add(remoteFile.fileInfo());
			}

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			futures.clear();
			modList.sort((a, b) -> a.filename().compareToIgnoreCase(b.filename()));
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
							download(requestBuilderBase, pipeline, path, relPath.toString(), file.fileInfo().size(), file.url(), file.gzip());
						}
					}
				}, executor));
			}
		}

		if (syncJson.has("server_icon")) {
			var file = new RemoteFile(syncJson.get("server_icon").getAsJsonObject());

			futures.add(CompletableFuture.runAsync(() -> {
				var path = gameDir.resolve("server-icon.png");

				if (file.replace(path, pipeline)) {
					download(requestBuilderBase, pipeline, path, "server-icon.png", file.fileInfo().size(), file.url(), file.gzip());
				}
			}, executor));
		}

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		futures.clear();

		if (errors.get() > 0) {
			return;
		}

		if (syncJson.has("servers") || syncJson.has("server_list")) {
			futures.add(CompletableFuture.runAsync(() -> {
				var localPath = gameDir.resolve("servers.dat");
				var iconPath = gameDir.resolve("server-icon.png");

				try {
					var icon = Files.exists(iconPath) ? Base64.getEncoder().encodeToString(Files.readAllBytes(iconPath)) : "";
					var localNbt = Files.exists(localPath) ? NBTCompoundTag.read(localPath) : new NBTCompoundTag();
					var localServerList = ServerMapEntry.load(localNbt, "");
					var remoteServerList = new ArrayList<ServerMapEntry>();

					if (syncJson.has("server_list")) {
						for (var entry : syncJson.get("server_list").getAsJsonArray()) {
							remoteServerList.add(new ServerMapEntry(entry.getAsJsonObject(), icon));
						}
					} else {
						var file = new RemoteFile(syncJson.get("servers").getAsJsonObject());

						fetch(requestBuilderBase, pipeline, "servers.dat", file.fileInfo().size(), file.url(), file.gzip(), in -> {
							try {
								var remoteNbt = NBTCompoundTag.readFully(in);
								remoteServerList.addAll(ServerMapEntry.load(remoteNbt, icon));
							} catch (Exception ex) {
								pipeline.addIssue(ModLoadingIssue.error("Failed to fetch remote servers.dat!").withCause(ex));
								errors.incrementAndGet();
							}
						});
					}

					for (var entry : remoteServerList) {
						boolean replaced = false;

						for (int i = 0; i < localServerList.size(); i++) {
							var lentry = localServerList.get(i);

							if (lentry.name().equals(entry.name())) {
								localServerList.set(i, entry);
								replaced = true;
							}
						}

						if (!replaced && !entry.ip().isEmpty()) {
							localServerList.add(entry);
						}
					}

					localServerList.removeIf(e -> e.ip().isEmpty());
					localNbt.put("servers", new NBTList(localServerList.stream().map(ServerMapEntry::toNBT).toList()));
					localNbt.write(localPath);
				} catch (Exception ex) {
					pipeline.addIssue(ModLoadingIssue.error("Failed to update servers.dat!").withCause(ex).withAffectedPath(localPath));
					errors.incrementAndGet();
				}
			}, executor));
		}

		if (errors.get() > 0) {
			return;
		}

		if (syncJson.has("options")) {
			futures.add(CompletableFuture.runAsync(() -> {
				LOGGER.info("Updating options.txt...");
				var path = gameDir.resolve("options.txt");

				try {
					var options = new LinkedHashMap<String, String>();
					boolean changed = false;

					if (Files.exists(path)) {
						for (var line : Files.readAllLines(path)) {
							var parts = line.split(":", 2);

							if (parts.length == 2) {
								options.put(parts[0], parts[1]);
							}
						}
					}

					var arr = syncJson.get("options").getAsJsonArray();

					for (var entry : arr) {
						var json = entry.getAsJsonObject();
						var key = json.get("key").getAsString();
						var value = json.get("value").getAsString();
						var force = json.has("force") && json.get("force").getAsBoolean();

						if (force || !options.containsKey(key)) {
							if (!Objects.equals(options.put(key, value), value)) {
								changed = true;
							}
						}
					}

					if (!options.containsKey("version")) {
						options.putFirst("version", "4325"); // FIXME: Figure out how to get SharedConstants.getCurrentVersion().getDataVersion().getVersion()
						changed = true;
					}

					if (changed) {
						var lines = options.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList();
						Files.write(path, lines);
					}
				} catch (Exception ex) {
					pipeline.addIssue(ModLoadingIssue.warning("Failed to update options.txt!").withCause(ex).withAffectedPath(path));
				}
			}, executor));
		}

		if (syncJson.has("server_properties")) {
			futures.add(CompletableFuture.runAsync(() -> {
				LOGGER.info("Updating server.properties...");
				var path = gameDir.resolve("server.properties");

				try {
					var properties = new Properties();
					boolean changed = false;

					if (Files.exists(path)) {
						try (var in = Files.newInputStream(path)) {
							properties.load(in);
						}
					}

					var arr = syncJson.get("server_properties").getAsJsonArray();

					for (var entry : arr) {
						var json = entry.getAsJsonObject();
						var key = json.get("key").getAsString();
						var value = json.get("value").getAsString();
						var force = json.has("force") && json.get("force").getAsBoolean();

						if (force || !properties.containsKey(key)) {
							if (!Objects.equals(properties.setProperty(key, value), value)) {
								changed = true;
							}
						}
					}

					if (changed) {
						try (var out = Files.newOutputStream(path)) {
							properties.store(out, "Minecraft server properties");
						}
					}
				} catch (Exception ex) {
					pipeline.addIssue(ModLoadingIssue.warning("Failed to update server.properties!").withCause(ex).withAffectedPath(path));
				}
			}, executor));
		}

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		futures.clear();

		try (var writer = Files.newBufferedWriter(versionFile)) {
			var versionJson = new JsonObject();
			versionJson.addProperty("version", newVersion);
			var modsJson = new JsonArray();

			for (var fileInfo : modList) {
				var modJson = new JsonObject();
				fileInfo.write(modJson);
				modsJson.add(modJson);
			}

			versionJson.add("mods", modsJson);
			gson.toJson(versionJson, writer);
		}

		var newKnownArtifacts = new HashSet<>(disabledArtifacts);

		for (var file : modList) {
			var artifact = file.artifact().artifact();

			if (!artifact.isEmpty()) {
				newKnownArtifacts.add(artifact);
			}
		}

		if (!knownArtifacts.equals(newKnownArtifacts)) {
			var obj = new JsonObject();

			for (var key : newKnownArtifacts.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
				obj.addProperty(key, disabledArtifacts.contains(key));
			}

			localConfigJson.add("disabled_artifacts", obj);

			try (var writer = Files.newBufferedWriter(localConfigFile)) {
				gson.toJson(localConfigJson, writer);
			}
		}

		LOGGER.info("Pack updated '" + packVersion + "' -> '" + newVersion + "'!");
		loadMods(repositoryFiles, modList, disabledArtifacts, pipeline);
	}

	private static boolean checkModsExist(Map<String, RepositoryFile> repositoryFiles, List<FileInfo> modList, Set<String> disabledArtifacts) {
		for (var fileInfo : modList) {
			try {
				var artifact = fileInfo.artifact().artifact();

				if (!artifact.isEmpty() && disabledArtifacts.contains(artifact)) {
					continue;
				}

				var repositoryFile = repositoryFiles.get(fileInfo.checksum());

				if (repositoryFile == null) {
					return false;
				}
			} catch (Exception ex) {
				return false;
			}
		}

		return true;
	}

	private static void loadMods(Map<String, RepositoryFile> repositoryFiles, List<FileInfo> modList, Set<String> disabledArtifacts, IDiscoveryPipeline pipeline) {
		var filesToLoad = new ArrayList<RepositoryFile>();

		for (var fileInfo : modList) {
			String filename = fileInfo.filename();

			var artifact = fileInfo.artifact().artifact();

			if (!artifact.isEmpty() && disabledArtifacts.contains(artifact)) {
				LOGGER.info("Skipping artifact '" + filename + "' (" + artifact + ")");
				continue;
			}

			var repositoryFile = repositoryFiles.get(fileInfo.checksum());

			if (repositoryFile != null) {
				filesToLoad.add(repositoryFile);
				LOGGER.info("Loaded mod " + fileInfo);
			} else {
				pipeline.addIssue(ModLoadingIssue.error("Pack Sync mod %s not found!", fileInfo.filename()));
			}
		}

		for (var file : filesToLoad) {
			pipeline.addPath(file.path(), ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ALWAYS);
		}
	}

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		long startTime = System.currentTimeMillis();
		LOGGER.info("Loading Pack Sync...");

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			findMods(executor, pipeline);
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
