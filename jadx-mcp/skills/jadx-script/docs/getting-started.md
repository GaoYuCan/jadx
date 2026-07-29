# 入门、运行契约与脚本工程

## 目录

- [何时使用脚本](#何时使用脚本)
- [前置条件](#前置条件)
- [run_script 参数](#run_script-参数)
- [返回值](#返回值)
- [执行生命周期](#执行生命周期)
- [文本模式与路径模式](#文本模式与路径模式)
- [最小探针](#最小探针)
- [结构化脚本骨架](#结构化脚本骨架)
- [配置管理](#配置管理)
- [Kotlin 与依赖](#kotlin-与依赖)
- [日志和失败](#日志和失败)
- [开发循环](#开发循环)
- [使用 JADX CLI 测试](#使用-jadx-cli-测试)
- [安全边界](#安全边界)

## 何时使用脚本

少量定点问题优先使用普通 jadx-mcp 工具，例如：

- 反编译一个类或方法；
- 查看一个类的成员；
- 查询一个符号的直接引用；
- 在小范围内搜索；
- 查看一个失败方法的 smali。

满足以下任一条件时考虑脚本：

- 遍历大量类或方法；
- 对数百个目标执行同一种提取；
- 联合指令、方法、类、源码元数据或资源信息；
- 构建调用图、继承索引、数据流或别名关系；
- 需要稳定、可复现、可 diff 的结构化结果；
- 需要把结果交给后续程序或分析任务；
- 普通 MCP 查询会产生大量重复往返。

开发脚本前仍应先人工查看几个代表性样例。先确认稳定的结构证据，再把规则推广到全项目。

## 前置条件

先确认当前上下文实际暴露 `current_project` 和 `run_script`。Skill 不负责安装或管理 jadx-mcp 服务端依赖。

先加载项目：

```json
{
  "path": "/absolute/path/app.apk",
  "skip_resources": false
}
```

输入也可以是 `.dex`、`.jar`、`.class`、`.smali`、`.aar`、`.aab`、`.xapk`、`.apkm` 或已保存的 `.jadx` 项目。

项目不确定时调用 `current_project`。未加载项目时，`run_script` 返回未加载错误。

## run_script 参数

路径模式：

```json
{
  "script_path": "/absolute/path/analyze.jadx.kts"
}
```

文本模式：

```json
{
  "script_text": "val jadx = getJadxInstance()\njadx.afterLoad { log.info { \"classes=${jadx.classes.size}\" } }"
}
```

约束：

- `script_path` 和 `script_text` 必须严格二选一；
- 两者都为空或同时提供都会失败；
- 路径会转成规范化绝对路径；
- 路径必须存在且是普通文件；
- 路径文件名必须以 `.jadx.kts` 结尾；
- 文本模式会创建临时 `.jadx.kts`，执行后删除。

runner 对当前已经加载的 `JadxDecompiler` 执行脚本，不重新打开项目。

## 返回值

| 字段 | 含义 |
| --- | --- |
| `success` | 编译、顶层代码、回调和捕获日志均未出现错误 |
| `source` | `path` 或 `text` |
| `script_name` | 去掉 `.jadx.kts` 后缀的脚本名 |
| `script_path` | 仅路径模式返回 |
| `duration_ms` | 墙钟执行时间 |
| `after_load_callbacks` | 实际调用的 `afterLoad` 回调数 |
| `logs[]` | `level`、`message` 和可选 `throwable` |
| `error` | 基础设施异常或未捕获回调异常摘要 |

编译诊断通常进入 `logs`。反射、插件兼容或未捕获执行错误还可能进入 `error`。

runner 捕获到脚本 logger 的 `ERROR` 级事件时，`success` 也会变成 `false`。普通待复核项使用结构化记录或 `warn`。

## 执行生命周期

执行顺序：

1. 编译脚本；
2. 执行 import 之后的顶层声明和初始化；
3. `getJadxInstance()` 返回当前反编译器的脚本 wrapper；
4. 顶层代码注册一个或多个 `jadx.afterLoad {}`；
5. runner 按注册顺序立即调用这些回调；
6. 捕获日志、关闭脚本相关资源并返回。

顶层适合放：

- import；
- 数据类；
- 常量；
- 纯 helper；
- 配置解析；
- `val jadx = getJadxInstance()`；
- `jadx.afterLoad {}` 注册。

项目遍历、反编译和资源读取放进 `afterLoad`，使执行边界与项目加载状态保持一致。

避免注册长期存活的对象：

- 后台线程；
- shutdown hook；
- GUI action；
- 依赖脚本 classloader 的长期事件回调；
- `afterLoad` 之外的异步任务。

脚本运行期间持有 `JadxSession` 写锁。`open_file`、`close_file`、`rename`、`save_project` 和其他需要会话锁的操作必须等待脚本结束。

runner 会临时把脚本文件加入 `jadx.args.inputFiles`，执行后恢复。记录真实程序输入时过滤 `.jadx.kts`：

```kotlin
val programInputs = jadx.args.inputFiles
	.filterNot { it.name.endsWith(".jadx.kts") }
	.map { it.absolutePath }
```

## 文本模式与路径模式

`script_text` 适合：

- 类或资源计数；
- 验证一个 import；
- 查看一个 API 属性名；
- 输出少量 descriptor；
- 在写完整脚本前验证假设。

`script_path` 适合：

- 超过几十行的脚本；
- 有配置文件；
- 有多个输出文件；
- 需要版本管理、评审和复用；
- 需要基于 `jadx.scriptFile` 定位相邻文件；
- 需要保存和复用。

文本模式的 `jadx.scriptFile` 指向临时文件。不要依赖它的父目录保存长期结果。

## 最小探针

```kotlin
val jadx = getJadxInstance()

jadx.afterLoad {
	val sample = jadx.classes.asSequence()
		.filterNot { it.isNoCode }
		.take(10)
		.map { it.rawName }
		.toList()

	log.info {
		"classes=${jadx.classes.size}, sample=$sample"
	}
}
```

这个探针验证：

- 插件已加载；
- 脚本可编译；
- wrapper 可访问；
- `afterLoad` 被调用；
- 类列表可读；
- 日志可以被 runner 捕获。

## 结构化脚本骨架

```kotlin
import com.google.gson.GsonBuilder
import java.io.File

data class Config(
	val packagePrefix: String = "",
	val outputPath: String? = null,
)

data class ClassRecord(
	val rawName: String,
	val methodCount: Int,
	val fieldCount: Int,
)

data class Result(
	val schemaVersion: Int,
	val inputFiles: List<String>,
	val config: Config,
	val classes: List<ClassRecord>,
)

val jadx = getJadxInstance()
val gson = GsonBuilder()
	.setPrettyPrinting()
	.disableHtmlEscaping()
	.create()

jadx.afterLoad {
	val scriptDir = requireNotNull(jadx.scriptFile.parentFile)
	val configFile = File(scriptDir, "analysis-config.json")
	val config = if (configFile.isFile) {
		gson.fromJson(
			configFile.readText(Charsets.UTF_8),
			Config::class.java,
		)
	} else {
		Config()
	}

	val classes = jadx.classes.asSequence()
		.filter { it.rawName.startsWith(config.packagePrefix) }
		.map { cls ->
			ClassRecord(
				rawName = cls.rawName,
				methodCount = cls.methods.size,
				fieldCount = cls.fields.size,
			)
		}
		.sortedBy(ClassRecord::rawName)
		.toList()

	val inputs = jadx.args.inputFiles
		.filterNot { it.name.endsWith(".jadx.kts") }
		.map { it.absolutePath }
		.sorted()

	val result = Result(
		schemaVersion = 1,
		inputFiles = inputs,
		config = config,
		classes = classes,
	)

	val output = config.outputPath
		?.let(::File)
		?: File(scriptDir, "analysis-result.json")
	output.writeText(gson.toJson(result), Charsets.UTF_8)

	log.info {
		"classes=${classes.size}, output=${output.absolutePath}"
	}
}
```

正式脚本再加入：

- 输出路径校验；
- 默认拒绝覆盖或原子写入；
- 方法级失败记录；
- 进度计数；
- 输出自校验；
- 脚本和 schema 版本。

## 配置管理

样本特定事实应放配置：

- 类和方法目标；
- 包范围；
- 已知入口；
- 输入版本或哈希；
- 数量、深度和字节上限；
- 输出路径；
- 允许的 fallback；
- 明确的库方法摘要。

通用语义规则放代码。混淆符号可以是配置目标，不能成为算法判定语义的依据。

路径脚本从脚本旁读取配置：

```kotlin
fun sibling(scriptFile: File, name: String): File =
	File(requireNotNull(scriptFile.parentFile), name)
```

MCP 服务进程只在启动时继承环境变量。每次执行可能变化的配置使用相邻 JSON 或直接写入短 `script_text`。

遍历前校验：

```kotlin
require(config.packagePrefix.isNotBlank()) {
	"packagePrefix 不能为空"
}
require(config.maxDepth in 1..100) {
	"maxDepth 超出范围"
}
```

为了复现，把生效后的配置写入结果。

## Kotlin 与依赖

脚本编译器可以看到 jadx-mcp 运行时 classpath，通常包括：

- JADX API 和内部类；
- Kotlin 标准库与脚本运行时；
- Gson；
- SLF4J 和 Kotlin Logging；
- Java 标准库。

适合使用：

- data class；
- sealed class；
- extension function；
- sequence；
- 普通集合；
- 局部函数和闭包。

分析状态优先使用明确的 `Map`、`Set`、`List` 和 data class。复杂 DSL 会增加调试成本。

官方插件支持依赖声明：

```kotlin
@file:DependsOn("org.example:artifact:1.2.3")
@file:Repository("https://repo.example.invalid/releases")
```

外部依赖会触发解析和可能的网络访问，并以 MCP 服务权限加载代码。只有用户同意时使用，固定精确版本和仓库；已有 classpath 能完成时不额外引入依赖。

第一次编译或依赖变化可能较慢，后续执行可命中脚本缓存。

## 日志和失败

使用注入 logger：

```kotlin
log.debug { "调试细节" }
log.info { "processed=$processed" }
log.warn { "reviewItems=${reviewItems.size}" }
log.error(error) { "分析失败：$target" }
```

`print` 和 `println` 也会被重定向，但显式日志级别更容易区分状态。

日志保持紧凑：

- 开始时输出输入和配置摘要；
- 长任务按固定间隔输出进度；
- 单项预期失败用一条短 warning；
- 完成时输出计数、完整状态和结果路径；
- 完整记录写文件。

配置无效和内部不变量使用异常：

```kotlin
requireNotNull(targetClass) {
	"找不到目标类：${config.target}"
}

check(recordIds.size == recordIds.toSet().size) {
	"记录 ID 重复"
}
```

预期歧义使用结构化记录：

```kotlin
data class ReviewRecord(
	val target: String,
	val reason: String,
	val evidence: List<String>,
)
```

不要在整个分析外层捕获 `Throwable` 后返回成功。只在可以保留其他有效结果的最小单元捕获异常，并把失败写入输出。

## 开发循环

1. 用 `decompile_code`、smali、成员和引用工具检查已知样例。
2. 写最小 `script_text` 验证最不确定的 API 或不变量。
3. 表示层确认后转为路径脚本。
4. 在一个目标上运行并与人工分析比较。
5. 增加负例、重载、分支、寄存器复用和反编译失败样例。
6. 扩大到配置的包或入口范围。
7. 输出排序后的结构化结果。
8. 重复运行并 diff。
9. 对不确定项做小范围人工或后续自动验证。

推荐分两阶段：

- 发现阶段生成目标配置和代表性证据；
- 确定性提取阶段消费配置并扫描完整范围。

## 使用 JADX CLI 测试

这一节用于本地开发 `.jadx.kts`。jadx-mcp 仍是最终运行环境。

### 准备 CLI

确认已安装发行版：

```bash
jadx --version
```

确认脚本插件：

```bash
jadx plugins --list
```

列表中没有 `jadx-script-kotlin` 时安装官方插件：

```bash
jadx plugins --install "github:jadx-decompiler:jadx-script-kotlin"
```

安装后重新运行 `jadx plugins --list`。这些命令只用于本地 CLI；jadx-mcp 服务端插件缺失时，应保留 `run_script` 原始错误并交给服务部署方处理。

### 运行路径脚本

把脚本作为一个输入文件传给 `jadx`：

```bash
jadx \
  --no-res \
  -d /tmp/jadx-script-output \
  /absolute/path/input.dex \
  /absolute/path/analyze.jadx.kts
```

分析 APK 资源时去掉 `--no-res`：

```bash
jadx \
  -d /tmp/jadx-script-output \
  /absolute/path/app.apk \
  /absolute/path/analyze.jadx.kts
```

使用单独的输出目录，避免和正式反编译结果混在一起。脚本生成的 JSON 等文件仍由脚本自己的路径逻辑决定。

CLI 会把 `.jadx.kts` 放入 `jadx.args.inputFiles`。记录程序输入时继续过滤：

```kotlin
val programInputs = jadx.args.inputFiles
	.filterNot { it.name.endsWith(".jadx.kts") }
```

CLI 用于本地试跑 `.jadx.kts`。交付前仍需通过 `run_script` 在当前加载的 jadx-mcp 项目上执行一次。

## 安全边界

脚本是受信任的本地 JVM 代码，可以读写文件、启动进程、访问网络、修改全局状态并消耗任意 CPU/内存。

默认约束：

- 只读当前 JADX 项目状态；
- 输出到用户明确允许的目录；
- 不删除或覆盖无关文件；
- 默认拒绝覆盖，或使用临时文件加原子替换；
- 未经请求不访问网络；
- 加载后不修改 `jadx.args`；
- 不调用 `addPass`、`stages`、`rename`、`replace` 和 GUI API；
- 不启动无界 executor；
- 循环、递归、固定点和集合增长都有上限；
- 大项目增量处理；
- 流和 writer 使用 `use` 关闭；
- 不把凭据或敏感环境变量写进脚本、日志和结果。

`run_script` 当前没有内部超时。无限循环会一直占用会话写锁，直到进程被中断。
