package dev.latvian.mods.packsync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.latvian.mods.packsync.platform.PackSyncPlatformContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record RemoteFile(
	FileInfo fileInfo,
	String url,
	String path,
	List<String> clonePaths,
	boolean lazy,
	boolean gzip,
	boolean local,
	boolean extract
) {
	public RemoteFile(JsonObject json) {
		this(
			new FileInfo(json),
			json.get("url").getAsString(),
			json.has("path") ? json.get("path").getAsString() : "",
			json.has("clone_paths") ? json.get("clone_paths").getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList() : List.of(),
			json.has("lazy") && json.get("lazy").getAsBoolean(),
			json.has("gzip") && json.get("gzip").getAsBoolean(),
			json.has("local") && json.get("local").getAsBoolean(),
			json.has("extract") && json.get("extract").getAsBoolean()
		);
	}

	public boolean replace(PackSyncPlatformContext context, Path path) {
		return lazy ? Files.notExists(path) : !fileInfo.isEqual(context, path);
	}
}
