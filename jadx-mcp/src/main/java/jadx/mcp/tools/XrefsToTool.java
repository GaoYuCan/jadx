package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ICodeInfo;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.utils.CodeUtils;
import jadx.mcp.JadxSession;
import jadx.mcp.format.RefEntry;
import jadx.mcp.format.RefTable;
import jadx.mcp.search.FieldOpScanner;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code xrefs_to} tool: lists all use sites of a class, method, or field.
 * <p>
 * Each result includes the using class, the enclosing method (when known), the line in the using class's
 * decompiled source, and a snippet. If the use site falls inside a cached {@link RefTable}, the row carries the
 * matching {@code ref_id} so the LLM can pivot via {@code resolve_ref} without re-counting anything.
 */
public final class XrefsToTool extends AbstractTool {

	private static final Logger LOG = LoggerFactory.getLogger(XrefsToTool.class);

	public XrefsToTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "xrefs_to";
	}

	@Override
	public String description() {
		return "Find all places that reference a given class, method, or field. `kind` selects the symbol "
				+ "type. For methods, supply the descriptor when the name is overloaded (`bar(I)V`); without a "
				+ "descriptor a single match is required. Each result includes the using class, the enclosing "
				+ "method when known, the raw line, and a snippet -- and a `ref_id` whenever the use site sits "
				+ "inside a class whose RefTable is already cached (call `decompile_code` on it first if you want "
				+ "guaranteed `ref_id`s).\n\n"
				+ "When `kind=field`, each row is enriched at the dex byte-code level: `op` is one of "
				+ "`read` / `write` / `init` (write inside `<init>` / `<clinit>`), `op_locations` collects every "
				+ "instruction in the using method that touches the field with `{op, insn_offset}` pairs, and "
				+ "the response gains an `op_summary` count (`{read_count, write_count, init_count}`). "
				+ "For class / method targets, no `op` field appears.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("target_fqn", "FQN of the target. For methods/fields use 'com.foo.A#bar' (or with descriptor).", true)
				.enumString("kind", "Symbol kind to look up.", true, "class", "method", "field")
				.string("descriptor", "Optional jvm descriptor for methods (e.g. '(I)V').", false)
				.integer("max_results", "Max use-sites to return. Default 500.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String fqn = requireString(args, "target_fqn");
		String kind = requireString(args, "kind");
		String descriptor = optString(args, "descriptor");
		int maxResults = Math.max(1, optInt(args, "max_results", 500));

		return session.read(decompiler -> {
			JavaNode subject = resolveSubject(fqn, kind, descriptor);
			List<JavaNode> usePlaces = subject.getUseIn();
			List<Map<String, Object>> results = new ArrayList<>(Math.min(usePlaces.size(), maxResults));
			boolean fieldKind = "field".equals(kind);
			FieldOpScanner.OpSummary opSummary = fieldKind ? new FieldOpScanner.OpSummary() : null;
			int auxFiltered = 0;
			int totalApp = 0;
			for (JavaNode use : usePlaces) {
				// Drop callers that live in an aux input. The subject itself may be an aux symbol (the user
				// asked for it explicitly); the question being answered here is "who in MY code uses it?",
				// so framework-internal cross-references would just dilute the signal.
				if (!session.isAppClass(use)) {
					auxFiltered++;
					continue;
				}
				totalApp++;
				if (results.size() >= maxResults) {
					continue;
				}
				Map<String, Object> row = describeUse(use, subject);
				if (fieldKind && subject instanceof JavaField jf && use instanceof JavaMethod jm) {
					FieldOpScanner.enrich(row, jf, jm, opSummary);
				}
				results.add(row);
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("target", subjectFqn(subject));
			body.put("kind", kind);
			body.put("count", results.size());
			body.put("total", usePlaces.size());
			body.put("total_app", totalApp);
			if (auxFiltered > 0) {
				body.put("aux_filtered", auxFiltered);
			}
			body.put("uses", results);
			if (opSummary != null) {
				body.put("op_summary", opSummary.toJson());
			}
			return body;
		});
	}

	private JavaNode resolveSubject(String fqn, String kind, String descriptor) {
		switch (kind) {
			case "class": {
				JavaClass cls = session.findClass(fqn);
				if (cls == null) {
					throw ToolException.notFound("class", fqn);
				}
				return cls;
			}
			case "method": {
				int hash = fqn.indexOf('#');
				if (hash < 0) {
					throw ToolException.invalidArg("target_fqn",
							"method target must contain '#', got '" + fqn + "'");
				}
				Target target;
				if (descriptor != null && fqn.indexOf('(') < 0) {
					target = TargetParser.parse(fqn + descriptor);
				} else {
					target = TargetParser.parse(fqn);
				}
				return TargetResolver.resolveMethod(session, target);
			}
			case "field": {
				int hash = fqn.indexOf('#');
				if (hash < 0) {
					throw ToolException.invalidArg("target_fqn",
							"field target must contain '#', got '" + fqn + "'");
				}
				String classFqn = fqn.substring(0, hash);
				String fieldName = fqn.substring(hash + 1);
				JavaClass cls = session.findClass(classFqn);
				if (cls == null) {
					throw ToolException.notFound("class", classFqn);
				}
				for (JavaField f : cls.getFields()) {
					if (f.getName().equals(fieldName) || f.getRawName().equals(fieldName)) {
						return f;
					}
				}
				throw ToolException.notFound("field", fqn);
			}
			default:
				throw ToolException.invalidArg("kind", "must be one of class | method | field");
		}
	}

	private Map<String, Object> describeUse(JavaNode use, JavaNode subject) {
		Map<String, Object> row = new LinkedHashMap<>();
		JavaClass enclosing = use.getTopParentClass();
		row.put("in_class", enclosing.getFullName());
		if (use instanceof JavaMethod jm) {
			row.put("in_method", jm.getName());
			String shortId = jm.getMethodNode().getMethodInfo().getShortId();
			int lp = shortId.indexOf('(');
			if (lp > 0) {
				row.put("in_method_descriptor", shortId.substring(lp));
			}
		} else if (use instanceof JavaField jf) {
			row.put("in_field", jf.getName());
		}
		int defPos = use.getDefPos();
		try {
			ICodeInfo codeInfo = enclosing.getCodeInfo();
			String code = codeInfo.getCodeStr();
			if (defPos >= 0 && defPos < code.length()) {
				int lineStart = CodeUtils.getLineStartForPos(code, defPos);
				int lineEnd = CodeUtils.getLineEndForPos(code, defPos);
				row.put("line", lineNumberAt(code, defPos));
				row.put("snippet", code.substring(lineStart, lineEnd).trim());
				RefTable refs = session.refCache().getIfFresh(enclosing.getFullName(), codeInfo);
				if (refs != null) {
					RefEntry near = refs.findNearOffset(defPos, 4);
					if (near != null) {
						row.put("ref_id", near.refId());
					}
				}
			}
		} catch (Throwable t) {
			LOG.debug("Failed to enrich xref for {}", enclosing.getFullName(), t);
		}
		return row;
	}

	private static String subjectFqn(JavaNode node) {
		if (node instanceof JavaMethod jm) {
			String shortId = jm.getMethodNode().getMethodInfo().getShortId();
			return jm.getDeclaringClass().getFullName() + "#" + shortId;
		}
		if (node instanceof JavaField jf) {
			return jf.getDeclaringClass().getFullName() + "#" + jf.getName();
		}
		return node.getFullName();
	}

	private static int lineNumberAt(String code, int pos) {
		int n = 1;
		for (int i = 0; i < pos && i < code.length(); i++) {
			if (code.charAt(i) == '\n') {
				n++;
			}
		}
		return n;
	}
}
