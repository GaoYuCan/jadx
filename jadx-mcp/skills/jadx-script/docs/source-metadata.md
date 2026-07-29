# 反编译源码、Metadata、位置与变量

## 目录

- [ICodeInfo](#icodeinfo)
- [区分坐标系](#区分坐标系)
- [枚举 metadata](#枚举-metadata)
- [从源码位置解析节点](#从源码位置解析节点)
- [枚举变量](#枚举变量)
- [字节码 offset 与源码位置](#字节码-offset-与源码位置)
- [生成行与原始行](#生成行与原始行)
- [提取方法源码](#提取方法源码)
- [文本搜索与 metadata 联合使用](#文本搜索与-metadata-联合使用)
- [移动、内联和重复渲染](#移动内联和重复渲染)
- [源码生成失败](#源码生成失败)
- [导出源码证据](#导出源码证据)

## ICodeInfo

`JavaClass.codeInfo` 返回 `ICodeInfo`：

```kotlin
val codeInfo = cls.codeInfo
val source = codeInfo.codeStr
val metadata = codeInfo.codeMetadata
val hasMetadata = codeInfo.hasMetadata()
```

`ICodeInfo` 包含：

- 生成的 Java 源码文本；
- 字符位置上的注解；
- 生成行到原始源码行的映射。

jadx-mcp 使用 annotated code writer，正常类源码通常带 metadata。以下情况可能没有：

- 代码生成失败；
- fallback 返回简单文本；
- 方法无代码；
- 某些插件提供的代码；
- 当前反编译选项没有产生相应注解。

读取 `codeInfo` 会触发类反编译。项目级扫描先筛选候选，避免无条件生成所有源码。

## 区分坐标系

JADX 分析中常见坐标：

| 坐标 | 示例 | 来源 |
| --- | --- | --- |
| 字符偏移 | `1532` | `ICodeMetadata` 的 key |
| 生成源码行 | `47` | 反编译 Java 的行 |
| 生成源码列 | `18` | 从字符偏移计算 |
| 原始源码行 | `123` | debug line mapping |
| 方法指令 offset | `0x42` | `InsnData.offset` / `InsnNode.offset` |
| 输入文件 offset | `0x12ab4` | `InsnData.fileOffset` |
| 变量句柄 | `r2v0` | 寄存器号与 SSA 版本 |

证据结构明确命名：

```kotlin
data class SourceLocation(
	val charOffset: Int?,
	val generatedLine: Int?,
	val generatedColumn: Int?,
	val originalLine: Int?,
	val instructionOffset: Int?,
	val fileOffset: Int?,
)
```

常见错误：

- 把生成源码行叫作 DEX 行；
- 把字符 offset 当指令 offset；
- 把 `fileOffset` 当方法内 offset；
- 用 source variable 名代替 SSA identity；
- 忽略列从 1 还是 0 开始。

输出协议中写清楚约定。本文示例字符 offset 从 0 开始，行和列从 1 开始。

## 枚举 metadata

注解实现 `ICodeAnnotation`，`annType` 常见值：

| 类型 | 含义 |
| --- | --- |
| `CLASS` | 类节点引用 |
| `FIELD` | 字段节点引用 |
| `METHOD` | 方法节点引用 |
| `PKG` | 包引用 |
| `VAR` | 变量定义节点 |
| `VAR_REF` | 指向变量定义的引用 |
| `DECLARATION` | 声明节点的 `NodeDeclareRef` |
| `OFFSET` | `InsnCodeOffset` |
| `END` | 类或方法体结束 |

按位置枚举：

```kotlin
for (
	(position, annotation) in
	codeInfo.codeMetadata.asMap.toSortedMap()
) {
	log.debug {
		"position=$position, " +
			"type=${annotation.annType}, " +
			"annotation=$annotation"
	}
}
```

一个大类可能包含大量 `VAR_REF`。先按 `annType` 筛选，再构建结果。

声明注解包装实际 node：

```kotlin
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.NodeDeclareRef

for ((position, annotation) in metadata.asMap) {
	if (
		annotation.annType ==
		ICodeAnnotation.AnnType.DECLARATION
	) {
		val declaration = annotation as NodeDeclareRef
		val nodeRef = declaration.node
		log.debug {
			"declare@$position -> $nodeRef"
		}
	}
}
```

不要依赖 annotation 的默认 `toString()` 作为稳定输出。转换到 API node 后导出原始符号 ID。

## 从源码位置解析节点

通过 `JadxDecompiler` 转换注解：

```kotlin
val decompiler = jadx.internalDecompiler

for ((position, annotation) in metadata.asMap) {
	val node = decompiler.getJavaNodeByCodeAnnotation(
		codeInfo,
		annotation,
	)
	if (node != null) {
		log.debug {
			"position=$position -> " +
				"${node.javaClass.simpleName} " +
				node.fullName
		}
	}
}
```

任意字符位置：

```kotlin
val exact = decompiler.getJavaNodeAtPosition(
	codeInfo,
	position,
)
val closest = decompiler.getClosestJavaNode(
	codeInfo,
	position,
)
val enclosing = decompiler.getEnclosingNode(
	codeInfo,
	position,
)
```

选择：

- `exact`：位置正好落在带注解 token；
- `closest`：位置落在符号 token 内或附近；
- `enclosing`：查当前方法或类。

解析结果可能为空：

- 该文本是关键字、空白或生成注释；
- metadata 不完整；
- 位置越界；
- 源码和 metadata 来自不同 `ICodeInfo`；
- 节点没有 API wrapper。

永远把 position 与产生它的同一个 `codeInfo` 配对。

一个原始节点可能渲染在另一个 code owner 内。证据同时保留：

```kotlin
val declaredBy = node.declaringClass?.rawName
val renderedIn = cls.rawName
```

## 枚举变量

变量声明通常表现为 `NodeDeclareRef`，转换后得到 `JavaVariable`：

```kotlin
import jadx.api.JavaVariable
import jadx.api.metadata.ICodeAnnotation

val variables = buildList {
	for ((position, annotation) in metadata.asMap) {
		if (
			annotation.annType !=
			ICodeAnnotation.AnnType.DECLARATION
		) {
			continue
		}

		val node = jadx.internalDecompiler
			.getJavaNodeByCodeAnnotation(
				codeInfo,
				annotation,
			)
		if (node is JavaVariable) {
			add(position to node)
		}
	}
}
```

导出：

```kotlin
data class VariableRecord(
	val methodTarget: String,
	val variableId: String,
	val name: String?,
	val type: String,
	val declarationOffset: Int,
)

val records = variables.map { (position, variable) ->
	val methodInfo = variable.mth.methodNode.methodInfo
	VariableRecord(
		methodTarget =
			"${methodInfo.declClass.makeRawFullName()}" +
				"#${methodInfo.shortId}",
		variableId =
			"r${variable.reg}v${variable.ssa}",
		name = variable.name,
		type = variable.type.toString(),
		declarationOffset = position,
	)
}
```

`r<register>v<ssa>` 与 jadx-mcp variable rename 使用的句柄一致。

变量边界：

- 一个源码变量可能合并多个 SSA 变量；
- 临时表达式可能没有渲染声明；
- debug 名与生成名可能不同；
- 重新加载、rename 或改变反编译选项会改变 SSA version；
- `VAR_REF` 会关联回变量定义；
- 无 debug 信息仍可能由 JADX 生成临时变量。

解析 `VAR_REF`：

```kotlin
val variable = jadx.internalDecompiler
	.getJavaNodeByCodeAnnotation(
		codeInfo,
		annotation,
	) as? JavaVariable
```

需要列出所有变量引用位置时，用 `variableId` 关联，不按显示名关联。

## 字节码 offset 与源码位置

`InsnCodeOffset` 把生成源码位置关联到原始方法内指令 offset：

```kotlin
import jadx.api.metadata.ICodeAnnotation
import jadx.api.metadata.annotations.InsnCodeOffset

data class OffsetMapping(
	val sourcePosition: Int,
	val instructionOffset: Int,
)

val mappings = metadata.asMap.mapNotNull {
	(position, annotation) ->
	if (
		annotation.annType ==
		ICodeAnnotation.AnnType.OFFSET
	) {
		OffsetMapping(
			sourcePosition = position,
			instructionOffset =
				(annotation as InsnCodeOffset).offset,
		)
	} else {
		null
	}
}.sortedBy(OffsetMapping::sourcePosition)
```

不是每个 token 都有 offset。JADX 可能把多条指令合并成一个表达式，也可能移动或消除中间值。

查前方最近 offset：

```kotlin
val offsetAnnotation = metadata.searchUp(
	position,
	ICodeAnnotation.AnnType.OFFSET,
) as? InsnCodeOffset
```

方向：

- `searchUp`：向更小字符位置；
- `searchDown`：向更大字符位置。

“最近”只表示文本位置最近，不自动证明语义属于同一个子表达式。敏感任务还要检查 enclosing method、节点 annotation 和原始指令。

从指令 offset 反查源码位置时，可能有多个位置或没有位置：

```kotlin
val positionsByInsn = mappings.groupBy(
	keySelector = OffsetMapping::instructionOffset,
	valueTransform = OffsetMapping::sourcePosition,
)
```

输出使用列表，不强行选择唯一位置。

## 生成行与原始行

`lineMapping`：

```kotlin
val originalLine =
	metadata.lineMapping[generatedLine]
```

类 helper：

```kotlin
val originalLine = cls.getSourceLine(generatedLine)
```

映射可能为空或不完整，常见于：

- DEX 没有调试行号；
- 优化后多条原始行合并；
- synthetic 代码；
- Kotlin 编译产物；
- inline 或 coroutine state machine；
- 代码生成失败。

字符位置转生成行列：

```kotlin
data class LineColumn(
	val line: Int,
	val column: Int,
)

fun lineColumn(
	source: String,
	position: Int,
): LineColumn {
	require(position in 0..source.length)
	var line = 1
	var lineStart = 0
	for (index in 0 until position) {
		if (source[index] == '\n') {
			line++
			lineStart = index + 1
		}
	}
	return LineColumn(
		line = line,
		column = position - lineStart + 1,
	)
}
```

大量位置时预计算每一行起点并二分查找，避免对每个位置从头扫描。

## 提取方法源码

使用 API：

```kotlin
val methodSource = method.codeStr
```

它会从代码所属顶层类中提取方法，能处理内部类和移动后的代码。不要自己用花括号计数切方法。

方法源码可能为空：

- abstract；
- native；
- 被禁止生成；
- code generation 失败；
- 方法被移动或内联后没有独立渲染体。

结果始终附带原始方法 ID：

```kotlin
data class MethodSource(
	val target: String,
	val source: String,
	val complete: Boolean,
	val failure: String?,
)
```

descriptor 从 `MethodInfo.shortId` 获取，不通过正则解析 Java 方法声明。

如果需要方法在完整类源码中的绝对 char range，应读取 declaration/end metadata；不要假设 `method.codeStr` 中的偏移等于 `class.codeInfo` 中的偏移。

## 文本搜索与 metadata 联合使用

文本搜索适合候选发现：

```kotlin
val pattern = Regex("""\bClass\.forName\s*\(""")

for (match in pattern.findAll(source)) {
	val position = match.range.first
	val enclosing = jadx.internalDecompiler
		.getEnclosingNode(codeInfo, position)
	val closest = jadx.internalDecompiler
		.getClosestJavaNode(codeInfo, position)
}
```

正确流程：

1. 文本搜索产生候选位置；
2. metadata 确定包围方法和最近节点；
3. 原始引用或 IR 验证调用身份；
4. 输出短 snippet 供人工阅读。

文本搜索适合：

- 生成错误注释；
- 反编译输出中的常量文本；
- 展示片段；
- 小范围候选缩减；
- JADX 已经还原的语法模式。

不适合单独证明：

- 方法身份和重载；
- receiver 对象身份；
- 别名关系；
- 调用图；
- 字段读写方向；
- 所有控制流路径；
- 被内联或未渲染的操作。

短片段：

```kotlin
fun snippet(
	source: String,
	start: Int,
	radius: Int = 120,
): String {
	val from = (start - radius).coerceAtLeast(0)
	val to = (start + radius)
		.coerceAtMost(source.length)
	return source.substring(from, to)
}
```

输出 snippet 前可做换行、控制字符和敏感内容处理，但保留原始 char offset。

## 移动内联和重复渲染

JADX 可能：

- 把内部类渲染在顶层父类中；
- 把匿名类代码放在使用点附近；
- 移动类或方法；
- 内联类或方法；
- 让 `codeParent` 与原始 owner 不同。

jadx-mcp 会关闭部分改变结构的选项，但语言 metadata、输入插件和 rename 仍可能影响呈现。

同时记录：

```kotlin
val rawOwner = node.declaringClass?.rawName
val topCodeOwner = node.topParentClass?.rawName
val renderedClass = cls.rawName
```

语义节点按原始符号 ID 去重。任务关注每个 call site 或渲染位置时，保留各 source occurrence。

不要按源码位置去重类或方法：同一语义节点的渲染位置可能变化，多个不同节点也可能出现相同文本。

## 源码生成失败

失败信号：

- `AType.JADX_ERROR`；
- `AFlag.INCONSISTENT_CODE`；
- 类代码包含 stack trace 或 error comment；
- metadata 为空；
- 方法体缺失；
- `codeStr` 异常为空；
- 全局 error/warning 计数增加。

降级：

```kotlin
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.attributes.AType

cls.decompile()

val failedMethods = cls.classNode.methods.filter { method ->
	method.contains(AType.JADX_ERROR) ||
		method.contains(AFlag.INCONSISTENT_CODE)
}

for (method in failedMethods) {
	val reader = method.codeReader
	if (reader != null) {
		// 扫描原始指令。
	} else {
		val smali = cls.smali
		// 保存该方法附近的有限证据。
	}
}
```

同一个类的其他方法可能仍然有效。只把失败方法标记为 incomplete。

不要把带错误注释的生成源码当真实程序语义。对其中已经解析出的符号，可以保留为低层事实并附加失败状态。

## 导出源码证据

通用结构：

```kotlin
data class SourceEvidence(
	val classRawName: String,
	val methodTarget: String?,
	val charOffset: Int?,
	val generatedLine: Int?,
	val generatedColumn: Int?,
	val originalLine: Int?,
	val instructionOffset: Int?,
	val annotationType: String?,
	val snippet: String?,
	val sourceKind: String,
	val complete: Boolean,
)
```

约束：

- snippet 有长度上限；
- 保留原始符号 target；
- 每个坐标写明类型；
- 有 instruction offset 时一并保留；
- 区分 declaration 和 reference；
- 记录 metadata 是否存在；
- 记录来源 `source-metadata`、`raw-insn` 或 `smali`；
- 不确定映射用列表或 `null`；
- 按类、方法、offset 排序。

证据强度可以分层：

```text
resolved-node + exact-offset
resolved-node + enclosing-method
text-match + metadata-nearest
text-only
smali-manual
```

输出规则本身，避免后续消费者把所有证据当成同一置信度。
