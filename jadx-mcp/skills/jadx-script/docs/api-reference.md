# 脚本运行时与 JADX API 参考

## 目录

- [API 层级选择](#api-层级选择)
- [注入的脚本运行时](#注入的脚本运行时)
- [JadxDecompiler](#jadxdecompiler)
- [JavaClass](#javaclass)
- [JavaMethod](#javamethod)
- [JavaField 与 JavaVariable](#javafield-与-javavariable)
- [JavaPackage](#javapackage)
- [稳定符号标识](#稳定符号标识)
- [解析类、方法和字段](#解析类方法和字段)
- [访问标志与类型](#访问标志与类型)
- [依赖、调用和引用](#依赖调用和引用)
- [注解与属性](#注解与属性)
- [错误和警告](#错误和警告)
- [MCP runner 中不可用的脚本能力](#mcp-runner-中不可用的脚本能力)

## API 层级选择

普通遍历优先使用 `jadx.api`：

- `JadxDecompiler`；
- `JavaClass`；
- `JavaMethod`；
- `JavaField`；
- `JavaVariable`；
- `JavaPackage`；
- `ICodeInfo`；
- `ICodeMetadata`；
- `ResourceFile`。

公开 wrapper 的优点：

- 名称和成员访问直接；
- 自动映射 alias；
- 源码和 metadata API 完整；
- 脚本可读性较高；
- 版本兼容性相对好。

公开 API 缺少所需证据时进入 `jadx.core`：

- 原始方法和字段 ID；
- `ICodeReader` 原始指令；
- 处理后指令；
- CFG、SSA、region；
- 内部属性和反编译错误；
- 原始类层次节点。

内部 API 会随 JADX 版本变化。把它们集中到少量 helper，升级时可以局部修复。

## 注入的脚本运行时

每个 `.jadx.kts` 隐式获得：

| 成员 | 用途 |
| --- | --- |
| `scriptName` | 去掉 `.jadx.kts` 的脚本名 |
| `log` | 名为 `JadxScript:<scriptName>` 的 Kotlin logger |
| `print(...)` | 重定向到 INFO 日志 |
| `println(...)` | 重定向到 INFO 日志 |
| `getJadxInstance()` | 返回 `JadxScriptInstance` |

只创建一次实例：

```kotlin
val jadx = getJadxInstance()
```

`JadxScriptInstance` 常用成员：

| 成员 | 作用 |
| --- | --- |
| `args` | 当前 `JadxArgs` |
| `classes` | 顶层 `List<JavaClass>` |
| `scriptFile` | 当前 `.jadx.kts` 文件 |
| `scriptName` | 脚本名 |
| `internalDecompiler` | 当前 `JadxDecompiler` |
| `search` | 按原始名搜索的辅助封装 |
| `decompile` | 批量反编译辅助封装 |
| `debug` | 内部诊断辅助 |
| `afterLoad {}` | 注册加载后分析 |

基本入口：

```kotlin
val jadx = getJadxInstance()

jadx.afterLoad {
	log.info {
		"classes=${jadx.classes.size}"
	}
}
```

`options`、`rename`、`stages`、`replace`、`gui`、`events` 等成员属于官方脚本插件的其他能力。MCP runner 只支持本次调用内的加载后分析。

## JadxDecompiler

通过 `jadx.internalDecompiler` 访问。

| Kotlin 属性或方法 | 含义 |
| --- | --- |
| `classes` | 顶层 API 类 |
| `classesWithInners` | 包含内部类的 API 类 |
| `packages` | 包树 |
| `resources` | 已加载资源 |
| `root` | 内部 `RootNode` |
| `args` | 当前参数 |
| `errorsCount` | JADX 全局错误计数 |
| `warnsCount` | JADX 全局警告计数 |
| `searchJavaClassByOrigFullName(name)` | 按原始 FQN 精确查类 |
| `searchJavaClassByAliasFullName(name)` | 按 alias/source FQN 查类 |
| `searchClassNodeByOrigFullName(name)` | 返回内部 `ClassNode` |
| `getJavaNodeByRef(ref)` | metadata node ref 转 API node |
| `getJavaNodeByCodeAnnotation(codeInfo, ann)` | 源码注解转 API node |
| `getJavaNodeAtPosition(codeInfo, pos)` | 解析精确字符位置 |
| `getClosestJavaNode(codeInfo, pos)` | 解析最近节点 |
| `getEnclosingNode(codeInfo, pos)` | 解析包围位置的类或方法 |

```kotlin
val decompiler = jadx.internalDecompiler
val cls = decompiler
	.searchJavaClassByOrigFullName("com.example.Target")
	?: decompiler
		.searchJavaClassByAliasFullName("com.example.Target")
```

不要调用 `load`、`close`、`reloadPasses`、`addCustomPass` 等生命周期方法。会话负责反编译器生命周期。

输入：

```kotlin
val inputFiles = decompiler.args.inputFiles
	.filterNot { it.name.endsWith(".jadx.kts") }
	.map { it.absolutePath }
```

资源：

```kotlin
val resources = decompiler.resources
```

若 `args.isSkipResources` 为 `true`，资源可能为空。

## JavaClass

| 属性或方法 | 含义 |
| --- | --- |
| `name` | alias 短类名 |
| `fullName` | alias 完整类名 |
| `rawName` | 原始字节码完整类名 |
| `package` | alias 包名 |
| `declaringClass` | 直接外部类 |
| `topParentClass` | 当前代码所属顶层类 |
| `originalTopParentClass` | 原始嵌套顶层类 |
| `codeParent` | 当前渲染代码的 owner |
| `innerClasses` | 内部类 |
| `inlinedClasses` | 内联到当前代码的类 |
| `fields` | API 字段 |
| `methods` | API 方法 |
| `dependencies` | 当前类依赖的类 |
| `useIn` | 使用当前类的类 |
| `accessInfo` | `AccessInfo` |
| `classNode` | 内部 `ClassNode` |
| `codeInfo` | 源码与 metadata |
| `code` | 完整反编译源码字符串 |
| `smali` | 类的 smali |
| `isNoCode` | 是否禁止生成代码 |
| `isInner` | 是否内部类 |

`codeInfo` 和 `code` 会触发反编译。只需要成员清单时不要无条件读取源码。

持久身份使用 `rawName`：

```kotlin
data class ClassSummary(
	val rawName: String,
	val aliasName: String,
	val methods: Int,
	val fields: Int,
)

val summaries = jadx.classes.asSequence()
	.map { cls ->
		ClassSummary(
			rawName = cls.rawName,
			aliasName = cls.fullName,
			methods = cls.methods.size,
			fields = cls.fields.size,
		)
	}
	.sortedBy(ClassSummary::rawName)
	.toList()
```

嵌套类有两种遍历方式：

```kotlin
val topLevel = jadx.internalDecompiler.classes
val withInners = jadx.internalDecompiler.classesWithInners
```

选择一种并写入输出配置，避免重复统计内部类。

`getSourceLine(decompiledLine)` 只在存在 source line map 时有意义，用于把生成源码行映射到调试信息中的原始源码行。

## JavaMethod

| 属性或方法 | 含义 |
| --- | --- |
| `name` | alias 方法名 |
| `fullName` | 格式化后的方法名 |
| `declaringClass` | owner API 类 |
| `arguments` | 参数 `ArgType` |
| `returnType` | 返回 `ArgType` |
| `accessFlags` | `AccessInfo` |
| `isConstructor` | 是否 `<init>` |
| `isClassInit` | 是否 `<clinit>` |
| `codeStr` | 单方法渲染源码 |
| `useIn` | 调用该方法的节点 |
| `used` | 该方法使用的已解析节点 |
| `unresolvedUsed` | 未解析 `MethodInfo` |
| `callsSelf()` | 是否记录了直接自调用 |
| `overrideRelatedMethods` | override 关系中的方法 |
| `methodNode` | 内部 `MethodNode` |

精确身份使用 `MethodInfo`：

```kotlin
val info = method.methodNode.methodInfo
log.info {
	"raw=${info.rawFullId}, " +
		"alias=${info.fullId}, " +
		"short=${info.shortId}, " +
		"signature=${info.makeSignature(true)}"
}
```

`shortId` 格式：

```text
methodName(argumentDescriptors)returnDescriptor
```

示例：

```text
size()I
execute(I[Ljava/lang/String;)V
<init>(Landroid/content/Context;)V
<clinit>()V
```

构造器和静态初始化器需要单独处理，不能按普通方法名理解。

无代码方法包括 abstract、native，以及某些损坏或被抑制的方法：

```kotlin
val reader = method.methodNode.codeReader
if (reader == null) {
	// 记录 noCode 原因，不继续读取指令。
}
```

## JavaField 与 JavaVariable

`JavaField`：

| 属性 | 含义 |
| --- | --- |
| `name` | alias 名 |
| `rawName` | 原始字段名 |
| `fullName` | alias owner 与字段名 |
| `declaringClass` | owner API 类 |
| `type` | `ArgType` |
| `accessFlags` | `AccessInfo` |
| `useIn` | 使用字段的方法 |
| `fieldNode` | 内部 `FieldNode` |

字段精确 ID：

```kotlin
val info = field.fieldNode.fieldInfo
val rawId = info.rawFullId
val shortId = info.shortId
```

推荐外部表示：

```text
com.example.Owner#fieldName:Ljava/lang/String;
```

`JavaVariable` 通常从源码 metadata 获取：

| 属性 | 含义 |
| --- | --- |
| `mth` | 所属 `JavaMethod` |
| `reg` | 原始寄存器号 |
| `ssa` | SSA 版本 |
| `name` | 渲染变量名 |
| `type` | `ArgType` |
| `defPos` | 声明字符偏移 |
| `varNode` | 内部 metadata node |

当前反编译结果内的变量句柄：

```kotlin
val variableId = "r${variable.reg}v${variable.ssa}"
```

改变 rename、反编译参数或重新加载后，重新生成变量句柄。

## JavaPackage

通过 `jadx.internalDecompiler.packages` 获取。

`JavaPackage` 提供：

- `name`、`fullName`：alias 包；
- `rawName`、`rawFullName`：原始包；
- `subPackages`；
- `classes`；
- `classesNoDup`；
- `isRoot`、`isLeaf`、`isDefault`；
- `isDescendantOf(other)`。

批量分析通常直接按 `JavaClass.rawName` 过滤，更容易控制是否包含内部类：

```kotlin
val appClasses = jadx.classes.filter {
	it.rawName.startsWith("com.example.")
}
```

如果需要呈现包树、统计直接子包或区分 alias 包，再使用 `JavaPackage`。

## 稳定符号标识

推荐序列化形式：

```text
class:  com.example.Owner
field:  com.example.Owner#fieldName:Ljava/lang/String;
method: com.example.Owner#methodName(I)Ljava/lang/String;
site:   com.example.Owner#methodName(I)V@42
var:    com.example.Owner#methodName(I)V / r2v0
```

helper：

```kotlin
import jadx.core.dex.info.FieldInfo
import jadx.core.dex.info.MethodInfo

fun MethodInfo.target(): String =
	"${declClass.makeRawFullName()}#$shortId"

fun FieldInfo.target(): String =
	"${declClass.makeRawFullName()}#$shortId"
```

持久记录优先：

- `MethodInfo.rawFullId`；
- `FieldInfo.rawFullId`；
- 原始 `ClassInfo.makeRawFullName()`；
- 原始 descriptor；
- 字节码 offset。

不要把任意节点的默认 `toString()` 当身份。它面向诊断，格式可能变化。

同时保留 alias：

```kotlin
data class SymbolName(
	val raw: String,
	val alias: String,
)
```

这样结果可以稳定匹配，也方便人工阅读。

## 解析类方法和字段

### 类

```kotlin
fun resolveClass(name: String) =
	jadx.internalDecompiler
		.searchJavaClassByOrigFullName(name)
		?: jadx.internalDecompiler
			.searchJavaClassByAliasFullName(name)
```

大量重复查找时一次建表：

```kotlin
val classesByAnyName = buildMap {
	for (cls in jadx.classes) {
		put(cls.rawName, cls)
		put(cls.fullName, cls)
	}
}
```

原始 descriptor `Lcom/example/Owner;` 需要先转换为 Java FQN，或用 `ArgType`/`ClassInfo` 解析。不要只删除首尾字符却忘记把 `/` 转成 `.`。

### 方法

按完整 `shortId`：

```kotlin
fun resolveMethod(
	owner: String,
	shortId: String,
) = resolveClass(owner)?.methods?.firstOrNull { method ->
	method.methodNode.methodInfo.shortId == shortId
}
```

已经验证配置有效时，可用内部精确解析：

```kotlin
val methodNode = jadx.internalDecompiler.root.resolveDirectMethod(
	"com.example.Owner",
	"method(I)V",
)
```

该方法在缺失时抛错，适合配置必须成立的场景。

### 字段

```kotlin
val clsNode = jadx.internalDecompiler.root
	.resolveRawClass("com.example.Owner")

val fieldNode = clsNode
	?.searchFieldByShortId("field:Ljava/lang/String;")
```

仅凭字段名可能在异常输入或继承语境中产生歧义。分析配置中保留类型 descriptor。

## 访问标志与类型

`AccessInfo` 常用判断：

- `isPublic`、`isProtected`、`isPrivate`、`isPackagePrivate`；
- `isStatic`、`isFinal`、`isAbstract`；
- `isInterface`、`isAnnotation`、`isEnum`；
- `isNative`、`isSynthetic`、`isBridge`；
- `isVarArgs`、`isSynchronized`；
- `isTransient`、`isVolatile`。

```kotlin
val publicStaticMethods = jadx.classes.flatMap { cls ->
	cls.methods.filter { method ->
		method.accessFlags.isPublic &&
			method.accessFlags.isStatic
	}
}
```

`ArgType.toString()` 的显示形式取决于类型种类。需要严格 descriptor 时使用 `MethodInfo`、`FieldInfo` 或 `TypeGen.signature(...)`。

类层次：

```kotlin
val node = cls.classNode
val superType = node.superClass
val interfaces = node.interfaces
val resolvedSuper = superType?.let { type ->
	node.root().resolveClass(type)
}
```

`resolveClass` 可能为空，例如父类来自缺失依赖。

## 依赖调用和引用

公开 API：

```kotlin
val methodCallers = method.useIn
val usedNodes = method.used
val fieldUsers = field.useIn
val classUsers = cls.useIn
val dependencies = cls.dependencies
```

内部方法级集合：

```kotlin
val node = method.methodNode
val callerMethods = node.useIn
val calleeMethods = node.used
val unresolved = node.unresolvedUsed
```

使用信息可能不完整：

- 目标类不在输入；
- input plugin 无法解析成员；
- 反射、JNI 或动态加载隐藏目标；
- 指令损坏；
- `invoke-custom` 等动态调用；
- 对应类或方法还未进入需要的处理阶段。

证据敏感任务用原始指令再次确认。

override 关系：

```kotlin
val related = method.overrideRelatedMethods
```

全项目 override 索引可遍历 `ClassNode.superClass`、`interfaces`，再按方法原始签名匹配。

## 注解与属性

类、方法和字段的内部节点支持属性读取：

```kotlin
import jadx.api.plugins.input.data.attributes.JadxAttrType

val annotations = cls.classNode
	.get(JadxAttrType.ANNOTATION_LIST)
	?.all
	.orEmpty()

for (annotation in annotations) {
	log.info {
		"type=${annotation.annotationClass}, " +
			"visibility=${annotation.visibility}, " +
			"values=${annotation.values}"
	}
}
```

注解类型使用 descriptor：

```text
Landroid/webkit/JavascriptInterface;
```

方法和字段分别读取 `method.methodNode`、`field.fieldNode`。

常用输入属性：

- `JadxAttrType.SIGNATURE`；
- `JadxAttrType.CONSTANT_VALUE`；
- `JadxAttrType.EXCEPTIONS`；
- `JadxAttrType.METHOD_PARAMETERS`；
- `JadxAttrType.ANNOTATION_MTH_PARAMETERS`；
- `JadxAttrType.SOURCE_FILE`。

处理阶段内部属性使用 `AType`，flag 使用 `AFlag`；两者都属于版本敏感 API。

## 错误和警告

全局计数：

```kotlin
val decompiler = jadx.internalDecompiler
log.info {
	"errors=${decompiler.errorsCount}, " +
		"warnings=${decompiler.warnsCount}"
}
```

节点级失败：

```kotlin
import jadx.core.dex.attributes.AFlag
import jadx.core.dex.attributes.AType

for (cls in jadx.classes) {
	runCatching { cls.decompile() }
	val failedMethods = cls.classNode.methods.filter { method ->
		method.contains(AType.JADX_ERROR) ||
			method.contains(AFlag.INCONSISTENT_CODE)
	}
	if (failedMethods.isNotEmpty()) {
		log.warn {
			"${cls.rawName}: failed=" +
				failedMethods.map { it.methodInfo.shortId }
		}
	}
}
```

代码生成失败时，某些接口可能返回包含诊断的文本而没有把异常抛到脚本。检查节点属性、源码质量和失败计数，不能只依赖 try/catch。

## MCP runner 中不可用的脚本能力

`run_script` 用于加载后分析。以下能力不应使用：

| 能力 | 原因 |
| --- | --- |
| `jadx.addPass` | runner 拒绝 pass 注册 |
| `jadx.stages.*` | 用于注册自定义反编译 pass |
| `jadx.rename.*` | 会注册 rename pass；改用 MCP `rename` |
| `jadx.replace.*` | 会注册指令替换 pass |
| `jadx.gui.*` | MCP 服务没有 GUI 上下文 |
| option 注册 | MCP 调用不提供每脚本插件选项 |
| 长期事件 | 调用结束后脚本 classloader 被关闭 |

可以使用的只读能力：

- `jadx.search`；
- `jadx.decompile`；
- `jadx.debug` 只读诊断；
- `jadx.classes`；
- `jadx.args` 读取；
- `jadx.internalDecompiler` 读取；
- 为分析临时触发内部处理，再及时释放。

项目加载后不要修改 `JadxArgs`。多数参数只有重新加载才生效，部分修改还会破坏 jadx-mcp 缓存和当前会话的不变量。
