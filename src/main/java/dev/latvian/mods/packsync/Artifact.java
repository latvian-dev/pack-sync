package dev.latvian.mods.packsync;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

public record Artifact(String artifact, String version) {
	public static Artifact NONE = new Artifact("", "");

	public static Artifact of(JsonObject json) {
		var a = json.has("artifact") ? json.get("artifact").getAsString() : "";
		var v = json.has("version") ? json.get("version").getAsString() : "";
		return a.isEmpty() && v.isEmpty() ? NONE : new Artifact(a, v);
	}

	public void write(JsonObject json) {
		if (!artifact.isEmpty()) {
			json.addProperty("artifact", artifact);
		}

		if (!version.isEmpty()) {
			json.addProperty("version", version);
		}
	}

	@Override
	public @NotNull String toString() {
		if (artifact.isEmpty() && version.isEmpty()) {
			return "no-artifact";
		}

		return artifact + ":" + version;
	}
}
