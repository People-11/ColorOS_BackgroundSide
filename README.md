# ColorOS 小窗后台挂机

Zygisk 模块（基于 [AndroidVMTools](https://github.com/aerath-stuff/AndroidVMTools)），在 ColorOS 16 上恢复旧版 realme 的
“小窗缩成边缘气泡后后台挂机”：应用继续以前台、亮屏状态运行。

测试环境：CPH2723 / ColorOS 16.0.2 / Android 16。系统里该功能的入口和逻辑已删除，但底层的 Mirage 虚拟屏
（`OplusMirageWindowManager`，castMode 7 保活屏，GameBounce 也用它）还在，本模块直接复用。

> **不支持 ColorOS 17**：本仓库缺乏 ColorOS 17 的测试条件，无法适配。ColorOS 17 用户请使用
> [Mubai-meng/ColorOS_BackgroundSide](https://github.com/Mubai-meng/ColorOS_BackgroundSide)。

## 效果

- 小窗最小化成边缘气泡（菜单、标题栏、手势、打开别的小窗时自动收起，任何方式都算）就是挂机：
  应用移到它自己的保活虚拟屏上继续运行（RESUMED、可见，不暂停也不停止）。
- 挂机期间：降到 10 fps 省电（不静音，系统可单独调应用音量）；主屏灭屏后虚拟屏不休眠；没有超时退出。
- 退出挂机：点击气泡恢复成小窗；从桌面或最近任务打开该应用；关掉气泡；划掉任务。

## 实现

| Hook（均在 system_server） | 作用 |
|---|---|
| `ExitFlexibleTaskToFloatStrategy.handleEvent` | 任何最小化的起点：为该任务创建虚拟屏 |
| `TaskFragmentExtImpl.shouldSkipPauseInRecentsAnim` | 最小化动画期间不暂停 |
| `FlexibleTaskController.exitFlexibleTask` | 原生最小化收尾时，在同一个推迟可见性的区间里把任务移到虚拟屏（直接用 WM 的移屏，保留小窗状态：尺寸不变、边框保持隐藏），焦点留在主屏 |
| `OplusMirageKeepAlivePolicy.shouldSleep` | 我们的虚拟屏不随主屏休眠 |
| `DisplayContent.computeScreenConfiguration` | 虚拟屏的触屏/色彩模式配置与主屏一致，避免 Chrome 等重建 Activity |
| `FloatHandleController.startActivityByFloatInfo` | 点气泡：原生恢复自带“虚拟屏任务移回主屏”，之后解除挂机 |
| `OplusFlexibleInfoManager.isSkipRecordSnapshot` | 恢复动画不盖最小化时的旧快照（否则画面冻结 1~2 秒） |
| `FlexibleFloatHandleAnimationSpec.defaultCallAnimationStart` | 恢复动画第一帧把动画层裁空，避免挂机应用的画面在气泡下方露出一帧 |
| `ActivityStarter.execute` / `ActivityTaskSupervisor.startActivityFromRecents` | 从桌面/最近任务打开：启动前先移回主屏，原生启动动画才正常。移屏前先自行隐藏其 Activity，使 Mirage 移屏里的 `makeInvisible()` 不再把仍 RESUMED 的 Activity 加入待停止队列（否则下次 idle 可能被停止，Chrome 等停止媒体播放） |
| `FloatHandleController.dispatchViewRemoved` / `TaskChangeNotificationController.notifyTaskRemoved` | 气泡被关掉、任务被移除：解除挂机 |
| `ActivityTaskManagerService.setLastResumedActivityUncheckLocked` | 挂机应用的其他界面（如别的应用里打开的 Chrome Custom Tab）成为前台时临时解除降帧，离开前台后恢复 |

降帧用 SurfaceFlinger 的按 uid 帧率覆盖后门（transaction 1039）。GameManager 的覆盖会被 1035 强制刷新率忽略，1039 不会。
覆盖按 uid 生效，会波及该应用在主屏上的其他窗口，所以只在它们成为前台时解除；可见但不在焦点上的（分屏另一侧等）仍是 10 fps。

## 构建

GitHub Actions 云端编译（`.github/workflows/build.yml`），每次都会把 `external/AndroidVMTools` 子模块更新到上游最新版本，
产物是 `module/build/outputs/magisk/` 下的 Magisk 模块 zip。
