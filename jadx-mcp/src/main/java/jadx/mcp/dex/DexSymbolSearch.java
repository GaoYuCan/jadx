package jadx.mcp.dex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jadx.api.plugins.input.data.IClassData;
import jadx.api.plugins.input.data.IMethodProto;
import jadx.api.plugins.input.data.IMethodRef;
import jadx.api.plugins.input.data.attributes.IJadxAttribute;
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr;
import jadx.core.dex.info.AccessInfo;
import jadx.mcp.dex.DexProject.ClassEntry;
import jadx.mcp.format.SymbolFormat;
import jadx.mcp.search.SearchEngine;
import jadx.mcp.search.SymbolFilter;

/** Metadata-only symbol search over original DEX class definitions. */
public final class DexSymbolSearch {
	private DexSymbolSearch() {
	}

	public static Map<String, Object> search(DexProject project, String kind, SearchEngine query,
			boolean matchFull, SymbolFilter filter, int offset, int limit) {
		List<Map<String, Object>> hits = new ArrayList<>();
		int[] seen = { 0 };
		List<ClassEntry> classes;
		if (filter.classFqn != null) {
			classes = List.of(project.find(filter.classFqn));
		} else {
			classes = project.classes().values().stream()
					.sorted(Comparator.comparing(ClassEntry::fqn)).toList();
		}
		for (ClassEntry entry : classes) {
			String fqn = entry.fqn();
			if (filter.packagePrefix != null && !fqn.startsWith(filter.packagePrefix)) {
				continue;
			}
			if (kind.equals("class")) {
				if (classMatches(project, entry, filter)
						&& nameMatches(query, matchFull ? fqn : simpleName(fqn))) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("target", fqn);
					row.put("name", simpleName(fqn));
					row.put("fqn", fqn);
					add(hits, seen, row, offset, limit);
				}
			} else {
				IClassData data = entry.data();
				data.visitFieldsAndMethods(field -> {
					if (!kind.equals("field") || !filter.matchesAccessMask(field.getAccessFlags())
							|| filter.annotation != null && !hasAnnotation(field.getAttributes(), filter.annotation)) {
						return;
					}
					String name = field.getName();
					if (nameMatches(query, matchFull ? fqn + '.' + name : name)) {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("target", fqn + '#' + name);
						row.put("class_fqn", fqn);
						row.put("name", name);
						add(hits, seen, row, offset, limit);
					}
				}, method -> {
					if (!kind.equals("method") || !filter.matchesAccessMask(method.getAccessFlags())
							|| filter.annotation != null && !hasAnnotation(method.getAttributes(), filter.annotation)) {
						return;
					}
					IMethodRef ref = method.getMethodRef();
					ref.load();
					String name = ref.getName();
					String descriptor = descriptor(ref);
					if (nameMatches(query, matchFull ? fqn + '.' + name : name)) {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("target", fqn + '#' + name + descriptor);
						row.put("class_fqn", fqn);
						row.put("name", name);
						row.put("short_id", name + descriptor);
						add(hits, seen, row, offset, limit);
					}
				});
			}
			if (seen[0] > offset + limit) {
				break;
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("kind", kind);
		result.put("hits", hits);
		result.put("count", hits.size());
		result.put("offset", offset);
		result.put("next_offset", seen[0] > offset + limit ? offset + hits.size() : -1);
		return result;
	}

	private static boolean classMatches(DexProject project, ClassEntry entry, SymbolFilter filter) {
		if (!filter.matchesAccessMask(entry.access())) {
			return false;
		}
		AccessInfo access = new AccessInfo(entry.access(), AccessInfo.AFType.CLASS);
		if (filter.classKind != null && !filter.classKind.equals(SymbolFormat.classKind(access))) {
			return false;
		}
		if (filter.superclass != null && !extendsClass(project, entry, filter.superclass,
				filter.superclassTransitive)) {
			return false;
		}
		if (filter.iface != null && !implementsInterface(project, entry, filter.iface,
				filter.interfaceTransitive)) {
			return false;
		}
		return filter.annotation == null || hasAnnotation(entry.data().getAttributes(), filter.annotation);
	}

	private static boolean extendsClass(DexProject project, ClassEntry entry, String wanted, boolean transitive) {
		String next = entry.superClass();
		Set<String> visited = new HashSet<>();
		while (next != null && visited.add(next)) {
			if (next.equals(wanted)) {
				return true;
			}
			if (!transitive) {
				return false;
			}
			ClassEntry parent = project.classes().get(next);
			next = parent == null ? null : parent.superClass();
		}
		return false;
	}

	private static boolean implementsInterface(DexProject project, ClassEntry entry, String wanted,
			boolean transitive) {
		return implementsInterface(project, entry, wanted, transitive, new HashSet<>());
	}

	private static boolean implementsInterface(DexProject project, ClassEntry entry, String wanted,
			boolean transitive, Set<String> visited) {
		if (!visited.add(entry.fqn())) {
			return false;
		}
		if (entry.interfaces().contains(wanted)) {
			return true;
		}
		if (!transitive) {
			return false;
		}
		for (String iface : entry.interfaces()) {
			ClassEntry parent = project.classes().get(iface);
			if (parent != null && implementsInterface(project, parent, wanted, true, visited)) {
				return true;
			}
		}
		ClassEntry parent = project.classes().get(entry.superClass());
		return parent != null && implementsInterface(project, parent, wanted, true, visited);
	}

	private static boolean hasAnnotation(List<IJadxAttribute> attributes, String wanted) {
		if (wanted == null) {
			return true;
		}
		for (IJadxAttribute attr : attributes) {
			if (attr instanceof AnnotationsAttr annotations && annotations.get(wanted) != null) {
				return true;
			}
		}
		return false;
	}

	private static boolean nameMatches(SearchEngine query, String name) {
		return query == null || query.find(name, 0) >= 0;
	}

	private static void add(List<Map<String, Object>> hits, int[] seen, Map<String, Object> row,
			int offset, int limit) {
		seen[0]++;
		if (seen[0] > offset && hits.size() < limit) {
			hits.add(row);
		}
	}

	private static String simpleName(String fqn) {
		return fqn.substring(fqn.lastIndexOf('.') + 1);
	}

	public static String descriptor(IMethodProto proto) {
		return "(" + String.join("", proto.getArgTypes()) + ")" + proto.getReturnType();
	}
}
