package jadx.mcp.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IFieldRef;
import jadx.api.plugins.input.insns.Opcode;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.nodes.MethodNode;

/**
 * Field-access classifier used by {@code xrefs_to kind=field} to enrich each use site with a
 * {@code read} / {@code write} / {@code init} label and per-instruction locations.
 *
 * <p>Walks the dex byte-code via {@link ICodeReader#visitInstructions} (no decompile, no smali codegen)
 * and matches the four field opcodes — {@link Opcode#IGET} / {@link Opcode#SGET} for reads,
 * {@link Opcode#IPUT} / {@link Opcode#SPUT} for writes — against the target field's declaring class
 * and name. Writes inside {@code <init>} / {@code <clinit>} are reported as {@code init} since they
 * represent state-establishment, not state-mutation.
 *
 * <p>Aggregates per-method counts in an {@link OpSummary} accumulator that the tool returns at the
 * top level so the LLM can see the read/write/init breakdown without summing the per-row arrays.
 */
public final class FieldOpScanner {

	private static final Logger LOG = LoggerFactory.getLogger(FieldOpScanner.class);

	/** Mutable accumulator shared across all use-site rows for one xrefs_to call. */
	public static final class OpSummary {
		public int read;
		public int write;
		public int init;
		public int indeterminate;

		public Map<String, Object> toJson() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("read_count", read);
			m.put("write_count", write);
			m.put("init_count", init);
			m.put("indeterminate_count", indeterminate);
			return m;
		}
	}

	private FieldOpScanner() {
	}

	/**
	 * Annotate {@code row} in place with {@code op} (one of read / write / init / mixed / indeterminate)
	 * and {@code op_locations[]}; bump the running {@code summary} counts.
	 */
	public static void enrich(Map<String, Object> row, JavaField field, JavaMethod method, OpSummary summary) {
		String fieldOwnerRaw = field.getDeclaringClass().getRawName();
		String fieldRawName = field.getName();
		MethodNode mn = method.getMethodNode();
		ICodeReader reader = mn.getCodeReader();
		if (reader == null) {
			row.put("op", "indeterminate");
			summary.indeterminate++;
			return;
		}
		MethodInfo mi = mn.getMethodInfo();
		boolean inInitMth = mi.isConstructor() || mi.isClassInit();

		List<Map<String, Object>> opLocations = new ArrayList<>();
		int[] readWriteInit = new int[3];
		try {
			reader.visitInstructions(insn -> {
				Opcode op = insn.getOpcode();
				if (op != Opcode.IGET && op != Opcode.IPUT
						&& op != Opcode.SGET && op != Opcode.SPUT) {
					return;
				}
				try {
					insn.decode();
				} catch (Throwable t) {
					return;
				}
				IFieldRef ref;
				try {
					ref = insn.getIndexAsField();
				} catch (Throwable t) {
					return;
				}
				if (ref == null) return;
				// IFieldRef.getParentClassType() returns the dex internal form ("Lcom/foo/Bar;"),
				// but JavaField.getDeclaringClass().getRawName() returns dot form. Normalise once.
				String parentDot = unwrapDexType(ref.getParentClassType());
				if (parentDot == null || !parentDot.equals(fieldOwnerRaw)) return;
				if (!fieldRawName.equals(ref.getName())) return;

				boolean isWrite = (op == Opcode.IPUT || op == Opcode.SPUT);
				String opName;
				if (isWrite && inInitMth) {
					opName = "init";
					readWriteInit[2]++;
				} else if (isWrite) {
					opName = "write";
					readWriteInit[1]++;
				} else {
					opName = "read";
					readWriteInit[0]++;
				}
				Map<String, Object> loc = new LinkedHashMap<>();
				loc.put("op", opName);
				loc.put("insn_offset", insn.getOffset());
				opLocations.add(loc);
			});
		} catch (Throwable t) {
			LOG.debug("Failed to scan instructions of {}", method.getName(), t);
		}

		if (opLocations.isEmpty()) {
			// Subject-field UseIn says this method touches the field, but bytecode scan didn't find
			// the opcode. Rare — usually a synthetic accessor that proxies through another method.
			row.put("op", "indeterminate");
			summary.indeterminate++;
			return;
		}
		row.put("op", dominantOp(readWriteInit));
		row.put("op_locations", opLocations);
		summary.read += readWriteInit[0];
		summary.write += readWriteInit[1];
		summary.init += readWriteInit[2];
	}

	/**
	 * Pick a single label for the row when one method touches the field multiple times.
	 * "Pure" categories take precedence (all reads → read, all writes → write, all inits → init);
	 * everything else collapses to "mixed".
	 */
	private static String dominantOp(int[] rwi) {
		int reads = rwi[0], writes = rwi[1], inits = rwi[2];
		if (inits > 0 && writes == 0 && reads == 0) return "init";
		if (writes > 0 && reads == 0 && inits == 0) return "write";
		if (reads > 0 && writes == 0 && inits == 0) return "read";
		return "mixed";
	}

	private static String unwrapDexType(String t) {
		if (t == null || t.length() < 3) return null;
		if (t.charAt(0) != 'L' || t.charAt(t.length() - 1) != ';') return t;
		return t.substring(1, t.length() - 1).replace('/', '.');
	}
}
