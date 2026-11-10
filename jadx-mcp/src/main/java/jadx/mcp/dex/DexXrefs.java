package jadx.mcp.dex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.api.plugins.input.data.ICodeReader;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.Opcode;
import jadx.mcp.dex.DexProject.ClassEntry;
import jadx.mcp.util.ToolException;
import jadx.plugins.input.dex.DexReader;

/** Project-wide bytecode references, without constructing a project-wide JADX graph. */
public final class DexXrefs {
	private DexXrefs() {
	}

	public static Map<String, Object> search(DexProject project, String target, String kind, int maxResults) {
		if (!kind.equals("class") && !kind.equals("method") && !kind.equals("field")) {
			throw ToolException.invalidArg("kind", "must be one of class | method | field");
		}
		int hash = target.indexOf('#');
		if (kind.equals("class") && hash >= 0) {
			throw ToolException.invalidArg("target_fqn", "class target must not include a member");
		}
		if (!kind.equals("class") && (hash <= 0 || hash == target.length() - 1)) {
			throw ToolException.invalidArg("target_fqn", "member target must use Class#member syntax");
		}
		String owner = hash < 0 ? target : target.substring(0, hash);
		String member = hash < 0 ? null : target.substring(hash + 1);
		String signature = null;
		if (kind.equals("method")) {
			int paren = member.indexOf('(');
			if (paren >= 0) {
				signature = member.substring(paren);
				member = member.substring(0, paren);
			}
			if (signature == null) {
				ClassEntry ownerEntry = project.classes().get(owner);
				if (ownerEntry == null) {
					throw ToolException.invalidArg("target_fqn",
							"include the JVM descriptor for external methods, e.g. Class#method(I)V");
				}
				signature = resolveDescriptor(ownerEntry, member);
			}
		} else if (kind.equals("field")) {
			int colon = member.indexOf(':');
			if (colon >= 0) {
				signature = member.substring(colon + 1);
				member = member.substring(0, colon);
			}
		}
		final String memberName = member;
		final String memberDescriptor = signature;
		Map<String, Map<String, Object>> callers = new LinkedHashMap<>();
		int[] summary = new int[3];
		Map<DexReader, DexReferenceIds> idsByDex = new HashMap<>();
		List<ClassEntry> entries = project.classes().values().stream()
				.filter(entry -> !idsByDex.computeIfAbsent(entry.reader(),
						dex -> DexReferenceIds.find(dex, kind, owner, memberName, memberDescriptor)).isEmpty())
				.sorted(Comparator.comparing(ClassEntry::fqn)).toList();
		for (ClassEntry entry : entries) {
			DexReferenceIds ids = idsByDex.get(entry.reader());
			entry.data().visitFieldsAndMethods(field -> {
			}, method -> {
				ICodeReader code = method.getCodeReader();
				if (code == null) {
					return;
				}
				code.copy().visitInstructions(insn -> {
					if (!ids.matches(insn)) {
						return;
					}
					IMethodRef callerRef = method.getMethodRef();
					callerRef.load();
					String callerDescriptor = DexSymbolSearch.descriptor(callerRef);
					String callerKey = entry.fqn() + '#' + callerRef.getName() + callerDescriptor;
					Map<String, Object> row = callers.computeIfAbsent(callerKey, key -> {
						Map<String, Object> created = new LinkedHashMap<>();
						created.put("in_class", entry.fqn());
						created.put("in_method", callerRef.getName());
						created.put("in_method_descriptor", callerDescriptor);
						created.put("insn_offsets", new ArrayList<Integer>());
						if (kind.equals("field")) {
							created.put("op_locations", new ArrayList<Map<String, Object>>());
						}
						return created;
					});
					@SuppressWarnings("unchecked")
					List<Integer> offsets = (List<Integer>) row.get("insn_offsets");
					offsets.add(insn.getOffset());
					if (kind.equals("field")) {
						String op = fieldOp(insn.getOpcode(), callerRef.getName());
						if (op != null) {
							@SuppressWarnings("unchecked")
							List<Map<String, Object>> locations =
									(List<Map<String, Object>>) row.get("op_locations");
							locations.add(Map.of("op", op, "insn_offset", insn.getOffset()));
							summary[op.equals("read") ? 0 : op.equals("write") ? 1 : 2]++;
							Object previousOp = row.get("op");
							row.put("op", previousOp == null || previousOp.equals(op) ? op : "mixed");
						}
					}
				});
			});
		}
		List<Map<String, Object>> uses = callers.values().stream().limit(maxResults).toList();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("target", target);
		body.put("kind", kind);
		body.put("count", uses.size());
		body.put("total", callers.size());
		body.put("uses", uses);
		if (kind.equals("field")) {
			body.put("op_summary", Map.of("read_count", summary[0], "write_count", summary[1],
					"init_count", summary[2]));
		}
		return body;
	}

	private static String resolveDescriptor(ClassEntry owner, String name) {
		List<String> matches = new ArrayList<>();
		owner.data().visitFieldsAndMethods(field -> {
		}, method -> {
			IMethodRef ref = method.getMethodRef();
			ref.load();
			if (ref.getName().equals(name)) {
				matches.add(DexSymbolSearch.descriptor(ref));
			}
		});
		if (matches.isEmpty()) {
			throw ToolException.notFound("method", owner.fqn() + '#' + name);
		}
		if (matches.size() != 1) {
			throw ToolException.ambiguous("method", owner.fqn() + '#' + name,
					Map.of("descriptors", matches));
		}
		return matches.get(0);
	}

	private static String fieldOp(Opcode opcode, String caller) {
		switch (opcode) {
			case IGET:
			case SGET:
				return "read";
			case IPUT:
			case SPUT:
				return caller.equals("<init>") || caller.equals("<clinit>") ? "init" : "write";
			default:
				return null;
		}
	}
}
