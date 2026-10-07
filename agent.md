# agent.md — Vmessage（跨服聊天）

> 给 AI 上手用的**代码地图**。服主视角的「怎么装、怎么配」在 `README.md`，本文档不重复。
> 本文档讲：代码在哪、改一处会牵动什么、哪些东西碰了就出事。

## 一句话定位

Velocity 代理端插件。玩家在任意子服说话 → 代理按统一格式转发到**其它**子服。
装在哪：**只装代理**。子服可选装 `PAPIProxyBridge-Bukkit` 和 `VmessageSuppress`。

- 源码：`D:\Code\mc\plugins\Vmessage`
- 仓库：`git@github.com:shijiu-world/Vmessage.git`（**走 SSH**，https 会被本机代理掐断 502）
- 上游：`FeuSalamander/Vmessage` 1.6.2，本 fork 修了 Velocity 4.x 崩溃并加了生产必需的功能
- 产物：`target/Vmessage-<版本>.jar`（class 61，Velocity 3.4 ~ 4.x 通用）。**版本号真源只有 `pom.xml` 一处**：
  pom → `src/main/java-templates/.../BuildConstants.java`（templating 插件生成）→ `@Plugin(version=...)`
  → 注解处理器重写的 `velocity-plugin.json`。升版改 pom 即可，`@Plugin` 里别写字面量。
- 已编译产物副本：`D:\game\Server\.workbuddy\vmessage\`

---

## 源码地图

包 `me.feusalamander.vmessage`，共 15 个源文件（其中 1 个是构建时生成的）。

| 文件 | 行 | 职责 | 动它之前先想清楚 |
|---|---|---|---|
| `ChatTooltip.java` | ~200 | 挂悬停提示（`prefix` + 发送时间）+ 点击填命令（`/msg`）。聊天与五档广播共用 | 事件挂在**根组件**上往下继承；消息里的 [链接] 有自己的 openUrl/hover，优先级更高不会被抢。🔴 `prefix` 只从 `apply(...,prefix)` 这条进来，广播的 `applyHover` 传 null |
| `BuildConstants.java`（⚠️在 `src/main/java-templates/`，**构建时生成**，别手改） | 21 | 唯一一个常量：`VERSION`，等于 pom 的 `<version>` | `@Plugin` 要编译期常量，没法写 `${project.version}`；以前硬编码导致产物里的版本号永远是旧值 |
| `VMessage.java` | 262 | 主类。`@Inject` 拿 `ProxyServer`/`Logger`/`Metrics.Factory`/`@DataDirectory`，注册监听器、命令、自动重载调度 | 命令名在这里注册（**小写 `/vmessage`**，大写只做别名） |
| `Listeners.java` | ~590 | **核心**。聊天/进出服/切服/踢人的全部转发逻辑；`broadcast()` 是五档广播的统一出口 | 动这里等于动整个插件行为，必看下面「数据流」 |
| `Configuration.java` | 705 | 全部配置项读取。用 `com.moandjiezana.toml.Toml`（tomlj）解析 | 加配置要同时改 `config.toml` 默认值 + README 表格 |
| `ChatColors.java` | ~300 | 颜色归一化：CMI 全系语法（`&x&F&F...`、3位hex、裸 `#RRGGBB`、`{#F00}`、命名色、渐变） | 渐变是「抠哨兵 → 逐字染色 → 插回」 |
| `FormatCleaner.java` | ~200 | 清理空段：`[&6%guild%&r]` 取不到值时连括号删掉；**PAPI 变量解析成功但值为空**时也要删（靠 mark/resolveMarks 记边界） | ⚠️ 靠哨兵机制，**不能折叠连续空格**（称号值里有空格）；只删紧贴哨兵**左侧**的颜色码 |
| `Linkify.java` | 96 | 网址 → 可点击 `[链接]` | 正则用 RFC3986 字符集，**不是** `https?://\S+`（中文无空格会吃整句） |
| `Suppression.java` | 188 | 收子服「这条聊天被取消了」信号，命中则整条丢弃 | 通道 `vmessage:suppress`，配套 `VmessageSuppress` 插件 |
| `PapiBridge.java` | 136 | 反射调 PAPIProxyBridge 解析 `%xxx%`，带缓存 | 反射是刻意的——桥接是可选依赖，不能编译期硬引 |
| `KickTracker.java` | 48 | 踢人去重：被 `/kick` 时 Velocity 同时给 `KickedFromServerEvent` 和 `DisconnectEvent` | 标记取一次即失效 + 10 秒兜底 |
| `ReloadCommand.java` | 51 | `/vmessage reload` | |
| `SendCommand.java` | 41 | `/sendall` | |
| `Metrics.java` | 1026 | bStats 统计，**上游自带，不要动** | 改坏了不影响功能但会刷异常 |

---

## 数据流：一条聊天怎么走完

```
PlayerChatEvent (Listeners.onMessage)
  ↓ ① 发送者所在服在不在名单里（server-filter-mode/filter）
  ↓    不在 → 直接 return：不转发、也不跑 commands
  ↓    ⚠️ 但不能 deny 原始聊天（all=true 时代理靠自己重发，deny 了又不代发 = 吞掉聊天）
  ↓ ② all=true → e.setResult(denied())
  ↓ ③ 这个服要不要等信号（await-cancel-signal 开着 + 该服在 await-cancel-servers 判定内）
  ↓    等 → 挂一个延迟任务（默认 100ms），到期问 Suppression：这条被取消了？是 → 整条丢弃
  ↓    不等（没装 VmessageSuppress 的服）→ 跳过延迟，立即往下走
  ↓ ④ 拼格式：#player#/#prefix#/#suffix#/#server# 内建位 → #xxx# 当 LuckPerms meta 键查
  ↓            → FormatCleaner.mark() 给 %xxx% 套边界标记 → %xxx% 走 PapiBridge 向「发送者所在子服」现算
  ↓ ⑤ ChatColors 按 message-colors（strip/parse/keep）+ vmessage.color 权限处理消息内容
  ↓    ⚠️ 格式串（format / no-papi-format / Tooltip / 广播 / %xxx% 的返回值）也走 ChatColors，
  ↓       但**恒按 parse**，不吃 message-colors —— 见铁律 19
  ↓ ⑥ FormatCleaner.finish()：resolveMarks（空值→哨兵）→ stripUnresolved（空括号→哨兵）→ collapse（收空格）→ Linkify 做网址
  ↓ ⑦ 发给「除发送者所在服以外」且通过名单的其它子服（这一步才挂上 [Tooltip] 的悬停/点击）
  ↓ ⑧ 跑 [Message].commands
```

进出服广播同理，走 `[Join]`/`[Leave]`/`[Kick]`/`[Disconnect]`/`[Server-change]` 五档：

```
事件（Join/Leave/Kick/Disconnect/Server-change）
  ↓ ① 总闸 [Broadcast].enabled —— false 就整条不处理（各档 commands 也不跑）
  ↓ ② 该档自己的 enabled（Join.enabled 等）+ vmessage.silent.* 权限
  ↓ ③ 跑该档 commands
  ↓ ④ Listeners.broadcast()：格式串解析成组件 → 挂 [Tooltip] 的 **hover 档**（不挂点击）→ proxyServer.sendMessage
```

**悬停/点击的三档**（adventure 规则：子节点自己设过事件就**不继承**父的）：

| 位置 | 悬停 | 点击 | 挂载点 |
|---|---|---|---|
| 聊天整条（= 前缀+名字那一段） | `Tooltip.prefix` + `Tooltip.hover` 发送时间 | `Tooltip.suggest` 填命令 | `ChatTooltip.apply(...,prefix)` ← `deliver()` |
| 聊天正文 `#message#` | `Tooltip.copy-hover` | 复制到剪贴板 | `ChatTooltip.copy()` ← `build()` |
| 正文里的「[链接]」 | `[Link].hover` 网址 | **打开浏览器** | `Linkify` 自己做，复制档**逐节点跳过**它 |
| 五档广播 | `Tooltip.hover`（🔴**不加 prefix**） | **无** | `ChatTooltip.applyHover()` ← `broadcast()` |

**`Tooltip.prefix` 的解析链路**（只有跨服聊天有）：

```
Listeners.message()
  ↓ prepare() 换掉 #server# / #player# / LP meta（跟主 format 同一批）
  ↓ 含 %xxx% → PapiBridge.format() 异步解析（⚠️ 与主 format **分开**两次调用，
  ↓            不拼成一个串——PAPI 的返回值里可能有分隔符，拆不回来）
  ↓ FormatCleaner.finish() 收尾（§→&、空段折叠）
  ↓ deliver(..., tooltipPrefix)
  ↓ ChatTooltip.apply(..., prefix)：prefix 直接拼在 hover 前面 → 一起 LEGACY 反序列化 → 一起过 fill()
```

---

## 铁律（改之前先背）

1. **`Message.all` 保持 `false`**。`true` 会 deny 原始聊天并代理全服重发，1.19.1+ 直接把玩家踢下线。
2. **`Message-format.minimessage` 保持 `false`**。LuckPerms 里存的是 `&` 颜色码，MiniMessage 模式会原样显示 `&c`。
3. **命令主名必须小写**。Velocity 走 Brigadier，literal 节点大小写敏感，注册成大写时敲小写会「命令不存在」并被转发给后端子服。
4. **TOML 写错时保留旧配置并 WARN**，绝不让插件变半成品。新增配置也照这个模式来。
5. **所有失效模式偏向「多发」**：名单模式写错退回 blacklist、服务器名取不到按「参与」处理。别改成偏向静默。
6. `#xxx#` 取不到值时抹成空串，**不能把 `#title#` 字面量显示给玩家**。
7. 剥颜色的正则里 `[0-9a-fA-Fk-oK-OrR]` 后面必须跟 `(?!=)`——否则 `?a=1&b=2` 会被吃成 `?a=1=2`，链接直接坏。
8. 提交前确认源文件是 **LF**（Windows 编辑器容易写成 CRLF）。⚠️ 本仓库**没有** `.gitattributes`，靠手动注意。
9. 🔴 **`%xxx%` 变量「解析成功但值是空串」时，只有 mark/resolveMarks 的边界标记能识别出来**
   —— 光看解析结果里有没有 `%xxx%` 字面量判断不了。改 `FormatCleaner` 时别把标记步骤绕过去，
   也别图省事改成「折叠连续空格」（称号值里有空格，会坏）。
   收空格的三条边界：紧贴哨兵**左侧**的颜色码要一起删；**右侧**的属于下一段，必须留。
10. **包名 `me.feusalamander.vmessage` 是上游命名空间，不是作者，别改**。改了要动 15 个文件 +
   `velocity-plugin.json` 的 `main`，纯属自找麻烦。作者字段只有三处：
   `velocity-plugin.json` 的 `authors`、`VMessage.java` 的 `@Plugin(authors=...)`、起服日志那句
    `Vmessage by xxx is working !`。三处已统一为「拾玖世界」。
    ⚠️ `@Plugin` 的英文 `description` 是**上游旧文案**（照 1.6.2 写），不影响运行，改它纯属洁癖。
11. 🔴 **校验产物别只看本地 `target/classes`**：`velocity-api` 自带注解处理器会在 compile 阶段
    按 `@Plugin` 重新生成 `velocity-plugin.json`，把 resources 里过滤好的那份盖掉
    （详见文末「已修复」一节）。务必 `jar xf` 出 **Actions 构建的那份产物**再验版本号。
12. 🔴 **adventure 里「子节点自己设过的事件优先，继承只发生在自己没设时」** ——
    想让某一截**不**继承父组件的悬停/点击，就得给它**显式**设上（提示留空也要 `showText(Component.empty())`，
    光是不设就会把父的继承过来）。正文的「复制」档正是靠这个把「发送时间 / 填 /msg」挡在外面。
    复制用的纯文本要用自写的 `ChatTooltip.plain()`（`PlainComponentSerializer` 不在 Velocity 4.x 里），
    且必须在网址被 Linkify 换成「[链接]」**之前**取。
12b. 🔴 **正文里的「[链接]」必须逐节点跳过，不能只靠 12 的继承规则保**：`ChatTooltip.copyDeep()`
    递归下钻，撞到自带 `hover`/`click` 的整棵子树就原样返回。原因：链接后面跟标点时
    Linkify 会 `append` 出一个**空根**（空根自己没事件），靠继承就保不住链接了 ——
    玩家会看到「复制该文本」、点一下变成复制而不是开浏览器。改 `copy()` 时别退回「只在根上挂一次」。
13. 📌 **五档广播只从 `Listeners.broadcast()` 出去**（别在事件里直接 `proxyServer.sendMessage`）——
    总闸 `[Broadcast].enabled` 和悬停/点击都挂在这一层，绕过去就会漏。
13. 📌 **升版本号只改 `pom.xml` 一处**：`@Plugin(version = BuildConstants.VERSION)`，
    `BuildConstants` 由 `src/main/java-templates/` 生成。**别把字面量写回注解里**。
14. 🔴 **`Tooltip.prefix` 只属于跨服聊天**：它是在 `Listeners.message()` 里跟主格式同一批解析、
    再由 `deliver()` 传给 `ChatTooltip.apply()` 的。五档广播走 `broadcast()` → `applyHover()`，
    那条路**根本没有前缀参数** —— 想「顺手让广播也带前缀」必须改挂载点，别在 `applyHover`
    里偷偷读 `cfg.getTooltipPrefix()`（那是刻意的设计：广播里顶一截称号/服务器名没有意义）。
15. 📌 **`prefix` 与主格式要分开调 `PapiBridge`**：两者是不同的模板串，不能先拼一个串再按
    分隔符拆（PAPI 的返回值里完全可能出现那个分隔符）。没含 `%` 的那一路直接给
    `CompletableFuture.completedFuture`，别多跑一趟桥接。
16. 📌 **起服速览归 `debug` 开关**：`VMessage.reportConfig()`（`reportTooltip`/`reportServerFilter`/
    `reportAwaitCancel` 都是它的子过程）第一行就 `if (!configuration.isDebug()) return;` —— 默认不打。
    排查时把 config.toml 的 `debug` 改成 true 再 `/vmessage reload` 就能看到，**不用重启代理**
    （`reload()` 里也调了 `reportConfig()`）。加新的速览行就往这几个方法里写，别自己开 logger.info。
    ⚠️ `PAPIProxyBridge 状态：已连接` 那两行**不受** debug 管（属运行状态，是验证桥接的标记）。
17. 🔴 **`Message.papi-servers` 是白名单**（列出**装了**桥接的服；v1.13.0 之前的
    `no-papi-servers` 是黑名单，已删）。判定的唯一入口是 `Configuration.isPapiServer()`，
    `isNoPapiServer()` 只是它的取反 —— 别在调用点自己写 `!contains(...)`。
    ⚠️ **留空 = 全部都按「装了」算**（沿用老默认，升级不炸）；只有非空时才逐个比对。
    老键 `no-papi-servers` / `read-bridge-blacklist` 只在 `warnRenamedKeys()` 里提示一句，
    **不参与计算** —— 黑名单转白名单要先知道「全部服有哪些」，配置阶段拿不到。
    （`PapiBlacklist.java` 已随 `read-bridge-blacklist` 一起删掉。）
18. 🔴 **裸 hex `BARE_HEX_6 = (?<![&§{])#([0-9a-fA-F]{6})`，头部断言不能拆，且在 `STRIP` 里必须排最后**：
    `(?<![&§{])` —— 没有它，`&#FF0000` 会被二次加 `&` 变成 `&&#FF0000`（多一个字面 `&`），
    `{#FF0000}` 也会被插进一个 `&#` 把 CMI 写法搅坏。
    🔴 **尾部刻意不加 `(?![0-9a-fA-F])`**（v1.14.0 加过、v1.14.1 删掉）：加了它，`#00ff001`
    「绿字 1」、`#FF0000abc` 这类**颜色码后直接跟数字/字母**的写法全部失效 ——
    而玩家实测日志里全是这种写法。现在统一按「`#` 后取 6 位当颜色、剩下的是文字」处理，
    `#FF0000AA` 会渲染成红色的「AA」，这个代价远小于漏解析。
    `STRIP` 里裸 hex 放在所有带 `&`/`{}` 的分支**之后**，否则 `#FF0000` 先被摘掉、留下孤零零的 `&` 或 `{`。
    ⚠️ **只认 6 位，故意不做裸 `#RGB`**：中文聊天里 `#666`「666」是高频网络用语，误伤太大；
    要简写请写 `&#F00`（带 `&` 本来就没歧义，照旧支持）。
    ⚠️ `normalize()` 里裸 hex 是**最后一步**（`&#RRGGBB` / `{#RRGGBB}` 都已在前面变成 `&#RRGGBB`），
    顺序调换了就会重复加 `&`。
19. 🔴 **两条解析路径，能力必须分清**：
    | | 解析器 | 受 `message-colors` / `vmessage.color` 约束 | 裸 hex / `{#RRGGBB}` / 渐变 |
    |---|---|---|---|
    | **玩家聊天内容** `#message#` | `ChatColors.component(mode, …)` | ✅ 是（strip 会摘掉颜色码） | ✅ |
    | **格式串**：`format` / `no-papi-format` / Tooltip hover+prefix / 五档广播 / `%xxx%` 返回值 | `ChatColors.format(…)` | ❌ **恒按 parse** | ✅（v1.15.0 起） |
    格式串恒 parse 是刻意的：它是管理员配的、PAPI 是子服插件算的，
    不能因为说话的人没 `vmessage.color` 权限就把称号/公会名/服务器名的颜色一起剥掉。
    ⚠️ `minimessage = true` 时格式串**不能**过 `ChatColors`（那是 `<red>` 语法，补 `&` 会破坏标签）——
    `Listeners.parseQuietly(text, mini, namedColors)` 里靠 `mini` 分支隔开。
    📌 风险点已测：归一化不会破坏 `#message#` 占位符（它后面还要 `replaceText` 换成正文组件）——
    `PapiFormatTest` 有专门断言。

---

## 改动地图

| 想改什么 | 改哪 | 连带要动 |
|---|---|---|
| 加一个配置项 | `Configuration.java` + `src/main/resources/config.toml` | README 的配置表格、起服日志里的 `reportConfig` |
| 加一个占位符 | `Listeners.java` 的 `BUILTIN` 集合 + 替换逻辑 | 内建名不能当 meta 键查 |
| 消息格式相关 | `Listeners.java` 的 `message()` | 只此一路，改格式串只影响游戏内显示 |
| 悬停提示 / 点击填命令 | `ChatTooltip.java`；三个挂载点：`Listeners.deliver()`（整条聊天）、`Listeners.build()` 里的正文 `#message#`、`Listeners.broadcast()`（五档广播） | 🔴 **子节点自己设过事件就不继承父的**：正文挂了复制档，所以不再继承整条的「时间 + /msg」；正文里的 [链接] 又盖过正文。广播只走 `applyHover`（不挂点击、**不加 prefix**） |
| 悬停前缀 `Tooltip.prefix` | `Configuration.getTooltipPrefix()`（存模板）→ `Listeners.message()` 里 `prepare()` + `PapiBridge` 解析 → `deliver(...,tooltipPrefix)` → `ChatTooltip.apply` | 🔴 前缀与主格式**分开**两次 PAPI 调用（不能拼一个串再拆）；含 `%` 才走异步。广播不走这条路 |
| 颜色/渐变语法 | `ChatColors.java` | VWhisper 有一份**同源但独立**的 `ChatColors`，改语法两边都要改 |
| 网址识别 | `Linkify.java` 的 `pattern` | 默认值在 `config.toml` 的 `[Link]` |
| 子服参与名单 | `Configuration.buildNoPapiServers` / `isChatServerAllowed` | 白名单配成空 = 全服断流，要打 WARN |

---

## 构建 & 测试

```bash
cd D:/Code/mc/plugins/Vmessage
JAVA_HOME=D:/Code/Java/zulu25.34.17-ca-jdk25.0.3-win_x64 mvn -B -o package
```

- 离线构建（`-o`），依赖都在 `~/.m2`。**不要**加 shade 插件，装不上。
- ⚠️ **本仓库没有 src/test**（别往里加：surefire 没有 JUnit provider 会挂）。
  但 `FormatCleaner` 是**纯 Java、不依赖 Velocity**，可以单独拿出来跑：
  ```
  # 测试台副本：D:\game\Server\.workbuddy\vmessage\FormatCleanerTest.java（36 条断言）
  javac -encoding UTF-8 -d out <仓库>/src/main/java/me/feusalamander/vmessage/FormatCleaner.java FormatCleanerTest.java
  java -cp out FormatCleanerTest
  ```
  ⚠️ 用 PowerShell 跑，别用 Git Bash（会把 `-cp` 里的 `D:/...` 做路径转换 → 找不到主类）。
  断言比的是「剥掉颜色码之后玩家看到的文本」，不是原始串 —— 多余空格是视觉问题，`&r` 会干扰比对。
- `ChatTooltip` 也有一份独立的测试台：`D:\game\Server\.workbuddy\vmessage\TooltipTest.java`（80 条断言，含 `FormatCleaner` 的收尾空格用例）。
- 🔴 **`[Tooltip].prefix` 走 `FormatCleaner.finishKeepTrailing()`，不是 `finish()`**：`finish()` 末尾有 `trim()`，
  会把 prefix 末尾那个**刻意写的分隔符空格**裁掉（`[生存]发送时间: …` 黏一起）。消息主格式末尾的空格没意义，
  所以主格式照旧用 `finish()`。空变量留下的残渣空格由哨兵那套吸收，不需要 trim 兜。
  它要 adventure + toml4j + gson 才能跑（**不能**只给 target/classes）：
  ```
  # classpath：仓库 target/classes + ~/.m2 里的 velocity-api、adventure-{api,key,
  #   text-serializer-legacy,text-serializer-minimessage,text-serializer-gson,
  #   text-serializer-commons}、examination-{api,string}、toml4j、gson、slf4j-api
  javac -encoding UTF-8 -cp "<上面那一串>" -d out TooltipTest.java
  java -cp "<上面那一串>;out" me.feusalamander.vmessage.TooltipTest
  ```
  ⚠️ 测试类必须放在 `me.feusalamander.vmessage` 包里（`apply`/`enabled`/`Configuration.load` 都是包级可见）。
  ⚠️ PowerShell 工具不回显 stdout，把输出重定向到日志文件再读，别指望控制台。
  📌 挑「时间格式写错」的用例时注意：`time-format = "yyyy-MM-dd ["` **是合法的**
  （末尾可选段没闭合 Java 也认，`QQQ` 也合法会输出「4季度」），别拿它们当非法用例；
  真正会被 `DateTimeFormatter.ofPattern` 拒的是未知格式字母，例如 `JJ`、`PPPP`。
- 其它类的逻辑改动只能靠本地测试服实机验证。

本地测试服：`D:\game\test_velocity`（velocity 25565 + server1/2 25566/25567）。
验证标志：代理日志出现 `[vmessage] PAPIProxyBridge 状态：已连接`。

---

## 兄弟项目 & 上下游

| 项目 | 关系 |
|---|---|
| [VmessageSuppress](https://github.com/shijiu-world/VmessageSuppress)（本地 `D:\Code\mc\plugins\VmessageSuppress`） | 子服配套。**每个子服都要装**，否则「被子服取消的聊天」退化为等超时后照常转发（这类服可以写进 `await-cancel-servers` 黑名单，让它不再白等）。通道 `vmessage:suppress`，LOWEST 记原文 + MONITOR 判 `isCancelled()`。编译用它的 `build.sh`（不用 Maven，单类 + Bukkit API） |
| `PAPIProxyBridge` 1.8.4 | 可选。Velocity 端 + Bukkit 端。**killer 服没装 PAPI → 加进代理端 `settings.yml` 黑名单**。Bukkit 原版 jar 因 `lettuce-core` 加载失败，**必须用 noredis 版** |
| `VWhisper` | 兄弟项目，管私聊。两者不冲突，但 `ChatColors` 是各自一份拷贝 |
| `VTpa` | 兄弟项目，管传送请求 |

⚠️ **上线状态**：配置与产物**尚未部署到线上**（手册 `跨服聊天上线手册-Vmessage+PAPIProxyBridge.md` 也未上传）。
上线时要停用现用的 `velocity-chat`。

⚠️ 上线后要在 LuckPerms 补 `vmessage.sendall`（Velocity 没有 OP 概念，权限全靠 LuckPerms）。
🔴 给 default 组授 `*` 会静默所有 `vmessage.silent.*` → 进出服广播全没。

---

## ✅ 已修复（v1.9.1）：产物里 `velocity-plugin.json` 的版本号不是当前版本

旧现象：`Vmessage-1.9.0.jar`（**Actions 编的那份**）里 `velocity-plugin.json` 的 `version` 是 **`1.6.1`**，
而本地 `mvn package` 编出来的那份偏偏是对的。`/velocity plugins` 显示的是产物里的值，于是看起来一直没升级。

原因：`velocity-api` 自带注解处理器 `com.velocitypowered.api.plugin.ap.PluginAnnotationProcessor`，
它在 **compile 阶段**（resources 之后）按 `@Plugin` 注解**重新生成** `velocity-plugin.json`，
把 `src/main/resources` 里过滤好的那份覆盖掉 —— 所以「resources 里写了 `${project.version}`」在 CI 的
`clean package` 上根本不算数。而 `@Plugin(version = "1.6.1")` 是上游遗留的字面量，从来没同步过。
本地因为**增量编译不跑处理器**，常常不复现，这就是为什么只验证本地会漏。

修法（已做，选了最省心的那条）：`templating-maven-plugin` 从
`src/main/java-templates/me/feusalamander/vmessage/BuildConstants.java` 生成一个只有 `VERSION` 常量的类，
`@Plugin(version = BuildConstants.VERSION)` 引它 —— 编译期常量成立，pom 仍是唯一真源。

> 另两条备选（没采用，记着以防 BuildConstants 哪天不好使）：
> ① `<proc>none</proc>` 禁掉注解处理器，让 resources 那份说了算；② 手工同步 `@Plugin` 的串（多一处真源，不推荐）。

📌 **校验产物别只看本地 `target/classes`**（可能没被处理器覆盖过），务必：
`jar xf <产物>.jar velocity-plugin.json && grep version velocity-plugin.json`，
而且要用 **Actions 构建出来的那份**（`gh release download` / `gh run download`）验，别拿本地 `target/` 当证据。

## 排查速查

| 现象 | 看什么 |
|---|---|

| 现象 | 看什么 |
|---|---|
| 一说话就崩 | 是不是用了原版 jar？必须自编译修补版。原版在 Velocity 4.x 上 `NoSuchMethodError` |
| `%xxx%` 显示为空 | 目标服没装 PPB-Bukkit；或 `papi-servers` 白名单里没写它（每次发言白等一次超时） |
| 起服看不到「配置速览」 | 正常 —— v1.13.0 起归 `debug` 管，默认不打。把 `debug` 改 true 再 `/vmessage reload` |
| 玩家输入「64」被别的服看到 | 那个子服没装 `VmessageSuppress`；或它被写进了 `await-cancel-servers` 黑名单（黑名单 = 不等） |
| 被踢的人播两条 | `KickTracker` 失效了，查标记是不是没取用/没兜底 |
| 消息里网址坏了 | 剥色正则的 `(?!=)` 断言被改掉了 |
