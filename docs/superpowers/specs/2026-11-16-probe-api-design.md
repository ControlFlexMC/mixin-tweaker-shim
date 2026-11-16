# 对外可依赖的 Mixin 查询 API 设计

- **日期**：2026-11-16
- **范围**：`ControlFlexMC/mixin-tweaker-shim`（本仓库）
- **决策来源**：与人类的对话；Maven 发布被否决，依赖走 mod 元数据

## 1. 要解决什么

其他模组想回答三个问题，但目前只能自己写反射：

1. 这个实例里**有没有** Mixin 运行时？
2. **是哪个版本**？
3. 这个版本**够不够**我的 config 用？

shim 已经掌握了这些事实（它自己就在驱动 Mixin），但没有把它们变成别人可以依赖的东西——尤其没有一个 modid，所以别人**无法在 `mcmod.info` / `@Mod` 里声明依赖**。玩家漏装时，调用方只会在运行时炸 `NoClassDefFoundError`。

## 2. 目标

- 别的模组能写 `required-after:mixintweakershim`，由启动器保证顺序与存在性。
- 三行代码回答上面三个问题，无需反射、无需 try/catch。
- 语义与"自己反射 `MixinBootstrap.VERSION`"**一致**（这是已有 §4.6 契约的延伸）。

## 3. 非目标

- **不做**健康判定框架：不产出 `Reason` 枚举、不决定"致命还是降级"、不提供文案。那些是每个 mod 自己的策略（ControlFlex 的 `MixinRuntimeProbe` + `HealthText` 留在原地）。
- **不做** Maven 坐标、不引入版本仓库。
- **不改**现有语义：`ForceLoadAsMod` 恒表示"这是 mod"，只写 `TweakClass` 恒表示"这是库"。

## 4. 反目标（做出来就算失败）

- 调用方为了"版本未知"必须判断 `null` 还是空串之类的隐式约定。
- shim 因为变成 mod 而在无 provider 的实例上消失（那等于把"基础设施"变成"又一个需要被 force-load 的受害者"）。
- 为了通用化，把 ControlFlex 的类型或文案拉进这个 jar。

## 5. 决策

### 5.1 变成 mod（方案 B）

新增 `mcmod.info` 与一个 `@Mod` 容器类，modid `mixintweakershim`。容器**只有身份**：无字段、无逻辑、无事件监听。

容器存在的唯一理由是拿到 modid，让依赖声明成立。

注解取值（都是"不要碍事"的方向）：

- `acceptableRemoteVersions = "*"` —— 客户端/服务端版本不一致不该被拦，它两边都是启动期设施；
- `acceptedMinecraftVersions = "[1.12.2]"` —— 这是 1.12.2 的构件，装到别的版本上明确拒绝，好过静默行为异常；
- **不声明任何 `dependencies`** —— 它必须能在没有 provider 的实例上工作，写 `after:mixinbooter` 会把"可选"变成"看起来必需"。

### 5.2 必须声明 `ForceLoadAsMod: true`

`required-after:` 是按 mod 列表校验的，所以本 jar 必须真的出现在列表里。它因此要声明自己那条规则所认的属性——**用自己的规矩约束自己**。

两种环境各由一方兜住：

- 有 provider：provider 的平台 agent 读 `ForceLoadAsMod` 并 force-load（ControlFlex 已在 5 个 provider 上验证过同一机制）；
- 无 provider：本 jar 的 tweaker 赢类名 → `unignoreModJars` 把它放回列表。

**移除条件必须放宽（实现时的判断）**：现在 `injectIntoClassLoader` 只在 `state == ABSENT` 时调用 `unignoreModJars`。但 `START_FAILED`（provider 在、`start()`/`doInit()` 抛异常）时既不 inject 也不 unignore，shim 就留在 ignored 表里，于是调用方的 `required-after` 先报**缺失依赖**，连降级页都出不来——这正是 §4 的反目标。

改为：**只要没有成功 delegate（`ABSENT` 或 `START_FAILED`）就执行移除**。成功 delegate 时不动，因为那时 provider 的 agent 会处理。

注意这条规则对本 jar **自己**同样适用：它声明了 `ForceLoadAsMod: true`，所以在无 provider 时它会把自己放回列表（这正是 `required-after` 需要的结果）。早期实现"移除自己"是因为当时它还不是 mod，规则换了之后这一步变成必要而非多余。

### 5.3 对外 API（三个查询）

```java
MixinTweakShim.hasMixinRuntime();            // boolean
MixinTweakShim.detectedVersion();            // "0.8.7" / "unknown" / null
MixinTweakShim.checkVersion("0.7.10");       // VersionCheck.AT_LEAST / BELOW / UNKNOWN
```

`checkVersion` 返回**三态枚举**而不是 `boolean`：0.7 早期引擎没有 `VERSION` 字段，把"读不到"压成 `false` 会让调用方误判为"太旧"。方法名不叫 `isAtLeast`——名字里带 `is` 的方法不该返回三个值。

`VersionCheck` 是嵌套枚举，语义：

| 值 | 含义 |
|---|---|
| `AT_LEAST` | 已知版本，且 ≥ 门槛 |
| `BELOW` | 已知版本，且 < 门槛 |
| `UNKNOWN` | 读不到版本（`null` 或 `"unknown"`），调用方自行决定保守或宽松 |

`checkVersion` 只做比较，不缓存、不写属性、不影响 `TweakState`。

### 5.4 依赖声明写在哪里（实现时的判断）

**写在 `@Mod(dependencies = "required-after:mixintweakershim")`，不是 `mcmod.info`。**

1.12.2 的 `FMLModContainer.bindMetadata` 默认 `useDependencyInformation = false`，优先解析注解；`mcmod.info` 里的 `dependencies` 只有同时写 `"useDependencyInformation": true` 才生效。README 若只给 `mcmod.info` 写法，调用方不会在启动时被拦，而是运行时 `NoClassDefFoundError`——恰好是 §4 想避免的那件事。

`mcmod.info` 仍然保留，但只承担显示元数据（名字、描述、作者），不承担依赖语义。

### 5.5 版本比较规则

按点分段比较数值：`0.7.9 < 0.7.10`（不能按字符串比，字典序会得出相反结论）。段数不同时缺省为 0（`0.8 == 0.8.0`）。

**每段取前导数字**，因此 `0.8.2-SNAPSHOT` 解析为 `{0,8,2}`，与 `0.8.2` 相等。这一步是必要的：Mixin 的 `VERSION` 在实测中出现过 `"0.8"`、`"0.8.1"`、`"0.6.15"`、`"0.7.11"` 等形态，若把带后缀的整段判为不可比，一个 `-SNAPSHOT` 构建会让所有调用方都拿到 `UNKNOWN`。

只有**某段完全没有前导数字**（如 `"dev"`、`""`）才返回 `UNKNOWN`（**不是** `BELOW`）。门槛参数本身非法（null、空串、无数字段）同样返回 `UNKNOWN`，不抛异常。

## 6. 构建影响

- `compileOnly` 需要 Forge 的 `@Mod` 注解类。取 `maven.minecraftforge.net` 上的 `net.minecraftforge:forge:1.12.2-14.23.5.2859:universal`，`transitive = false`（只要注解，不要它拖来的整棵依赖树）。版本与主仓库固定的 1.12.2 构建一致。
- 这是本仓库**第一次依赖 Forge**；tweak 阶段的两个类**仍然不得**引用任何 Minecraft 类型，这条约束不变，门禁也不会因为多了容器类而放宽对 `org/` 的检查。
- `verifyShimJar` 从"恰好 3 个 class"改为"恰好这 5 个，逐一点名"：`MixinTweaker`、`MixinTweakShim`、`MixinTweakShim$TweakState`、`MixinTweakShim$VersionCheck`、`MixinShimMod`。多一个少一个都失败。
- jar 名与版本不变（`mixin-tweaker-shim-0.9.0.jar`）。
- **`mcmod.info` 必须是模板**，版本用 `${version}` 从 `build.gradle` 展开（`processResources`）。手写死 `0.9.0` 会造成两处版本漂移——而这个 jar 已经因为版本号改过一次，知道它会漂。

## 7. 交付的文档

README 增补"从别的模组依赖它"一节：`@Mod(dependencies = ...)` 写法、三行调用示例，以及两种"漏装"的**不同**后果——

- 实例里有 provider：FML 拒绝启动并列出缺失依赖；
- 实例里没有 provider：更早、更原始的 `ClassNotFoundException`（因为 LaunchWrapper 在 Forge 之前就解析 tweaker 类）。这不是新行为，但必须写明，否则那句承诺会被当成无条件成立。

## 8. 验证

单测：版本比较的边界（`0.7.9` vs `0.7.10`、`0.8` vs `0.8.0`、`unknown`、`null`、非数字段）、三个查询互不影响。

真机五格（前两格必测，其余按成本取舍）：

1. **无 provider + shim + 调用方**：shim 出现在 mod 列表，调用方拿到 `ABSENT`/`null`；
2. **有 provider + shim + 调用方**：provider 的 agent 把 shim force-load，`required-after` 成立，版本可读；
3. **只装调用方（漏装 shim），有 provider**：FML 报缺失依赖并拒绝启动——这是选 B 换来的行为。
4. **专用服务端 + shim + provider**：容器不因 `acceptableRemoteVersions` 被拦，`required-after` 同样成立（这条只需一次启动确认，不逐 provider 铺开）。
5. **只装调用方（漏装 shim），无 provider**：仍然是矩阵 #2 那个 `ClassNotFoundException`，因为 LaunchWrapper 在 Forge 之前就死了，根本到不了依赖检查。**这两个格必须分开测**，否则 README 里那句"没装会被启动器拦住"会被误读成无条件成立。

## 9. 留给实现者的空间

- `mcmod.info` 的显示名、描述、作者字段文案；
- 容器类的包名与类名（建议 `com.ifels.mixinshim.MixinShimMod`）；
- `VersionCheck` 三态在 `checkVersion` 内部的具体实现方式（分段解析 + 整型比较即可）；
- 门禁里"逐一点名"的写法。
