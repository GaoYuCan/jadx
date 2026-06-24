package jadx.mcp.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

/**
 * Tiny helper for building {@link JsonSchema} input schemas for MCP tools.
 * <p>
 * The MCP {@link JsonSchema} record accepts a {@code Map<String, Object>} for {@code properties} where each
 * value is itself a JSON-schema fragment (e.g. {@code Map.of("type", "string", "description", "...")}).
 * This builder cuts down on boilerplate while keeping the schemas readable.
 */
public final class SchemaBuilder {

	private final Map<String, Object> properties = new LinkedHashMap<>();
	private final List<String> required = new ArrayList<>();

	public static SchemaBuilder object() {
		return new SchemaBuilder();
	}

	public SchemaBuilder string(String name, String description, boolean required) {
		properties.put(name, Map.of("type", "string", "description", description));
		if (required) {
			this.required.add(name);
		}
		return this;
	}

	public SchemaBuilder integer(String name, String description, boolean required) {
		properties.put(name, Map.of("type", "integer", "description", description));
		if (required) {
			this.required.add(name);
		}
		return this;
	}

	public SchemaBuilder bool(String name, String description, boolean required) {
		properties.put(name, Map.of("type", "boolean", "description", description));
		if (required) {
			this.required.add(name);
		}
		return this;
	}

	public SchemaBuilder enumString(String name, String description, boolean required, String... values) {
		properties.put(name, Map.of(
				"type", "string",
				"description", description,
				"enum", List.of(values)));
		if (required) {
			this.required.add(name);
		}
		return this;
	}

	public SchemaBuilder enumStringArray(String name, String description, boolean required, String... values) {
		properties.put(name, Map.of(
				"type", "array",
				"description", description,
				"items", Map.of("type", "string", "enum", List.of(values))));
		if (required) {
			this.required.add(name);
		}
		return this;
	}

	/**
	 * Add a nested object property. The nested schema is built with another {@link SchemaBuilder} and
	 * supplied via the {@code build} lambda. Useful for grouping related "filter" fields under one key.
	 */
	public SchemaBuilder object(String name, String description, boolean required, java.util.function.Consumer<SchemaBuilder> build) {
		SchemaBuilder nested = new SchemaBuilder();
		build.accept(nested);
		Map<String, Object> fragment = new LinkedHashMap<>();
		fragment.put("type", "object");
		fragment.put("description", description);
		fragment.put("properties", nested.properties);
		if (!nested.required.isEmpty()) {
			fragment.put("required", new ArrayList<>(nested.required));
		}
		fragment.put("additionalProperties", false);
		properties.put(name, fragment);
		if (required) {
			this.required.add(name);
		}
		return this;
	}

	/** Add a string-array property (no enum constraint). */
	public SchemaBuilder stringArray(String name, String description, boolean required) {
		properties.put(name, Map.of(
				"type", "array",
				"description", description,
				"items", Map.of("type", "string")));
		if (required) {
			this.required.add(name);
		}
		return this;
	}

	public JsonSchema build() {
		return new JsonSchema("object", properties, required, false, null, null);
	}

	private SchemaBuilder() {
	}
}
