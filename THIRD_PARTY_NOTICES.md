# 第三方组件声明

本产品打包/引用了以下第三方组件，请遵守其许可证：

| 组件 | 用途 | 许可证 | 来源 |
|---|---|---|---|
| proot | 用户态 Linux 模拟（静态二进制内置于 APK） | **GPL-2.0** | https://github.com/proot-me/proot （源码版本见 app/build.sh，未修改） |
| Ubuntu 24.04 base (arm64) | rootfs 基础系统 | 各包自带（含 GPL/BSD/MIT 等） | https://cdimage.ubuntu.com/ubuntu-base/ |
| Node.js 24 | 运行时 | MIT | https://nodejs.org |
| @deepseek-ai/dsh 及其依赖 | DeepSeek Harness 智能体 | MIT | https://github.com/deepseek-ai/deepseek-harness |
| @deepseek-ai/dsh-web-frontend | 网页端界面 | MIT | 同上 |

## GPL-2.0 合规说明（proot）

APK 内置的 `proot` 二进制由 [proot-me/proot](https://github.com/proot-me/proot) 源码
在构建时（`app/build.sh`）自动编译，**未做任何修改**。按照 GPL-2.0 要求，本仓库的
`app/build.sh` 会拉取并编译对应源码；如需源码快照，请从上述仓库按构建脚本记录的
commit 获取。

## 免责声明

本项目仅供学习交流。使用 DeepSeek API 产生的费用与风险由使用者自行承担。
