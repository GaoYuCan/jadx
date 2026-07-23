# jadx-mcp

`jadx-mcp` 是基于 jadx 的 MCP 工具，它把包结构浏览、类成员查询、符号搜索、字符串搜索、反编译、反汇编、交叉引用、继承关系和资源解码封装成可调用工具，供模型按需读取分析结果。

## 适合做什么

- **逆向分析**：快速梳理 APK / DEX / JAR 的包结构、类关系、调用关系和关键字符串，按需反编译目标类或方法，适合分析业务逻辑、协议实现、加固壳外层逻辑和第三方 SDK 行为。
- **Android 应用漏洞挖掘**：定位 WebView JS Bridge、导出组件、硬编码密钥、敏感接口、加密/签名逻辑、IPC 调用链等风险点，并通过交叉引用和继承关系追踪真实可达路径。

## 构建

`jadx-mcp` 需要 JDK 17+。这是 MCP Java SDK 的要求；jadx 其他模块仍按项目原有要求构建。

```bash
./gradlew :jadx-mcp:shadowJar
```

构建产物：

```text
jadx-mcp/build/libs/jadx-mcp-dev-all.jar
```

## 接入 MCP 客户端

在 MCP 客户端配置中加入一个本地 server。下面以 Cursor 的 `~/.cursor/mcp.json` 为例。

```json
{
  "mcpServers": {
    "jadx": {
      "command": "/abs/path/to/jadx/jadx-mcp/bin/jadx-mcp.sh"
    }
  }
}
```

如果需要解析 Android framework 或其他 SDK stub，在配置里加入 `JADX_MCP_AUX_INPUTS`：

```json
{
  "mcpServers": {
    "jadx": {
      "command": "/abs/path/to/jadx/jadx-mcp/bin/jadx-mcp.sh",
      "env": {
        "JADX_MCP_AUX_INPUTS": "/abs/android-35.jar:/abs/other-sdk-stubs.jar"
      }
    }
  }
}
```

路径分隔符与 `$CLASSPATH` 一致：macOS / Linux 使用 `:`，Windows 使用 `;`。

## 超长结果

`jadx-mcp` 通过 stdio 暴露 MCP 工具。为了避免大类源码、大 XML 或大搜索结果撑爆 MCP 客户端上下文，工具结果序列化后超过 50000 字符时，不会把完整 JSON 直接返回；服务器会把完整结果写入本机临时目录，并在 tool call 返回中给出：

- `output_truncated=true`
- `output_file=/path/to/jadx-mcp-*.json`
- `preview`

模型或外部 agent 可以直接读取 `output_file` 获取完整 JSON。默认目录是系统临时目录下的 `jadx-mcp-output-<pid>`，进程退出时会尝试清理。触发落盘的阈值固定为 50000 字符，返回中的预览长度固定为 8000 字符。

## 工具列表

### 会话管理

| 工具 | 用途 |
| --- | --- |
| `open_file` | 加载或替换当前项目。支持 `.apk`、`.dex`、`.jar`、`.class`、`.smali`、`.zip`、`.aar`、`.arsc`、`.aab`、`.xapk`、`.apkm`、`.jadx`。再次调用会先关闭旧项目并清理缓存。 |
| `close_file` | 关闭当前项目并释放缓存。重复调用也是安全的。 |
| `current_project` | 返回当前是否已加载项目，以及主输入文件的绝对路径、文件名、扩展名、大小和最后修改时间。 |
| `save_project` | 把主输入文件和 MCP 中产生的 rename 记录保存为 jadx GUI 可打开的 v2 `.jadx` 项目文件。 |

### 浏览与导航

| 工具 | 用途 |
| --- | --- |
| `list_classes` | 浏览包树。不传参返回顶层包；传 `package_prefix` 返回直接子包和直接类；`max_depth=0` 递归展开整个子树。 |
| `class_members` | 查看一个类的方法和字段，包含 descriptor、类型和访问标志。它不做反编译，是观察类 API 形状的便宜入口。 |
| `list_resources` | 枚举资源和 zip 条目，可按 `type`、`path_prefix`、`path_glob` 过滤。只返回元数据，不解码内容。 |

### 反编译与反汇编

| 工具 | 用途 |
| --- | --- |
| `decompile_code` | 反编译一个类或一个方法为 Java 源码。默认返回带行号的源码和旁路 `refs` 数组；`refs` 中的 `ref_id` 可交给 `resolve_ref`。设置 `include_variables=true` 会额外返回可用于 rename 的变量句柄。 |
| `disassemble` | 反汇编一个类或一个方法为 smali。 |
| `decompile_xml` | 按资源路径解码 Android XML 或文本资源。 |
| `resolve_ref` | 把 `decompile_code` 返回的 `(class_fqn, ref_id)` 解析为具体的类、方法或字段。 |

`decompile_code` 的 `annotate` 参数：

| 值 | 效果 |
| --- | --- |
| `sidecar` | 默认值。源码保持干净，引用信息放在 `refs[]` 中。 |
| `inline` | 在源码调用点插入 `/*->Target#Rxx*/` 标记，适合复杂链式调用。 |
| `both` | 同时返回 `refs[]` 和内联标记。 |
| `off` | 只返回源码。 |

行号坐标在 `decompile_code`、`search_code`、`xrefs_to` 和 `resolve_ref` 之间保持一致。行号槽和内联注解不会改变这个坐标系。

### 搜索与分析

| 工具 | 用途 |
| --- | --- |
| `search_symbol` | 按符号表搜索类、方法、字段。支持名称、正则、大小写、包前缀、注解、父类、接口、访问标志等过滤；不反编译。 |
| `search_strings` | 在 dex 字符串常量、final 字段 encoded values、注解参数中搜索字符串；不反编译。 |
| `search_code` | 在反编译 Java 或 smali 文本中全文搜索。第一次会按范围生成代码，成本最高，但结果会在会话内缓存。 |
| `search_resource` | 在解码后的文本资源中搜索，支持正则、忽略大小写、路径前缀和 glob。 |
| `xrefs_to` | 查询类、方法、字段的交叉引用。字段引用会区分 `read`、`write`、`init`。 |
| `method_overrides` | 从一个方法向下查找子类或子接口中的重写，相当于 GUI 里的 Define Plus。 |
| `inheritance_tree` | 查询类型层级。`up` 返回父类链和接口；`down` 返回子类或接口实现者；`transitive=true` 返回完整闭包。 |
| `rename` | 对类、字段、方法或变量添加或更新用户重命名。 |

## 字节码优先的默认设置

MCP 模式下，jadx-mcp 会关闭一组“让源码更好看但会改写符号结构”的 jadx 选项。目标是让工具输出尽量贴近 dex 中真实存在的类、方法和字段，避免后续 `xrefs_to`、`method_overrides`、`inheritance_tree` 或 `search_symbol` 查不到模型刚刚看到的符号。

命名相关：

| 设置 | 原因 |
| --- | --- |
| `deobfuscationOn=false` | 不生成 dex 中不存在的去混淆别名。 |
| `useSourceNameAsClassNameAlias=NEVER` | 不用 `SourceFile` 推导类名别名。 |
| `renameCaseSensitive=false` / `renameValid=false` / `renamePrintable=false` | 不为了 Java 源码合法性改写标识符，保持与字节码符号一致。 |

结构相关：

| 设置 | 原因 |
| --- | --- |
| `moveInnerClasses=false` | 保留 `Outer$Inner` 形式，方便与 smali 和 xref 对齐。 |
| `inlineMethods=false` | 被内联的方法会失去声明位点。 |
| `inlineAnonymousClasses=false` | 匿名类不被折进外层方法，类型图和符号搜索仍能看到它。 |
| `replaceConsts=false` | 保留 `R.id.foo` 这类字段读取，不把它折成字面值。 |

Kotlin metadata / source debug extension 插件仍可恢复 Kotlin 源码名，因为这些名字来自 APK 中的编译器元数据，不是猜测。
