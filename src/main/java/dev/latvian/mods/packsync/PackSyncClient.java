package dev.latvian.mods.packsync;

import com.google.gson.JsonArray;

public class PackSyncClient {
	public static void loadSupportedClientFeatures(JsonArray features) {
		/* Wrong thread. Hmpf.
		var cap = GL.getCapabilities();
		if (cap.GL_NV_mesh_shader &&
			cap.GL_NV_uniform_buffer_unified_memory &&
			cap.GL_NV_vertex_buffer_unified_memory &&
			cap.GL_NV_representative_fragment_test &&
			cap.GL_ARB_sparse_buffer &&
			cap.GL_NV_bindless_multi_draw_indirect) {
			features.add("mesh_shaders");
		}
		 */
	}
}
