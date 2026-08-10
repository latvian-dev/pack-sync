package dev.latvian.mods.packsync;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.latvian.mods.packsync.repackaged.nbt.NBTByte;
import dev.latvian.mods.packsync.repackaged.nbt.NBTCompoundTag;
import dev.latvian.mods.packsync.repackaged.nbt.NBTList;
import dev.latvian.mods.packsync.repackaged.nbt.NBTString;

import java.util.ArrayList;
import java.util.List;

public record ServerMapEntry(
	String name,
	String ip,
	String icon,
	boolean hidden
) {
	public static List<ServerMapEntry> load(NBTCompoundTag nbt, String defaultIcon) {
		if (nbt.get("servers") instanceof NBTList list) {
			var result = new ArrayList<ServerMapEntry>(list.size());

			for (var entry : list) {
				if (entry instanceof NBTCompoundTag tag && tag.containsKey("name") && tag.containsKey("ip")) {
					result.add(new ServerMapEntry(tag, defaultIcon));
				}
			}

			return result;
		}

		return new ArrayList<>(0);
	}

	public ServerMapEntry(JsonObject json, String defaultIcon) {
		this(
			json.get("name").getAsString(),
			json.get("ip").getAsString(),
			json.get("icon") instanceof JsonPrimitive p && !p.getAsString().isEmpty() ? p.getAsString() : defaultIcon,
			json.has("hidden") && json.get("hidden").getAsBoolean()
		);
	}

	public ServerMapEntry(NBTCompoundTag nbt, String defaultIcon) {
		this(
			nbt.get("name") instanceof NBTString n ? n.value() : "",
			nbt.get("ip") instanceof NBTString n ? n.value() : "",
			nbt.get("icon") instanceof NBTString n && !n.value().isEmpty() ? n.value() : defaultIcon,
			nbt.get("hidden") instanceof NBTByte n && n.value() != 0
		);
	}

	public NBTCompoundTag toNBT() {
		var nbt = new NBTCompoundTag();
		nbt.put("name", NBTString.of(name));
		nbt.put("ip", NBTString.of(ip));

		if (!icon.isEmpty()) {
			nbt.put("icon", NBTString.of(icon));
		}

		if (hidden) {
			nbt.put("hidden", NBTByte.ONE);
		}

		return nbt;
	}
}
