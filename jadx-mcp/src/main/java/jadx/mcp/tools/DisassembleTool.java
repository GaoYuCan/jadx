package jadx.mcp.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.mcp.JadxSession;
import jadx.mcp.format.LineNumberPrefixer;
import jadx.mcp.format.SmaliMethodExtractor;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code disassemble} tool: returns smali for a class or for a single method.
 * <p>
 * Smali is a textual representation that already contains FQNs, so there is no RefTable / inline-annotation
 * machinery here -- every reference is already explicit in the text.
 */
public final class DisassembleTool extends AbstractTool {

	public DisassembleTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "disassemble";
	}

	@Override
	public String description() {
		return "Disassemble a class or a single method to smali. `target` follows the same format as `decompile_code` "
				+ "(`com.foo.A` for whole class, `com.foo.A#bar(I)V` for one method; descriptor required to "
				+ "disambiguate overloads). Smali already contains fully qualified type/method/field references, "
				+ "so no separate refs sidecar is provided.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("target", "FQN target: 'com.foo.A' or 'com.foo.A#bar(I)V'.", true)
				.bool("line_numbers", "Add a line-number gutter to the returned smali. Default true.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		Target target = TargetParser.parse(requireString(args, "target"));
		boolean lineNumbers = optBool(args, "line_numbers", true);
		return session.read(decompiler -> {
			JavaClass javaClass = TargetResolver.resolveClass(session, target);
			String classSmali = javaClass.getSmali();
			String code;
			String scope;
			String descriptor = null;
			int firstLine = 1;
			if (target.isClass()) {
				scope = "class";
				code = classSmali;
			} else {
				JavaMethod method = TargetResolver.resolveMethod(session, target);
				String shortId = method.getMethodNode().getMethodInfo().getShortId();
				descriptor = shortId.substring(shortId.indexOf('('));
				String body = SmaliMethodExtractor.extract(classSmali, shortId);
				if (body == null) {
					throw new ToolException(ToolException.Code.INTERNAL,
							"Could not locate '.method " + shortId + "' in smali for " + javaClass.getFullName());
				}
				int hdrLine = SmaliMethodExtractor.findHeaderLine(classSmali, shortId);
				firstLine = hdrLine > 0 ? hdrLine : 1;
				scope = "method";
				code = body;
			}
			if (lineNumbers) {
				code = renumberPrefix(code, firstLine);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("class_fqn", javaClass.getFullName());
			result.put("scope", scope);
			if (descriptor != null) {
				result.put("method_descriptor", descriptor);
			}
			result.put("first_line", firstLine);
			result.put("code", code);
			return result;
		});
	}

	private static String renumberPrefix(String code, int firstLine) {
		if (firstLine == 1) {
			return LineNumberPrefixer.prefix(code);
		}
		// adapt LineNumberPrefixer for an arbitrary starting line
		int totalLines = 1;
		for (int i = 0; i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				totalLines++;
			}
		}
		int width = Math.max(2, Integer.toString(firstLine + totalLines - 1).length());
		StringBuilder out = new StringBuilder(code.length() + totalLines * (width + 3));
		int line = firstLine;
		boolean lineStart = true;
		for (int i = 0; i <= code.length(); i++) {
			if (lineStart) {
				String s = Integer.toString(line);
				for (int p = s.length(); p < width; p++) {
					out.append(' ');
				}
				out.append(s).append("| ");
				lineStart = false;
			}
			if (i == code.length()) {
				break;
			}
			char c = code.charAt(i);
			out.append(c);
			if (c == '\n') {
				line++;
				lineStart = true;
			}
		}
		return out.toString();
	}
}
