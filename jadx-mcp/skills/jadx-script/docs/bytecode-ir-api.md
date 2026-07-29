# 原始字节码与 JADX IR API

## 目录

- [选择表示层](#选择表示层)
- [安全扫描原始指令](#安全扫描原始指令)
- [解析索引引用](#解析索引引用)
- [理解寄存器和参数](#理解寄存器和参数)
- [调试局部变量与源码行](#调试局部变量与源码行)
- [获得处理后的 JADX IR](#获得处理后的-jadx-ir)
- [遍历内部指令](#遍历内部指令)
- [CFG、支配和后支配](#cfg支配和后支配)
- [SSA 与 phi](#ssa-与-phi)
- [失败降级](#失败降级)
- [内存与运行时间](#内存与运行时间)

## 选择表示层

JADX 同时暴露多种表示。选择能够证明目标事实的最低成本层：

| 层 | 主要对象 | 优点 | 限制 |
| --- | --- | --- | --- |
| 符号模型 | `JavaClass`、`JavaMethod`、`MethodInfo`、`FieldInfo` | 身份、层次、成员和 usage 便宜 | 缺少表达式细节 |
| 反编译源码 | `ICodeInfo`、`JavaMethod.codeStr` | 适合阅读控制结构和表达式 | 转换会隐藏原始字节码结构 |
| 源码 metadata | `ICodeMetadata` | 位置可映射到节点、变量和字节码 offset | 依赖 annotated code generation |
| 原始输入指令 | `ICodeReader`、`InsnData` | offset、寄存器、opcode、引用稳定 | 无 SSA，控制流低级 |
| 处理后 JADX IR | `InsnNode`、`BlockNode`、`SSAVar`、`Region` | 规范化调用、表达式、CFG、SSA | 内部 API，代码生成后通常卸载 |
| smali | `JavaClass.smali` | 完整的人工检查 fallback | 文本解析会丢失类型化节点身份 |

推荐顺序：

1. 解析精确符号；
2. usage 或 metadata 足够时直接使用；
3. 大范围提取、需要 offset 或反编译失败时扫原始指令；
4. 只有控制流、SSA、对象来源等问题才加载处理后 IR；
5. smali 用于人工证据和最后降级。

不要因为 IR 能表达更多信息就默认处理整个项目。全项目 `forceProcess` 往往没有必要。

## 安全扫描原始指令

有代码的 `MethodNode` 通常持有 `ICodeReader`：

```kotlin
val methodNode = method.methodNode
val reader = methodNode.codeReader
	?: return@afterLoad
```

遍历：

```kotlin
reader.copy().visitInstructions { insn ->
	insn.decode()
	// 只在回调内读取 insn。
}
```

必须遵守：

- reader 有状态，每次遍历使用 `copy()`；
- 读取寄存器、常量、target、索引引用前调用 `decode()`；
- 回调结束后不保留 `InsnData`；
- 需要长期使用时投影成自己的不可变 data class；
- 只有 `indexType` 匹配时才读取对应 `indexAs*`；
- 异常或未知 opcode 也要记录 offset 和 opcode。

通用投影：

```kotlin
import jadx.api.plugins.input.insns.InsnIndexType

data class RawInsn(
	val offset: Int,
	val fileOffset: Int,
	val opcode: String,
	val regs: List<Int>,
	val resultReg: Int,
	val indexType: String,
	val index: String?,
)

fun rawInstructions(
	method: jadx.core.dex.nodes.MethodNode,
): List<RawInsn> {
	val reader = method.codeReader ?: return emptyList()
	val result = mutableListOf<RawInsn>()

	reader.copy().visitInstructions { insn ->
		insn.decode()
		val index = when (insn.indexType) {
			InsnIndexType.STRING_REF -> insn.indexAsString
			InsnIndexType.TYPE_REF -> insn.indexAsType
			InsnIndexType.FIELD_REF ->
				insn.indexAsField.target()
			InsnIndexType.METHOD_REF ->
				insn.indexAsMethod.target()
			else -> null
		}

		result += RawInsn(
			offset = insn.offset,
			fileOffset = insn.fileOffset,
			opcode = insn.opcode.name,
			regs = (0 until insn.regsCount)
				.map(insn::getReg),
			resultReg = insn.resultReg,
			indexType = insn.indexType.name,
			index = index,
		)
	}
	return result
}
```

`literal`、`target`、payload 等字段只对相应 opcode 有意义。不要把 getter 的默认值当成真实数据。

若需要保留原始异常：

```kotlin
data class MethodScanFailure(
	val method: String,
	val error: String,
)
```

按方法隔离扫描错误，继续处理其他方法。

## 解析索引引用

`InsnIndexType` 决定引用类型：

| 类型 | getter | 结果 |
| --- | --- | --- |
| `STRING_REF` | `indexAsString` | 字符串常量 |
| `TYPE_REF` | `indexAsType` | 类型 descriptor |
| `FIELD_REF` | `indexAsField` | `IFieldRef` |
| `METHOD_REF` | `indexAsMethod` | `IMethodRef` |
| `CALL_SITE` | `indexAsCallSite` | 动态调用点数据 |

方法引用按需加载。读取 owner、名称、参数或返回值前调用 `load()`：

```kotlin
fun jadx.api.plugins.input.data.IMethodRef.target(): String {
	load()
	return "$parentClassType->$name" +
		"(${argTypes.joinToString("")})$returnType"
}
```

字段引用：

```kotlin
fun jadx.api.plugins.input.data.IFieldRef.target(): String =
	"$parentClassType->$name:$type"
```

常见 descriptor：

| descriptor | 含义 |
| --- | --- |
| `I` | int |
| `J` | long |
| `Z` | boolean |
| `V` | void |
| `Ljava/lang/String;` | 对象 |
| `[I` | int 数组 |
| `[[Ljava/lang/Object;` | 二维对象数组 |

结构化输出保留 descriptor。展示时可以额外生成可读类型，不要覆盖原值。

动态调用需要单独建模：

- `invoke-custom`；
- method handle；
- call site bootstrap；
- lambda/metafactory；
- 字符串拼接 call site。

无法解析最终目标时，保留 call site 原始数据和 `dynamic=true`。

## 理解寄存器和参数

DEX 寄存器是存储槽，不等于源码变量，也不等于对象身份。

`ICodeReader` 提供：

- `registersCount`；
- `argsStartReg`；
- `unitsCount`；
- `codeOffset`；
- `debugInfo`；
- `tries`。

参数位于从 `argsStartReg` 开始的高位寄存器。实例方法还包含 `this`，`long` 和 `double` 占两个 DEX 寄存器。

内部节点参数：

```kotlin
val node = method.methodNode
node.reload()

val thisArg = node.thisArg
val explicitArgs = node.argRegs
val allArgs = node.allArgRegs
```

`reload()` 会加载内部指令，但不会执行完整处理 pipeline。它修改内部状态，只在独占脚本执行期间使用，并在适当位置卸载。

必须保持的事实：

- 同一寄存器在不同 offset 可保存无关值；
- move 会让不同寄存器指向同一值；
- 分支合流会把多个定义带入一个寄存器；
- debug local 可在不同范围复用同一寄存器；
- `MOVE_RESULT` 绑定前一个调用或分配的结果；
- 寄存器号本身不能作为稳定变量 ID。

原始定义至少用“方法 + 定义 offset + 寄存器”标识：

```text
com.example.Owner#method(I)V@66:r3
```

若需要范围敏感的数据流，block entry state 中的每次赋值必须 kill 旧值。

## 调试局部变量与源码行

```kotlin
val reader = method.methodNode.codeReader
val debugInfo = reader?.debugInfo
val sourceLines = debugInfo?.sourceLineMapping.orEmpty()
val locals = debugInfo?.localVars.orEmpty()

for (local in locals) {
	log.info {
		"${local.name}: reg=${local.regNum}, " +
			"type=${local.type}, " +
			"range=${local.startOffset}..${local.endOffset}, " +
			"parameterHint=${local.isMarkedAsParameter}"
	}
}
```

`isMarkedAsParameter` 只是输入提示，可能不正确。

调试信息可能：

- 完全缺失；
- 局部范围重叠；
- 名称重复；
- 类型比实际指令更宽或更窄；
- 行号非单调；
- 被优化器移动。

结果中保留寄存器号和 offset 范围，不按变量名去重。

## 获得处理后的 JADX IR

JADX 生成源码后通常会卸载方法 blocks、SSA 和 region，因此以下属性可能为空：

```kotlin
method.methodNode.basicBlocks
method.methodNode.region
```

`MethodNode.instructions` 是处理流水线早期使用的线性指令数组。完整处理建立 basic block 后，它通常会被清空；这不表示方法没有 IR。

不注册新 pass 的情况下，临时处理顶层类：

```kotlin
val root = jadx.internalDecompiler.root
val topClass = cls.classNode.topParentClass

root.processClasses.forceProcess(topClass)
try {
	for (method in topClass.methods) {
		val blocks = method.basicBlocks.orEmpty()
		val instructions = blocks.flatMap {
			it.instructions
		}
		val ssaVars = method.sVars
		val region = method.region
		// 在类保持 processed 的期间提取自己的不可变摘要。
	}
} finally {
	topClass.unload()
}
```

关键约束：

- `forceProcess` 是内部 API；
- 处理 `topParentClass`；
- 运行当前已经配置好的 passes；
- 可能同时处理内部类；
- 读取 IR 必须发生在 `unload()` 前；
- 完整处理后的指令从 `BlockNode.instructions` 读取；
- `MethodNode.instructions == null` 在 basic block 已建立后属于正常状态；
- 不把 `InsnNode`、`BlockNode`、`SSAVar` 或 `Region` 保存到长期缓存；
- `finally` 中卸载，即使分析异常。

若确实需要某个中间阶段：

```kotlin
val process = jadx.internalDecompiler.root.processClasses
val ok = process.processMethodUntilVisitor(
	methodNode,
	"SSATransform",
	true,
)
require(ok) {
	"找不到目标 pass"
}
```

pass 名称和阶段不变量会变。用当前版本：

```kotlin
jadx.debug.printPasses()
```

普通分析优先使用完整 `forceProcess`，只有算法明确依赖早期 IR 才停在指定 visitor。

## 遍历内部指令

`InsnNode` 常用内容：

- `type`；
- `result`；
- `arguments` / `argList`；
- `argsCount`；
- `offset`；
- 属性与 flag；
- `visitInsns` 遍历嵌套指令；
- `getRegisterArgs` 获取寄存器参数。

常见 `InsnType`：

- 常量：`CONST`、`CONST_STR`、`CONST_CLASS`；
- 移动和类型：`MOVE`、`CAST`、`CHECK_CAST`、`PHI`；
- 字段：`IGET`、`IPUT`、`SGET`、`SPUT`；
- 数组：`AGET`、`APUT`、`NEW_ARRAY`、`FILLED_NEW_ARRAY`；
- 对象：`NEW_INSTANCE`、`CONSTRUCTOR`；
- 调用：`INVOKE`；
- 控制流：`IF`、`SWITCH`、`GOTO`、`RETURN`、`THROW`；
- 表达式：`ARITH`、`TERNARY`、`STR_CONCAT`。

完整处理后先遍历 basic block：

```kotlin
val instructions = methodNode.basicBlocks
	.orEmpty()
	.asSequence()
	.flatMap { block ->
		block.instructions.asSequence()
	}
```

`methodNode.reload()` 后、block 构建前可以访问线性的 `methodNode.instructions`；这些指令尚未经过完整 JADX passes。不要把这两种阶段混用。

已解析调用：

```kotlin
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.InvokeNode

for (insn in instructions) {
	if (
		insn.type == InsnType.INVOKE &&
		insn is InvokeNode
	) {
		val callee = insn.callMth
		val receiver = insn.instanceArg
		val explicitArgs =
			insn.argList.drop(insn.firstArgOffset)

		log.info {
			"callee=${callee.rawFullId}, " +
				"static=${insn.isStaticCall}, " +
				"receiver=$receiver, " +
				"args=$explicitArgs"
		}
	}
}
```

构造器可能成为 `ConstructorInsn`：

```kotlin
import jadx.core.dex.instructions.mods.ConstructorInsn

if (
	insn.type == InsnType.CONSTRUCTOR &&
	insn is ConstructorInsn &&
	insn.isNewInstance
) {
	val allocatedType = insn.classType.fullName
	val constructor = insn.callMth.rawFullId
	val args = insn.argList
}
```

字段指令通常是 `IndexInsnNode`，其 index 为 `FieldInfo`。

包装表达式必须递归遍历。一个调用直接作为另一个调用参数时，可能位于 `InsnWrapArg` 内部，不会出现在顶层 instruction list 的独立位置：

```kotlin
insn.visitInsns { nested ->
	// 包含 insn 自身和包装的子指令。
}
```

## CFG支配和后支配

处理后 `MethodNode.basicBlocks` 包含 `BlockNode`。

常用字段：

- `id`、`cId`；
- `startOffset`；
- `instructions`；
- `predecessors`；
- `successors`；
- `cleanSuccessors`；
- `iDom`；
- `iPostDom`；
- `doms`；
- `postDoms`；
- `domFrontier`；
- `isReturnBlock`；
- `isMthExitBlock`。

```kotlin
for (block in methodNode.basicBlocks.orEmpty()) {
	log.debug {
		"block=${block.id}, start=${block.startOffset}, " +
			"pred=${block.predecessors.map { it.id }}, " +
			"succ=${block.successors.map { it.id }}"
	}
}
```

支配关系适合回答：

- 某个校验是否支配敏感调用；
- 某次赋值是否在所有到达 return 的路径上发生；
- 分配点是否支配全部局部使用；
- 两个定义是否处于互斥路径；
- cleanup 是否后支配资源获取；
- 循环 header 和回边。

源码文本顺序不能证明无条件执行。需要使用 dominance、post-dominance、control dependence 或 region 结构。

异常边会影响支配关系。安全分析不能只看 `cleanSuccessors` 而忽略异常路径。

## SSA 与 phi

`RegisterArg.sVar` 把寄存器使用关联到 `SSAVar`。

`SSAVar` 常用字段：

- `regNum`；
- `version`；
- `assign`；
- `assignInsn`；
- `useList`；
- `useCount`；
- `usedInPhi`；
- `phiList`；
- `codeVar`；
- `name`；
- 类型信息。

```kotlin
for (ssa in methodNode.sVars) {
	log.debug {
		"r${ssa.regNum}v${ssa.version}: " +
			"assign=${ssa.assignInsn}, " +
			"uses=${ssa.useCount}, " +
			"phis=${ssa.usedInPhi.size}"
	}
}
```

phi 把不同 predecessor 的值合并。来源分析必须 union 所有 incoming origins，并把合流点保存为证据。

以下等式都不成立：

```text
寄存器号 == 对象身份
SSA 变量 == 分配点
源码变量名 == 语义角色
```

SSA 变量只有一个定义，但它的值可能是多个对象的 phi，也可能是返回未知别名的调用。

处理 copy、cast 和 wrap 时，可以继续追踪输入；遇到 phi、field、call 时保留显式节点，避免证据路径丢失。

## 失败降级

处理后检查：

```kotlin
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.attributes.AType

val failed = methodNode.contains(AType.JADX_ERROR)
val inconsistent =
	methodNode.contains(AFlag.INCONSISTENT_CODE)
```

降级顺序：

1. 保留已经解析的符号和 usage；
2. 扫描 `MethodNode.codeReader`；
3. 使用 debug locals 和 source line；
4. 获取 `JavaClass.smali`；
5. 输出未解决问题和证据位置。

按方法隔离异常：

```kotlin
for (method in cls.classNode.methods) {
	try {
		analyze(method)
	} catch (error: Exception) {
		reviews += ReviewRecord(
			target = method.methodInfo.rawFullId,
			reason = error.message
				?: error.javaClass.simpleName,
		)
	}
}
```

不要捕获 `VirtualMachineError`。也不要把所有失败静默转换为空结果。

对 IR 处理失败的方法，原始 `codeReader` 往往仍可用。这也是项目级候选扫描优先走原始指令的原因。

## 内存与运行时间

原始扫描通常最便宜，因为不需要完整反编译。

处理后 IR：

- `forceProcess` 前先筛选类；
- 每次处理一个顶层类；
- 只提取自己的不可变摘要；
- `finally` 中 `unload()`；
- 不长期保存内部节点；
- 缓存序列化摘要；
- 限制路径深度、来源集合和固定点次数；
- 记录扫描、失败、截断计数。

避免并行调用 `forceProcess`。类处理共享缓存和依赖状态；MCP runner 已提供会话独占访问，顺序处理更容易复现。

大型 APK 推荐阶段：

1. 原始指令全项目候选扫描；
2. 精确符号过滤；
3. 只对候选方法使用处理后 IR；
4. 对失败项定点使用 smali；
5. 排序输出并保留待复核项。
