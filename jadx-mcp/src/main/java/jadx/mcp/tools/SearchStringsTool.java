package jadx.mcp.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import jadx.api.JavaClass;
import jadx.api.plugins.input.data.IClassData;
import jadx.api.plugins.input.data.IFieldData;
import jadx.api.plugins.input.data.IMethodData;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.annotations.EncodedType;
import jadx.api.plugins.input.data.annotations.EncodedValue;
import jadx.api.plugins.input.data.annotations.IAnnotation;
import jadx.api.plugins.input.data.attributes.IJadxAttribute;
import jadx.api.plugins.input.data.attributes.JadxAttrType;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.api.plugins.input.insns.Opcode;
import jadx.mcp.JadxSession;
import jadx.mcp.search.SearchEngine;
import jadx.mcp.util.SchemaBuilder;

/**
 * {@code search_strings} tool: byte-code level full-text search for string literals embedded in a dex / jar:
 * <ul>
 *   <li>{@code const-string} instructions in method bodies (the bulk of strings used in app logic),</li>
 *   <li>{@code static_values} on final fields (e.g. {@code static final String FOO = "..."}),</li>
 *   <li>annotation parameters of any kind (e.g. retrofit's {@code @GET("/api/v1")}).</li>
 * </ul>
 *
 * <p>Skips both Java decompilation and smali codegen entirely — typically 1-2 orders of magnitude faster
 * than {@link SearchCodeTool} for the same query. The right tool for hunting URLs, crypto algorithm names,
 * error messages, SharedPreferences keys, and similar literal-string targets.
 *
 * <p>Walks the input-plugin API ({@link IClassData} → {@link ICodeReader#visitInstructions}) directly, so no
 * MethodNode IR is constructed. The same path that {@code UsageInfoVisitor} uses for cross-references.
 */
public final class SearchStringsTool extends AbstractTool {

	private static final Logger LOG = LoggerFactory.getLogger(SearchStringsTool.class);

	private static final String KIND_CONST = "const_string";
	private static final String KIND_STATIC = "static_value";
	private static final String KIND_ANNOTATION = "annotation";

	public SearchStringsTool(JadxSession session) {
		super(session);
	}

	@Override
	public String name() {
		return "search_strings";
	}

	@Override
	public String description() {
		return "Search for string literals at the dex byte-code level: `const-string` opcodes, encoded values "
				+ "on static final fields, and annotation parameters. Skips decompilation entirely (1-2 orders "
				+ "of magnitude faster than `search_code` on the same query). Use this for finding URLs, crypto "
				+ "algorithm names, error messages, SharedPreferences keys, regex patterns, etc. "
				+ "For source-level constructs (lambdas, control flow), fall back to `search_code`."
				+ "\n\nLike `search_code`, results are bounded by `time_budget_ms` (default 30s) with a "
				+ "`next_class_fqn` resume cursor. Each hit carries a `kind` field telling you which of the "
				+ "three sources it came from (`const_string` / `static_value` / `annotation`). Note that "
				+ "byte-code has no source line numbers; if you need source context for a `const_string` hit, "
				+ "call `decompile_code` with the returned `class_fqn` + `method_signature`.";
	}

	@Override
	public JsonSchema schema() {
		return SchemaBuilder.object()
				.string("query", "Substring or regex to search for in string literals.", true)
				.bool("regex", "Treat `query` as a Java regex. Default false.", false)
				.bool("ignore_case", "Case-insensitive matching. Default false.", false)
				.string("package_prefix", "Only scan classes whose FQN starts with this prefix.", false)
				.enumStringArray("include",
						"Subset of sources to scan. Default = all three.",
						false, KIND_CONST, KIND_STATIC, KIND_ANNOTATION)
				.integer("max_results", "Max number of hits to return. Default 200.", false)
				.integer("offset", "Skip this many hits before returning (pagination cursor). Default 0.", false)
				.integer("time_budget_ms",
						"Stop scanning after this many milliseconds and return what we have so far with "
								+ "`exceeded_budget=true` and `next_class_fqn` set. Default 30000. "
								+ "Set <=0 to disable.",
						false)
				.string("start_class_fqn",
						"Resume cursor: skip every class that comes BEFORE this FQN in the iteration order. "
								+ "Pass the `next_class_fqn` from the previous response.",
						false)
				.build();
	}

	@Override
	protected Object call(Map<String, Object> args) {
		String query = requireString(args, "query");
		boolean regex = optBool(args, "regex", false);
		boolean ignoreCase = optBool(args, "ignore_case", false);
		String pkgPrefix = optString(args, "package_prefix");
		List<String> includeList = optStringList(args, "include");
		Set<String> include = (includeList == null || includeList.isEmpty())
				? Set.of(KIND_CONST, KIND_STATIC, KIND_ANNOTATION)
				: new HashSet<>(includeList);
		int maxResults = Math.max(1, optInt(args, "max_results", 200));
		int skip = Math.max(0, optInt(args, "offset", 0));
		long budgetMs = optInt(args, "time_budget_ms", 30_000);
		String startClassFqn = optString(args, "start_class_fqn");

		SearchEngine engine = new SearchEngine(query, regex, ignoreCase);
		long deadlineNanos = budgetMs > 0 ? System.nanoTime() + budgetMs * 1_000_000L : Long.MAX_VALUE;
		long startNanos = System.nanoTime();

		return session.read(decompiler -> {
			List<Map<String, Object>> hits = new ArrayList<>();
			Counter seen = new Counter();
			boolean exceededBudget = false;
			String nextClassFqn = null;
			boolean cursorReached = startClassFqn == null;
			int classesScanned = 0;
			boolean hitLimit = false;

			List<JavaClass> classes = session.appClasses();
			for (JavaClass cls : classes) {
				if (!cursorReached) {
					if (cls.getFullName().equals(startClassFqn)) {
						cursorReached = true;
					} else {
						continue;
					}
				}
				if (pkgPrefix != null && !cls.getFullName().startsWith(pkgPrefix)) {
					continue;
				}
				// Budget check at class boundary: we never start a class we can't finish.
				if (System.nanoTime() >= deadlineNanos) {
					exceededBudget = true;
					nextClassFqn = cls.getFullName();
					break;
				}
				IClassData clsData;
				try {
					clsData = cls.getClassNode().getClsData();
				} catch (Throwable t) {
					LOG.debug("Failed to access ClsData for {}", cls.getFullName(), t);
					continue;
				}
				if (clsData == null) {
					// synthetic class (no on-disk dex/jar entry) — nothing to scan
					continue;
				}
				classesScanned++;

				hitLimit = scanClass(cls, clsData, engine, include, hits, seen, skip, maxResults);
				if (hitLimit) {
					// Resume from the SAME class on next call: there may be more matches in cls beyond
					// the ones we've returned. Caller bumps `offset` to skip already-returned hits.
					nextClassFqn = cls.getFullName();
					break;
				}
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("hits", hits);
			result.put("count", hits.size());
			result.put("offset", skip);
			result.put("next_offset", hitLimit ? skip + hits.size() : -1);
			result.put("next_class_fqn", nextClassFqn);
			result.put("classes_scanned", classesScanned);
			result.put("elapsed_ms", (System.nanoTime() - startNanos) / 1_000_000L);
			result.put("exceeded_budget", exceededBudget);
			return result;
		});
	}

	/** Scans one class. Returns true if {@code maxResults} was hit (caller should stop). */
	private static boolean scanClass(JavaClass cls, IClassData clsData, SearchEngine engine,
			Set<String> include, List<Map<String, Object>> hits, Counter seen, int skip, int maxResults) {
		String classFqn = cls.getFullName();

		// (1) Class-level annotations
		if (include.contains(KIND_ANNOTATION)) {
			for (IJadxAttribute attr : clsData.getAttributes()) {
				if (attr.getAttrType() == JadxAttrType.ANNOTATION_LIST && attr instanceof AnnotationsAttr) {
					if (scanAnnotations((AnnotationsAttr) attr, engine, hits, seen, skip, maxResults,
							classFqn, null, null)) {
						return true;
					}
				}
			}
		}

		// (2) Per-field & per-method scan via the input-plugin visitor.
		// NOTE: visitFieldsAndMethods can't return early; we use a flag and short-circuit cheaply.
		ScanState state = new ScanState();
		state.engine = engine;
		state.include = include;
		state.hits = hits;
		state.seen = seen;
		state.skip = skip;
		state.maxResults = maxResults;
		state.classFqn = classFqn;

		try {
			clsData.visitFieldsAndMethods(
					field -> scanField(field, state),
					mth -> scanMethod(mth, state));
		} catch (StopScanException stop) {
			state.stopped = true;
		}
		return state.stopped;
	}

	private static void scanField(IFieldData field, ScanState s) {
		String fieldName = field.getName();
		for (IJadxAttribute attr : field.getAttributes()) {
			if (s.include.contains(KIND_STATIC)
					&& attr.getAttrType() == JadxAttrType.CONSTANT_VALUE
					&& attr instanceof EncodedValue) {
				if (scanEncodedValue((EncodedValue) attr, s.engine, s.hits, s.seen, s.skip, s.maxResults,
						KIND_STATIC, s.classFqn, null, fieldName, null, null)) {
					throw new StopScanException();
				}
			}
			if (s.include.contains(KIND_ANNOTATION)
					&& attr.getAttrType() == JadxAttrType.ANNOTATION_LIST
					&& attr instanceof AnnotationsAttr) {
				if (scanAnnotations((AnnotationsAttr) attr, s.engine, s.hits, s.seen, s.skip, s.maxResults,
						s.classFqn, null, fieldName)) {
					throw new StopScanException();
				}
			}
		}
	}

	private static void scanMethod(IMethodData mth, ScanState s) {
		IMethodRef ref = mth.getMethodRef();
		ref.load();
		String mthSig = methodShortSig(ref);

		// Method-level annotations
		if (s.include.contains(KIND_ANNOTATION)) {
			for (IJadxAttribute attr : mth.getAttributes()) {
				if (attr.getAttrType() == JadxAttrType.ANNOTATION_LIST && attr instanceof AnnotationsAttr) {
					if (scanAnnotations((AnnotationsAttr) attr, s.engine, s.hits, s.seen, s.skip, s.maxResults,
							s.classFqn, mthSig, null)) {
						throw new StopScanException();
					}
				}
			}
		}

		if (!s.include.contains(KIND_CONST)) {
			return;
		}
		ICodeReader cr = mth.getCodeReader();
		if (cr == null) {
			return;
		}
		cr.visitInstructions(insn -> {
			if (insn.getOpcode() != Opcode.CONST_STRING) {
				return;
			}
			try {
				insn.decode();
			} catch (Throwable t) {
				return;
			}
			String str = insn.getIndexAsString();
			if (str == null || s.engine.find(str, 0) < 0) {
				return;
			}
			s.seen.value++;
			if (s.seen.value <= s.skip) {
				return;
			}
			Map<String, Object> hit = new LinkedHashMap<>();
			hit.put("class_fqn", s.classFqn);
			hit.put("kind", KIND_CONST);
			hit.put("method_signature", mthSig);
			hit.put("insn_offset", insn.getOffset());
			hit.put("string", str);
			s.hits.add(hit);
			if (s.hits.size() >= s.maxResults) {
				throw new StopScanException();
			}
		});
	}

	private static boolean scanAnnotations(AnnotationsAttr attr, SearchEngine engine,
			List<Map<String, Object>> hits, Counter seen, int skip, int maxResults,
			String classFqn, String methodSig, String fieldName) {
		for (IAnnotation ann : attr.getAll()) {
			String annClass = ann.getAnnotationClass();
			for (Map.Entry<String, EncodedValue> e : ann.getValues().entrySet()) {
				if (scanEncodedValue(e.getValue(), engine, hits, seen, skip, maxResults,
						KIND_ANNOTATION, classFqn, methodSig, fieldName, annClass, e.getKey())) {
					return true;
				}
			}
		}
		return false;
	}

	/** Returns true if maxResults reached. */
	private static boolean scanEncodedValue(EncodedValue value, SearchEngine engine,
			List<Map<String, Object>> hits, Counter seen, int skip, int maxResults,
			String kind, String classFqn, String methodSig, String fieldName,
			String annotationClass, String annotationParam) {
		if (value == null) {
			return false;
		}
		EncodedType type = value.getType();
		if (type == EncodedType.ENCODED_STRING) {
			String str = (String) value.getValue();
			if (str == null || engine.find(str, 0) < 0) {
				return false;
			}
			seen.value++;
			if (seen.value <= skip) {
				return false;
			}
			Map<String, Object> hit = new LinkedHashMap<>();
			hit.put("class_fqn", classFqn);
			hit.put("kind", kind);
			if (methodSig != null) {
				hit.put("method_signature", methodSig);
			}
			if (fieldName != null) {
				hit.put("field_name", fieldName);
			}
			if (annotationClass != null) {
				hit.put("annotation_class", annotationClass);
				hit.put("annotation_param", annotationParam);
			}
			hit.put("string", str);
			hits.add(hit);
			return hits.size() >= maxResults;
		}
		if (type == EncodedType.ENCODED_ARRAY) {
			Object inner = value.getValue();
			if (inner instanceof List<?> list) {
				for (Object item : list) {
					if (!(item instanceof EncodedValue ev)) {
						continue;
					}
					if (scanEncodedValue(ev, engine, hits, seen, skip, maxResults,
							kind, classFqn, methodSig, fieldName, annotationClass, annotationParam)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	// ---------- helpers ----------

	/** Build a smali-style short method signature: {@code name(ArgTypes)RetType}. */
	private static String methodShortSig(IMethodRef ref) {
		StringBuilder sb = new StringBuilder(ref.getName());
		sb.append('(');
		for (String at : ref.getArgTypes()) {
			sb.append(at);
		}
		sb.append(')').append(ref.getReturnType());
		return sb.toString();
	}

	/** Mutable counter passed through deeply-nested helpers. */
	private static final class Counter {
		int value;
	}

	/** Per-class scan state to keep the visitor lambdas lean. */
	private static final class ScanState {
		SearchEngine engine;
		Set<String> include;
		List<Map<String, Object>> hits;
		Counter seen;
		int skip;
		int maxResults;
		String classFqn;
		boolean stopped;
	}

	/** Throw to break out of {@link ICodeReader#visitInstructions}, which has no early-exit API. */
	private static final class StopScanException extends RuntimeException {
		StopScanException() {
			super(null, null, false, false);
		}
	}
}
