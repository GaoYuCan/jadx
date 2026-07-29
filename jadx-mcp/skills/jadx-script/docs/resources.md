# Android 资源与清单分析

## 目录

- [适用范围](#适用范围)
- [确认资源已加载](#确认资源已加载)
- [资源 API 概览](#资源-api-概览)
- [建立资源清单](#建立资源清单)
- [读取解码后的文本资源](#读取解码后的文本资源)
- [递归遍历资源表](#递归遍历资源表)
- [安全读取二进制资源](#安全读取二进制资源)
- [错误处理与边界](#错误处理与边界)

## 适用范围

本模块用于 APK、AAB、APKM、XAPK 等 Android 输入中的资源分析，包括：

- `AndroidManifest.xml`；
- `resources.arsc` 或 `resources.pb`；
- `res/xml`、`res/layout`、`res/navigation` 等 XML；
- `assets`、原始 JSON、JavaScript、配置文件；
- 图片、字体、本地库等二进制条目；
- 资源名、资源 ID 与代码引用之间的关联。

若任务只处理 `.dex`、`.class` 或 `.jar`，资源列表通常为空，这属于正常情况。

## 确认资源已加载

`open_file` 的 `skip_resources=true` 会跳过资源。脚本无法在运行时补回已经跳过的资源；需要重新调用 `open_file`，并设置：

```json
{
  "path": "/absolute/path/app.apk",
  "skip_resources": false
}
```

脚本中先检查当前设置和资源数量：

```kotlin
val jadx = getJadxInstance()

jadx.afterLoad {
	val skipResources = jadx.args.isSkipResources
	val resources = jadx.internalDecompiler.resources
	log.info {
		"skipResources=$skipResources, resources=${resources.size}"
	}
}
```

空列表可能表示：

- 输入本身没有资源；
- 打开项目时跳过了资源；
- 输入格式的资源由插件处理，但对应插件未加载；
- 资源加载过程中发生错误。

不要把空列表直接解释为“应用没有资源”。

## 资源 API 概览

主要入口：

| API | 含义 |
| --- | --- |
| `jadx.internalDecompiler.resources` | 当前项目的 `List<ResourceFile>` |
| `ResourceFile.originalName` | 输入条目中的原始路径 |
| `ResourceFile.deobfName` | 经过资源别名处理后的路径 |
| `ResourceFile.type` | `ResourceType` |
| `ResourceFile.loadContent()` | 解码为 `ResContainer` |
| `ResourceFile.zipEntry` | 底层压缩包条目，独立文件时可为空 |
| `ResContainer.dataType` | 内容表示方式 |
| `ResContainer.text` | 文本或 XML 解码结果 |
| `ResContainer.decodedData` | 已解码的二进制数据 |
| `ResContainer.subFiles` | 资源表产生的子文件 |
| `ResContainer.resLink` | 指向另一个 `ResourceFile` |

常见 `ResourceType`：

- `MANIFEST`；
- `ARSC`；
- `XML`；
- `JSON`；
- `TEXT`；
- `HTML`；
- `IMG`；
- `LIB`；
- `ARCHIVE`；
- `UNKNOWN`、`UNKNOWN_BIN`。

`ResourceType` 是 jadx 根据路径和内容做出的分类。分析逻辑仍应保留原始路径，避免分类变化导致结果无法复现。

## 建立资源清单

先建立轻量清单，再决定加载哪些内容。`loadContent()` 可能触发 XML 或资源表解码，不适合无条件用于所有大文件。

```kotlin
data class ResourceRecord(
	val originalName: String,
	val decodedName: String,
	val type: String,
	val compressedSize: Long?,
	val uncompressedSize: Long?,
)

val records = jadx.internalDecompiler.resources.asSequence()
	.map { resource ->
		val entry = resource.zipEntry
		ResourceRecord(
			originalName = resource.originalName,
			decodedName = resource.deobfName,
			type = resource.type.name,
			compressedSize = entry?.compressedSize,
			uncompressedSize = entry?.uncompressedSize,
		)
	}
	.sortedBy(ResourceRecord::originalName)
	.toList()
```

常用筛选：

```kotlin
val manifests = resources.filter { it.type == jadx.api.ResourceType.MANIFEST }

val xmlFiles = resources.filter {
	it.type == jadx.api.ResourceType.XML ||
		it.type == jadx.api.ResourceType.MANIFEST
}

val assets = resources.filter {
	it.originalName.startsWith("assets/")
}

val nativeLibraries = resources.filter {
	it.type == jadx.api.ResourceType.LIB
}
```

路径匹配应明确大小写规则。Android 资源路径通常区分大小写，不要默认转成小写后再输出。

## 读取解码后的文本资源

`ResourceFile.loadContent()` 返回 `ResContainer`。根据 `dataType` 分支读取：

```kotlin
import jadx.core.xmlgen.ResContainer

fun collectText(container: ResContainer): List<Pair<String, String>> =
	when (container.dataType) {
		ResContainer.DataType.TEXT -> {
			listOf(container.name to container.text.codeStr)
		}
		ResContainer.DataType.RES_TABLE -> {
			buildList {
				val rootText = container.text.codeStr
				if (rootText.isNotBlank()) {
					add(container.name to rootText)
				}
				container.subFiles.forEach { child ->
					addAll(collectText(child))
				}
			}
		}
		ResContainer.DataType.RES_LINK -> {
			collectText(container.resLink.loadContent())
		}
		ResContainer.DataType.DECODED_DATA -> {
			emptyList()
		}
	}
```

`ResContainer.DataType.TEXT` 返回 `ICodeInfo`，正文通过 `codeStr` 读取。XML、JSON、HTML 和普通文本都使用同一个文本接口；调用方再根据 `ResourceType` 和路径选择解析器。

解码后的文本是分析表示。需要证明原始字节内容时，同时保留资源路径、类型和压缩包条目信息。

## 递归遍历资源表

`resources.arsc` 可能解码成 `RES_TABLE`，其中包含多个子文件。递归时要保留容器路径：

```kotlin
data class DecodedResource(
	val path: String,
	val text: String,
)

fun walk(container: ResContainer, output: MutableList<DecodedResource>) {
	when (container.dataType) {
		ResContainer.DataType.TEXT -> {
			output += DecodedResource(
				path = container.name,
				text = container.text.codeStr,
			)
		}
		ResContainer.DataType.RES_TABLE -> {
			container.subFiles.forEach { child ->
				walk(child, output)
			}
		}
		ResContainer.DataType.RES_LINK -> {
			walk(container.resLink.loadContent(), output)
		}
		ResContainer.DataType.DECODED_DATA -> Unit
	}
}
```

防止重复和异常链接：

```kotlin
fun walkSafe(
	container: ResContainer,
	visited: MutableSet<String>,
	output: MutableList<DecodedResource>,
) {
	val key = "${container.dataType}:${container.name}"
	if (!visited.add(key)) {
		return
	}
	// 按上面的 dataType 逻辑继续遍历。
}
```

正常资源表不应形成循环，但通用脚本应限制递归深度或记录已访问容器，避免异常输入拖垮分析。

## 安全读取二进制资源

直接调用 `zipEntry.bytes` 会一次性分配完整内容。先检查声明大小，并设置上限：

```kotlin
fun readEntryPrefix(
	entry: jadx.zip.IZipEntry,
	maxBytes: Int,
): ByteArray {
	require(maxBytes > 0)
	val declared = entry.uncompressedSize
	require(declared < 0 || declared <= maxBytes) {
		"Resource too large: ${entry.name}, size=$declared"
	}
	entry.inputStream.use { input ->
		return input.readNBytes(maxBytes)
	}
}
```

安全原则：

- 不信任压缩包声明的大小；
- 同时限制单文件字节数、总字节数和条目数量；
- 使用 `use` 关闭流；
- 不把资源路径直接拼接成任意输出路径；
- 避免解压全部内容后再筛选；
- 对未知二进制只读取文件头做类型识别；
- 哈希大文件时使用流式摘要。

计算 SHA-256：

```kotlin
import java.security.MessageDigest

fun sha256(entry: jadx.zip.IZipEntry, maxBytes: Long): String {
	val digest = MessageDigest.getInstance("SHA-256")
	var total = 0L
	entry.inputStream.use { input ->
		val buffer = ByteArray(64 * 1024)
		while (true) {
			val count = input.read(buffer)
			if (count < 0) {
				break
			}
			total += count
			require(total <= maxBytes) {
				"Resource exceeds limit: ${entry.name}"
			}
			digest.update(buffer, 0, count)
		}
	}
	return digest.digest().joinToString("") { "%02x".format(it) }
}
```

## 错误处理与边界

按资源隔离错误：

```kotlin
data class ResourceFailure(
	val name: String,
	val type: String,
	val error: String,
)

for (resource in jadx.internalDecompiler.resources) {
	try {
		analyzeResource(resource)
	} catch (error: Exception) {
		failures += ResourceFailure(
			name = resource.originalName,
			type = resource.type.name,
			error = error.message ?: error.javaClass.simpleName,
		)
	}
}
```

需要明确记录的边界：

- 项目打开时跳过资源；
- 资源插件缺失；
- 某个条目解码失败；
- 二进制条目超过限制；
- XML 不合法；
- 资源 ID 无法解析；
- 类名只解析到别名；
- 多输入项目中资源来源不唯一。

输出资源结果时始终排序，并包含当前输入文件信息、脚本版本、限制参数和失败数量。
