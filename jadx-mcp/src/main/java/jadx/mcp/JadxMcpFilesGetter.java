package jadx.mcp;

import java.nio.file.Path;

import jadx.commons.app.JadxCommonFiles;
import jadx.commons.app.JadxTempFiles;
import jadx.core.plugins.files.IJadxFilesGetter;

/**
 * jadx-mcp's implementation of {@link IJadxFilesGetter}, mirroring the one in jadx-cli.
 * Kept private to this module so jadx-mcp doesn't have to depend on jadx-cli.
 */
final class JadxMcpFilesGetter implements IJadxFilesGetter {

	static final JadxMcpFilesGetter INSTANCE = new JadxMcpFilesGetter();

	private JadxMcpFilesGetter() {
	}

	@Override
	public Path getConfigDir() {
		return JadxCommonFiles.getConfigDir();
	}

	@Override
	public Path getCacheDir() {
		return JadxCommonFiles.getCacheDir();
	}

	@Override
	public Path getTempDir() {
		return JadxTempFiles.getTempRootDir();
	}
}
