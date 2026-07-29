package jadx.mcp.script;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import jadx.api.JadxDecompiler;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.options.JadxPluginOptions;
import jadx.core.plugins.PluginContext;
import jadx.mcp.util.ToolException;

/**
 * Reflection adapter for the external {@code jadx-script-kotlin} plugin.
 * <p>
 * Keeping the plugin behind reflection lets jadx-mcp start and expose its other tools when
 * scripting
 * support is not installed. The actual script API and compiler remain owned by the official plugin.
 */
public final class KotlinScriptRunner implements ScriptRunner {

	private static final String PLUGIN_ID = "jadx-script-kotlin";
	private static final String SCRIPT_SUFFIX = ".jadx.kts";
	private static final String SCRIPT_LOG_PREFIX = "JadxScript:";
	private static final String SCRIPT_EVAL_CLASS = "jadx.plugins.script.kotlin.eval.ScriptEval";

	@Override
	public ScriptRunResult run(JadxDecompiler decompiler, Path scriptPath) {
		PluginContext pluginContext = findPluginContext(decompiler);
		ClassLoader pluginClassLoader = pluginContext.getPluginInstance().getClass().getClassLoader();
		File scriptFile = scriptPath.toFile();
		String scriptName = removeSuffix(scriptFile.getName(), SCRIPT_SUFFIX);
		CapturingAppender appender = new CapturingAppender();
		Logger scriptLogger = (Logger) LoggerFactory.getLogger(SCRIPT_LOG_PREFIX + scriptName);
		Level previousLevel = scriptLogger.getLevel();
		boolean previousAdditive = scriptLogger.isAdditive();
		scriptLogger.setLevel(Level.DEBUG);
		scriptLogger.setAdditive(false);
		appender.setContext(scriptLogger.getLoggerContext());
		appender.start();
		scriptLogger.addAppender(appender);

		long started = System.nanoTime();
		RunState state = new RunState();
		List<File> inputFiles = decompiler.getArgs().getInputFiles();
		List<File> originalInputFiles = new ArrayList<>(inputFiles);
		try {
			inputFiles.removeIf(file -> file.getName().endsWith(SCRIPT_SUFFIX));
			inputFiles.add(scriptFile);
			pluginContext.classLoaderWrap(() -> execute(
					decompiler,
					pluginContext,
					pluginClassLoader,
					scriptFile,
					state));
		} catch (ToolException e) {
			throw e;
		} catch (Throwable t) {
			state.failure = unwrap(t);
		} finally {
			inputFiles.clear();
			inputFiles.addAll(originalInputFiles);
			scriptLogger.detachAppender(appender);
			appender.stop();
			scriptLogger.setLevel(previousLevel);
			scriptLogger.setAdditive(previousAdditive);
		}

		List<ScriptLogEntry> logs = appender.snapshot();
		boolean loggedError = logs.stream().anyMatch(entry -> "ERROR".equals(entry.level));
		boolean success = state.failure == null && !state.scriptError && !loggedError;
		String error = state.failure == null ? null : describe(state.failure);
		long durationMs = (System.nanoTime() - started) / 1_000_000;
		return new ScriptRunResult(
				success,
				scriptName,
				durationMs,
				state.afterLoadCallbacks,
				logs,
				error);
	}

	private static PluginContext findPluginContext(JadxDecompiler decompiler) {
		return decompiler.getPluginManager().getResolvedPluginContexts().stream()
				.filter(context -> PLUGIN_ID.equals(context.getPluginId()))
				.findFirst()
				.orElseThrow(() -> new ToolException(
						ToolException.Code.UNSUPPORTED,
						"`run_script` requires the official `" + PLUGIN_ID
								+ "` plugin. Install it with "
								+ "`jadx plugins --install \"github:jadx-decompiler:jadx-script-kotlin\"`."));
	}

	private static void execute(JadxDecompiler decompiler, PluginContext pluginContext,
			ClassLoader pluginClassLoader, File scriptFile, RunState state) {
		Object pluginData = null;
		try {
			JadxPluginOptions registeredOptions = pluginContext.getOptions();
			if (registeredOptions == null) {
				throw new ToolException(ToolException.Code.UNSUPPORTED,
						"The `" + PLUGIN_ID + "` plugin is loaded but did not register its options.");
			}
			JadxPluginOptions options = (JadxPluginOptions) registeredOptions.getClass()
					.getConstructor()
					.newInstance();
			options.setOptions(decompiler.getArgs().getPluginOptions());
			Class<?> evalClass = pluginClassLoader.loadClass(SCRIPT_EVAL_CLASS);
			Object evaluator = evalClass.getConstructor().newInstance();
			Method process = findProcessMethod(evalClass, options.getClass());
			JadxPluginContext readOnlyContext = readOnlyContext(pluginContext);
			pluginData = process.invoke(evaluator, readOnlyContext, options);

			List<?> scriptsData = invokeList(pluginData, "getScriptsData");
			Object scriptData = scriptsData.stream()
					.filter(data -> scriptFile.equals(invoke(data, "getScriptFile")))
					.findFirst()
					.orElseThrow(() -> new IllegalStateException(
							"Script plugin did not evaluate " + scriptFile.getAbsolutePath()));
			state.scriptError = (boolean) invoke(scriptData, "getError");
			if (!state.scriptError) {
				runAfterLoadCallbacks(scriptData, pluginClassLoader, state);
				state.scriptError = (boolean) invoke(scriptData, "getError");
			}
		} catch (InvocationTargetException e) {
			state.failure = unwrap(e);
		} catch (ToolException e) {
			throw e;
		} catch (ReflectiveOperationException | LinkageError e) {
			throw new ToolException(
					ToolException.Code.UNSUPPORTED,
					"Installed `" + PLUGIN_ID + "` version is incompatible with `run_script`: "
							+ e.getClass().getSimpleName() + ": " + e.getMessage(),
					null,
					e);
		} catch (Throwable t) {
			state.failure = unwrap(t);
		} finally {
			closePluginData(pluginData, state);
		}
	}

	private static Method findProcessMethod(Class<?> evalClass, Class<?> optionsClass)
			throws NoSuchMethodException {
		for (Method method : evalClass.getMethods()) {
			Class<?>[] parameters = method.getParameterTypes();
			if (method.getName().equals("process")
					&& parameters.length == 2
					&& parameters[0] == JadxPluginContext.class
					&& parameters[1].isAssignableFrom(optionsClass)) {
				return method;
			}
		}
		throw new NoSuchMethodException(evalClass.getName() + ".process(JadxPluginContext, "
				+ optionsClass.getName() + ")");
	}

	private static JadxPluginContext readOnlyContext(PluginContext pluginContext) {
		InvocationHandler handler = (proxy, method, args) -> {
			String name = method.getName();
			if (name.equals("addPass")
					|| name.equals("addCodeInput")
					|| name.equals("registerOptions")
					|| name.equals("registerInputsHashSupplier")) {
				throw new UnsupportedOperationException(
						"`run_script` does not support changing jadx plugins or registering decompile passes");
			}
			try {
				return method.invoke(pluginContext, args);
			} catch (InvocationTargetException e) {
				throw unwrap(e);
			}
		};
		return (JadxPluginContext) Proxy.newProxyInstance(
				JadxPluginContext.class.getClassLoader(),
				new Class<?>[] { JadxPluginContext.class },
				handler);
	}

	private static void runAfterLoadCallbacks(Object scriptData, ClassLoader pluginClassLoader, RunState state) {
		List<?> callbacks = invokeList(scriptData, "getAfterLoad");
		state.afterLoadCallbacks = callbacks.size();
		if (callbacks.isEmpty()) {
			return;
		}
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try {
			Object scriptClassLoader = invoke(scriptData, "getScriptClassLoader");
			if (scriptClassLoader instanceof ClassLoader classLoader) {
				Thread.currentThread().setContextClassLoader(classLoader);
			} else {
				Thread.currentThread().setContextClassLoader(pluginClassLoader);
			}
			Class<?> functionClass = pluginClassLoader.loadClass("kotlin.jvm.functions.Function0");
			Method invoke = functionClass.getMethod("invoke");
			for (Object callback : callbacks) {
				try {
					invoke.invoke(callback);
				} catch (InvocationTargetException e) {
					Throwable failure = unwrap(e);
					invoke(scriptData, "setError", new Class<?>[] { boolean.class }, true);
					state.failure = failure;
					return;
				}
			}
		} catch (ReflectiveOperationException e) {
			state.failure = e;
		} finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	private static void closePluginData(@Nullable Object pluginData, RunState state) {
		if (pluginData == null) {
			return;
		}
		try {
			invoke(pluginData, "close");
		} catch (Throwable t) {
			if (state.failure == null) {
				state.failure = unwrap(t);
			} else {
				state.failure.addSuppressed(unwrap(t));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static List<?> invokeList(Object target, String method) {
		return (List<?>) invoke(target, method);
	}

	private static Object invoke(Object target, String method) {
		return invoke(target, method, new Class<?>[0]);
	}

	private static Object invoke(Object target, String method, Class<?>[] parameterTypes, Object... args) {
		try {
			return target.getClass().getMethod(method, parameterTypes).invoke(target, args);
		} catch (InvocationTargetException e) {
			throw new ReflectionFailure(unwrap(e));
		} catch (ReflectiveOperationException e) {
			throw new ReflectionFailure(e);
		}
	}

	private static Throwable unwrap(Throwable throwable) {
		Throwable current = throwable;
		while ((current instanceof InvocationTargetException || current instanceof ReflectionFailure)
				&& current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static String describe(Throwable throwable) {
		String message = throwable.getMessage();
		if (message == null || message.isBlank()) {
			return throwable.getClass().getSimpleName();
		}
		return throwable.getClass().getSimpleName() + ": " + message;
	}

	private static String removeSuffix(String value, String suffix) {
		return value.endsWith(suffix)
				? value.substring(0, value.length() - suffix.length())
				: value;
	}

	private static final class RunState {
		private boolean scriptError;
		private int afterLoadCallbacks;
		private Throwable failure;
	}

	private static final class ReflectionFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private ReflectionFailure(Throwable cause) {
			super(cause);
		}
	}

	private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
		private final List<ScriptLogEntry> entries = new ArrayList<>();

		@Override
		protected synchronized void append(ILoggingEvent event) {
			String throwable = event.getThrowableProxy() == null
					? null
					: ThrowableProxyUtil.asString(event.getThrowableProxy());
			entries.add(new ScriptLogEntry(
					event.getLevel().levelStr,
					event.getFormattedMessage(),
					throwable));
		}

		private synchronized List<ScriptLogEntry> snapshot() {
			return List.copyOf(entries);
		}
	}
}
