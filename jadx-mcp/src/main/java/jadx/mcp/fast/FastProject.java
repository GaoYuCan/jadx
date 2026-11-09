package jadx.mcp.fast;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourceType;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.impl.AnnotatedCodeWriter;
import jadx.api.plugins.input.ICodeLoader;
import jadx.api.plugins.input.data.IClassData;
import jadx.api.plugins.input.data.impl.EmptyCodeLoader;
import jadx.mcp.JadxMcpFilesGetter;
import jadx.mcp.util.ToolException;
import jadx.plugins.input.dex.DexFileLoader;
import jadx.plugins.input.dex.DexInputOptions;
import jadx.plugins.input.dex.DexReader;
import jadx.plugins.tools.JadxExternalPluginsLoader;
import jadx.zip.IZipEntry;
import jadx.zip.ZipContent;
import jadx.zip.ZipReader;

/** A project-wide class directory backed by original DEX buffers, without loading ClassNodes. */
public final class FastProject implements Closeable {
	private final List<DexReader> readers = new ArrayList<>();
	private final Map<String, ClassEntry> classes = new LinkedHashMap<>();
	private final Map<String, String> classAliases = new LinkedHashMap<>();
	private final Map<String, String> rawByAlias = new LinkedHashMap<>();
	private final Map<String, String> memberAliases = new LinkedHashMap<>();
	private final List<File> inputs;
	private final JadxCodeData codeData;
	private final ZipReader zipReader = new ZipReader();
	private final List<ResourceEntry> resourceEntries = new ArrayList<>();
	private JadxDecompiler resourceDecompiler;
	private List<ResourceFile> resourceFiles;

	public FastProject(List<File> inputs, JadxCodeData codeData) throws IOException {
		this.inputs = List.copyOf(inputs);
		this.codeData = codeData;
		DexFileLoader loader = new DexFileLoader(new DexInputOptions());
		for (File file : inputs) {
			String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
			if (lower.endsWith(".dex")) {
				readers.addAll(loader.loadDexReaders(file.getAbsolutePath(), Files.readAllBytes(file.toPath())));
				resourceEntries.add(new ResourceEntry(file.getAbsolutePath(), ResourceType.CODE,
						file.length(), file.length(), null));
			} else if (lower.endsWith(".apk")) {
				try (ZipContent zip = zipReader.open(file)) {
					for (IZipEntry entry : zip.getEntries()) {
						if (!entry.isDirectory()) {
							resourceEntries.add(new ResourceEntry(entry.getName(),
									ResourceType.getFileType(entry.getName()), entry.getUncompressedSize(),
									entry.getCompressedSize(), entry.getName()));
						}
						if (isRuntimeDex(entry.getName())) {
							readers.addAll(loader.loadDexReaders(file.getName() + ':' + entry.getName(), entry.getBytes()));
						}
					}
				}
			} else {
				throw new IOException("Fast mode accepts only APK and DEX: " + file);
			}
		}
		for (DexReader reader : readers) {
			reader.visitClasses(data -> {
				String fqn = dotName(data.getType());
				// DexReader reuses its class-data cursor; copy captures this definition's offset.
				classes.putIfAbsent(fqn, new ClassEntry(reader, data.copy().getInputFileOffset(), fqn,
						data.getAccessFlags(), dotName(data.getSuperType()),
						data.getInterfacesTypes().stream().map(FastProject::dotName).toList()));
			});
		}
		for (ICodeRename rename : codeData.getRenames()) {
			if (rename.getCodeRef() != null) {
				continue;
			}
			IJavaNodeRef ref = rename.getNodeRef();
			String rawClass = ref.getDeclaringClass();
			if (!classes.containsKey(rawClass)) {
				continue;
			}
			if (ref.getType() == IJavaNodeRef.RefType.CLASS) {
				String alias = classAlias(rawClass, rename.getNewName());
				classAliases.put(rawClass, alias);
				rawByAlias.putIfAbsent(alias, rawClass);
			} else if (ref.getType() == IJavaNodeRef.RefType.FIELD
					|| ref.getType() == IJavaNodeRef.RefType.METHOD) {
				memberAliases.put(rawClass + '#' + ref.getShortId(), rename.getNewName());
			}
		}
	}

	private static String classAlias(String rawClass, String name) {
		if (name.startsWith(".")) {
			return name.substring(1);
		}
		if (name.contains(".")) {
			return name;
		}
		int dot = rawClass.lastIndexOf('.');
		String simple = rawClass.substring(dot + 1);
		int inner = simple.lastIndexOf('$');
		String prefix = inner < 0 ? "" : simple.substring(0, inner + 1);
		return (dot < 0 ? "" : rawClass.substring(0, dot + 1)) + prefix + name;
	}

	private static boolean isRuntimeDex(String name) {
		if (name.equals("classes.dex")) {
			return true;
		}
		if (!name.startsWith("classes") || !name.endsWith(".dex")) {
			return false;
		}
		String suffix = name.substring(7, name.length() - 4);
		return !suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit);
	}

	public static String dotName(String type) {
		if (type == null) {
			return null;
		}
		if (type.startsWith("L") && type.endsWith(";")) {
			return type.substring(1, type.length() - 1).replace('/', '.');
		}
		return type.replace('/', '.');
	}

	public int classCount() {
		return classes.size();
	}

	public Map<String, ClassEntry> classes() {
		return Collections.unmodifiableMap(classes);
	}

	public List<ResourceEntry> resourceEntries() {
		return Collections.unmodifiableList(resourceEntries);
	}

	/** Resource parsing is initialized only when decoded content is requested. */
	public synchronized List<ResourceFile> resources() {
		if (resourceFiles != null) {
			return resourceFiles;
		}
		JadxArgs args = new JadxArgs();
		args.setSkipResources(true);
		args.setPluginLoader(new JadxExternalPluginsLoader());
		args.setFilesGetter(JadxMcpFilesGetter.INSTANCE);
		JadxDecompiler local = new JadxDecompiler(args);
		local.addCustomCodeLoader(EmptyCodeLoader.INSTANCE);
		try {
			local.load();
			List<ResourceFile> files = new ArrayList<>();
			for (File input : inputs) {
				local.getResourcesLoader().defaultLoadFile(files, input, "");
			}
			local.getRoot().loadResources(local.getResourcesLoader(), files);
			resourceDecompiler = local;
			resourceFiles = Collections.unmodifiableList(files);
			return resourceFiles;
		} catch (Throwable failure) {
			local.close();
			throw failure;
		}
	}

	public ClassEntry find(String fqn) {
		ClassEntry entry = classes.get(rawByAlias.getOrDefault(fqn, fqn));
		if (entry == null) {
			throw ToolException.notFound("class", fqn);
		}
		return entry;
	}

	public String displayClassFqn(String rawFqn) {
		return classAliases.getOrDefault(rawFqn, rawFqn);
	}

	public String displayMemberName(String rawClass, String shortId, String rawName) {
		return memberAliases.getOrDefault(rawClass + '#' + shortId, rawName);
	}

	public String rawMemberName(String rawClass, String alias, String descriptor, boolean method) {
		String prefix = rawClass + '#';
		for (Map.Entry<String, String> entry : memberAliases.entrySet()) {
			String shortId = entry.getKey();
			if (!shortId.startsWith(prefix) || !entry.getValue().equals(alias)) {
				continue;
			}
			shortId = shortId.substring(prefix.length());
			int separator = shortId.indexOf(method ? '(' : ':');
			if (separator >= 0 && (descriptor == null || shortId.substring(separator).equals(descriptor))) {
				return shortId.substring(0, separator);
			}
		}
		return alias;
	}

	public JadxDecompiler decompiler(Set<String> classNames) {
		for (String className : classNames) {
			find(className);
		}
		JadxArgs args = new JadxArgs();
		args.setSkipResources(true);
		args.setPluginLoader(new JadxExternalPluginsLoader());
		args.setFilesGetter(JadxMcpFilesGetter.INSTANCE);
		args.setCodeData(codeData);
		args.setCodeWriterProvider(AnnotatedCodeWriter::new);
		args.setDeobfuscationOn(false);
		args.setUseSourceNameAsClassNameAlias(jadx.api.args.UseSourceNameAsClassNameAlias.NEVER);
		args.setRenameCaseSensitive(false);
		args.setRenameValid(false);
		args.setRenamePrintable(false);
		args.setMoveInnerClasses(false);
		args.setInlineMethods(false);
		args.setInlineAnonymousClasses(false);
		args.setReplaceConsts(false);
		JadxDecompiler decompiler = new JadxDecompiler(args);
		decompiler.addCustomCodeLoader(new ICodeLoader() {
			@Override
			public void visitClasses(Consumer<IClassData> consumer) {
				for (String name : classNames) {
					consumer.accept(find(name).data());
				}
			}

			@Override
			public boolean isEmpty() {
				return classNames.isEmpty();
			}

			@Override
			public void close() {
			}
		});
		try {
			decompiler.load();
			return decompiler;
		} catch (Throwable failure) {
			decompiler.close();
			throw failure;
		}
	}

	@Override
	public void close() {
		if (resourceDecompiler != null) {
			resourceDecompiler.close();
			resourceDecompiler = null;
			resourceFiles = null;
		}
		classes.clear();
		classAliases.clear();
		rawByAlias.clear();
		memberAliases.clear();
		readers.clear();
		resourceEntries.clear();
	}

	public record ResourceEntry(String name, ResourceType type, long sizeBytes, long compressedBytes,
			String zipEntryName) {
	}

	public record ClassEntry(DexReader reader, int offset, String fqn, int access,
			String superClass, List<String> interfaces) {
		public IClassData data() {
			jadx.plugins.input.dex.sections.SectionReader section =
					new jadx.plugins.input.dex.sections.SectionReader(reader, offset);
			return new jadx.plugins.input.dex.sections.DexClassData(section,
					new jadx.plugins.input.dex.sections.annotations.AnnotationsParser(
							section.copy(), section.copy()));
		}
	}
}
