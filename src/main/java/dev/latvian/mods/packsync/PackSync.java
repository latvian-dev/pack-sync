package dev.latvian.mods.packsync;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.moddiscovery.locators.ModsFolderLocator;
import net.neoforged.neoforgespi.IIssueReporting;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.math.BigInteger;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class PackSync extends ModsFolderLocator {
	private static final Logger LOGGER = LogUtils.getLogger();

	public PackSync() {
		super(FMLPaths.GAMEDIR.get().resolve("pack-sync"), "pack-sync");
	}

	public static String checksum(Path path, IIssueReporting issues) {
		if (Files.notExists(path)) {
			return "";
		}

		try (var in = new BufferedInputStream(Files.newInputStream(path))) {
			var md = MessageDigest.getInstance("SHA-512");
			var tempBuffer = new byte[32768];

			while (true) {
				int len = in.read(tempBuffer);

				if (len >= 0) {
					md.update(tempBuffer, 0, len);
				} else {
					break;
				}
			}

			return "%0128x".formatted(new BigInteger(1, md.digest()));
		} catch (Exception ex) {
			issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Failed to read checksum of file %s!", List.of(path.getFileName().toString()), ex, path, null, null));
		}

		return "";
	}

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

	private boolean download(HttpClient httpClient, HttpRequest.Builder requestBuilderBase, Path path, String fileName, long size, String uri, IIssueReporting issues) {
		var actualFileName = fileName.isEmpty() ? path.getFileName().toString() : fileName;

		try {
			LOGGER.info("Downloading " + actualFileName + " from " + uri + (size > 0L ? "[%,d bytes]...".formatted(size) : "..."));
			var response = httpClient.send(requestBuilderBase.copy().uri(URI.create(uri)).build(), HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() / 100 != 2) {
				issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Failed to update %s! Error code %d", List.of(actualFileName, response.statusCode()), null, path, null, null));
				return false;
			}

			var parent = path.getParent();

			if (Files.notExists(parent)) {
				Files.createDirectories(parent);
			}

			try (var in = new BufferedInputStream(response.body()); var out = new BufferedOutputStream(Files.newOutputStream(path))) {
				in.transferTo(out);
				return true;
			}
		} catch (Exception ex) {
			issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Failed to update %s!", List.of(actualFileName), ex, path, null, null));
			return false;
		}
	}

	private boolean delete(Path path, String fileName, IIssueReporting issues) {
		var actualFileName = fileName.isEmpty() ? path.getFileName().toString() : fileName;

		try {
			LOGGER.info("Deleting " + actualFileName + " [%,d bytes]...".formatted(Files.exists(path) ? Files.size(path) : 0L));
			Files.deleteIfExists(path);
			return true;
		} catch (Exception ex) {
			issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Failed to delete %s!", List.of(actualFileName), ex, path, null, null));
			return false;
		}
	}

	public void loadMods(Executor executor, HttpClient httpClient, IIssueReporting issues) throws Exception {
		var gameDir = FMLPaths.GAMEDIR.get();
		long startTime = System.currentTimeMillis();
		LOGGER.info("Checking for Pack Sync updates...'");

		var packVersion = "";

		var versionFile = gameDir.resolve("pack-sync-version.txt");

		if (Files.exists(versionFile)) {
			packVersion = Files.readString(versionFile).trim();
		}

		var configFile = FMLPaths.MODSDIR.get().resolve("pack-sync.json");

		if (Files.notExists(configFile)) {
			issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Pack Sync config file not found!", List.of()));
			return;
		}

		var gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

		JsonObject config;

		try (var configReader = Files.newBufferedReader(configFile)) {
			config = gson.fromJson(configReader, JsonObject.class);
		} catch (Exception ex) {
			issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Failed to read Pack Sync config file!", List.of(), ex, null, null, null));
			return;
		}

		var api = config.get("api").getAsString();

		while (api.endsWith("/")) {
			api = api.substring(0, api.length() - 1);
		}

		var packCode = config.get("pack_code").getAsString();

		var requestBuilderBase = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30L)).header("User-Agent", "dev.latvian.mods.packsync/1.0");

		var token = System.getenv().getOrDefault("PACK_SYNC_TOKEN", "");

		if (!token.isEmpty()) {
			requestBuilderBase.header("Authorization", "Bearer " + token);
		}

		String newVersion;

		try {
			var versionRequest = httpClient.send(requestBuilderBase.copy().uri(URI.create(api + "/version/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8))).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (versionRequest.statusCode() / 100 != 2) {
				issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, "Failed to update the modpack with error %d - %s!", List.of(versionRequest.statusCode(), versionRequest.body())));
				return;
			}

			newVersion = versionRequest.body().trim();
		} catch (HttpTimeoutException | ConnectException ex) {
			issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, "Pack Sync update server timed out!", List.of(), ex, null, null, null));
			return;
		}

		if (newVersion.equals(packVersion)) {
			LOGGER.info("Pack is up to date ('" + packVersion + "')!");
			return;
		}

		LOGGER.info("Update found! '" + packVersion + "' -> '" + newVersion + "'");

		var modDir = gameDir.resolve("pack-sync");

		if (Files.notExists(modDir)) {
			Files.createDirectories(modDir);
		}

		var futures = new ArrayList<CompletableFuture<Void>>();

		var requestJson = new JsonObject();
		requestJson.addProperty("pack_version", packVersion);
		requestJson.addProperty("mc_version", FMLLoader.versionInfo().mcVersion());
		requestJson.addProperty("loader_version", FMLLoader.versionInfo().fmlVersion());
		requestJson.addProperty("loader_api_version", FMLLoader.versionInfo().neoForgeVersion());
		requestJson.addProperty("platform", getPlatform());
		requestJson.addProperty("dev", !FMLLoader.isProduction());
		requestJson.addProperty("server", FMLLoader.getDist().isDedicatedServer());

		var syncRequest = httpClient.send(requestBuilderBase.copy().uri(URI.create(api + "/sync/" + URLEncoder.encode(packCode, StandardCharsets.UTF_8))).POST(HttpRequest.BodyPublishers.ofString(requestJson.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (syncRequest.statusCode() / 100 != 2) {
			issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, "Failed to update the modpack with error %d - %s!", List.of(syncRequest.statusCode(), syncRequest.body())));
			return;
		}

		var syncJson = gson.fromJson(syncRequest.body(), JsonObject.class);

		if (syncJson.has("warnings")) {
			for (var entry : syncJson.get("warnings").getAsJsonArray()) {
				issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, entry.getAsString(), List.of()));
			}
		}

		if (syncJson.has("errors")) {
			for (var entry : syncJson.get("errors").getAsJsonArray()) {
				issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, entry.getAsString(), List.of()));
			}

			return;
		}

		if (syncJson.has("mods")) {
			var currentModFutures = new ArrayList<CompletableFuture<Void>>();
			var currentMods = new ConcurrentHashMap<String, Path>();

			try (var stream = Files.list(modDir)) {
				for (var file : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
					currentModFutures.add(CompletableFuture.runAsync(() -> {
						var checksum = checksum(file, issues);

						if (!checksum.isEmpty()) {
							currentMods.put(checksum, file);
						}
					}, executor));
				}
			}

			CompletableFuture.allOf(currentModFutures.toArray(new CompletableFuture[0])).join();

			var newMods = new HashMap<String, KnownFile>();

			for (var entry : syncJson.get("mods").getAsJsonArray()) {
				var file = new KnownFile(entry.getAsJsonObject());
				newMods.put(file.checksum(), file);
			}

			for (var mod : newMods.values()) {
				if (!currentMods.containsKey(mod.checksum())) {
					futures.add(CompletableFuture.runAsync(() -> {
						var filename = mod.checksum() + "-" + mod.filename();
						download(httpClient, requestBuilderBase, modDir.resolve(filename), "pack-sync/" + filename, mod.size(), mod.url(), issues);
					}, executor));
				}
			}

			for (var entry : currentMods.entrySet()) {
				if (!newMods.containsKey(entry.getKey())) {
					futures.add(CompletableFuture.runAsync(() -> delete(entry.getValue(), "pack-sync/" + entry.getValue().getFileName(), issues), executor));
				} else {
					LOGGER.info("Skipping pack-sync/%s".formatted(entry.getValue().getFileName()));
				}
			}
		}

		if (syncJson.has("extra_files")) {
			for (var entry : syncJson.get("extra_files").getAsJsonArray()) {
				var file = new KnownFile(entry.getAsJsonObject());

				futures.add(CompletableFuture.runAsync(() -> {
					var path = gameDir.resolve(file.path());

					if (!path.startsWith(gameDir)) {
						issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Pack Sync attempted to update file outside game directory!", List.of(), null, path, null, null));
					} else if (file.replace(path, issues)) {
						var relPath = gameDir.relativize(path);

						if (file.size() == 0L && file.filename().equals("deleted")) {
							delete(path, relPath.toString(), issues);
						} else {
							download(httpClient, requestBuilderBase, path, relPath.toString(), file.size(), file.url(), issues);
						}
					}
				}, executor));
			}
		}

		if (syncJson.has("servers")) {
			var file = new KnownFile(syncJson.get("servers").getAsJsonObject());

			futures.add(CompletableFuture.runAsync(() -> {
				var localPath = gameDir.resolve("pack-sync-servers.dat");
				var path = gameDir.resolve("servers.dat");

				if (file.replace(localPath, issues) || Files.notExists(path)) {
					if (download(httpClient, requestBuilderBase, localPath, "servers.dat", file.size(), file.url(), issues)) {
						try {
							Files.deleteIfExists(path);
							Files.copy(localPath, path);
						} catch (Exception ex) {
							issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Failed to copy pack-sync-servers.dat to servers.dat!", List.of(), ex, localPath, null, null));
						}
					}
				}
			}, executor));
		}

		if (syncJson.has("server_icon")) {
			var file = new KnownFile(syncJson.get("server_icon").getAsJsonObject());

			futures.add(CompletableFuture.runAsync(() -> {
				var path = gameDir.resolve("server-icon.png");

				if (file.replace(path, issues)) {
					download(httpClient, requestBuilderBase, path, "server-icon.png", file.size(), file.url(), issues);
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
				options.put("version", "4189");
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
						issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, "Failed to update options.txt!", List.of(), ex, path, null, null));
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
						issues.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, "Failed to update server.properties!", List.of(), ex, path, null, null));
					}
				}, executor));
			}
		}

		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		futures.clear();

		Files.writeString(versionFile, newVersion);
		LOGGER.info("Pack updated '" + packVersion + "' -> '" + newVersion + "' in " + (System.currentTimeMillis() - startTime) + " ms!");
	}

	@Override
	public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15L)).followRedirects(HttpClient.Redirect.ALWAYS).build()) {
			loadMods(executor, httpClient, pipeline);
		} catch (HttpTimeoutException ex) {
			pipeline.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.WARNING, "Pack Sync update server timed out!", List.of(), ex, null, null, null));
			return;
		} catch (Exception ex) {
			pipeline.addIssue(new ModLoadingIssue(ModLoadingIssue.Severity.ERROR, "Pack Sync Crashed!", List.of(), ex, null, null, null));
			return;
		}

		super.findCandidates(context, pipeline);
	}
}
