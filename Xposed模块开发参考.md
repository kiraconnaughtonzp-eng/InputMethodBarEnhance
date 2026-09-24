---
name: xposed-module-dev
description: 基于 DYhide（抖音好友隐藏）LSPosed 模块实战总结的 Xposed/LSPosed 模块开发参考——工程骨架、跨进程配置、实时生效、钩子模式、性能卡顿防治、调试日志方法论。写新模块时按此文档的章节逐项核对。
---

# Xposed / LSPosed 模块开发参考（DYhide 实战总结）

> 本文档由 `com.example.douyinhidefriend`（DYhide，抖音好友隐藏模块，v9.x 全功能）实战沉淀。
> 覆盖：工程骨架、跨进程配置、实时生效、钩子模式、**性能卡顿防治（血泪教训）**、
> 调试日志方法论、发布流程。写新模块时逐节对照，能少踩 80% 的坑。

---

## 0. 核心认知（先读）

- **Hook 的是别人的进程**：所有代码跑在目标 App 进程里，任何主线程阻塞都会被用户感知为"App 卡了"。
- **配置跨进程**：模块 UI（自己的 App 进程）改配置，目标 App 进程要能读到——用 ContentProvider + Binder，别用文件（SELinux 隔离）。
- **用户测试不可控**：可能不彻底重启、贴日志片段、跑旧版本——**每次发布都必须在日志里打 `module version = X` 行**，分析前先验证版本。
- **隐蔽性是需求**：尽量无 toast、无弹窗，UI 融入目标风格。

---

## 1. 工程骨架

```
app/src/main/
├── AndroidManifest.xml        # xposedmodule / xposeddescription / xposedminversion / xposedscope
├── java/com/example/xxx/
│   ├── HookEntry.java         # implements IXposedHookLoadPackage, 入口
│   ├── FriendHider.java       # 全部钩子（单文件大而全，调试方便；也可按功能拆分）
│   ├── ConfigProvider.java    # ContentProvider：跨进程配置读写
│   ├── MainActivity.java      # 模块配置 UI
│   └── GlassRows.java         # 共享 UI 组件（模块进程 + 目标进程弹窗共用，不依赖 Xposed API）
└── res/values/arrays.xml      # xposedscope = 目标包名
```

**关键点**：
- `compileOnly 'de.robv.android.xposed:api:82'`——运行时由 LSPosed 提供。
- Manifest 声明作用域数组（静态作用域 v9.74）：`xposedscope` → `@array/xposedscope`。
- 启动流程：`handleLoadPackage` 先拿 `ActivityThread.currentApplication()` 缓存 Context（**启动早期可能为 null，要懒加载重试**），再读配置、装钩子。

---

## 2. 跨进程配置（最核心的架构）

**问题**：抖音进程 SELinux 下**读不到模块 App 的 /data/data 文件**（XSharedPreferences 底层也是文件读取，同样失败）。

**方案**：ContentProvider + Binder。模块 App 里注册 provider（authority 如 `com.example.douyinhidefriend.config`），目标进程通过 `ContentResolver.query/update` 读写。

**版本号一致性（v9.55/56/57 血泪）**：
- 配置带 `version` 列 = 保存时间戳，**严格单调递增**（同毫秒连续两次操作版本号必须不同，否则缓存/provider 打平会误用旧数据）。
- 读时：`cacheVersion > providerVersion` → 用缓存并**回写 provider 自愈**；打平 → 缓存优先。
- 写入顺序：**先写目标进程本地缓存（带版本）再写 provider**——provider 进程不可用时配置不丢。

**commit() vs apply()**：需要"立即生效 + 被杀进程不丢"的场景用 `commit()`（同步写盘）；`apply()` 异步，立即 setReadable 会读到旧文件。

**保存的幂等与去重**：开关防抖 300ms 合并保存（`scheduleQuickSave`）；"全部启用"操作先读再算，**本来就全启用就跳过**（避免无意义写盘）。

---

## 3. 实时生效机制

目标进程要有**配置变化感知**，否则改了配置要重启才生效：

- **配置 watcher**：后台线程每 800ms 轮询 provider，签名变化（uids|nicks|enabled 串）→ reloadConfig + 刷新列表；provider 不可用时退避到 3s。
- **getter 钩子过滤**：`hookAllMethods(adapter, "LLLILZJ")` 后 `param.setResult(filtered)`——返回时过滤，显示层拦截。
- **setData 钩子过滤**：`beforeHookedMethod` 里 `param.args[0] = filtered`——**数据传入瞬间过滤**，从源头杜绝完整列表出现。
- **字段直改**：`setBaseAdapterItems(adapter, filteredList)` + `notifyDataSetChanged()`——兜底修复已渲染的 mItems。
- **多入口刷新**：配置变化 → 立即刷新 + 800ms 延迟二刷（覆盖异步加载场景）。

---

## 4. 钩子模式与踩坑（重点）

### 4.1 渲染走字段不走 getter（v9.129 教训）
- 有的 Adapter **渲染读 mItems 字段、不走 getter**——getter 钩子过滤的是 getter 返回值，**拦不住字段被重新填充**。
- 排查顺序：先确认渲染路径（getter？字段？），再选钩子位置。**setData 源头过滤 > getter 过滤 > 字段直改兜底**。

### 4.2 类级钩子的反射开销（v9.129 → v9.131 卡顿教训）
- `hookAllMethods(cls, "setData")` 是**类级**的，目标 App 任何数据刷新都会调用。
- 钩子回调里如果**对每个元素做深反射匹配**（递归遍历对象字段找 uid/昵称），即使没命中也要完整遍历——高频调用时主线程直接卡死。
- **教训**：钩子回调必须极轻。深反射匹配前先做快速短路（空名单判断、类型判断）；命中率低的热路径宁可只做字符串/类型级预筛。

### 4.3 钩子选择原则
| 场景 | 用 | 别用 |
|---|---|---|
| 列表显示过滤 | setData 参数过滤（源头） | 只靠 getter（字段被重填就漏） |
| 单字段拦截（计数/文本） | 对应 getter/setter 钩子 | 全局 TextView.setText（会误伤） |
| 生命周期时机 | 目标类 onResume/onPause 精确钩 | 全局 Fragment 钩（影响面大） |
| 已渲染列表修复 | 字段直改 + notifyDataSetChanged | 只改缓存 |

### 4.4 生命周期标志位（返回键/页面切换类需求）
- 用精确类（如消息列表 Fragment 的 onResume/onPause）维护 `sessionListVisible`。
- 聊天详情打开 → 消息适配器绑定时置 `chatDetailOpen=true`；**返回列表时列表 Fragment 可能一直 resumed（详情是叠加 Fragment）不会重新 onResume** → 全局 `Fragment.onPause` 里非列表类暂停即清零（v9.124 修复"返回后不触发"）。
- 时序：`onBackPressed` 先跑（看到 true 不隐藏）→ Fragment 弹出触发 onPause 清零 → 下一次返回正常触发。

---

## 5. 性能与卡顿防治（血泪教训，写模块必读）

目标 App 是别人的，主线程阻塞=用户骂娘。DYhide 排查切后台回前台卡 3-5 秒的完整链条：

### 5.1 ContentProvider/Binder 主线程阻塞（v9.132）
- `acquireContentProviderClient(uri)` 在 provider 进程不可用（模块被 force-stop/未启动）时，**会触发系统拉起模块进程，主线程阻塞数秒**。
- **防治**：
  - **不可用冷却**：记录失败时间戳，3 秒内直接读本地缓存，不再 acquire（不阻塞、不反复拉起）。
  - **节流**：高频入口（如全局 Fragment.onResume 里 reloadConfig）2 秒内最多一次。
  - 冷却期跳过 provider 写入（缓存已写 + 版本号自愈回写兜底）。

### 5.2 重查询类调用阻塞主线程（v9.133）
- 每次保存配置都触发 `refreshData()`（让目标 App 重新查询数据存储）——切一次后台 = hide + revert 两条链 × 多次重查，**主线程累积卡 3-5 秒**。
- **防治**：
  - **后台静默保存**：后台触发的隐藏/还原只写配置，**跳过列表刷新**（列表不可见无需立即刷新，回前台进列表页时 onResume 再刷）。
  - **按需重查**：重查询类调用只在"相关页面可见"时执行（`sessionListVisible` 门槛）。
  - 可见界面触发（返回键/开关）保留完整刷新——用户在看着。

### 5.3 系统冻结（MIUI App Freezer）
- 锁屏/切后台后目标 App 主线程可能被冻结，`postDelayed` 的任务被卡到解锁才执行。
- 现象：锁屏时写入很快，但解锁后 UI 才更新（好友可见 ~3s）。
- **防治**：回前台/页面 onResume 时**立即重过滤**，不依赖延迟任务。

### 5.4 通用原则
- 主线程上：Binder 调用、数据库重查、深反射遍历 → 全部要防。
- 后台线程做的 UI 操作（notifyDataSetChanged 等）要 post 回主线程。
- 加"耗时日志"（`took=Xms`）到关键链路上——下次卡顿一眼定位。

---

## 6. 调试与日志方法论

### 6.1 版本行（最重要）
```java
XposedBridge.log("[DTH] module version = 9.133 (short desc)");
```
**分析日志先找这行**，确认用户装的是新版。用户可能不彻底重启、跑旧版——没有版本行一切都是白分析。

### 6.2 日志去噪三板斧
- **一次性**：`logCountOnce(msg)`（Set 记录，同消息每会话只打一次）——启动注册、诊断 dump 用。
- **限流**：`logBadge(key, msg)` 每 key 每秒最多一次——高频 setText/刷新日志用。
- **翻转才打**：状态日志只在 true/false 翻转时打——provider 可达性、轮询状态。

### 6.3 定位类日志（排障时加，修复后删）
- **触发路径标识**：同一功能多条触发链时，每条打 `xxx trigger: onPause / SCREEN_OFF / onStop`——一次复现就能看出走的是哪条路。
- **耗时日志**：`took=Xms` 打在链路入口/出口——卡顿定位用。
- **状态诊断**：`back hide state: visible=X chatDetailOpen=Y`（每 5s）——排查标志位状态。

### 6.4 调试节奏
1. 加"触发路径 + 耗时"日志 → 发布 → 用户复现贴日志。
2. 根据日志定位到具体函数 → 修复。
3. 修复确认后**把临时诊断日志删掉**（保持日志干净），再发布。

---

## 7. 隐蔽性与 UI

- **无 toast 成功提示**（用户要求"尽量隐蔽"）；只有错误/引导类才提示。
- 深色玻璃风 UI：`GradientDrawable` 半透明白（`0x14FFFFFF`）+ 圆角 + 细描边（`0x1AFFFFFF`），深色渐变窗口（`#22223A → #12121F`），状态栏同色融入。
- 分区卡片布局：区块标题用"强调竖条 + 粗体标题 + 右侧灰字说明"；主操作按钮实底蓝色、危险操作半透明红。
- 目标进程内的快捷弹窗与模块 UI **共用一套纯 Android 代码组件**（不依赖 Xposed API），两进程都能安全调用。

---

## 8. 版本管理与发布流程

1. **改代码 → 同步三处**：`FriendHider.java` 的 `module version =` 行、`build.gradle` 的 `versionCode/versionName`、`版本更新日志.md` 顶部新条目。
2. **编译检查**（不依赖 Gradle 时）：
   ```
   javac -encoding UTF-8 -source 8 -target 8 \
     -cp "android-34.jar;stubs目录" -d out src/**/*.java
   ```
   - stubs 目录自己维护（XposedBridge 等 compileOnly 类）。
   - **MainActivity 的 R 类报错是预期的**（无 R 类），只要 Hook 相关文件零报错即可。
3. **用户测试**：Android Studio Run 安装 → **彻底杀掉目标 App 再开** → 贴日志。
4. **验证**：先看 `module version =` 行确认版本，再看功能日志。

---

## 9. 最小骨架模板（新模块起步）

```java
public class HookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!lp.packageName.equals("目标包名")) return;
        // 1. 缓存 Context（可能为 null，懒加载重试）
        // 2. 读配置（ContentProvider 跨进程）
        // 3. XposedBridge.log("[DTH] module version = 0.1")
        // 4. 逐个装钩子，每个 try/catch + 一行日志确认挂上
    }
}
```

```xml
<!-- AndroidManifest.xml 模块声明 -->
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" android:value="模块说明" />
<meta-data android:name="xposedminversion" android:value="82" />
<meta-data android:name="xposedscope" android:resource="@array/xposedscope" />
```

```xml
<!-- res/values/arrays.xml -->
<array name="xposedscope">
    <item>目标包名</item>
</array>
```

---

## 10. 适用性与裁剪指南（按模块类型）

本文档源于"列表过滤/内容隐藏"类模块，但方法论分三层通用性——写新模块前先对号入座：

| 章节 | 列表过滤类 | 单点行为修改类 | 无 UI 纯自动类 | UI 增强类 |
|---|---|---|---|---|
| 1 工程骨架 | ✅ 全用 | ✅ 全用 | ✅ 全用 | ✅ 全用 |
| 2 跨进程配置 | ✅ 必需 | ◐ 有设置页才用 | ✕ 跳过 | ◐ 有设置页才用 |
| 3 实时生效 | ✅ 必需 | ◐ 配置热生效才用 | ✕ 启动时钩一次即可 | ◐ 同左 |
| 4 钩子模式 | ✅ 全用 | ◐ 只看 4.3 通用原则 | ◐ 只看 4.3 | ◐ 只看 4.3 + 4.4 |
| 5 性能防治 | ✅ 全用 | ◐ 总原则 + 5.1/5.4 | ◐ 总原则 + 5.4 | ◐ 总原则 + 5.4 |
| 6 调试日志 | ✅ 全用 | ✅ 全用 | ✅ 全用 | ✅ 全用 |
| 7 隐蔽性与 UI | ✅ 全用 | ◐ UI 部分按需 | ✕ 跳过 UI | ✅ 全用 |
| 8 版本与发布 | ✅ 全用 | ✅ 全用 | ✅ 全用 | ✅ 全用 |
| 9 骨架模板 | ✅ 全用 | ✅ 全用 | ✅ 全用 | ✅ 全用 |

- ✅ = 直接用；◐ = 按需裁剪（看模块有没有 UI/配置/热生效需求）；✕ = 跳过
- **列表/内容过滤隐藏类**（如 DYhide、消息过滤、内容替换）＝本文档全量适用
- **单点行为修改类**（改按钮行为、禁用某功能、篡改返回值）＝骨架 + 日志 + 钩子通用原则，
  钩子的具体姿势（getter/setData/字段）换成"目标方法 before/after 改参/改结果"即可
- **无 UI 纯自动类**（自动操作、跳过广告）＝只有第 1/6/8/9 节，其余跳过
- **UI 增强类**（注入控件、改主题）＝骨架 + 日志 + 第 7 节风格参考

**跨类型通用的三条铁律**（任何模块都别破）：
1. 日志首行打 `module version = X`，分析先验证版本
2. 钩子回调极轻，主线程不碰 Binder/重查/深反射
3. 三处版本同步 + 用户"彻底杀 App 再开"后测试

---

## 11. 检查清单（写新模块每节过一遍）

- [ ] 日志首行打 `module version = X`
- [ ] 配置跨进程用 ContentProvider，带**单调递增版本号**，缓存优先
- [ ] 写配置用 `commit()`，先写目标进程缓存再写 provider
- [ ] 列表过滤优先 **setData 源头**，getter/字段修复兜底
- [ ] 钩子回调**无深反射热路径**；主线程**无 Binder/重查/深遍历**
- [ ] 后台触发的保存**静默化**（不刷新列表），重查**按需化**
- [ ] provider 不可用**冷却** + 高频入口**节流**
- [ ] 日志**一次性/限流/翻转才打**，临时诊断用完就删
- [ ] 版本三处同步（日志行 / build.gradle / 更新日志）
- [ ] 发布后让用户**彻底杀目标 App 再开**，先验证版本行
