# UniversalClientLauncher

UniversalClientLauncher（简称 UCL）是一个面向 Android 平台的 Minecraft Java 版启动器。

它让 Android 设备可以直接运行 Minecraft Java 版，界面按 Minecraft 官方界面的视觉与操作习惯重新实现，目标是在手机上得到接近桌面端的操作方式。

> 本项目是第三方作品，与 Mojang Studios、Microsoft 及其关联公司没有隶属关系。

## 功能

**账户**

支持微软账户登录、离线账户、外部认证服务器与 Blabby 账户四种方式。账户之间可以随时切换，各自的存档与配置互不影响。

**版本**

按版本 ID 管理已安装的游戏，支持 Forge、Fabric、OptiFine 等安装器入口，并以独立目录隔离每个版本的配置。

**下载**

内置镜像源与任务队列。支持多任务并行、断点续传、限速与队列持久化，中断的任务在下次启动后可以继续。

**运行环境**

按设备架构下载对应的 OpenJDK 运行时，安装包本身不内置 Java。

**渲染**

提供 OpenGL（GL4ES）、Zink 与 VirGL 等渲染器，可在设置中按设备情况切换。

**控制**

支持自定义触控布局、手柄按键映射、陀螺仪视角、虚拟鼠标与鼠标指针速度调节。

**界面**

提供简体中文、繁體中文、日本語、한국어、English、Français、Deutsch 七种语言，切换后立即生效。

## 仓库说明

**本仓库只包含源代码**，即 Java 层与 C/C++ 原生层。

界面资源、字体、图标、原生二进制库、构建脚本与签名文件均不在本仓库内。因此本仓库不能独立构建，构建所需材料由发布方自行维护。

## 源代码结构

```
app_pojavlauncher/src/main/java    启动器主体与界面层
  net/kdt/pojavlaunch/mcui        本项目的界面实现
app_pojavlauncher/src/main/jni    原生层：图形桥接、JVM 钩子、驱动辅助
jre_lwjgl3glfw                    桌面 OpenGL 与 GLFW 的绑定
arc_dns_injector                  DNS 注入
forge_installer                   Forge 安装器
```

## 许可证

本项目以 GNU 通用公共许可证第三版分发。其中移植自 Fold Craft Launcher（GPLv3）的部分同样受该许可证约束。

主要第三方组件及其许可证：

| 组件 | 许可证 |
|---|---|
| PojavLauncher | LGPL-3.0 |
| Fold Craft Launcher | GPL-3.0 |
| OpenJDK | GPLv2 与 Classpath Exception |
| LWJGL 2 与 LWJGL 3 | BSD 3-Clause |
| GL4ES | MIT |
| OpenAL Soft | LGPL 2.0 及以后版本 |
| Mesa 与 OSMesa | MIT |
| FreeType | FreeType License 或 GPLv2 |
| AndroidX 与 ConstraintLayout | Apache License 2.0 |
| Noto Sans | SIL Open Font License 1.1 |

完整列表随应用内「关于」页面提供。

## 致谢

启动器主体来自 [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher)。主界面的 3D 角色预览移植自 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 的渲染实现。角色头像由 MCHeads 提供。

## 状态

当前版本 Alpha_0.0.1，仍在开发中，功能与界面都可能变动。

## 免责声明

使用本项目需要自行准备 Minecraft 账号。本仓库不包含游戏本体，也不含任何绕过账号验证的功能。

Minecraft 是 Mojang Studios 的商标。因使用本项目产生的数据丢失或账号风险，由使用者自行承担。

---

## English

UniversalClientLauncher is an unofficial Minecraft: Java Edition launcher for Android, developed from [PojavLauncher](https://github.com/PojavLauncherTeam/PojavLauncher).

This repository contains source code only. Interface resources, fonts, icons, native binaries, build scripts and signing material are not included, so the repository cannot be built on its own.

The launcher is distributed under the GNU General Public License version 3, and the 3D character preview is ported from Fold Craft Launcher (GPL-3.0). It is not affiliated with, endorsed by or sponsored by Mojang Studios or Microsoft.
