package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.api.utils.CodeUtils;
import jadx.core.dex.nodes.MethodNode;
import jadx.mcp.JadxSession;
import jadx.mcp.format.InlineRefAnnotator;
import jadx.mcp.format.LineNumberPrefixer;
import jadx.mcp.format.RefEntry;
import jadx.mcp.format.RefTable;
import jadx.mcp.format.RefTableBuilder;
import jadx.mcp.format.VariableEntry;
import jadx.mcp.format.VariableTableBuilder;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code decompile_code} tool: returns Java source for a class or a single method, plus a sidecar
 * refs table.
 * <p>
 * See {@link jadx.mcp.format.RefTableBuilder} for the symbol-resolution model and
 * {@link jadx.mcp.format.InlineRefAnnotator} for the optional inline annotation format.
 */
public final class DecompileCodeTool extends AbstractTool {

	public DecompileCodeTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "decompile_code";
	}

	@Override
	public String description() {
		return "Decompile a class or a method to Java source. `target` is `com.foo.A` for a class or "
				+ "`com.foo.A#bar` / `com.foo.A#bar(I)V` for a method (descriptor optional; ambiguous overloads "
				+ "produce an AMBIGUOUS error listing all candidates). When `annotate` is `sidecar` (default) the "
				+ "raw decompiled source is returned with a fixed-width line-number gutter and a separate `refs` "
				+ "array; each ref carries a stable `ref_id` you pass to `resolve_ref` (no need to count lines or "
				+ "columns). When `annotate` is `inline` or `both`, the source itself is decorated with "
				+ "`/*->Target#Rxx*/` block comments pinned to each call so deeply nested calls remain "
				+ "unambiguous. `annotate=off` returns just the source. The line numbers in `code` and in `refs` "
				+ "use the same coordinate space as `search_code` and `xrefs_to`. Set `include_variables=true` "
				+ "to receive a `variables` sidecar containing precise `variable_id` handles for the `rename` tool.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("target", "FQN target: 'com.foo.A' (class) or 'com.foo.A#bar' / 'com.foo.A#bar(I)V' (method).", true)
				.enumString("annotate", "How to surface symbol references. Default 'sidecar'.", false,
						"off", "sidecar", "inline", "both")
				.bool("line_numbers", "Add a line-number gutter to `code`. Default true.", false)
				.bool("include_variables", "Include renameable variable handles in a `variables` sidecar. Default false.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		Target target = TargetParser.parse(requireString(args, "target"));
		String annotate = optString(args, "annotate", "sidecar");
		boolean lineNumbers = optBool(args, "line_numbers", true);
		boolean includeVariables = optBool(args, "include_variables", false);

		return session.read(decompiler -> {
			JavaClass javaClass = TargetResolver.resolveClass(session, target);
			ICodeInfo codeInfo = javaClass.getCodeInfo();
			String classCode = codeInfo.getCodeStr();

			// build (or fetch cached) RefTable for the entire class -- ref_ids are class-scoped & stable
			boolean wantRefs = !"off".equals(annotate);
			RefTable refTable = null;
			if (wantRefs) {
				refTable = session.refCache().getIfFresh(javaClass.getFullName(), codeInfo);
				if (refTable == null) {
					refTable = RefTableBuilder.build(decompiler, javaClass);
					session.refCache().put(javaClass.getFullName(), codeInfo, refTable);
				}
			}

			// figure out which slice to return (whole class or method-only)
			Slice slice;
			if (target.isClass()) {
				slice = new Slice("class", null, classCode, 1);
			} else {
				JavaMethod method = TargetResolver.resolveMethod(session, target);
				slice = sliceMethod(javaClass, method, codeInfo, classCode);
			}

			// project refs onto the slice (line filter); ref_ids stay class-scoped
			List<RefEntry> sliceRefs;
			if (refTable == null) {
				sliceRefs = List.of();
			} else if (target.isClass()) {
				sliceRefs = refTable.entries();
			} else {
				int endLine = slice.firstLine + countLines(slice.rawCode) - 1;
				sliceRefs = new ArrayList<>();
				for (RefEntry e : refTable.entries()) {
					if (e.line() >= slice.firstLine && e.line() <= endLine) {
						sliceRefs.add(e);
					}
				}
			}

			List<VariableEntry> sliceVariables = List.of();
			if (includeVariables) {
				List<VariableEntry> allVariables = VariableTableBuilder.build(decompiler, javaClass);
				if (target.isClass()) {
					sliceVariables = allVariables;
				} else {
					int endLine = slice.firstLine + countLines(slice.rawCode) - 1;
					sliceVariables = new ArrayList<>();
					for (VariableEntry variable : allVariables) {
						if (variable.line() >= slice.firstLine && variable.line() <= endLine) {
							sliceVariables.add(variable);
						}
					}
				}
			}

			// shape the rendered code
			String code = slice.rawCode;
			boolean wantInline = "inline".equals(annotate) || "both".equals(annotate);
			if (wantInline && !sliceRefs.isEmpty()) {
				if (target.isClass()) {
					code = InlineRefAnnotator.annotate(classCode, javaClass.getFullName(), sliceRefs);
				} else {
					// annotate the slice in isolation: shift entries' defPos relative to slice start
					int sliceOffset = slice.startOffsetInClass;
					List<RefEntry> shifted = new ArrayList<>(sliceRefs.size());
					for (RefEntry e : sliceRefs) {
						shifted.add(new RefEntry(e.refId(), e.line(), e.col(),
								e.defPos() - sliceOffset, e.kind(),
								e.targetFqn(), e.targetMember(), e.targetDescriptor(), e.snippet()));
					}
					code = InlineRefAnnotator.annotate(slice.rawCode, javaClass.getFullName(), shifted);
				}
			}
			if (lineNumbers) {
				if (slice.firstLine == 1) {
					code = LineNumberPrefixer.prefix(code);
				} else {
					code = renumberPrefix(code, slice.firstLine);
				}
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("class_fqn", javaClass.getFullName());
			result.put("scope", slice.scope);
			if (slice.methodDescriptor != null) {
				result.put("method_descriptor", slice.methodDescriptor);
			}
			result.put("first_line", slice.firstLine);
			result.put("annotate", annotate);
			result.put("code", code);
			if (wantRefs && !"inline".equals(annotate)) {
				result.put("refs", refsAsMaps(sliceRefs));
			} else if (wantRefs && "both".equals(annotate)) {
				result.put("refs", refsAsMaps(sliceRefs));
			}
			if (includeVariables) {
				result.put("variables", variablesAsMaps(sliceVariables));
			}
			return result;
		});
	}

	// ---------- internal helpers ----------

	private static List<Map<String, Object>> refsAsMaps(List<RefEntry> refs) {
		List<Map<String, Object>> out = new ArrayList<>(refs.size());
		for (RefEntry e : refs) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("ref_id", e.refId());
			m.put("line", e.line());
			m.put("col", e.col());
			m.put("kind", e.kind().name());
			m.put("target_fqn", e.targetFqn());
			if (e.targetMember() != null) {
				m.put("target_member", e.targetMember());
			}
			if (e.targetDescriptor() != null) {
				m.put("target_descriptor", e.targetDescriptor());
			}
			m.put("snippet", e.snippet());
			out.add(m);
		}
		return out;
	}

	private static List<Map<String, Object>> variablesAsMaps(List<VariableEntry> variables) {
		List<Map<String, Object>> out = new ArrayList<>(variables.size());
		for (VariableEntry variable : variables) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("variable_id", variable.variableId());
			row.put("name", variable.name());
			row.put("type", variable.type());
			row.put("line", variable.line());
			row.put("col", variable.col());
			row.put("method_target", variable.methodTarget());
			out.add(row);
		}
		return out;
	}

	private static Slice sliceMethod(JavaClass javaClass, JavaMethod method, ICodeInfo codeInfo, String classCode) {
		MethodNode mthNode = method.getMethodNode();
		int startInClass = methodStartInClass(mthNode, codeInfo);
		int endInClass = CodeUtils.getMethodEnd(mthNode, codeInfo);
		if (startInClass < 0 || endInClass < 0 || endInClass < startInClass) {
			throw new ToolException(ToolException.Code.INTERNAL,
					"Could not slice method body for " + javaClass.getFullName() + "#" + method.getName()
							+ " (no metadata)");
		}
		// extend end to the end of its line so the ".}" closing brace is included
		int lineEnd = CodeUtils.getLineEndForPos(classCode, endInClass);
		String body = classCode.substring(startInClass, lineEnd);
		int firstLine = lineNumberAt(classCode, startInClass);
		String shortId = mthNode.getMethodInfo().getShortId();
		String descriptor = shortId.substring(shortId.indexOf('('));
		Slice s = new Slice("method", descriptor, body, firstLine);
		s.startOffsetInClass = startInClass;
		return s;
	}

	private static int methodStartInClass(MethodNode mth, ICodeInfo codeInfo) {
		int defPos = mth.getDefPosition();
		if (defPos <= 0) {
			return -1;
		}
		// walk up annotations to find the enclosing DECLARATION for this method (covers
		// comments/annotations)
		ICodeAnnotation start = codeInfo.getCodeMetadata().searchUp(defPos - 1, (pos, ann) -> {
			if (ann.getAnnType() == ICodeAnnotation.AnnType.DECLARATION) {
				ICodeNodeRef inner = ((NodeDeclareRef) ann).getNode();
				if (inner == mth) {
					return ann;
				}
			}
			return null;
		});
		if (start instanceof NodeDeclareRef ref) {
			return ref.getDefPos();
		}
		return defPos;
	}

	private static int lineNumberAt(String code, int pos) {
		int line = 1;
		for (int i = 0; i < pos && i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static int countLines(String code) {
		int n = 1;
		for (int i = 0; i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				n++;
			}
		}
		return n;
	}

	private static String renumberPrefix(String code, int firstLine) {
		// re-implements LineNumberPrefixer.prefix() but starts numbering at `firstLine`
		int totalLines = countLines(code);
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

	private static final class Slice {
		final String scope;
		final String methodDescriptor;
		final String rawCode;
		final int firstLine;
		int startOffsetInClass = 0;

		Slice(String scope, String methodDescriptor, String rawCode, int firstLine) {
			this.scope = scope;
			this.methodDescriptor = methodDescriptor;
			this.rawCode = rawCode;
			this.firstLine = firstLine;
		}
	}
}
