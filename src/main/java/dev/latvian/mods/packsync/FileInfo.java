package dev.latvian.mods.packsync;

import com.google.gson.JsonObject;
import net.neoforged.neoforgespi.IIssueReporting;

import java.nio.file.Path;

public record FileInfo(
	String checksum,
	String filename,
	long size,
	String artifact,
	String version
) {
	public FileInfo(JsonObject json) {
		this(
			json.get("checksum").getAsString(),
			json.get("filename").getAsString(),
			json.get("size").getAsLong(),
			json.has("artifact") ? json.get("artifact").getAsString() : "",
			json.has("version") ? json.get("version").getAsString() : ""
		);
	}

	public FileInfo(String filename, long size) {
		this("", filename, size, "", "");
	}

	public boolean isEqual(Path path, IIssueReporting issues) {
		return size == PackSync.size(path) && checksum.equals(Checksum.md5(path, issues));
	}

	public void toJson(JsonObject json) {
		json.addProperty("checksum", checksum);
		json.addProperty("filename", filename);
		json.addProperty("size", size);

		if (!artifact.isEmpty()) {
			json.addProperty("artifact", artifact);
		}

		if (!version.isEmpty()) {
			json.addProperty("version", version);
		}
	}
}
