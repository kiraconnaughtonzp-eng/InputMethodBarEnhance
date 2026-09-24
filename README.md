> LSPosed / Xposed 模块 · 模块 id `com.wetype.enhance` · 由 LSPosedModuleKit 框架生成

# 输入法底栏增强

给输入法（`com.tencent.wetype`）的键盘加一排快捷按钮：**全选、复制、剪切、粘贴、
光标移动、行首行尾、撤销/重做、收起键盘**。

- 模块 id：`com.wetype.enhance`　日志前缀：`[ENHANCE]`
- 所有动作都走 Android 标准 `InputConnection` 通道（不依赖输入法的混淆类名，升级后一般不会失效）
- 只钩 framework 的稳定方法（`InputMethodService` / `Service`），所以不挑输入法版本

## 支持哪些输入法（**不绑定某一个输入法**）

模块的钩子只用了 framework 的 `InputMethodService` / `Service`，动作只用了标准 `InputConnection`，
**所以任何输入法都能用**（主要测试目标是微信输入法）。默认已把这些包名写进作用域：

| 输入法 | 包名 |
|---|---|
| 微信输入法 | `com.tencent.wetype` |
| 搜狗输入法 | `com.sohu.inputmethod.sogou`（小米定制：`com.sohu.inputmethod.sogou.xiaomi`） |
| 百度输入法 | `com.baidu.input`（小米定制：`com.baidu.input_mi`） |
| 讯飞输入法 | `com.iflytek.inputmethod` |
| Gboard | `com.google.android.inputmethod.latin` |
| AOSP 输入法 | `com.android.inputmethod.latin` |

**要用别的输入法，两步**：

1. 把包名加进 `ModuleScope.TARGET_PACKAGES`，并同步 `res/values/arrays.xml` 的 `xposedscope`；
2. 在 **LSPosed 作用域里勾选那个输入法**（LSPosed 只往你勾选的 App 里注入，作用域数组只是"推荐勾选"）。

注意：不同输入法的键盘窗口结构不同，底栏靠 framework 的 `mInputFrame` 定位（通用）；
若某个输入法把窗口高度写死，可能出现底栏被裁剪 —— 把「底栏高度」调小，或把 `view_tree` 结果发我调整挂载方式。


## 一、功能

| 按钮 | 说明 | 实现通道 |
|---|---|---|
| 全选 | 选中输入框全部内容 | `performContextMenuAction(selectAll)` |
| 复制 | 复制选中内容 | `copy`；失败时 `getSelectedText()` + 系统剪贴板兜底 |
| 剪切 | 剪切选中内容 | `cut` |
| 粘贴 | 粘贴系统剪贴板 | `paste`；失败时 `commitText(系统剪贴板)` 兜底 |
| 清空 | 清空输入框全部内容（危险，默认关闭） | `selectAll` + `commitText("")` / 删除键 |
| ◀ / ▶ | 光标左右移动 | `getExtractedText` + `setSelection`（失败退回方向键） |
| 行首 / 行尾 | 光标跳到行首/行尾 | `KEYCODE_MOVE_HOME / MOVE_END` |
| 撤销 / 重做 | 撤销上一步输入 | `undo / redo` |
| 收起 | 收起键盘 | `requestHideSelf()` |

**形态**：只有一种 —— **键盘最底部的一行**。
做法是把键盘容器包成 `[键盘, 快捷栏]`（键盘整体上移），快捷键占最下面一行，**不覆盖任何按键**。
之前的「植入自带工具栏」「键盘上方新增一行」「覆盖自带底栏」三种尝试已全部去掉。

**外观全部可调**（改完约 1.5 秒自动生效，不用重启输入法）：

| 设置 | 说明 |
|---|---|
| 背景配色 | 跟随键盘 / 深色 / 浅色 / **自定义颜色**（下面三个 RGB 滑杆） |
| 自定义背景 · 红/绿/蓝 | 0~255，选「自定义颜色」时生效 |
| 背景不透明度 | 0~100%，调低就是半透明底栏（0 = 只看得到按钮） |
| 图标 / 文字颜色 | 自动（按背景亮度取对比色）/ 深色 / 浅色 |
| 按钮样式 | 图标 / 图标+文字 / 文字 |
| 底栏高度 | 28~72dp |
| **按钮间距** | 0~24dp（按钮**等宽均分整条底栏**，这里调它们之间的间隙） |
| 文字大小 | 9~22sp（按钮样式为"文字/图标+文字"时生效） |
| 点击震动反馈 | 开 / 关 |

其余：**详细日志**（在「高级」折叠组里）、每个按钮单独开关。

图标是纯代码矢量路径（`ime/BarIcons.kt`，24×24 视口）。
改完造型可以跑 `tools/icon-preview.ps1` 生成预览图先看效果，再回填到代码里。

## 二、真机验证（必须做的三步，代码这边已完成，装不上/没生效一定先过这三步）

> **不想自己编译**：直接到 [Releases](https://github.com/kiraconnaughtonzp-eng/InputMethodBarEnhance/releases) 下载 `app-release.apk`（110KB，已签名）安装即可。

1. **装 APK**：`adb install -r app\build\outputs\apk\debug\app-debug.apk`（或 Android Studio 直接 Run）
   - 想要更小的包就用 release：`adb install -r app\build\outputs\apk\release\app-release.apk`（**110KB**，已签名，可直接覆盖已装的 debug 包）
   - release 默认**复用 debug 签名**（`app/build.gradle.kts` 里 `signingConfig = signingConfigs.getByName("debug")`）——
     未签名的 APK 系统会拒绝安装；想换成自己的 keystore，改那一行即可（换签名后要先卸载旧包）
2. **LSPosed 里启用「输入法增强」**，作用域勾选 **输入法**
3. **强制停止输入法**（或在 LSPosed 里点"重启作用域"），然后打开任意输入框弹出键盘

判定成功：**键盘最底部出现快捷栏**（快捷键那一行，键盘整体上移、不遮挡按键）；模块 App 的「运行状态」出现**心跳时间**。

## 三、调试

| 手段 | 做法 |
|---|---|
| LSPosed 日志 | `adb logcat -s LSPosed-Bridge`，过滤 `ENHANCE`；或 LSPosed 管理器日志页 |
| App 内诊断 | 模块 App → 「诊断」：心跳 / 最近日志 / **执行诊断命令** / 查看配置 JSON / 一键复制诊断信息 |
| 诊断命令 | `ime_state`（服务类名、底栏状态、当前按钮）、`bar_layout`（底栏挂载情况）、`view_tree`（键盘窗口视图树）、`prefs`、`ping` |

正常注入时日志顺序：

```
[ENHANCE] [I] ... module version = 1.0.0 (code 1)      ← 先确认版本
[ENHANCE] [I] ... 模块注入：pkg=com.tencent.wetype process=com.tencent.wetype first=true
[ENHANCE] [I] ... 钩子安装完成，等待输入法创建输入法服务
[ENHANCE] [I] ... 捕获输入法服务实例：com.tencent.wetype.xxxxx
[ENHANCE] [I] ... 输入法服务就绪：com.tencent.wetype
[ENHANCE] [I] ... 底栏已挂载（键盘下方兄弟行）：parent=... index=...
```

## 四、常见问题

| 现象 | 排查 |
|---|---|
| 键盘底部没有快捷栏 | ① 作用域是否勾选输入法；② 是否**强制停止**过输入法；③ 模块 App 有没有心跳；④ 查日志里 `底栏挂载失败` / `未找到键盘容器` |
| 没有心跳 | 模块没注入：确认 LSPosed 列表里能看到本模块、`assets/xposed_init` 与包名一致（脚本已自检） |
| 按钮点了没反应 | 部分输入框（密码框、自绘控件）不支持 `performContextMenuAction`，日志里有记录 |
| 剪贴板为空 | 粘贴/复制走的是系统剪贴板：先复制一段文本再试（Android 10+ 只允许当前输入法读剪贴板） |
| 改了设置没反应 | 1.5 秒内自动生效；输入法进程被系统冻结时会延后，切回键盘即恢复 |
| 想要「并进它自带的底栏」 | 现在的方案是**键盘最底部一行**（键盘整体上移、不覆盖任何按键）。要真正插进它自带那一排，需要先跑 `view_tree` 把视图树发我 —— 它的类名是混淆的，拿到真实层级才能写精确策略 |

## 五、性能与省电（已做的优化）

| 机制 | 说明 |
|---|---|
| **配置推送** | 模块 App 保存配置时 `notifyChange`，输入法进程的 `ContentObserver` 立刻醒来读 → **改完立即生效**，不依赖轮询频率 |
| **自适应兜底轮询** | 键盘显示 **10 秒** / 收起 **120 秒** / 失败退避 15 秒。Binder 查询从最初"每 1.5 秒一次"（40 次/分）降到 **6 次/分**，键盘收起时约 **0.5 次/分** |
| **按需启动** | 跨进程配置监听只在"真的抓到输入法服务"的进程里启动（`ModuleRuntime.ensureStarted()`），输入法其它进程零开销 |
| **诊断回写降噪** | 只回写最近 40 行、间隔 20 秒，且用 `apply()`：① **详细日志（[v]）不再触发回写**（只进 logcat 与 App 面板，等下一行有意义的日志一起带走）② 心跳搭同一趟 Binder 顺带刷新，不额外花钱 |
| **日志本身** | 时间戳按"秒"缓存，同一秒内的多条日志不再各自 `new Date + format` |
| **底栏自愈** | 底栏被摘下时（`onViewDetachedFromWindow`）**事件驱动**立刻重挂；兜底看门狗 **30 秒**一次，且只在键盘可见时检查 |
| **布局自检** | 只在开了「详细日志」时才跑，平时一次额外 post 都不做 |
| **Provider 侧** | 配置 JSON 内存缓存（`LocalConfig.loadJson`），目标进程每次查询不必重新序列化整份配置 |
| **渲染侧** | 用"配置代数"判断配置变没变（不再每次 bind 重新序列化 JSON）；按钮底色透明时不画内容层（少一层 overdraw） |
| **包体** | release 打开 R8 **裁剪**（`-dontobfuscate` 不混淆）：**约 1.0MB → 110KB**（框架 729KB → 83KB）；release 复用 debug 签名，可直接安装/覆盖；debug 保持不裁剪，方便改完就装。保留规则见 `app/src/main/keepRules/rules.keep` |
| **进程级去重** | 同一进程只注入一次，钩子/监听/日志不重复 |
| **已移除** | 剪贴板功能（原本会常驻一个 HandlerThread + 剪贴板监听 + 每次点击遍历视图树） |

粗算一下目标进程的常驻开销：**一个 `MIN_PRIORITY` 后台线程（平时阻塞在 `poll` 上，约 1 次/分钟的 Binder 查询）+ 一个 30 秒的主线程轻量检查 + 若干钩子回调**，除此之外不占 CPU。

## 六、代码结构（本模块新增的部分）

```
app/src/main/java/com/wetype/enhance/
├── ModuleHooks.kt              # 装配：钩子 + 诊断命令 + 配置热生效
├── config/Settings.kt          # ★ 所有配置项声明（设置界面自动生成）
└── ime/
    ├── ImeHook.kt              # 输入法服务捕获 + 生命周期钩子
    ├── BarInjector.kt          # 把底栏挂成"键盘最底部一行"（含还原）
    ├── KeyboardBar.kt          # 底栏视图（等宽均分、间距可调、配色/透明度/对比度全可调）
    ├── BarAction.kt            # 按钮定义（id / 名称 / 显示开关 key）
    ├── BarIcons.kt             # 图标矢量路径（24×24 视口，纯代码）
    ├── BarIconView.kt          # 图标绘制
    ├── ShortcutActions.kt      # 各按钮的动作
    └── (剪贴板相关代码已移除：复制/粘贴直接用系统剪贴板)

tools/
└── icon-preview.ps1            # 开发用：把图标渲染成预览图，改造型先看效果
```


---

> 本模块由 **LSPosedModuleKit** 框架生成；本仓库只包含这一个模块工程。
> 模块 id `com.wetype.enhance` 是历史原因保留的，与「只支持微信输入法」无关 —— 任何输入法都能用。
