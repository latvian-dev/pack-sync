package dev.latvian.mods.packsync;

import com.google.gson.JsonObject;
import net.neoforged.neoforgespi.IIssueReporting;

import java.nio.file.Files;
import java.nio.file.Path;

public record RemoteFile(
	FileInfo fileInfo,
	String url,
	String path,
	boolean lazy,
	boolean gzip,
	boolean local
) {
	public RemoteFile(JsonObject json) {
		this(
			new FileInfo(json),
			json.get("url").getAsString(),
			json.has("path") ? json.get("path").getAsString() : "",
			json.has("lazy") && json.get("lazy").getAsBoolean(),
			json.has("gzip") && json.get("gzip").getAsBoolean(),
			json.has("local") && json.get("local").getAsBoolean()
		);
	}

	public boolean replace(Path path, IIssueReporting issues) {
		return lazy ? Files.notExists(path) : !fileInfo.isEqual(path, issues);
	}
}
