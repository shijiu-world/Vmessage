![](https://github.com/FeuSalamander/Vmessage/blob/main/src/main/resources/Vmessage_desc.jpg?raw=true)
**Ever wanted to have your messages sent globally across your Velocity proxy with LuckPerms ranks ? Here's a simple plugin to do just that!**
![](https://github.com/FeuSalamander/Vmessage/blob/main/src/main/resources/features.png?raw=true)
- **4 Events with custom messages for each**
  - **Player Message**
  - **Player join the network**
  - **Player left the network**
  - **Player server change**
- **LuckPerms Prefix and Suffix Support**
- **Reload command: "/vmessage reload"**
- **Global message command: "/sendall"**
- **MiniMessage support**
- **Discord Integration with this another plugin: https://github.com/fooooooooooooooo/VelocityDiscord**
![](https://github.com/FeuSalamander/Vmessage/blob/main/src/main/resources/permissions.png?raw=true)
- **"vmessage.reload" to access to the reload command**
- **"vmessage.minimessage" the player needs to have it to use MiniMessage in his messages**
- **"vmessage.silent. leave,change, join" if the player have it/them, no message will be sent**

---

## 本 Fork 的改动（fork of [FeuSalamander/Vmessage](https://github.com/FeuSalamander/Vmessage)）

基于上游 `1.6.2` 的源码，为「拾玖世界」群组服做的修补与增强。产物可直接用于 **Velocity 3.4 / 4.x**。

### 1. 修复 Velocity 4.x 一说话就崩

上游用了 `Component.replaceText(String, ComponentLike)`，这个两参重载在新版 adventure 里已被移除，
在 Velocity 4.x 上玩家一说话就抛 `NoSuchMethodError`。改为 `TextReplacementConfig` 写法，3.x / 4.x 都能跑。

### 2. `#xxx#` 直接当 LuckPerms meta 键，个数不限

上游只认死的两个槽位 `custom1` / `custom2`。现在：

- format 里出现的**任何** `#xxx#`（除内建的 `player/prefix/suffix/message/server/oldserver`）
  都会直接当作 LuckPerms 的 **meta 键名**去查 —— meta 叫 `title` 就写 `#title#`，不用登记。
- `[Custom-Meta]` 降级为可选的「起别名」用，想写 `#称号#` 但 meta 键是 `title` 时才需要，条目数不限。
- 取不到值时占位符被抹成空串，不会把 `#title#` 这种字面量显示给玩家。

### 3. 支持子服 PlaceholderAPI 变量（可选，需装 PAPIProxyBridge）

format 里可以直接写子服的 PAPI 变量，例如对齐 CMI 聊天格式：

```toml
format = "&8[&b#server#&8] &r%playerTitle_use% [&6%legendaryguild_guild%&r] #prefix#&f#player#&7: &r#message#"
```

`%xxx%` 由**玩家所在子服**的 PAPI 现算（走 `PAPIProxyBridge` 的插件消息通道），
代理端不需要预先同步任何 meta、也不用改 LuckPerms 存储。

控制项：

| 配置 | 默认 | 说明 |
|---|---|---|
| `Message.papiproxybridge` | `true` | 总开关；没装桥接时自动降级为空，不会报错 |
| `Message.papi-cache-millis` | `30000` | 解析结果缓存时长 |
| `Message.papi-timeout-millis` | `1500` | 等子服响应的超时，超时就当没解析 |
| `Message.papi-retry-times` | `0` | 桥接内部重试次数，设 0 避免失败时白等 |

⚠️ 没装 `PAPIProxyBridge-Bukkit` 的子服，每次发言都要等满一次超时。
把这种服加进**代理端** `plugins/PAPIProxyBridge/settings.yml` 的黑名单即可零延迟：

```yaml
serverListMode: BLACKLIST
serverList:
  - killer
```

### 4. 空包裹符清理

`[&6%legendaryguild_guild%&r]` 这类带方括号的段，在变量取不到时会剩一对空的 `[]`。
清理时会连同方括号（及其前一个空格）一起删掉，只删「除颜色码外为空」的括号，
`[青龙]`、`[AFK]` 这类有内容的不会被误伤。

### 5. 补齐上游缺失的文件

上游仓库 clone 下来**编译不过**（缺类）且打出的 jar **缺 `velocity-plugin.json`**（Velocity 不认）。本 fork 补上了：

- `src/main/resources/velocity-plugin.json` —— 插件描述，版本由 Maven 过滤自动填入
- `src/main/java/ooo/foooooooooooo/velocitydiscord/VelocityDiscord.java` —— 仅用于通过编译的 stub
  （上游 `Listeners` 引用了它但仓库里没有这个类；运行时未安装该插件时不会被调用）
- `pom.xml`：`java.version` 11 → 17

### 构建

```bash
mvn package
```

产物：`target/Vmessage-<version>.jar`（class 版本 61，Velocity 3.4 / 4.x 通用）。
