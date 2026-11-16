# 依赖 API 真机验证

- **日期**：2026-11-16
- **jar**：`mixin-tweaker-shim-0.9.0.jar`（5 class，`mcmod.info` + `ForceLoadAsMod`）
- **调用方**：`controlflex-0.9.0.0-mc1.12.2-forge.jar`
- **实例**：Prism `1.12.2-forge`（Forge 14.23.5.2859）

## 格 1 —— 无 provider + shim + 调用方：通过

```
restored 2 jar(s) from FML's known-library list (ForceLoadAsMod):
  [controlflex-0.9.0.0-mc1.12.2-forge.jar, mixin-tweaker-shim-0.9.0.jar]
[ControlFlex] degraded mode: NO_PROVIDER (Mixin unknown) -- controller features disabled, keyboard and mouse unaffected
ControlFlexForge mod loaded (degraded)
Forge Mod Loader has successfully loaded 6 mods
```

结论：无 provider 时 shim **把自己和调用方一起**放回列表（这正是 `required-after` 需要的前提），mod 降级加载。6 个 mod 含 shim 自身。

## 格 2 —— 有 provider（MixinBooter 5.0）+ shim + 调用方：通过，但有一条警告

```
Mixing: 35
Mod mixintweakershim is missing the required element 'version' and a version.properties
  file could not be found. Falling back to metadata version 0.9.0
Mod controlflex is missing the required element 'version' ... Falling back to metadata version 0.9.0.0
[ControlFlex] Mixin runtime OK: the base config's interface injection is live (MouseHandlerAccess implemented)
```

结论：provider 的 agent 把 shim force-load 进列表，`required-after` 的前提成立；35 条 mixin 正常应用。

**关于那条警告**：`@Mod` 不写 `version` 时 FML 会警告并在找不到 `version.properties` 时回退到 `mcmod.info` 的版本（这里正是模板展开的 0.9.0，行为正确）。**调用方自己也在产生同一条警告**——这是这个项目既有的写法，不是本次引入的问题。

不修的理由：要消掉它就得把版本号写进注解（与 `build.gradle` 两处漂移），或生成一个 `BuildInfo` 类（class 数变 6，削弱"逐一点名"的门禁）。为了消一条警告付这两个代价不划算。

## 未验证（明确标注）

| 格 | 场景 | 原因 |
|---|---|---|
| 3 | 只装调用方、有 provider → FML 报缺失依赖 | 需要调用方先在 `@Mod(dependencies=...)` 里声明 `required-after:mixintweakershim`，属主仓库改动，不在本次范围 |
| 4 | 专用服务端 + shim + provider | 未启动服务端 |
| 5 | 只装调用方、无 provider → CNFE | 已由早前矩阵 #2 覆盖（同一个 `ClassNotFoundException`），未重复执行 |

## 附加观察

格 2 里有两行 `Attempting connection with missing mods [minecraft, mcp, FML, forge, mixinbooter, mixintweakershim, controlflex] at CLIENT/SERVER`——这是 FML 启动期探测客户端/服务端 mod 列表的常规日志，与本改动无关；同一台实例在改动前的日志里也有类似行。
