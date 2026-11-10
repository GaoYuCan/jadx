package jadx.mcp.dex;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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
import jadx.api.impl.AnnotatedCodeWriter;
import jadx.api.plugins.input.ICodeLoader;
import jadx.api.plugins.input.data.IClassData;
import jadx.api.plugins.input.data.impl.EmptyCodeLoader;
import jadx.mcp.JadxMcpFilesGetter;
import jadx.mcp.util.ToolException;
import jadx.plugins.input.dex.DexFileLoader;
import jadx.plugins.input.dex.DexInputOptions;
import jadx.plugins.input.dex.DexReader;
import jadx.plugins.input.dex.sections.DexConsts;
import jadx.plugins.tools.JadxExternalPluginsLoader;
import jadx.zip.IZipEntry;
import jadx.zip.ZipContent;
import jadx.zip.ZipReader;

/** A project-wide class directory backed by original DEX buffers, without loading ClassNodes. */
public final class DexProject implements Closeable {
	private final Map<String, ClassEntry> classes = new LinkedHashMap<>();
	private final File input;
	private final boolean apk;
	private final ZipReader zipReader = new ZipReader();
	private final List<ResourceEntry> resourceEntries = new ArrayList<>();
	private JadxDecompiler resourceDecompiler;
	private List<ResourceFile> resourceFiles;

	public DexProject(File input) throws IOException {
		this.input = input;
		byte[] magic;
		try (InputStream stream = Files.newInputStream(input.toPath())) {
			magic = stream.readNBytes(DexConsts.MAX_MAGIC_SIZE);
		}
		apk = Arrays.equals(magic, DexConsts.ZIP_FILE_MAGIC);
		DexFileLoader loader = new DexFileLoader(new DexInputOptions());
		List<DexReader> readers = new ArrayList<>();
		if (Arrays.equals(magic, DexConsts.DEX_FILE_MAGIC)) {
			readers.addAll(loader.loadDexReaders(input.getAbsolutePath(), Files.readAllBytes(input.toPath())));
			resourceEntries.add(new ResourceEntry(input.getAbsolutePath(), ResourceType.CODE,
					input.length(), -1));
		} else if (apk) {
			try (ZipContent zip = zipReader.open(input)) {
				IZipEntry manifest = zip.searchEntry("AndroidManifest.xml");
				if (manifest == null || manifest.isDirectory()) {
					throw new ToolException(ToolException.Code.UNSUPPORTED, "APK is missing AndroidManifest.xml: " + input);
				}
				for (IZipEntry entry : zip.getEntries()) {
					if (entry.isDirectory()) {
						continue;
					}
					resourceEntries.add(new ResourceEntry(entry.getName(),
							ResourceType.getFileType(entry.getName()), entry.getUncompressedSize(),
							entry.getCompressedSize()));
					if (isRuntimeDex(entry.getName())) {
						readers.addAll(loader.loadDexReaders(input.getName() + ':' + entry.getName(), entry.getBytes()));
					}
				}
			}
		} else {
			throw new ToolException(ToolException.Code.UNSUPPORTED, "Expected an APK or DEX input: " + input);
		}
		for (DexReader reader : readers) {
			reader.visitClasses(data -> {
				String fqn = dotName(data.getType());
				// DexReader reuses its class-data cursor; copy captures this definition's offset.
				classes.putIfAbsent(fqn, new ClassEntry(reader, data.copy().getInputFileOffset(), fqn,
						data.getAccessFlags(), dotName(data.getSuperType()),
						data.getInterfacesTypes().stream().map(DexProject::dotName).toList()));
			});
		}
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
			if (apk) {
				local.getResourcesLoader().defaultLoadFile(files, input, "");
			} else {
				files.add(ResourceFile.createResourceFile(local, input, ResourceType.CODE));
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
		ClassEntry entry = classes.get(fqn);
		if (entry == null) {
			throw ToolException.notFound("class", fqn);
		}
		return entry;
	}

	public JadxDecompiler decompiler(Set<String> classNames) {
		for (String className : classNames) {
			find(className);
		}
		JadxArgs args = new JadxArgs();
		args.setSkipResources(true);
		args.setPluginLoader(new JadxExternalPluginsLoader());
		args.setFilesGetter(JadxMcpFilesGetter.INSTANCE);
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
		resourceEntries.clear();
	}

	public record ResourceEntry(String name, ResourceType type, long sizeBytes, long compressedBytes) {
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
