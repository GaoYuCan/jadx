package jadx.mcp.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ICodeInfo;
import jadx.api.ResourceFile;
import jadx.core.xmlgen.ResContainer;
import jadx.mcp.JadxSession;
import jadx.mcp.format.LineNumberPrefixer;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code decompile_xml} tool: returns the decoded XML / textual content of a single resource file.
 * <p>
 * For binary AndroidManifest.xml / *.xml in APKs, jadx provides decoded textual XML through
 * {@link ResContainer#getText()}. For arsc / resource tables, the root container is also a textual dump.
 * Non-text resources (images, fonts, etc.) produce an UNSUPPORTED error with a hint about the resource type.
 */
public final class DecompileXmlTool extends AbstractTool {

	public DecompileXmlTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "decompile_xml";
	}

	@Override
	public String description() {
		return "Decode an Android XML / text resource by path. `resource_path` matches the original or "
				+ "deobfuscated resource entry name (e.g. 'AndroidManifest.xml', 'res/layout/main.xml'). For binary "
				+ "AAPT-encoded XML the result is the decoded text. Returns UNSUPPORTED for non-text resources "
				+ "(images, fonts, etc.).";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("resource_path", "Resource entry path or name, e.g. 'AndroidManifest.xml'.", true)
				.bool("line_numbers", "Add line-number gutter to the returned text. Default true.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String path = requireString(args, "resource_path");
		boolean lineNumbers = optBool(args, "line_numbers", true);
		return session.read(decompiler -> {
			ResourceFile match = null;
			for (ResourceFile rf : decompiler.getResources()) {
				if (rf.getOriginalName().equals(path) || rf.getDeobfName().equals(path)) {
					match = rf;
					break;
				}
			}
			if (match == null) {
				// fall back to suffix match (jadx resource paths sometimes start with archive prefixes)
				for (ResourceFile rf : decompiler.getResources()) {
					if (rf.getOriginalName().endsWith(path) || rf.getDeobfName().endsWith(path)) {
						match = rf;
						break;
					}
				}
			}
			if (match == null) {
				throw ToolException.notFound("resource", path);
			}
			ResContainer container = match.loadContent();
			if (container == null) {
				throw new ToolException(ToolException.Code.UNSUPPORTED,
						"Resource has no decoded content: " + match.getDeobfName());
			}
			if (container.getDataType() != ResContainer.DataType.TEXT
					&& container.getDataType() != ResContainer.DataType.RES_TABLE) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("resource_path", match.getDeobfName());
				details.put("data_type", container.getDataType().name());
				details.put("resource_type", match.getType().name());
				throw new ToolException(ToolException.Code.UNSUPPORTED,
						"Resource is not text: " + match.getDeobfName(), details);
			}
			ICodeInfo text = container.getText();
			String code = text == null ? "" : text.getCodeStr();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("resource_path", match.getDeobfName());
			result.put("resource_type", match.getType().name());
			result.put("code", lineNumbers ? LineNumberPrefixer.prefix(code) : code);
			return result;
		});
	}
}
