package jadx.mcp.dex;

import java.util.BitSet;

import jadx.api.plugins.input.insns.InsnData;
import jadx.plugins.input.dex.DexReader;
import jadx.plugins.input.dex.sections.DexHeader;
import jadx.plugins.input.dex.sections.SectionReader;

import static jadx.plugins.input.dex.sections.DexConsts.NO_INDEX;

/** The instruction operand IDs for one target, scoped to one DEX. */
final class DexReferenceIds {
	private static final int MEMBER_ID_SIZE = 8;

	private final BitSet types = new BitSet();
	private final BitSet methods = new BitSet();
	private final BitSet fields = new BitSet();

	static DexReferenceIds find(DexReader dex, String kind, String owner, String name, String descriptor) {
		SectionReader in = new SectionReader(dex, 0);
		DexReferenceIds ids = new DexReferenceIds();
		boolean classTarget = kind.equals("class");
		String rawOwner = owner.replace('.', '/');
		if (classTarget && owner.length() == 1) {
			int type = findType(in, rawOwner);
			if (type != NO_INDEX) {
				ids.types.set(type);
			}
		}
		String ownerDescriptor = owner.startsWith("[") ? rawOwner : 'L' + rawOwner + ';';
		int ownerId = findType(in, ownerDescriptor);
		if (ownerId == NO_INDEX) {
			return ids;
		}
		if (classTarget) {
			ids.types.set(ownerId);
		}
		if (classTarget || kind.equals("method")) {
			addMembers(ids.methods, in, ownerId, classTarget ? null : name, descriptor, true);
		}
		if (classTarget || kind.equals("field")) {
			addMembers(ids.fields, in, ownerId, classTarget ? null : name, descriptor, false);
		}
		return ids;
	}

	boolean isEmpty() {
		return types.isEmpty() && methods.isEmpty() && fields.isEmpty();
	}

	boolean matches(InsnData insn) {
		BitSet ids = switch (insn.getIndexType()) {
			case TYPE_REF -> types;
			case METHOD_REF -> methods;
			case FIELD_REF -> fields;
			default -> null;
		};
		if (ids == null || ids.isEmpty()) {
			return false;
		}
		insn.decode();
		return ids.get(insn.getIndex());
	}

	private static int findType(SectionReader in, String descriptor) {
		// type_ids follows the descriptor string index order.
		int lo = 0;
		int hi = in.getDexReader().getHeader().getTypeIdsSize() - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			int cmp = descriptor.compareTo(in.getType(mid));
			if (cmp == 0) {
				return mid;
			}
			if (cmp > 0) {
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return NO_INDEX;
	}

	private static void addMembers(BitSet ids, SectionReader in, int ownerId, String name, String descriptor, boolean method) {
		DexHeader header = in.getDexReader().getHeader();
		int offset = method ? header.getMethodIdsOff() : header.getFieldIdsOff();
		int count = method ? header.getMethodIdsSize() : header.getFieldIdsSize();
		int first = firstMember(in, offset, count, ownerId);
		int end = firstMember(in, offset, count, ownerId + 1);
		if (name == null) {
			ids.set(first, end);
			return;
		}
		for (int id = first; id < end; id++) {
			in.absPos(offset + id * MEMBER_ID_SIZE + 2);
			int protoOrType = in.readUShort();
			int nameId = in.readInt();
			if (!name.equals(in.getString(nameId))) {
				continue;
			}
			if (descriptor == null) {
				ids.set(id);
				continue;
			}
			String actual = method ? DexSymbolSearch.descriptor(in.getMethodProto(protoOrType)) : in.getType(protoOrType);
			if (descriptor.equals(actual)) {
				ids.set(id);
			}
		}
	}

	private static int firstMember(SectionReader in, int offset, int count, int ownerId) {
		// Both member tables are grouped by their first field, class_idx.
		int lo = 0;
		int hi = count;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			int current = in.absPos(offset + mid * MEMBER_ID_SIZE).readUShort();
			if (current < ownerId) {
				lo = mid + 1;
			} else {
				hi = mid;
			}
		}
		return lo;
	}
}
