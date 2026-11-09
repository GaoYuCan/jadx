package jadx.mcp.fast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jadx.api.plugins.input.data.IFieldRef;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.insns.InsnIndexType;
import jadx.api.plugins.input.insns.Opcode;
import jadx.mcp.fast.FastProject.ClassEntry;
import jadx.mcp.util.ToolException;

/** Project-wide bytecode references, without constructing a project-wide JADX graph. */
public final class FastXrefs {
	private FastXrefs() {
	}

	public static Map<String, Object> search(FastProject project, String target, String kind,
			String descriptor, int maxResults) {
		if (!kind.equals("class") && !kind.equals("method") && !kind.equals("field")) {
			throw ToolException.invalidArg("kind", "must be one of class | method | field");
		}
		int hash = target.indexOf('#');
		String owner = project.find(hash < 0 ? target : target.substring(0, hash)).fqn();
		if (!kind.equals("class") && hash < 0) {
			throw ToolException.invalidArg("target_fqn", "member target must contain '#'");
		}
		String member = hash < 0 ? null : target.substring(hash + 1);
		String signature = descriptor;
		if (kind.equals("method")) {
			int paren = member.indexOf('(');
			if (paren >= 0) {
				signature = member.substring(paren);
				member = member.substring(0, paren);
			}
			if (signature == null) {
				signature = resolveDescriptor(project, project.find(owner), member);
			}
			member = project.rawMemberName(owner, member, signature, true);
		}
		String fieldType = null;
		if (kind.equals("field") && member.contains(":")) {
			int colon = member.indexOf(':');
			fieldType = member.substring(colon + 1);
			member = member.substring(0, colon);
		}
		if (kind.equals("field")) {
			member = project.rawMemberName(owner, member,
					fieldType == null ? null : ':' + fieldType, false);
		}
		final String memberName = member;
		final String memberDescriptor = signature;
		final String wantedFieldType = fieldType;
		Map<String, Map<String, Object>> callers = new LinkedHashMap<>();
		int[] summary = new int[3];
		List<ClassEntry> entries = project.classes().values().stream()
				.sorted(Comparator.comparing(ClassEntry::fqn)).toList();
		for (ClassEntry entry : entries) {
			entry.data().visitFieldsAndMethods(field -> {
			}, method -> {
				if (method.getCodeReader() == null) {
					return;
				}
				IMethodRef callerRef = method.getMethodRef();
				callerRef.load();
				String callerDescriptor = FastSymbolSearch.descriptor(callerRef);
				String callerKey = entry.fqn() + '#' + callerRef.getName() + callerDescriptor;
				method.getCodeReader().copy().visitInstructions(insn -> {
					InsnIndexType indexType = insn.getIndexType();
					if (indexType != InsnIndexType.METHOD_REF && indexType != InsnIndexType.FIELD_REF
							&& indexType != InsnIndexType.TYPE_REF) {
						return;
					}
					insn.decode();
					String referencedOwner;
					if (indexType == InsnIndexType.METHOD_REF) {
						IMethodRef ref = insn.getIndexAsMethod();
						ref.load();
						referencedOwner = FastProject.dotName(ref.getParentClassType());
						if (kind.equals("method") && (!referencedOwner.equals(owner)
								|| !ref.getName().equals(memberName)
								|| !FastSymbolSearch.descriptor(ref).equals(memberDescriptor))) {
							return;
						}
					} else if (indexType == InsnIndexType.FIELD_REF) {
						IFieldRef ref = insn.getIndexAsField();
						referencedOwner = FastProject.dotName(ref.getParentClassType());
						if (kind.equals("field") && (!referencedOwner.equals(owner)
								|| !ref.getName().equals(memberName)
								|| wantedFieldType != null && !wantedFieldType.equals(ref.getType()))) {
							return;
						}
					} else {
						referencedOwner = FastProject.dotName(insn.getIndexAsType());
					}
					if (kind.equals("class") ? !owner.equals(referencedOwner)
							: kind.equals("method") && indexType != InsnIndexType.METHOD_REF
								|| kind.equals("field") && indexType != InsnIndexType.FIELD_REF) {
						return;
					}
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
		body.put("total_app", callers.size());
		body.put("uses", uses);
		if (kind.equals("field")) {
			body.put("op_summary", Map.of("read_count", summary[0], "write_count", summary[1],
					"init_count", summary[2], "indeterminate_count", 0));
		}
		return body;
	}

	private static String resolveDescriptor(FastProject project, ClassEntry owner, String name) {
		List<String> matches = new ArrayList<>();
		owner.data().visitFieldsAndMethods(field -> {
		}, method -> {
			IMethodRef ref = method.getMethodRef();
			ref.load();
			if (ref.getName().equals(name)
					|| project.displayMemberName(owner.fqn(), ref.getName() + FastSymbolSearch.descriptor(ref),
							ref.getName()).equals(name)) {
				matches.add(FastSymbolSearch.descriptor(ref));
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
