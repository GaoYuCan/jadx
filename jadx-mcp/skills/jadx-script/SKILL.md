---
name: jadx-script
description: 为 jadx-mcp 当前加载的项目编写并运行 Kotlin `.jadx.kts` 分析脚本。适用于全项目或重复性逆向分析、提取类和资源的结构化数据、扫描原始字节码或 JADX IR、读取源码元数据、导出可复现结果，以及用一次确定性分析替代大量零散 MCP 查询。
---

# JADX 脚本

为 jadx-mcp 编写加载后分析脚本。脚本默认只读当前 JADX 项目状态；需要产出结果时，写入用户明确指定的文件。

MCP runner 支持：

- 顶层 import、数据类、函数和常量；
- `getJadxInstance()`；
- `jadx.afterLoad {}`；
- 路径脚本和文本脚本；
- 类、方法、字段、原始指令、已有 IR、源码元数据和资源分析。

MCP runner 不支持注册反编译 pass，也不支持脚本修改 rename、replace、GUI 或插件选项。

## 工作流程

1. 调用 `current_project`，确认加载的是目标输入。
2. 用普通 jadx-mcp 工具查看少量代表性类、方法、smali 或资源，确定要使用的分析层。
3. 十几行的 API 探针使用 `script_text`；需要配置、复用或输出文件的脚本使用 `.jadx.kts` 文件和 `script_path`。
4. 数据类和 helper 放在顶层；项目遍历放在 `jadx.afterLoad {}`。
5. 调用 `run_script`，`script_text` 和 `script_path` 严格二选一。
6. 检查 `success`、`after_load_callbacks`、`logs` 和 `error`。
7. 大结果写成确定性 JSON/JSONL，日志只输出路径、计数和失败摘要。
8. 用已知样例和至少一个反编译失败或有歧义的样例验证结果。

## 文档路由

按任务读取所需模块。模块彼此独立，主 SKILL 只保留入口和共通约束。

| 需求 | 文档 |
| --- | --- |
| runner 生命周期、路径/文本模式、脚本结构、配置和依赖 | [docs/getting-started.md](docs/getting-started.md) |
| `JadxDecompiler`、类、方法、字段、变量、包和符号解析 API | [docs/api-reference.md](docs/api-reference.md) |
| 原始字节码、处理后 IR、CFG、SSA 和指令节点 API | [docs/bytecode-ir-api.md](docs/bytecode-ir-api.md) |
| 反编译源码、位置映射、节点注解、变量元数据和证据定位 | [docs/source-metadata.md](docs/source-metadata.md) |
| AndroidManifest、资源表、XML、assets 和二进制资源 | [docs/resources.md](docs/resources.md) |

阅读原则：

- 先读 `getting-started.md`；
- 只做公开 API 遍历时再读 `api-reference.md`；
- 需要寄存器、偏移、控制流或 SSA 时读 `bytecode-ir-api.md`；
- 需要把源码片段精确映射回节点时读 `source-metadata.md`；
- 输入是 APK/AAB 且需要非代码信息时读 `resources.md`；
- 使用 `jadx.core` 内部 API 时，先用当前安装版本运行最小探针确认签名。

## 最小模板

```kotlin
import com.google.gson.GsonBuilder
import java.io.File

data class MethodRecord(
	val owner: String,
	val method: String,
)

val jadx = getJadxInstance()

jadx.afterLoad {
	val records = jadx.classes.asSequence()
		.filterNot { it.isNoCode }
		.flatMap { cls ->
			cls.methods.asSequence().map { method ->
				MethodRecord(
					owner = cls.rawName,
					method = method.methodNode.methodInfo.shortId,
				)
			}
		}
		.sortedWith(compareBy(MethodRecord::owner, MethodRecord::method))
		.toList()

	val output = File(jadx.scriptFile.parentFile, "jadx-script-result.json")
	val gson = GsonBuilder().setPrettyPrinting().create()
	output.writeText(gson.toJson(records), Charsets.UTF_8)
	log.info {
		"已写入 ${records.size} 条方法记录：${output.absolutePath}"
	}
}
```

## API 快速路由

| 任务 | 首选 API |
| --- | --- |
| 当前输入和选项 | `jadx.args` |
| 顶层类 | `jadx.classes` |
| 类名和成员 | `JavaClass.rawName`、`fullName`、`fields`、`methods` |
| 完整类源码 | `JavaClass.codeInfo.codeStr` |
| 单方法源码 | `JavaMethod.codeStr` |
| Java 反编译失败后的 smali | `JavaClass.smali` |
| 方法精确身份 | `method.methodNode.methodInfo.rawFullId` 或 `shortId` |
| 原始名/别名查类 | `searchJavaClassByOrigFullName(...)`、`searchJavaClassByAliasFullName(...)` |
| 原始字节码 | `MethodNode.codeReader`、`InsnData` |
| 处理后 IR | `MethodNode.basicBlocks`、`region`、`sVars`；早期阶段才使用 `instructions` |
| 源码注解和行映射 | `JavaClass.codeInfo.codeMetadata` |
| Android 资源 | `jadx.internalDecompiler.resources` |
| 日志 | `log.info {}`、`log.warn {}`、`log.error {}` |

Kotlin 属性会映射 Java getter。例如 `cls.methods` 调用 `getMethods()`。

## 核心模式

### 精确解析类和重载方法

```kotlin
fun findClass(name: String) =
	jadx.internalDecompiler.searchJavaClassByOrigFullName(name)
		?: jadx.internalDecompiler.searchJavaClassByAliasFullName(name)

val cls = requireNotNull(findClass("com.example.Target")) {
	"找不到类"
}

val method = requireNotNull(cls.methods.firstOrNull {
	it.methodNode.methodInfo.shortId ==
		"execute(I)Ljava/lang/String;"
}) {
	"找不到方法"
}
```

持久标识使用原始完整类名和 JVM/Dex descriptor。显示名或 alias 只作为附加字段。

### 扫描原始指令

反编译 Java 不完整、需要寄存器或指令偏移、或源码正则不可靠时使用：

```kotlin
import jadx.api.plugins.input.insns.InsnIndexType

val reader = method.methodNode.codeReader
	?: error("方法没有字节码")

reader.copy().visitInstructions { insn ->
	insn.decode()
	if (insn.indexType == InsnIndexType.METHOD_REF) {
		val callee = insn.indexAsMethod
		callee.load()
		val descriptor =
			"(${callee.argTypes.joinToString("")})${callee.returnType}"
		log.info {
			"${method.methodNode.methodInfo.rawFullId}" +
				"@${insn.offset}: " +
				"${callee.parentClassType}.${callee.name}$descriptor"
		}
	}
}
```

读取寄存器、常量、跳转目标或索引引用前必须调用 `decode()`。使用 `copy()`，因为 instruction reader 有状态。

### 使用源码元数据

```kotlin
val codeInfo = cls.codeInfo
val source = codeInfo.codeStr
val metadata = codeInfo.codeMetadata
val annotations = metadata.asMap
val lineMapping = metadata.lineMapping
```

源码文本适合候选发现和展示。需要把位置关联到类、方法、字段或变量时，优先用 metadata 和已解析节点。

### Java 反编译失败时降级

```kotlin
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.attributes.AType

cls.decompile()
val failedMethods = cls.classNode.methods.filter { method ->
	method.contains(AType.JADX_ERROR) ||
		method.contains(AFlag.INCONSISTENT_CODE)
}

if (failedMethods.isNotEmpty()) {
	val smali = cls.smali
	// 也可以逐个扫描 failedMethods 的 codeReader。
}
```

类源码可能部分可用。保留已经成功提取的事实，并把失败方法记录到 `reviewItems`；不能把缺失代码当成空方法。

### 从脚本旁加载配置

```kotlin
val config = File(
	jadx.scriptFile.parentFile,
	"analysis-config.json",
)
require(config.isFile) {
	"缺少配置：${config.absolutePath}"
}
```

样本特定的类名、方法 descriptor、包范围和限制参数放入配置。算法根据结构化事实工作，不依赖混淆名称表达的语义。

## 共通约束

1. 不调用 `jadx.addPass`、`jadx.stages`、`jadx.rename`、`jadx.replace`、选项注册或 GUI API。
2. 不根据混淆后的类名、字段名或方法名推断语义。
3. 重载方法按 owner、原始名称、参数 descriptor 和返回 descriptor 匹配。
4. 寄存器表示存储位置。相同编号不保证始终是同一对象，不同编号也可能别名到同一对象。
5. 符号关系优先使用字节码引用和 JADX 节点，避免解析渲染后的 Java 字符串。
6. 项目级分析先走便宜且精确的原始扫描，再反编译候选类。
7. 每条结果保留 owner、方法 descriptor、指令或源码位置、证据类型和命中规则。
8. 有歧义时输出待复核项和原因，不伪造确定结论。
9. 输出排序并使用稳定 ID，保证重复执行可比较。
10. 脚本以 MCP 服务进程权限执行，禁止破坏性写入、无界循环、隐式网络访问和失控线程。

## 交付要求

- 小探针通过日志返回；
- 大结果写入 JSON/JSONL；
- 日志包含输出路径、扫描计数、记录数、失败数和完整状态；
- 结果包含输入文件、有效配置、schema 版本和脚本版本；
- 为反编译失败、资源失败和达到分析上限分别保留明确状态；
- 最终通过 jadx-mcp `run_script` 真实运行一次，而不只做静态检查。

## 快速排错

- `run_script` 不存在：当前 jadx-mcp 服务没有提供脚本能力，停止并向用户说明。
- `run_script` 返回脚本插件缺失：这是 jadx-mcp 服务端部署问题，保留原始错误并向用户说明。
- 编译失败：先运行最小 API 探针；内部 API 可能随安装的 JADX 版本变化。
- 回调未运行：确认项目已加载，并把遍历放进 `jadx.afterLoad {}`。
- 找不到方法：输出 `methodInfo.shortId` 或 `rawFullId` 后再匹配。
- Java 反编译异常：检查 `MethodNode.codeReader` 和 `JavaClass.smali`。
- 资源为空：确认 `open_file` 没有设置 `skip_resources=true`。
- 输出过大：改写 JSONL，只在日志返回摘要。
- 需要自定义 decompile pass：当前 runner 不支持；改用加载后 API、原始指令或已有 IR 完成分析。
