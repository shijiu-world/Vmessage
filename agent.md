# agent.md — Vmessage（跨服聊天）

> 给 AI 上手用的**代码地图**。服主视角的「怎么装、怎么配」在 `README.md`，本文档不重复。
> 本文档讲：代码在哪、改一处会牵动什么、哪些东西碰了就出事。

## 一句话定位

Velocity 代理端插件。玩家在任意子服说话 → 代理按统一格式转发到**其它**子服。
装在哪：**只装代理**。子服可选装 `PAPIProxyBridge-Bukkit` 和 `VmessageSuppress`。

- 源码：`D:\Code\mc\plugins\Vmessage`
- 仓库：`git@github.com:shijiu-world/Vmessage.git`（**走 SSH**，https 会被本机代理掐断 502）
- 上游：`FeuSalamander/Vmessage` 1.6.2，本 fork 修了 Velocity 4.x 崩溃并加了生产必需的功能
- 产物：`target/Vmessage-1.6.2-velocity4fix-multimeta.jar`（class 61，Velocity 3.4 ~ 4.x 通用）
- 已编译产物副本：`D:\game\Server\.workbuddy\vmessage\`

---

## 源码地图

包 `me.feusalamander.vmessage`，共 14 个类。

| 文件 | 行 | 职责 | 动它之前先想清楚 |
|---|---|---|---|
| `VMessage.java` | 215 | 主类。`@Inject` 拿 `ProxyServer`/`Logger`/`Metrics.Factory`/`@DataDirectory`，注册监听器、命令、自动重载调度 | 命令名在这里注册（**小写 `/vmessage`**，大写只做别名） |
| `Listeners.java` | 597 | **核心**。聊天/进出服/切服/踢人的全部转发逻辑 | 动这里等于动整个插件行为，必看下面「数据流」 |
| `Configuration.java` | 563 | 全部配置项读取。用 `com.moandjiezana.toml.Toml`（tomlj）解析 | 加配置要同时改 `config.toml` 默认值 + README 表格 |
| `ChatColors.java` | 278 | 颜色归一化：CMI 全系语法（`&x&F&F...`、3位hex、`{#F00}`、命名色、渐变） | 渐变是「抠哨兵 → 逐字染色 → 插回」 |
| `FormatCleaner.java` | ~200 | 清理空段：`[&6%guild%&r]` 取不到值时连括号删掉；**PAPI 变量解析成功但值为空**时也要删（靠 mark/resolveMarks 记边界） | ⚠️ 靠哨兵机制，**不能折叠连续空格**（称号值里有空格）；只删紧贴哨兵**左侧**的颜色码 |
| `Linkify.java` | 96 | 网址 → 可点击 `[链接]` | 正则用 RFC3986 字符集，**不是** `https?://\S+`（中文无空格会吃整句） |
| `Suppression.java` | 106 | 收子服「这条聊天被取消了」信号，命中则整条丢弃 | 通道 `vmessage:suppress`，配套 `VmessageSuppress` 插件 |
| `PapiBridge.java` | 136 | 反射调 PAPIProxyBridge 解析 `%xxx%`，带缓存 | 反射是刻意的——桥接是可选依赖，不能编译期硬引 |
| `PapiBlacklist.java` | 169 | 读 PPB 的 `settings.yml`，把黑名单并入 `no-papi-servers` | 纯文本解析 yml，格式变了会失效（失效模式是取不到 → 按全参与处理） |
| `KickTracker.java` | 48 | 踢人去重：被 `/kick` 时 Velocity 同时给 `KickedFromServerEvent` 和 `DisconnectEvent` | 标记取一次即失效 + 10 秒兜底 |
| `ReloadCommand.java` | 51 | `/vmessage reload` | |
| `SendCommand.java` | 41 | `/sendall` | |
| `Metrics.java` | 1026 | bStats 统计，**上游自带，不要动** | 改坏了不影响功能但会刷异常 |
| `ooo.foooooooooooo.velocitydiscord/VelocityDiscord.java` | 9 | **stub**，只为让引用 Discord 的代码编译通过 | 没装真插件时相关分支不执行 |

---

## 数据流：一条聊天怎么走完

```
PlayerChatEvent (Listeners.onMessage)
  ↓ ① 发送者所在服在不在名单里（server-filter-mode/filter）
  ↓    不在 → 直接 return：不转发、不跑 commands、不进 Discord
  ↓    ⚠️ 但不能 deny 原始聊天（all=true 时代理靠自己重发，deny 了又不代发 = 吞掉聊天）
  ↓ ② all=true → e.setResult(denied())
  ↓ ③ 这个服要不要等信号（await-cancel-signal 开着 + 该服在 await-cancel-servers 判定内）
  ↓    等 → 挂一个延迟任务（默认 100ms），到期问 Suppression：这条被取消了？是 → 整条丢弃
  ↓    不等（没装 VmessageSuppress 的服）→ 跳过延迟，立即往下走
  ↓ ④ 拼格式：#player#/#prefix#/#suffix#/#server# 内建位 → #xxx# 当 LuckPerms meta 键查
  ↓            → FormatCleaner.mark() 给 %xxx% 套边界标记 → %xxx% 走 PapiBridge 向「发送者所在子服」现算
  ↓ ⑤ ChatColors 按 message-colors（strip/parse/keep）+ vmessage.color 权限处理消息内容
  ↓ ⑥ FormatCleaner.finish()：resolveMarks（空值→哨兵）→ stripUnresolved（空括号→哨兵）→ collapse（收空格）→ Linkify 做网址
  ↓ ⑦ 发给「除发送者所在服以外」且通过名单的其它子服
  ↓ ⑧ 跑 [Message].commands、转发 Discord（装了才走）
```

进出服广播同理，走 `[Join]`/`[Leave]`/`[Kick]`/`[Disconnect]`/`[Server-change]` 五档。

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
10. **包名 `me.feusalamander.vmessage` 是上游命名空间，不是作者，别改**。改了要动 14 个文件 +
   `velocity-plugin.json` 的 `main`，纯属自找麻烦。作者字段只有三处：
   `velocity-plugin.json` 的 `authors`、`VMessage.java` 的 `@Plugin(authors=...)`、起服日志那句
   `Vmessage by xxx is working !`。三处已统一为「拾玖世界」。
   ⚠️ `@Plugin` 注解里的 `version = "1.6.1"` 和英文 description 是**上游旧值**，跟实际 1.6.2 不符；
   真正生效的是 `velocity-plugin.json` 那份（Maven 会过滤掉 `${project.version}`）。

---

## 改动地图

| 想改什么 | 改哪 | 连带要动 |
|---|---|---|
| 加一个配置项 | `Configuration.java` + `src/main/resources/config.toml` | README 的配置表格、起服日志里的 `reportConfig` |
| 加一个占位符 | `Listeners.java` 的 `BUILTIN` 集合 + 替换逻辑 | 内建名不能当 meta 键查 |
| 消息格式相关 | `Listeners.java` 的 `message()` | 注意 Discord 分支用的是同一条格式串（曾出过 `#message#` 字面量 bug） |
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
- ⚠️ **本仓库没有单元测试**。逻辑改动只能靠本地测试服实机验证。

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

## 排查速查

| 现象 | 看什么 |
|---|---|
| 一说话就崩 | 是不是用了原版 jar？必须自编译修补版。原版在 Velocity 4.x 上 `NoSuchMethodError` |
| `%xxx%` 显示为空 | 目标服没装 PPB-Bukkit；或没加进 `no-papi-servers`（每次发言白等一次超时） |
| 玩家输入「64」被别的服看到 | 那个子服没装 `VmessageSuppress`；或它被写进了 `await-cancel-servers` 黑名单（黑名单 = 不等） |
| 被踢的人播两条 | `KickTracker` 失效了，查标记是不是没取用/没兜底 |
| 消息里网址坏了 | 剥色正则的 `(?!=)` 断言被改掉了 |
