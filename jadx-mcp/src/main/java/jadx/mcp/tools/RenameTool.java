package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.ICodeInfo;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.JavaVariable;
import jadx.api.data.impl.JadxCodeRef;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.metadata.ICodeAnnotation;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.metadata.annotations.NodeDeclareRef;
import jadx.core.codegen.TypeGen;
import jadx.core.deobf.NameMapper;
import jadx.core.dex.attributes.AFlag;
import jadx.mcp.JadxSession;
import jadx.mcp.format.VariableTableBuilder;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/** {@code rename} tool: add or replace a persistent user rename and reload the active project. */
public final class RenameTool extends AbstractTool {

	private static final Pattern VARIABLE_ID = Pattern.compile("r\\d+v\\d+");

	public RenameTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "rename";
	}

	@Override
	public String description() {
		return "Rename a class, field, method, or variable in the active project and persist the change for "
				+ "`save_project`. `target` is a class FQN, `Class#field` (optionally `:JVMType`), or "
				+ "`Class#method(args)return`. For variables, pass the containing method as `target` plus a "
				+ "`variable_id` returned by `decompile_code(include_variables=true)`. Repeating the same target "
				+ "replaces its prior rename. The project is reloaded before this call returns.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.enumString("kind", "Symbol kind to rename.", true, "class", "field", "method", "variable")
				.string("target", "Class, field, or method target in jadx-mcp canonical syntax.", true)
				.string("new_name", "New Java identifier (or full FQN for a top-level class).", true)
				.string("variable_id", "Required for kind=variable, e.g. `r2v0` from decompile_code.", false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String kind = requireString(args, "kind");
		String target = requireString(args, "target");
		String newName = requireString(args, "new_name");
		String variableId = optString(args, "variable_id");
		if (!kind.equals("class") && !kind.equals("field")
				&& !kind.equals("method") && !kind.equals("variable")) {
			throw ToolException.invalidArg("kind", "must be one of class | field | method | variable");
		}
		if (kind.equals("variable")) {
			if (variableId == null) {
				throw ToolException.invalidArg("variable_id", "is required when kind=variable");
			}
			if (!VARIABLE_ID.matcher(variableId).matches()) {
				throw ToolException.invalidArg("variable_id", "must use the `r<reg>v<ssa>` form, e.g. r2v0");
			}
		}

		JadxSession.RenameApplyResult<ResolvedRename> applied = session.applyRename(decompiler -> {
			ResolvedRename resolved = resolve(decompiler, kind, target, variableId, newName);
			return new JadxSession.RenameMutation<>(resolved.rename, resolved);
		});

		ResolvedRename resolved = applied.value;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("renamed", true);
		body.put("kind", kind);
		body.put("target", resolved.canonicalTarget);
		if (resolved.variableId != null) {
			body.put("variable_id", resolved.variableId);
		}
		body.put("old_name", resolved.oldName);
		body.put("new_name", newName);
		body.put("rename_count", applied.renameCount);
		return body;
	}

	private ResolvedRename resolve(JadxDecompiler decompiler, String kind, String target,
			String variableId, String newName) {
		switch (kind) {
			case "class":
				return resolveClass(target, newName);
			case "field":
				validateSimpleName(newName);
				return resolveField(target, newName);
			case "method":
				validateSimpleName(newName);
				return resolveMethodRename(target, newName);
			case "variable":
				validateSimpleName(newName);
				return resolveVariable(decompiler, target, variableId, newName);
			default:
				throw ToolException.invalidArg("kind", "unsupported kind: " + kind);
		}
	}

	private ResolvedRename resolveClass(String target, String newName) {
		if (target.indexOf('#') >= 0) {
			throw ToolException.invalidArg("target", "class target must not contain '#': " + target);
		}
		JavaClass cls = session.findClass(target);
		if (cls == null) {
			throw ToolException.notFound("class", target);
		}
		if (cls.getClassNode().contains(AFlag.DONT_RENAME)) {
			throw unsupported("Class is marked DONT_RENAME: " + target);
		}
		boolean valid = NameMapper.isValidIdentifier(newName);
		if (!cls.getClassNode().isInner()) {
			valid = valid
					|| NameMapper.isValidFullIdentifier(newName)
					|| newName.startsWith(".") && NameMapper.isValidIdentifier(newName.substring(1));
		}
		if (!valid) {
			throw ToolException.invalidArg("new_name",
					"must be a valid Java identifier"
							+ (cls.getClassNode().isInner() ? " for an inner class" : " or fully qualified class name"));
		}
		return new ResolvedRename(
				new JadxCodeRename(JadxNodeRef.forCls(cls), newName),
				cls.getRawName(),
				cls.getFullName(),
				null);
	}

	private ResolvedRename resolveField(String input, String newName) {
		MemberParts parts = parseFieldTarget(input);
		JavaClass cls = requireClass(parts.classFqn);
		List<JavaField> matches = new ArrayList<>();
		for (JavaField field : cls.getFields()) {
			String rawName = field.getRawName();
			if (!rawName.equals(parts.memberName) && !field.getName().equals(parts.memberName)) {
				continue;
			}
			String descriptor = TypeGen.signature(field.getFieldNode().getType());
			if (parts.descriptor == null || descriptor.equals(parts.descriptor)) {
				matches.add(field);
			}
		}
		if (matches.isEmpty()) {
			throw ToolException.notFound("field", input);
		}
		if (matches.size() > 1) {
			throw ambiguousFields(input, cls, matches);
		}
		JavaField field = matches.get(0);
		if (field.getFieldNode().contains(AFlag.DONT_RENAME)) {
			throw unsupported("Field is marked DONT_RENAME: " + input);
		}
		String canonical = cls.getRawName() + "#" + field.getFieldNode().getFieldInfo().getShortId();
		return new ResolvedRename(
				new JadxCodeRename(JadxNodeRef.forFld(field), newName),
				canonical,
				field.getName(),
				null);
	}

	private ResolvedRename resolveMethodRename(String input, String newName) {
		JavaMethod method = resolveMethod(input, false);
		if (method.isConstructor() || method.isClassInit()) {
			throw unsupported("Constructors and class initializers cannot be renamed as methods; rename the class instead");
		}
		if (method.getMethodNode().contains(AFlag.DONT_RENAME)) {
			throw unsupported("Method is marked DONT_RENAME: " + input);
		}
		String canonical = rawMethodTarget(method);
		return new ResolvedRename(
				new JadxCodeRename(JadxNodeRef.forMth(method), newName),
				canonical,
				method.getName(),
				null);
	}

	private ResolvedRename resolveVariable(JadxDecompiler decompiler, String methodTarget,
			String variableId, String newName) {
		JavaMethod method = resolveMethod(methodTarget, true);
		Target parsed = TargetParser.parse(methodTarget);
		if (!parsed.hasDescriptor()) {
			throw ToolException.invalidArg("target", "variable target must include the full method descriptor");
		}
		JavaClass cls = method.getDeclaringClass();
		ICodeInfo codeInfo = cls.getCodeInfo();
		for (Map.Entry<Integer, ICodeAnnotation> entry : codeInfo.getCodeMetadata().getAsMap().entrySet()) {
			ICodeAnnotation annotation = entry.getValue();
			if (!isVariableDeclaration(annotation)) {
				continue;
			}
			JavaNode node = decompiler.getJavaNodeByCodeAnnotation(codeInfo, annotation);
			if (!(node instanceof JavaVariable variable) || !variable.getMth().equals(method)) {
				continue;
			}
			String candidateId = VariableTableBuilder.variableId(variable.getReg(), variable.getSsa());
			if (!candidateId.equals(variableId)) {
				continue;
			}
			if (variable.getName() == null) {
				throw unsupported("Variable has no renameable source name: " + variableId);
			}
			return new ResolvedRename(
					new JadxCodeRename(JadxNodeRef.forMth(method), JadxCodeRef.forVar(variable), newName),
					rawMethodTarget(method),
					variable.getName(),
					variableId);
		}
		throw ToolException.notFound("variable", methodTarget + "@" + variableId);
	}

	private JavaMethod resolveMethod(String input, boolean requireDescriptor) {
		Target target = TargetParser.parse(input);
		if (!target.isMethod()) {
			throw ToolException.invalidArg("target", "expected a method target such as com.foo.A#method(I)V");
		}
		if (requireDescriptor && !target.hasDescriptor()) {
			throw ToolException.invalidArg("target", "method descriptor is required for this operation");
		}
		JavaClass cls = requireClass(target.classFqn());
		List<JavaMethod> matches = new ArrayList<>();
		for (JavaMethod method : cls.getMethods()) {
			String rawName = method.getMethodNode().getMethodInfo().getName();
			if (!rawName.equals(target.memberName()) && !method.getName().equals(target.memberName())) {
				continue;
			}
			String shortId = method.getMethodNode().getMethodInfo().getShortId();
			String descriptor = shortId.substring(shortId.indexOf('('));
			if (!target.hasDescriptor() || descriptor.equals(target.memberDescriptor())) {
				matches.add(method);
			}
		}
		if (matches.isEmpty()) {
			throw ToolException.notFound("method", input);
		}
		if (matches.size() > 1) {
			List<Map<String, Object>> candidates = new ArrayList<>(matches.size());
			for (JavaMethod method : matches) {
				candidates.add(Map.of("target", rawMethodTarget(method)));
			}
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("candidates", candidates);
			details.put("hint", "Re-issue the request with one candidate target.");
			throw ToolException.ambiguous("method", input, details);
		}
		return matches.get(0);
	}

	private JavaClass requireClass(String fqn) {
		JavaClass cls = session.findClass(fqn);
		if (cls == null) {
			throw ToolException.notFound("class", fqn);
		}
		return cls;
	}

	private static MemberParts parseFieldTarget(String input) {
		int hash = input.indexOf('#');
		if (hash <= 0 || hash == input.length() - 1) {
			throw ToolException.invalidArg("target", "member target must use Class#member syntax: " + input);
		}
		String classFqn = input.substring(0, hash);
		String member = input.substring(hash + 1);
		String descriptor = null;
		int colon = member.indexOf(':');
		if (colon >= 0) {
			descriptor = member.substring(colon + 1);
			member = member.substring(0, colon);
			if (descriptor.isEmpty()) {
				throw ToolException.invalidArg("target", "field JVM type is empty: " + input);
			}
		}
		if (member.isEmpty()) {
			throw ToolException.invalidArg("target", "member name is empty: " + input);
		}
		return new MemberParts(classFqn, member, descriptor);
	}

	private static ToolException ambiguousFields(String input, JavaClass cls, List<JavaField> fields) {
		List<Map<String, Object>> candidates = new ArrayList<>(fields.size());
		for (JavaField field : fields) {
			candidates.add(Map.of("target", cls.getRawName() + "#"
					+ field.getFieldNode().getFieldInfo().getShortId()));
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("candidates", candidates);
		details.put("hint", "Re-issue the request with one candidate target.");
		return ToolException.ambiguous("field", input, details);
	}

	private static boolean isVariableDeclaration(ICodeAnnotation annotation) {
		if (!(annotation instanceof NodeDeclareRef declaration)) {
			return false;
		}
		ICodeNodeRef node = declaration.getNode();
		return node.getAnnType() == ICodeAnnotation.AnnType.VAR;
	}

	private static String rawMethodTarget(JavaMethod method) {
		return method.getDeclaringClass().getRawName() + "#"
				+ method.getMethodNode().getMethodInfo().getShortId();
	}

	private static void validateSimpleName(String name) {
		if (!NameMapper.isValidIdentifier(name)) {
			throw ToolException.invalidArg("new_name", "must be a valid, non-reserved Java identifier");
		}
	}

	private static ToolException unsupported(String message) {
		return new ToolException(ToolException.Code.UNSUPPORTED, message);
	}

	private static final class MemberParts {
		final String classFqn;
		final String memberName;
		final String descriptor;

		MemberParts(String classFqn, String memberName, String descriptor) {
			this.classFqn = classFqn;
			this.memberName = memberName;
			this.descriptor = descriptor;
		}
	}

	private static final class ResolvedRename {
		final JadxCodeRename rename;
		final String canonicalTarget;
		final String oldName;
		final String variableId;

		ResolvedRename(JadxCodeRename rename, String canonicalTarget, String oldName, String variableId) {
			this.rename = rename;
			this.canonicalTarget = canonicalTarget;
			this.oldName = oldName;
			this.variableId = variableId;
		}
	}
}
