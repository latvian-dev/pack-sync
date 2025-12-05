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
	boolean gzip
) {
	public RemoteFile(JsonObject json) {
		this(
			new FileInfo(json),
			json.get("url").getAsString(),
			json.has("path") ? json.get("path").getAsString() : "",
			json.has("lazy") && json.get("lazy").getAsBoolean(),
			json.has("gzip") && json.get("gzip").getAsBoolean()
		);
	}

	public RemoteFile(String filename, long size) {
		this(new FileInfo(filename, size), "", "", false, false);
	}

	public boolean replace(Path path, IIssueReporting issues) {
		return lazy ? Files.notExists(path) : !fileInfo.isEqual(path, issues);
	}
}
