package jadx.mcp.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared Gson instance for tool result serialization. Pretty-printed for readability in the LLM context window.
 */
public final class JsonUtil {

	public static final Gson GSON = new GsonBuilder()
			.serializeNulls()
			.disableHtmlEscaping()
			.create();

	public static final Gson PRETTY = new GsonBuilder()
			.serializeNulls()
			.disableHtmlEscaping()
			.setPrettyPrinting()
			.create();

	public static String toJson(Object obj) {
		return GSON.toJson(obj);
	}

	public static String toPrettyJson(Object obj) {
		return PRETTY.toJson(obj);
	}

	private JsonUtil() {
	}
}
