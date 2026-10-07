# Vmessage（拾玖世界 fork）

![Vmessage](src/main/resources/Vmessage_desc.jpg)

跨服聊天插件。玩家在任意子服说话，消息按统一格式转发到其它子服，支持 LuckPerms 前缀/后缀、称号公会等自定义字段、子服 PlaceholderAPI 变量。

> 本仓库 fork 自 [FeuSalamander/Vmessage](https://github.com/FeuSalamander/Vmessage) `1.6.2`，
> 针对「拾玖世界」群组服做了修补与增强。产物可直接用于 **Velocity 3.4 / 4.x**。
>
> 上游 1.6.2 在 Velocity 4.x 上**玩家一说话就崩**，且仓库 clone 下来编译不过。本 fork 修了这些，
> 并补齐了一批生产环境必需的能力。改动清单见 [本 fork 的改动](#本-fork-的改动)。

---

## ⚠️ 先看这里

1. **`Message.all` 必须保持 `false`**。改成 `true` 会 deny 原始聊天事件并由代理全服重发，
   在 1.19.1+ 会直接把玩家踢下线。
2. **`Message-format.minimessage` 必须保持 `false`**。LuckPerms 里存的是 `&` 颜色码，
   MiniMessage 模式下会原样显示成 `&c`。
3. **命令是小写 `/vmessage`**（大写 `Vmessage` 作为别名保留）。Velocity 底层走 Brigadier，
   literal 节点大小写敏感——敲 `/Vmessage` 会被判定为「命令不存在」并转发给后端子服。

---

## 安装

### 代理端（Velocity）

| 文件 | 放到哪 |
|---|---|
| `Vmessage-<version>.jar` | `plugins/` |
| `PAPIProxyBridge-Velocity-*.jar` | `plugins/`（可选，要用 `%xxx%` 变量才需要） |

### 各子服（Bukkit / Paper / Leaf）

| 文件 | 放到哪 | 必要性 |
|---|---|---|
| `PAPIProxyBridge-Bukkit-*.jar` | `plugins/` | 可选，同上 |
| `VmessageSuppress-1.1.1.jar` | `plugins/` | 可选，见[被子服取消的聊天](#9-被子服取消的聊天不再跨服泄露) |

启动后代理日志会打一句 `Vmessage by 拾玖世界 is working !`，随后逐条报告当前生效的配置
（哪些服参与、等不等抑制信号、网址怎么处理等）——配置错了在日志里一眼能看出来。

---

## 配置文件全解

文件位置：`plugins/vmessage/config.toml`。改完执行 `/vmessage reload`，或把 `auto-reload` 打开。

### 顶层：热重载

| 配置 | 默认 | 说明 |
|---|---|---|
| `auto-reload` | `false` | `true` = 定时检查文件修改时间，改了自动重载，不用敲命令不用重启 |
| `auto-reload-interval-seconds` | `5` | 检查间隔（秒），最小 1 |

⚠️ 热重载**只管 `config.toml` 这一个文件**。TOML 写错时会保留旧配置并打 WARN，不会让插件失效。
`PAPIProxyBridge` 自己的 `settings.yml`、jar 本身不在此列。

### `[Message-format]`

| 配置 | 默认 | 说明 |
|---|---|---|
| `minimessage` | `false` | **保持 false**，原因见上 |

### `[Message]`

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `all` | `false` | **保持 false**。false = 不拦原始聊天，只把格式化消息发给「除发送者所在服以外」的子服 |
| `format` | 见文件 | 聊天格式，可用占位符见下 |
| `commands` | `[]` | 有人说话时代理执行的命令 |
| `message-colors` | `"strip"` | `strip` 去色 / `parse` 解析颜色码 / `keep` 原样保留。持有 `vmessage.color` 的玩家强制 parse |
| `papiproxybridge` | `true` | 子服 PAPI 变量总开关；没装桥接时自动降级为空，不报错 |
| `no-papi-format` | 见文件 | 发给「没装桥接的子服」的简化格式，里面不能写 `%xxx%`；留空则用 `format` |
| `no-papi-servers` | `[]` | 没装桥接的子服名单（`velocity.toml` 里注册的名字） |
| `read-bridge-blacklist` | `true` | 把 PAPIProxyBridge 的 `serverList` 自动并入上面的名单，两处只维护一个 |
| `papi-cache-millis` | `30000` | 解析结果缓存时长；`0` = 关缓存 |
| `papi-timeout-millis` | `1500` | 等子服响应的超时，超时就把 `%xxx%` 当空串 |
| `papi-retry-times` | `0` | 桥接内部重试次数，设 0 避免失败时白等好几轮超时 |
| `await-cancel-signal` | `true` | 是否等子服的「这条聊天被取消了」信号，见下 |
| `await-cancel-timeout-millis` | `100` | 最长等多久（毫秒），上限 1000，`0` = 不等待 |
| `await-cancel-server-mode` | `"blacklist"` | 哪些子服要等这个信号，见下 |
| `await-cancel-servers` | `[]` | 名单内容 |
| `server-filter-mode` | `"blacklist"` | 哪些子服参与跨服聊天，见下 |
| `server-filter` | `[]` | 名单内容 |

#### 被子服取消的聊天，不再跨服泄露

商店让玩家输入购买数量、菜单让输入颜色值，这些插件会 `setCancelled(true)` 把消息吞掉。
但代理收到聊天的**同一时刻**就把消息发给了别的子服，而子服的取消发生在**之后**且无法回传——
于是别的子服会看到玩家输入的「64」。

`await-cancel-signal = true` 时，代理先等 `await-cancel-timeout-millis` 毫秒，
子服配套的 `VmessageSuppress` 回一句「这条被取消了」就整条不转发
（跨服转发、`commands` 一起都不做）。

- **失效模式是安全的**：子服没装配套插件，代理每次等到超时就照常转发，不会漏发，
  只是别的服看到消息晚一点。
- **只影响别的子服看到消息的时间**。玩家自己所在服的聊天由子服自己广播，手感不变。

#### 指定哪些子服要等这个信号

没装 `VmessageSuppress` 的子服永远不会有信号，等它只是白等。
用这一组配置把它们排除掉：排除掉的服**收到就转发，零延迟**（代价是商店输入这类被取消的
聊天会漏到别的服，反正那些服本来也拦不住）。

```toml
await-cancel-server-mode = "blacklist"   # blacklist = 名单里的不等；whitelist = 只有名单里的等
await-cancel-servers = []                # velocity.toml 里注册的服务器名，大小写都行
```

| 模式 | 名单为空 | 名单非空 |
|---|---|---|
| `blacklist`（默认） | **全都等**（与加这个功能之前一致） | 名单里的不等，其余都等 |
| `whitelist` | **谁都不等**＝关掉抑制 | 只有名单里的等 |

```toml
# 例 1：只有生存服装了配套插件 → 白名单
await-cancel-server-mode = "whitelist"
await-cancel-servers = ["survival"]

# 例 2：小游戏服和起床服没装 → 黑名单
await-cancel-server-mode = "blacklist"
await-cancel-servers = ["minigame", "bedwars"]
```

- 白名单配成空列表会打 WARN（等于全服关掉抑制）。
- 模式只认 `blacklist`/`whitelist`，写错或留空退回黑名单；服务器名取不到时按「等」处理
  （晚一点不会丢消息，反过来就会漏掉抑制）。
- `await-cancel-signal = false` 或 `await-cancel-timeout-millis = 0` 时，任何服都不等
  ——这两个总开关优先于名单。起服日志会逐条打出到底哪些服在等。

#### 指定哪些子服参与

```toml
server-filter-mode = "blacklist"   # blacklist = 名单里的不参与；whitelist = 只有名单里的参与
server-filter = []                 # velocity.toml 里注册的服务器名，大小写都行
```

| 模式 | 名单为空 | 名单非空 |
|---|---|---|
| `blacklist`（默认） | 全部参与 | 名单里的不参与，其余都参与 |
| `whitelist` | **谁都不参与**＝关掉跨服聊天 | 只有名单里的参与 |

- **不参与是双向的**：既不往外发，也收不到别服的聊天；它自己服内部的聊天不受影响。
- 被排除的服说话时 `commands` 不执行。
- 白名单配成空列表会打 WARN（那是全服断流，最容易踩的坑）。
- 模式只认 `blacklist`/`whitelist`，写错或留空退回黑名单；服务器名取不到时按「参与」处理。
  失效模式一律偏向「多发」，不会静默断流。
- ⚠️ `all = true` 时若发送者所在服被排除，代理**不会** deny 它的原始聊天——
  否则 deny 了又不代发，那个服玩家的聊天会被彻底吞掉。

### `[Message.named-colors]`

`{#brown}`、`{#orange}` 这类 CMI 命名色的对照表。查不到的命名色会被摘掉标记、保留文字。

### `[Broadcast]` —— 五档广播的总闸

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | **一句话关掉全部广播**：`false` 时 Join/Leave/Kick/Disconnect/Server-change 一条都不发 |

- 不用把五个 `enabled` 一个个改成 `false`；想恢复就改回 `true`。
- ⚠️ `false` 时各档自己配的 `commands` **也不跑**（效果等同把五档的 `enabled` 全关）。
- ⚠️ 只管广播，不管聊天：`[Message]` 的跨服转发不受它影响。

### `[Join]` / `[Leave]` / `[Kick]` / `[Disconnect]` / `[Server-change]`

五档进出服广播，结构相同（整体开关见上面的 `[Broadcast]`）：

| 配置 | 说明 |
|---|---|
| `enabled` | 该档开关（总闸 `[Broadcast].enabled = false` 时这一项不用管） |
| `format` | 格式串 |
| `commands` | 触发时执行的命令 |

| 档 | 触发时机 | 可用占位符 |
|---|---|---|
| `Join` | 进入网络 | `#player#` `#server#` |
| `Leave` | 正常离开 | `#player#` `#oldserver#` |
| `Kick` | 被踢出子服 | `#player#` `#oldserver#` |
| `Disconnect` | **还没进任何服就断开**：版本不符、封禁、顶号、登录超时 | 只有 `#player#` |
| `Server-change` | 切服 | `#player#` `#oldserver#` `#server#` |

- `Disconnect` 是发给**全体在线玩家**的。若有人扫服、或被封的号反复重连，全服会跟着刷屏——
  真出现就把 `enabled` 改成 `false`。
- 被 `/kick` 的人走 `[Kick]`，**不会**再补一条 `[Leave]`（Velocity 会同时给两个事件，
  已用 `KickTracker` 去重；标记取一次即失效，被踢的人重连后正常退出不会被误吞）。

### `[Aliases]`

服务器名 → 显示名的映射，供 `#server#` / `#oldserver#` 使用：

```toml
lobby = "大厅"
survival = "净土"
```

### `[Custom-Meta]`

`#xxx#` 默认直接当 LuckPerms meta 键名查（meta 叫 `title` 就写 `#title#`，不用登记）。
这里只在想**起别名**时才用：想写 `#称号#` 但 meta 键是 `title`，就加一行 `称号 = "title"`。

### `[Tooltip]` —— 悬停看发送时间，点一下自动填私聊命令

鼠标放到消息上会显示提示，点一下有动作。**分三档，效果不一样**：

| 鼠标放在哪 | 悬停显示 | 点一下 |
|---|---|---|
| 转发聊天的**前缀 + 名字**（`#message#` 以外的部分） | `prefix` + `hover`（默认发送时间） | 把 `suggest` **填进聊天框**（不是直接发出去） |
| 转发聊天的**正文**（`#message#`，玩家真正说的那句话） | `copy-hover`（默认「复制该文本」） | 把这句话**复制到剪贴板** |
| 正文里的「[链接]」 | **网址提示**（`[Link].hover`） | **打开浏览器**（`[Link]` 自己的行为，不受三档影响） |
| 五档广播（Join/Leave/Kick/Disconnect/Server-change） | `hover`（**不加 `prefix`**） | **不响应**（广播没有「给谁发消息」的含义） |

广播里的 `{player}` 就是那条广播的主角。

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关；`false` 时三档都不挂 |
| `prefix` | `""` | 悬停文本前面再加一截 —— **只加在跨服聊天上，广播不加**；留空 = 不加。详见下面 |
| `hover` | `"&e发送时间: &6{time}"` | 前缀/名字（以及广播）上的悬停文本；**留空 = 不显示提示** |
| `suggest` | `"/msg {player} "` | 点前缀/名字时填入聊天框的命令；**留空 = 点了没反应** |
| `copy` | `true` | 正文的「悬停 + 点一下复制」开关；`false` = 正文恢复成跟前后一样（悬停看时间、点一下填命令） |
| `copy-hover` | `"&7复制该文本"` | 正文上的悬停文本；**留空 = 只不显示提示，点击照样复制** |
| `time-format` | `"HH:mm:ss"` | `{time}` 的时间格式（Java 写法），写错退回 `HH:mm:ss` |
| `time-zone` | `""` | `{time}` 的时区，留空 = 服务器系统时区。例如 `"Asia/Shanghai"` |

`hover` / `suggest` 里可以写的占位符：

| 占位符 | 换成什么 |
|---|---|
| `{time}` | 这条消息的发送时刻（按 `time-format` / `time-zone` 渲染） |
| `{player}` | 发送者名字 |
| `{server}` | 发送者所在子服，走 `[Aliases]` 别名（例如「净土」） |

#### `prefix` —— 悬停文本前面再加一截（只给跨服聊天加）

想在悬停里先亮一行「来自哪个服 / 什么称号」，就写在 `prefix` 里：

```toml
[Tooltip]
prefix = "&8[&b#server#&8] &7来自 &f%playerTitle_use% "
hover  = "&e发送时间: &6{time}"
```

- 🔴 **只加在跨服聊天上** —— Join/Leave/Kick/Disconnect/Server-change 五档广播的悬停里
  **不会出现** `prefix`（「XX 加入了服务器」前面顶一截称号/服务器名没有意义）。
- 里面可以写**消息格式那一套**（跟 `Message.format` 同一批解析、都按**发送者所在服**计算）：
  `#server#` / `#player#` / `#prefix#` / `#suffix#` / `[Custom-Meta]` 定义的，
  以及 `%xxx%` 子服 PAPI 变量（需要装 PAPIProxyBridge）。
  同一条消息里 `prefix` 和 `format` 是分别解析的，互不干扰。
- 顺带也认 `{time}` / `{player}` / `{server}`（前缀是拼进 `hover` 之后一起填的）。
- ⚠️ **直接拼在 `hover` 前面，不会自动加分隔符** —— 想换行或空格就自己写在末尾。
- 留空（默认）= 不加，行为跟加这个功能之前完全一样。

几点说明：

- **正文自己设了事件，就不会再继承整条消息的悬停/点击** —— 这是 adventure 的规则（子节点优先）。
  所以正文上看到的是「复制该文本」，不是发送时间；点它也不会去填 `/msg`。
- 🔴 **正文里的「[链接]」永远保持 `[Link]` 自己的行为**：悬停看完整网址、点一下开浏览器，
  **不会**变成「复制该文本」也不会去复制。这一档是**逐节点跳过**实现的 ——
  挂复制时撞到自带 hover/click 的整棵子树就原样放过，不靠继承规则兜底。
- `all = true` 时发给**发送者自己所在服**的那一份聊天，前缀/名字不加悬停/点击
  （不然点一下是给自己发消息）；正文的复制不受影响。
- 广播里 `{server}` 是相关子服的显示名；`Disconnect` 那档人根本没落到子服上，`{server}` 是空串。
- 复制到剪贴板用的是**纯文本**（网址会保留完整 URL，不会被「[链接]」三个字替换）。
- 悬停文本支持 `&` 颜色码；想换行就写 `\n`（TOML 里 `"第一行\n第二行"`）。
- `suggest` 末尾留一个空格，玩家点完就能直接接着打字；这里只能填到聊天框，不能直接执行。

### `[Link]`

消息里的网址做成可点击的「[链接]」（点一下打开浏览器，悬停看完整网址）。

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 开关 |
| `text` | `"&9[链接]"` | 显示文本 |
| `hover` | `"&7点击打开：&f{url}"` | 悬停文本 |
| `pattern` | 见文件 | 网址正则 |

⚠️ 默认正则**不是** `https?://\S+`——中文之间没有空格，`\S+` 会把整句话吞进去。
用的是 RFC3986 允许的 URI 字符集；末尾粘着的标点会剥掉后回补成普通文本。

### 颜色语法（`message-colors = "parse"` 时）

`message-colors = "parse"` 时支持（对齐 CMI/CMILib 的 `CMIChatColor`）：

| 写法 | 例子 |
|---|---|
| 传统 16 色 + 格式码 | `&4` `&c` `&l` `&r` |
| hex | `&#FF0000`、`&#F00`（3 位自动展开成 6 位） |
| 1.16 原生 hex | `&x&F&F&0&0&0&0` → `&#FF0000` |
| CMI 花括号 hex | `{#FF0000}` `{#F00}` |
| 命名色 | `{#brown}`（查 `[Message.named-colors]`） |
| 渐变 | `{#FF0000>}文字{#0000FF<}` |
| 自定义字体 | `{@字体}` —— 无法实现，忽略 |

渐变需要权限 `vmessage.gradient`（配置 `gradient-permission`，留空则不限）。
无权限时只摘掉渐变标记、文字保留，其余 `&c`/`{#F00}` 照常解析。
渐变段内的 `&l` 这类格式码会被丢掉，颜色名查不到会退化成纯文字。

---

## 占位符

| 占位符 | 含义 |
|---|---|
| `#player#` | 玩家名 |
| `#prefix#` / `#suffix#` | LuckPerms 前缀 / 后缀 |
| `#message#` | 聊天内容 |
| `#server#` / `#oldserver#` | 当前 / 上一个服务器名（走 `[Aliases]`） |
| `#xxx#` | 任意 LuckPerms meta 键；取不到时抹成空串，不会把 `#title#` 字面量显示给玩家 |
| `%xxx%` | 子服 PlaceholderAPI 变量，由**玩家所在子服**现算 |

`%xxx%` 走 `PAPIProxyBridge` 的插件消息通道，代理端不用预先同步任何 meta、也不用改 LuckPerms 存储：

```toml
format = "&8[&b#server#&8] &r%playerTitle_use% [&6%legendaryguild_guild%&r] #prefix#&f#player#&7: &r#message#"
```

⚠️ 没装 `PAPIProxyBridge-Bukkit` 的子服，每次发言都要等满一次超时。
把这种服加进**代理端** `plugins/PAPIProxyBridge/settings.yml` 的黑名单即可零延迟：

```yaml
serverListMode: BLACKLIST
serverList:
  - killer
```

PAPI 缓存的键是「(发送者 UUID, 目标 UUID, 所在服名) + format 模板」——**按玩家隔离，
称号公会不会串号**，切服因服名入键自然重取。代价是变更后最多延迟一个 TTL。

---

## 权限节点

| 权限 | 作用 |
|---|---|
| `vmessage.color` | 消息里的颜色码始终解析（不受 `message-colors` 限制） |
| `vmessage.gradient` | 使用 `{#A>}文字{#B<}` 渐变 |
| `vmessage.minimessage` | 在消息里使用 MiniMessage 语法 |
| `vmessage.reload` | 执行 `/vmessage reload` |
| `vmessage.sendall` | 执行 `/sendall` |
| `vmessage.silent.join` | 进出服不广播 Join |
| `vmessage.silent.leave` | 进出服不广播 Leave |
| `vmessage.silent.change` | 切服不广播 Server-change |

⚠️ `vmessage.sendall` 是新增的。上线后要在 LuckPerms 给管理员组，否则没人能用
（Velocity 没有 OP 概念，权限全靠 LuckPerms）：

```
/lp group admin permission set vmessage.sendall true
```

---

## 命令

| 命令 | 说明 |
|---|---|
| `/vmessage reload` | 重载 `config.toml`（别名 `/Vmessage`） |
| `/sendall <内容>` | 把一句话广播到所有服，走聊天那套格式 |

⚠️ 命令在主名注册成大写时敲小写会「不存在」——上游就踩了这个坑，这里主名改成小写、大写做别名。
**通用教训：Velocity 插件注册命令一律用小写主名。**

---

## 本 fork 的改动

相对上游 `1.6.2`。

### 1. 修 Velocity 4.x 一说话就崩

上游用了 `Component.replaceText(String, ComponentLike)`，这个两参重载在新版 adventure 里已移除，
在 4.x 上玩家一说话就抛 `NoSuchMethodError`。改为 `TextReplacementConfig` 写法，3.x / 4.x 都能跑。

### 2. `#xxx#` 直接当 LuckPerms meta 键，个数不限

上游只认死的两个槽位 `custom1` / `custom2`。现在 format 里出现的**任何** `#xxx#`
（除内建的 `player/prefix/suffix/message/server/oldserver`）都直接当 meta 键名去查。
`[Custom-Meta]` 降级为可选别名，条目数不限。取不到值时占位符被抹成空串，不显示字面量。

### 3. 支持子服 PlaceholderAPI 变量（可选）

见上文「占位符」。配套加了 `no-papi-format` / `no-papi-servers` / `read-bridge-blacklist`，
给没桥接的服发简化格式，名单自动沿用 PPB 的黑名单（两处只维护一个）。

### 4. 空段清理（含「解析成功但值是空的」变量）

两类空段都会被收掉，不留多余空格、不留空的 `[]`：

- **没解析出来**：`[&6%legendaryguild_guild%&r]` 这类带方括号的段，变量取不到时会剩一对空的 `[]`。
  清理时连同方括号（及其前一个空格）一起删掉，只删「除颜色码外为空」的括号，
  `[青龙]`、`[AFK]` 这类有内容的不会被误伤。
- **解析成功但值是空的**：比如 `%playerTitle_use%` 玩家没称号时返回**空串**。
  这时 `%xxx%` 已经不在字符串里了，光看结果判断不出这段是空的 ——
  格式里 `&r%playerTitle_use% [&6%legendaryguild_guild%&r]` 会渲染成
  `[生存]␣␣[无公会]`（`&r` 是看不见的，玩家只看到两个空格）。

  做法：解析**之前**给每个 `%xxx%` 两侧套一个私用区标记记住边界，
  解析**之后**按标记取回值——值里只有颜色码/空格就当成没有，整段连同
  紧邻的空格、紧贴左边的颜色码（那是给这个空段写的）一起删掉。

⚠️ 实现上用的是哨兵机制：**不能直接折叠连续空格**——称号的值里可能本来就有空格
（`&f[ 大佬 &f]`），折叠会把它破坏掉。只删紧贴左侧的颜色码，右侧的颜色码属于下一段
（`%title% &f#player#` 里的 `&f`），删了玩家名会变色。

### 5. 补齐上游缺失的文件

上游仓库 clone 下来**编译不过**（缺类）且打出的 jar **缺 `velocity-plugin.json`**（Velocity 不认）。补上了：

- `src/main/resources/velocity-plugin.json` —— 插件描述。`version` 写的是 `${project.version}`，
  构建时被 Maven 过滤成真版本号；不过真正生效的是 compile 阶段由 `@Plugin` 注解生成的那份（会覆盖它），
  所以**升版本只用改 `pom.xml`**（`@Plugin(version=)` 引的是自动生成的 `BuildConstants.VERSION`）
- `src/main/java/ooo/foooooooooooo/velocitydiscord/VelocityDiscord.java` —— 仅用于通过编译的 stub
  （**已随 Discord 转发功能一并删除**，见第 14 条）
- `pom.xml`：`java.version` 11 → 17

### 6. 命令名改成小写

见上文「命令」。

### 7. 消息颜色模式与 CMI 语法兼容

`message-colors = strip | parse | keep` + 权限 `vmessage.color`。
`ChatColors.java` 归一化 CMI 的全部颜色语法（含 `&x&F&F&0&0&0&0`、3 位 hex、命名色）。

### 8. 渐变

`{#FF0000>}文字{#0000FF<}` 逐字插值染色，需 `vmessage.gradient`。
实现上是抠出换哨兵 → 逐字生成组件 → 插回，因为段内 `&l` 会丢、认不出的色会退化成纯文字。

### 9. 被子服取消的聊天不再跨服泄露

见上文。需配套插件 [VmessageSuppress](https://github.com/shijiu-world/VmessageSuppress)（源码也在本地
`D:\Code\mc\plugins\VmessageSuppress`）。没装的服可以用 `await-cancel-servers` 排除，不再白等。

### 10. 指定哪些子服参与（黑名单 / 白名单）

见上文。默认黑名单 + 空名单 = 全部参与，装上后行为与加这个功能之前完全一致。
同一套黑/白名单写法也用在 `await-cancel-servers`（哪些服要等抑制信号）。

### 11. 补上「还没进服就断开」的广播 `[Disconnect]`

对齐线上 velocity-chat 的 `[disconnect]` 档。判据是「登录没成功」或「断开时不在任何子服上」。

### 12. 踢人不再播两条

被 `/kick` 时 Velocity 既给 `KickedFromServerEvent(DisconnectPlayer)` 又给 `DisconnectEvent`，
原来 `[Kick]` 和 `[Leave]` 各播一次。新增 `KickTracker`：踢人时记一笔，紧接着的 `[Leave]` 跳过，
标记取一次即失效 + 10 秒兜底。

### 13. 修 4 个 bug + 解析兜底

| # | 问题 | 后果 |
|---|---|---|
| 1 | Discord 转发用的是 `#message#` **还没替换**的格式串 | Discord 收到 `xxx: #message#` 字面量 |
| 2 | Discord 剥色是 `split("§")` 后每段 `substring(1)` | 只在串以颜色码开头时才对；前缀为空时会吃掉第一个字：`史蒂夫离开了` → `蒂夫离开了` |
| 3 | `/sendall` 只取 `args[0]` | `/sendall 大家好 各位` 只发出「大家好」 |
| 4 | `/sendall` 的 `hasPermission` 只判 `instanceof Player` | 任何玩家都能全服广播 |

> 注：第 1、2 条只影响 Discord 转发，该功能已整体移除（见第 14 条）。

另外：

- **删掉上游的调试残留**：`Join`/`Leave`/`Kick`/`Server-change` 里各有一句
  `proxyServer.sendMessage(Component.text(Arrays.toString(dump2)))`，
  每次进/出/切服都会向**全服**广播一句 `[&e, xxx离开了...]` 这样的数组文本。
- **剥色会弄坏网址**：`?a=1&b=2` 里的 `&b` 是合法颜色码字符，会被吃掉变成 `?a=1=2`。
  正则加了「后面不是 `=`」的约束。
- **解析兜底**：`build()` 里的 `deserialize` 加了 try/catch。玩家内容或 LP 前缀有畸形语法时
  退化成纯文本发出去，而不是让整条消息消失（以前那种情况别的服一条都收不到）。只 warn 一次，不刷屏。

### 14. 移除 Discord 转发

上游自带 Discord 转发，但它的 ClassLoader 隔离问题让这个功能永远拿不到真插件（详见
[已知限制](#已知限制)），留着只是个定时 NPE 源。整套删除：stub 类、11 处调用、剥色正则、开关字段、
可选依赖。

### 15. 悬停看发送时间，点一下自动填私聊命令

见上文 [`[Tooltip]`](#tooltip--悬停看发送时间点一下自动填私聊命令)。跨服聊天消息整条挂上悬停提示
和 `suggest_command`，`all = true` 时发给发送者自己所在服的那一份不挂。

### 16. 广播也带悬停，并加了 `[Broadcast]` 总闸

五档广播（Join/Leave/Kick/Disconnect/Server-change）走 `Listeners.broadcast()` 统一出口，
共用 `hover`（`{player}` = 广播主角），但**不带点击**。
新增 `[Broadcast].enabled`：**一个开关**关掉全部广播（含各档的 `commands`），默认 `true`。

### 17. 正文单独一档：悬停「复制该文本」，点一下复制到剪贴板

`#message#` 那一截自己挂上 `copy-hover` + `ClickEvent.copyToClipboard`，
于是它不再继承整条消息的「悬停看发送时间 / 点一下填 `/msg`」——
前缀和名字保留那两个效果，正文只负责复制。纯文本要在网址被换成「[链接]」**之前**取。

### 18. 正文里的「[链接]」不被复制档抢走

正文挂复制是**逐节点下钻**的（`ChatTooltip.copyDeep`）：撞到自带 `hover` / `click` 的整棵子树
就原样跳过，所以 `[Link]` 做出来的「[链接]」仍是悬停看完整网址、点一下开浏览器，
不会显示「复制该文本」，点击也不会去复制。
（本来也可以只靠 adventure「子节点优先」的继承规则，但链接有时会被包一层空节点，
那层空根自己是没事件的，靠继承就保不住了。）

### 19. 悬停文本也能加前缀（只给跨服聊天加）

`[Tooltip].prefix`：悬停提示前面再拼一截自定义内容，支持 `#server#` / `#player#` 和
`%xxx%` 子服 PAPI 变量（跟 `Message.format` 同一批、按发送者所在服解析）。
🔴 **五档广播不加前缀** —— 广播走的是另一档挂载点（`ChatTooltip.applyHover`），
根本传不进前缀，所以 Join/Leave/Kick 那些提示里永远只有 `hover` 那一行。

---

## 配套插件 `VmessageSuppress`

**每个子服都要装**，否则「被子服取消的聊天」这一项不生效（退化为等到超时后照常转发）。

- `AsyncPlayerChatEvent` 挂两个监听器：**LOWEST** 记下玩家真正输入的原文，
  **MONITOR** 发现 `isCancelled()` 为真就走 `vmessage:suppress` 通道回传 `(玩家UUID, 原文)`。
- 记**原文**而不是事件里的 message——万一中间插件改写过，代理端拿原始输入比对才对得上号。
- 只有被取消时才发包，正常聊天零开销。

仓库：<https://github.com/shijiu-world/VmessageSuppress>（本地 `D:\Code\mc\plugins\VmessageSuppress`）。
编译（仓库里有 `build.sh`，会自动找 paper-api）：

```bash
javac -encoding UTF-8 --release 17 -cp <服务端 API jar> -d out src/main/java/cn/shijiu/vmessagesuppress/VmessageSuppress.java
cp src/main/resources/plugin.yml src/main/resources/config.yml out/
jar cf target/VmessageSuppress-1.1.1.jar -C out .
```

---

## 构建

```bash
mvn package        # 离线：mvn -B -o package
```

产物：`target/Vmessage-<version>.jar`（class 版本 61，Velocity 3.4 / 4.x 通用）。

⚠️ 提交前确认 `Listeners.java` 等源文件是 **LF** 换行（编辑器在 Windows 上容易写成 CRLF）。

---

## 已知限制

- **Discord 转发已整体移除**（原依赖 [VelocityDiscord](https://github.com/fooooooooooooooo/VelocityDiscord)，
  且因 Velocity 各插件 ClassLoader 隔离、本插件只能看见自带的 stub，装了真插件反而会 NPE）。
  本项目用不到，故彻底删掉：stub、开关字段、11 处转发调用、剥色正则一并清除。
- `no-papi-format` 的默认值 `<#player#>: #message#` 里的尖括号在 `minimessage = false` 下
  会原样显示。想要干净的输出建议改成 `&f#player#&7: &r#message#`。
- 自动热重载的调度任务常驻（默认每 5 秒检查一次），关掉时也在空转——开销极小，暂未优化。
- `Link` 每条消息都跑一遍正则 + 组件树遍历，没做短路优化。
