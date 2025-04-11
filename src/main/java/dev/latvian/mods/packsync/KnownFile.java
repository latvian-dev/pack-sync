package dev.latvian.mods.packsync;

import com.google.gson.JsonObject;
import net.neoforged.neoforgespi.IIssueReporting;

import java.nio.file.Files;
import java.nio.file.Path;

public record KnownFile(
	String checksum,
	String filename,
	long size,
	String url,
	String path,
	boolean lazy
) {
	public KnownFile(JsonObject json) {
		this(
			json.get("checksum").getAsString(),
			json.get("filename").getAsString(),
			json.get("size").getAsLong(),
			json.get("url").getAsString(),
			json.has("path") ? json.get("path").getAsString() : "",
			json.has("lazy") && json.get("lazy").getAsBoolean()
		);
	}

	public KnownFile(String filename, long size) {
		this("", filename, size, "", "", false);
	}

	public boolean replace(Path path, IIssueReporting issues) {
		return lazy ? Files.notExists(path) : !checksum.equals(PackSync.checksum(path, issues));
	}
}
