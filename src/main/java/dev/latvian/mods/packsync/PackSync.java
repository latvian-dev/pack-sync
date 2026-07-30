package dev.latvian.mods.packsync;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.latvian.apps.nbt.NBTCompoundTag;
import dev.latvian.apps.nbt.NBTList;
import dev.latvian.mods.packsync.platform.Issue;
import dev.latvian.mods.packsync.platform.PackSyncPlatformContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

public class PackSync {
	public static final Logger LOGGER = LoggerFactory.getLogger("PackSync");

	public static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(60L))
		.followRedirects(HttpClient.Redirect.ALWAYS)
		.build();

	public static String getPlatform() {
		String s = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
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

	private static void fetch(PackSyncPlatformContext context, HttpRequest.Builder requestBuilderBase, String fileName, long size, String uri, boolean gzip, Consumer<InputStream> callback) {
		try {
			LOGGER.info("Fetching " + fileName + " from " + uri + (size > 0L ? " [%,d bytes]...".formatted(size) : "..."));
			var response = HTTP_CLIENT.send(requestBuilderBase.copy().uri(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() / 100 != 2) {
				context.addIssue(Issue.error("Failed to update %s! Error code %d", fileName, response.statusCode()));
			}

			try (var in = gzip(response.body(), gzip)) {
				callback.accept(in);
			}
		} catch (Exception ex) {
			context.addIssue(Issue.error("Failed to update %s!", fileName).cause(ex));
		}
	}

	private static boolean download(PackSyncPlatformContext context, HttpRequest.Builder requestBuilderBase, Path path, String fileName, long size, URI uri, boolean gzip) {
		var actualFileName = fileName.isEmpty() ? path.getFileName().toString() : fileName;

		try {
			LOGGER.info("Downloading " + actualFileName + " from " + uri + (size > 0L ? " [%,d bytes]...".formatted(size) : "..."));
			var response = HTTP_CLIENT.send(requestBuilderBase.copy().uri(uri).build(), HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() / 100 != 2) {
				context.addIssue(Issue.error("Failed to update %s! Error code %d", actualFileName, response.statusCode()).path(path));
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
			context.addIssue(Issue.error("Failed to update %s!", actualFileName).cause(ex).path(path));
			return false;
		}
	}

	private static boolean delete(PackSyncPlatformContext context, Path path, String fileName) {
		var actualFileName = fileName.isEmpty() ? path.getFileName().toString() : fileName;

		try {
			LOGGER.info("Deleting " + actualFileName + " [%,d bytes]...".formatted(Files.exists(path) ? Files.size(path) : 0L));
			Files.deleteIfExists(path);
			return true;
		} catch (Exception ex) {
			context.addIssue(Issue.error("Failed to delete %s!", actualFileName).cause(ex).path(path));
			return false;
		}
	}

	public static void load(PackSyncPlatformContext context) {
		long startTime = System.currentTimeMillis();
		PackSync.LOGGER.info("Loading Pack Sync...");

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			PackSync.findMods(context, executor);
		} catch (HttpTimeoutException ex) {
			context.addIssue(Issue.warning("Pack Sync update server timed out!").cause(ex));
		} catch (Exception ex) {
			context.addIssue(Issue.error("Pack Sync Crashed!").cause(ex));
		}

		var now = System.currentTimeMillis();
		PackSync.LOGGER.info("Finished loading Pack Sync in " + (now - startTime) + " ms!");
	}

	public static void findMods(PackSyncPlatformContext context, Executor executor) throws Exception {
		var errors = new AtomicInteger(0);
		var gameDir = context.getGameDirectory();
		long startTime = System.currentTimeMillis();
		var gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
		var futures = new ArrayList<CompletableFuture<Void>>();

		var configFile = context.getModsDirectory().resolve("pack-sync.json");

		if (Files.notExists(configFile)) {
			context.addIssue(Issue.error("Pack Sync config file not found!").path(configFile));
			return;
		}

		JsonObject config;

		try (var reader = Files.newBufferedReader(configFile)) {
			config = gson.fromJson(reader, JsonObject.class);
		} catch (Exception ex) {
			context.addIssue(Issue.error("Failed to read Pack Sync config file!").cause(ex).path(configFile));
			return;
		}

		var localPackSyncDirectory = context.getLocalDirectory().resolve("pack-sync");

		if (Files.notExists(localPackSyncDirectory)) {
			try {
				Files.createDirectories(localPackSyncDirectory);
			} catch (Exception ex) {
				context.addIssue(Issue.error("Failed to create Pack Sync local directory!").cause(ex).path(localPackSyncDirectory));
				return;
			}
		}

		boolean saveLocalConfig = false;
		boolean pauseUpdates = false;
		var knownArtifacts = new HashSet<String>();
		var disabledArtifacts = new HashSet<String>();

		var oldLocalConfigFile = localPackSyncDirectory.resolve("config.json");
		var localConfigFile = localPackSyncDirectory.resolve("config.yml");

		if (Files.exists(localConfigFile)) {
			try {
				var map = new LinkedHashMap<String, String>();

				for (var line : Files.readAllLines(localConfigFile)) {
					if (line.isEmpty() || line.startsWith("#")) {
						continue;
					}

					var l = line.split(":", 2);

					if (l.length == 2) {
						map.putFirst(l[0].trim(), l[1].trim());
					}
				}

				pauseUpdates = "true".equals(map.remove("pause_updates"));

				for (var e : map.entrySet()) {
					var artifact = e.getKey();
					knownArtifacts.add(artifact);

					if (!"true".equals(e.getValue())) {
						disabledArtifacts.add(artifact);
					}
				}
			} catch (Exception ex) {
				context.addIssue(Issue.error("Failed to read Pack Sync local config file!").cause(ex).path(localConfigFile));
				return;
			}
		} else {
			saveLocalConfig = true;

			if (Files.exists(oldLocalConfigFile)) {
				try (var reader = Files.newBufferedReader(oldLocalConfigFile)) {
					var json = gson.fromJson(reader, JsonObject.class);
					pauseUpdates = json.has("pause_updates") && json.get("pause_updates").getAsBoolean();

					if (json.has("ignored_mods")) {
						for (var mod : json.get("ignored_mods").getAsJsonArray()) {
							knownArtifacts.add(mod.getAsString());
							disabledArtifacts.add(mod.getAsString());
						}
					} else if (json.has("disabled_artifacts")) {
						for (var entry : json.get("disabled_artifacts").getAsJsonObject().entrySet()) {
							knownArtifacts.add(entry.getKey());

							if (entry.getValue().getAsBoolean()) {
								disabledArtifacts.add(entry.getKey());
							}
						}
					}
				} catch (Exception ex) {
					context.addIssue(Issue.error("Failed to read Pack Sync local config file!").cause(ex).path(oldLocalConfigFile));
					return;
				}
			}
		}

		var localRepository = localPackSyncDirectory.resolve("repository");

		if (Files.notExists(localRepository)) {
			try {
				Files.createDirectories(localRepository);
			} catch (Exception ex) {
				context.addIssue(Issue.error("Failed to create Pack Sync local repository directory!").cause(ex).path(localRepository));
				return;
			}
		}

		var platform = getPlatform();
		var userHome = platform.equals("windows") ? System.getenv("APPDATA") : System.getProperty("user.home");
		var repositoryEnv = Optional.ofNullable(System.getenv("PACK_SYNC_REPO_DIRECTORY")).orElse("");

		var repository = repositoryEnv.isEmpty() ? Path.of(userHome).resolve("latvian.dev").resolve("pack-sync") : Path.of(repositoryEnv);

		if (Files.notExists(repository) || !Files.isDirectory(repository)) {
			try {
				Files.createDirectories(repository);
			} catch (AccessDeniedException ex) {
				repository = localRepository;
				LOGGER.error("Failed to create Pack Sync repository directory! Switching to local repository directory");
			} catch (Exception ex) {
				context.addIssue(Issue.error("Failed to create Pack Sync repository directory!").cause(ex).path(repository));
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
								context.addIssue(Issue.warning("Failed to load metadata file of Pack Sync repository file %s!", filename).path(metaPath));
							}
						} catch (Exception ex) {
							context.addIssue(Issue.warning("Failed to load Pack Sync repository file %s!", filename).cause(ex).path(file));
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

		var api = URI.create(api0);

		var packCode = config.has("pack_code") ? config.get("pack_code").getAsString() : "";
		var packId = config.has("pack_id") ? config.get("pack_id").getAsString() : packCode;

		System.setProperty("dev.latvian.mods.packsync.id", packId);
		System.setProperty("dev.latvian.mods.packsync.code", packCode);

		var packToken = config.has("pack_token") ? config.get("pack_token").getAsString() : "";

		var requestBuilderBase = HttpRequest.newBuilder().timeout(Duration.ofSeconds(60L)).header("User-Agent", "dev.latvian.mods.packsync/1.0");

		if (!packToken.isEmpty()) {
			requestBuilderBase.header("Authorization", "Bearer " + packToken);
		}

		String sessionId;
		String newVersion;

		try {
			var versionUri = packToken.isEmpty() ? api.resolve("version/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8)) : api.resolve("version");
			var versionRequest = HTTP_CLIENT.send(requestBuilderBase.copy().uri(versionUri).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			sessionId = versionRequest.headers().firstValue("X-Pack-Sync-Session-ID").orElse("");
			packId = versionRequest.headers().firstValue("X-Pack-Sync-Pack-ID").orElse(packId);
			packCode = versionRequest.headers().firstValue("X-Pack-Sync-Pack-Code").orElse(packCode);
			System.setProperty("dev.latvian.mods.packsync.id", packId);
			System.setProperty("dev.latvian.mods.packsync.code", packCode);

			if (versionRequest.statusCode() / 100 != 2) {
				context.addIssue(Issue.warning("Failed to update the modpack with error %d - %s!", versionRequest.statusCode(), versionRequest.body()));
				return;
			}

			newVersion = versionRequest.body().trim();
		} catch (HttpTimeoutException | ConnectException ex) {
			context.addIssue(Issue.warning("Pack Sync update server timed out!").cause(ex));
			return;
		}

		System.setProperty("dev.latvian.mods.packsync.version", newVersion);

		if (!sessionId.isEmpty()) {
			System.setProperty("dev.latvian.mods.packsync.session", sessionId);
			requestBuilderBase.header("X-Pack-Sync-Session-ID", sessionId);
		}

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
							context.addIssue(Issue.error("Pack Sync error loading mod %s!", entry.toString()).cause(ex));
						}
					}
				}
			} catch (Exception ex) {
				context.addIssue(Issue.error("Failed to read Pack Sync version file!").cause(ex).path(versionFile));
				return;
			}
		}

		if (!packVersion.isEmpty() && !checkModsExist(repositoryFiles, modList, disabledArtifacts)) {
			LOGGER.info("Found missing or broken repository files, forcing an update...");
			packVersion = "";
		}

		if (!packVersion.isEmpty() && pauseUpdates) {
			LOGGER.info("Pack updates are paused ('" + packVersion + "')!");
			loadMods(context, repositoryFiles, modList, disabledArtifacts);
			return;
		}

		if (newVersion.equals(packVersion)) {
			LOGGER.info("Pack is up to date ('" + packVersion + "')!");
			loadMods(context, repositoryFiles, modList, disabledArtifacts);
			return;
		}

		LOGGER.info("Update found! '" + packVersion + "' -> '" + newVersion + "'");

		boolean isServer = context.isServer();

		var requestJson = new JsonObject();
		requestJson.addProperty("pack_version", packVersion);
		requestJson.addProperty("mc_version", context.getMinecraftVersion());
		requestJson.addProperty("loader_version", context.getLoaderVersion());
		requestJson.addProperty("platform", platform);
		requestJson.addProperty("platform_arch", System.getProperty("os.arch", ""));
		requestJson.addProperty("platform_version", System.getProperty("os.version", ""));
		requestJson.addProperty("dev", context.isDev());
		requestJson.addProperty("server", isServer);

		var supportedFeatures = new JsonArray();
		supportedFeatures.add("gzip");
		supportedFeatures.add("server_list");
		supportedFeatures.add("session");

		if (!isServer) {
			loadSupportedClientFeatures(supportedFeatures);
		}

		requestJson.add("supported_features", supportedFeatures);

		var syncUri = packToken.isEmpty() ? api.resolve("sync/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8)) : api.resolve("sync");
		var syncRequest = HTTP_CLIENT.send(requestBuilderBase.copy().uri(syncUri).POST(HttpRequest.BodyPublishers.ofString(requestJson.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (syncRequest.statusCode() / 100 != 2) {
			context.addIssue(Issue.warning("Failed to update the modpack with error %d - %s!", syncRequest.statusCode(), syncRequest.body()));
			loadMods(context, repositoryFiles, modList, disabledArtifacts);
			return;
		}

		var syncJson = gson.fromJson(syncRequest.body(), JsonObject.class);

		if (syncJson.has("warnings")) {
			for (var entry : syncJson.get("warnings").getAsJsonArray()) {
				context.addIssue(Issue.warning(entry.getAsString()));
			}
		}

		if (syncJson.has("errors")) {
			for (var entry : syncJson.get("errors").getAsJsonArray()) {
				context.addIssue(Issue.error(entry.getAsString()));
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
							context.addIssue(Issue.error("Failed to create Pack Sync repository directory!").cause(ex).path(dir));
							return;
						}
					}

					futures.add(CompletableFuture.runAsync(() -> {
						var exti = filename.lastIndexOf('.');
						var ext = exti == -1 ? "" : filename.substring(exti);
						var downloadPath = dir.resolve(checksum + ext);

						if (repositoryFile != null || download(context, requestBuilderBase, downloadPath, filename + " (" + checksum + ")", remoteFile.fileInfo().size(), api.resolve(remoteFile.url()), remoteFile.gzip())) {
							var file = new RepositoryFile(downloadPath, remoteFile.fileInfo());
							repositoryFiles.put(file.fileInfo().checksum(), file);

							var json = new JsonObject();
							file.fileInfo().write(json);

							var metaPath = downloadPath.resolveSibling(checksum + ".meta.json");

							try (var writer = Files.newBufferedWriter(metaPath)) {
								gson.toJson(json, writer);
							} catch (Exception ex) {
								context.addIssue(Issue.error("Failed to save Pack Sync file %s metadata!", filename).cause(ex).path(metaPath));
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
						context.addIssue(Issue.error("Pack Sync attempted to update file outside game directory!").path(path));
						errors.incrementAndGet();
					} else if (file.replace(context, path)) {
						var relPath = gameDir.relativize(path);

						if (file.fileInfo().size() == 0L && file.fileInfo().filename().equals("deleted")) {
							delete(context, path, relPath.toString());
						} else {
							download(context, requestBuilderBase, path, relPath.toString(), file.fileInfo().size(), api.resolve(file.url()), file.gzip());
						}
					}
				}, executor));
			}
		}

		if (syncJson.has("server_icon")) {
			var file = new RemoteFile(syncJson.get("server_icon").getAsJsonObject());

			futures.add(CompletableFuture.runAsync(() -> {
				var path = gameDir.resolve("server-icon.png");

				if (file.replace(context, path)) {
					download(context, requestBuilderBase, path, "server-icon.png", file.fileInfo().size(), api.resolve(file.url()), file.gzip());
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

						fetch(context, requestBuilderBase, "servers.dat", file.fileInfo().size(), file.url(), file.gzip(), in -> {
							try {
								var remoteNbt = NBTCompoundTag.readFully(in);
								remoteServerList.addAll(ServerMapEntry.load(remoteNbt, icon));
							} catch (Exception ex) {
								context.addIssue(Issue.error("Failed to fetch remote servers.dat!").cause(ex));
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
					context.addIssue(Issue.error("Failed to update servers.dat!").cause(ex).path(localPath));
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
						options.putFirst("version", context.getDataVersion());
						changed = true;
					}

					if (changed) {
						var lines = options.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList();
						Files.write(path, lines);
					}
				} catch (Exception ex) {
					context.addIssue(Issue.warning("Failed to update options.txt!").cause(ex).path(path));
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
					context.addIssue(Issue.warning("Failed to update server.properties!").cause(ex).path(path));
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
			saveLocalConfig = true;
		}

		if (saveLocalConfig) {
			var list = newKnownArtifacts.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
			int len = "pause_updates".length();

			for (var key : list) {
				len = Math.max(len, key.length());
			}

			var result = new ArrayList<String>();
			result.add("# Config");
			result.add("pause_updates: " + " ".repeat(Math.max(0, len - "pause_updates".length())) + pauseUpdates);
			result.add("# Enabled Artifacts");

			for (var artifact : list) {
				result.add(artifact + ": " + " ".repeat(Math.max(0, len - artifact.length())) + !disabledArtifacts.contains(artifact));
			}

			Files.write(localConfigFile, result);
			Files.deleteIfExists(oldLocalConfigFile);
		}

		LOGGER.info("Pack updated '" + packVersion + "' -> '" + newVersion + "'!");
		loadMods(context, repositoryFiles, modList, disabledArtifacts);
	}

	private static void loadSupportedClientFeatures(JsonArray features) {
		PackSyncClient.loadSupportedClientFeatures(features);
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

	private static void loadMods(PackSyncPlatformContext context, Map<String, RepositoryFile> repositoryFiles, List<FileInfo> modList, Set<String> disabledArtifacts) {
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
				context.addIssue(Issue.error("Pack Sync mod %s not found!", fileInfo.filename()));
			}
		}

		for (var file : filesToLoad) {
			context.addPath(file.path());
		}
	}
}
