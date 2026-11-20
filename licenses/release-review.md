# ProbeLoom 分发说明

应用代码采用 Apache-2.0，全文为根目录 `LICENSE`。第三方组件保持自身许可、版权和通知；版本及实际分发文件以 `dependencies.json` 为准。

| 范围 | 当前分发方式 | 材料 |
|---|---|---|
| MySQL Connector/J 8.0.33 | 未修改的官方驱动按 GPLv2 + Universal FOSS Exception 1.0 分发；完整应用源码与运行包配对提供 | `sources/mysql-connector-j_8.0.33/`：官方完整归档、12 个接口定义、原文、构建入口、哈希和对应性证据 |
| Logback core/classic 1.2.3 | 选择 EPL 1.0；未修改库，完整源码及构建文件随两包提供 | `sources/logback_1.2.3/` |
| Jakarta Annotations 1.3.5 | 选择 EPL 2.0；未修改库，完整源码、原 LICENSE/NOTICE 随两包提供 | `sources/jakarta.annotation-api_1.3.5/` |
| react-remove-scroll-bar 2.3.8 | 保留官方 MIT 声明、完整许可、版权及代码对应证据 | `upstream/react-remove-scroll-bar_2.3.8/`；缺少 npm provenance 不表述为已取得完整供应链证明 |
| LightningCSS | 仅作构建工具，不分发 node_modules 或工具二进制 | `npm/`、`supplemental-distribution-notices.md` |
| Barlow、XML 接口和 Jackson 内嵌解析器 | 保留 OFL、Apache、W3C、SAX、MIT、BSD 原文及版权 | `fonts/`、`java/`、`upstream/`、`supplemental-distribution-notices.md` |
| Apache SVN 命令行组件及配套库 | 独立进程调用；逐组件保留原文、版权、实际文件和固定来源哈希；允许替换配套 DLL | `native/dependencies.json`、`native/DISTRIBUTION-NOTICES.txt`、`native/msys2/sources/` |
| MSYS 运行库及其他要求源码的配套库 | 按各自 LGPL/GPL 与适用例外分发；完整对应源码、构建配方和补丁随两包提供 | `native/` 各组件的原始许可和源码材料；不将这些库改套应用主许可 |

执行 `python scripts/audit-dependencies.py` 更新实际依赖清单，再执行 `python scripts/package-release.py` 生成两包。打包检查主 LICENSE、对应源码、运行依赖和原生材料哈希。运行包 `START.txt` 绑定同批完整源码包的 SHA-256，分发时同时提供两包及所有法律材料。

厂商引用、构建和样例验证结果记录在总处理清单。公司同意公开已由用户确认；具体版权署名按实际权属填写，不将公开许可解释为版权转让。
