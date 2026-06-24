package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.JavaClass;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.core.codegen.TypeGen;
import jadx.core.dex.info.AccessInfo;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.mcp.JadxSession;
import jadx.mcp.format.SymbolFormat;
import jadx.mcp.util.SchemaBuilder;
import jadx.mcp.util.ToolException;

/**
 * {@code class_members} tool: returns a class's methods and fields with descriptors and access flags
 * without running the Java decompiler.
 * <p>
 * Reads the symbol tables that {@code ClassNode.load(IClassData)} populates eagerly; the heavy
 * decompile-and-codegen pipeline is never triggered.
 */
public final class ListClassMembersTool extends AbstractTool {

	public ListClassMembersTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "class_members";
	}

	@Override
	public String description() {
		return "List a class's methods and fields with descriptors, return / parameter / field types, and "
				+ "access flags — no decompilation, no smali. Each method hit carries a `target` string "
				+ "(`com.foo.A#bar(I)V`) ready to feed into `decompile_code`, `disassemble`, or `xrefs_to`. "
				+ "Use this instead of `decompile_code` when you only need the API shape of a class.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("class_fqn", "Fully qualified class name (alias or raw form). Required.", true)
				.bool("include_inherited",
						"Also list methods / fields declared on the supertype chain (default false). When "
								+ "enabled, each inherited member carries a `declared_in` field with the "
								+ "FQN of the original declarer; same-named overrides on the subject class "
								+ "are listed once (subject-class declaration wins).",
						false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String classFqn = requireString(args, "class_fqn");
		boolean includeInherited = optBool(args, "include_inherited", false);

		return session.read(decompiler -> {
			JavaClass cls = session.findClass(classFqn);
			if (cls == null) {
				throw ToolException.notFound("class", classFqn);
			}
			ClassNode cn = cls.getClassNode();

			Map<String, Object> body = new LinkedHashMap<>();
			body.put("class_fqn", cls.getFullName());
			if (!cls.getFullName().equals(cls.getRawName())) {
				body.put("raw_class_fqn", cls.getRawName());
			}
			body.put("class", classMeta(cn));

			List<Map<String, Object>> methods = new ArrayList<>();
			List<Map<String, Object>> fields = new ArrayList<>();

			collectFromClass(cn, methods, fields, null);

			if (includeInherited) {
				java.util.Set<String> ownMethodIds = new java.util.HashSet<>();
				java.util.Set<String> ownFieldNames = new java.util.HashSet<>();
				for (MethodNode m : cn.getMethods()) {
					ownMethodIds.add(m.getMethodInfo().getShortId());
				}
				for (FieldNode f : cn.getFields()) {
					ownFieldNames.add(f.getName());
				}

				ClassNode parent = resolveSuper(cn);
				while (parent != null) {
					String declaredIn = parent.getClassInfo().getFullName();
					for (MethodNode m : parent.getMethods()) {
						String shortId = m.getMethodInfo().getShortId();
						if (ownMethodIds.contains(shortId)) {
							continue; // overridden on subject class; subject decl wins
						}
						AccessInfo acc = m.getAccessFlags();
						// skip private (not inherited) and constructors / static initializers (per-class)
						if (acc.isPrivate() || acc.isConstructor() || m.getMethodInfo().isClassInit()) {
							continue;
						}
						methods.add(methodHit(parent, m, declaredIn));
						ownMethodIds.add(shortId);
					}
					for (FieldNode f : parent.getFields()) {
						if (ownFieldNames.contains(f.getName()) || f.getAccessFlags().isPrivate()) {
							continue;
						}
						fields.add(fieldHit(parent, f, declaredIn));
						ownFieldNames.add(f.getName());
					}
					parent = resolveSuper(parent);
				}
			}

			body.put("method_count", methods.size());
			body.put("field_count", fields.size());
			body.put("methods", methods);
			body.put("fields", fields);
			return body;
		});
	}

	private void collectFromClass(ClassNode cn, List<Map<String, Object>> methods,
			List<Map<String, Object>> fields, String declaredIn) {
		for (MethodNode m : cn.getMethods()) {
			methods.add(methodHit(cn, m, declaredIn));
		}
		for (FieldNode f : cn.getFields()) {
			fields.add(fieldHit(cn, f, declaredIn));
		}
	}

	private ClassNode resolveSuper(ClassNode cn) {
		ArgType sup = cn.getSuperClass();
		if (sup == null || !sup.isObject()) {
			return null;
		}
		String superName = sup.getObject();
		if ("java.lang.Object".equals(superName)) {
			return null;
		}
		JavaClass jc = session.findClass(superName);
		return jc == null ? null : jc.getClassNode();
	}

	private static Map<String, Object> classMeta(ClassNode cn) {
		AccessInfo acc = cn.getAccessFlags();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("access", SymbolFormat.accessTokens(acc, AccessInfo.AFType.CLASS));
		meta.put("kind", SymbolFormat.classKind(acc));
		meta.put("is_inner", cn.getClassInfo().isInner());
		ArgType sup = cn.getSuperClass();
		if (sup != null && sup.isObject()) {
			meta.put("super_class", sup.getObject());
		}
		List<String> ifs = new ArrayList<>();
		for (ArgType i : cn.getInterfaces()) {
			if (i.isObject()) {
				ifs.add(i.getObject());
			}
		}
		meta.put("interfaces", ifs);
		String inputFile = cn.getInputFileName();
		if (inputFile != null) {
			meta.put("source_dex", inputFile);
		}
		List<String> inners = new ArrayList<>();
		for (ClassNode in : cn.getInnerClasses()) {
			inners.add(in.getClassInfo().getFullName());
		}
		meta.put("inner_classes", inners);
		return meta;
	}

	private static Map<String, Object> methodHit(ClassNode owner, MethodNode m, String declaredIn) {
		MethodInfo info = m.getMethodInfo();
		AccessInfo acc = m.getAccessFlags();
		String classFqn = owner.getClassInfo().getFullName();
		String shortId = info.getShortId();
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("name", info.getAlias());
		String raw = info.getName();
		if (!raw.equals(info.getAlias())) {
			hit.put("raw_name", raw);
		}
		hit.put("short_id", shortId);
		hit.put("target", classFqn + "#" + shortId);
		String desc = shortId.substring(shortId.indexOf('('));
		hit.put("descriptor", desc);
		hit.put("return_type", TypeGen.signature(m.getReturnType()));
		List<String> args = new ArrayList<>();
		for (ArgType a : info.getArgumentsTypes()) {
			args.add(TypeGen.signature(a));
		}
		hit.put("arg_types", args);
		hit.put("access", SymbolFormat.accessTokens(acc, AccessInfo.AFType.METHOD));
		hit.put("is_static", acc.isStatic());
		hit.put("is_native", acc.isNative());
		hit.put("is_abstract", acc.isAbstract());
		hit.put("is_synthetic", acc.isSynthetic());
		hit.put("is_constructor", acc.isConstructor() || info.isConstructor());
		hit.put("is_class_init", info.isClassInit());
		// presence of method-level annotations (cheap to detect, no decompile)
		if (m.get(JadxAttrType.ANNOTATION_LIST) != null) {
			hit.put("has_annotations", true);
		}
		if (declaredIn != null) {
			hit.put("declared_in", declaredIn);
		}
		return hit;
	}

	private static Map<String, Object> fieldHit(ClassNode owner, FieldNode f, String declaredIn) {
		AccessInfo acc = f.getAccessFlags();
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("name", f.getAlias());
		String raw = f.getName();
		if (!raw.equals(f.getAlias())) {
			hit.put("raw_name", raw);
		}
		ArgType t = f.getType();
		hit.put("type", t.isObject() ? t.getObject() : t.toString());
		hit.put("jvm_type", TypeGen.signature(t));
		hit.put("access", SymbolFormat.accessTokens(acc, AccessInfo.AFType.FIELD));
		hit.put("is_static", acc.isStatic());
		hit.put("is_final", acc.isFinal());
		hit.put("is_volatile", acc.isVolatile());
		hit.put("is_transient", acc.isTransient());
		hit.put("is_synthetic", acc.isSynthetic());
		hit.put("is_enum", acc.isEnum());
		hit.put("has_constant_value", f.get(JadxAttrType.CONSTANT_VALUE) != null);
		if (f.get(JadxAttrType.ANNOTATION_LIST) != null) {
			hit.put("has_annotations", true);
		}
		String classFqn = owner.getClassInfo().getFullName();
		hit.put("target", classFqn + "#" + f.getAlias());
		if (declaredIn != null) {
			hit.put("declared_in", declaredIn);
		}
		return hit;
	}

}
