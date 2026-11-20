# ProbeLoom

支持协议扩展的接口与数据测试平台。包含案例管理、工作簿导入导出、队列调度、HTTP 调用与文件上传、JDBC 操作、断言、变量提取、数据快照恢复及执行记录。

## 构建与启动

需要 JDK 8、Maven 3.6+、Node.js 22+ 和 pnpm。

```powershell
./build.ps1
./run.ps1
```

前端依赖从 npm 公共仓库安装；Java 构建通过 `.mvn/public-settings.xml` 使用 Maven Central，依赖隔离在 `.build/m2`。可执行文件为 `test-platform-deploy/target/probeloom.jar`。

Windows x64 的 SVN 备份使用随包的 Apache Subversion 命令行组件。使用 Python 3.8+ 和 Windows 自带的 `tar.exe` 重新取得固定版本二进制，并校验项目随附的源码和许可材料：

```powershell
python scripts/fetch-svn-cli.py
```

脚本从 MSYS2 公共仓库取得固定版本包，逐一核对 SHA-256，只提取 `svn.exe`、`svnmucc.exe` 及其实际 DLL 依赖，输出到 `native/windows-x86_64/bin`。对应源码、构建配方和原始许可随项目提供，构建与替换说明见 `licenses/native/msys2/SOURCE-BUILD.md`。运行机通过 `run.ps1` 使用随包客户端，无需安装 SVN 或 MSYS2。

Linux/macOS：

```sh
cd test-platform-ui
pnpm install --frozen-lockfile --store-dir ../.cache/pnpm-store --registry=https://registry.npmjs.org
pnpm build
cd ..
mvn -B -s .mvn/public-settings.xml -gs .mvn/public-settings.xml -Dmaven.repo.local="$PWD/.build/m2" clean package
sh run.sh
```

打开 `http://localhost:18090`。首次启动自动创建 SQLite 空库 `data/probeloom.db`，页面直接设置 `admin` 的密码，确认后自动登录。之后使用该账号和自己设置的密码登录。平台数据库及加密密钥位于运行目录，请一起保管。

平台存储也支持 PostgreSQL，通过 Spring Boot 参数配置：

```properties
platform.storage.mode=jdbc
platform.datasource.default.driverClassName=org.postgresql.Driver
platform.datasource.default.jdbcUrl=jdbc:postgresql://localhost:5432/probeloom
platform.datasource.default.username=probeloom
platform.datasource.default.password=your-password
platform.datasource.default.schema=public
```

## 案例配置

1. 在基础配置中添加业务数据源。内置公共 SQLite、PostgreSQL、MySQL、SQL Server JDBC 驱动。
2. 在环境中添加连接：标识、协议 `HTTP`、HTTP/HTTPS 基础地址、请求头。环境中的应用名称用于案例分组。
3. HTTP 和上传步骤通过连接标识及相对路径选择接口；数据库步骤通过数据源标识选择数据库。纯 SQL 案例可不选环境。
4. 入队时冻结连接、数据源和案例配置。随后修改环境不会改变已提交任务。

执行链：环境与连接/数据源 → 入队冻结配置 → 协议适配器或 SQL 执行器 → 断言与结果保存。失败后执行清理并恢复本次快照。

“下载模板”提供通用 `demo_records(record_id, record_name, amount)` 示例，包括备份、插入、更新、HTTP、上传、断言、清理和恢复。执行前创建对应业务表，配置标识 `default` 的数据源，以及标识 `demo`、提供 `/echo` 和 `/upload` 的 HTTP 连接。

## 验证

Maven `package` 会执行工作簿契约、协议适配器及失败恢复集成验证。运行中的平台可使用独立样例验证脚本：

```powershell
python scripts/smoke-platform.py --url http://127.0.0.1:18092
```

样例在空库上设置随机管理员密码；已有账号时，通过 `PROBELOOM_PASSWORD` 环境变量或 `--password` 参数提供当前密码。

脚本在 `.build/smoke` 创建独立 SQLite 业务库和 HTTP 服务，完成模板下载、导入、执行、导出再导入和再次执行，核对数据库恢复结果。用于样例验证的平台实例也应使用单独的空库。可用 `--username` 和环境变量 `PROBELOOM_PASSWORD` 提供登录凭据。验证输出位于 `.build/smoke/report.json`。

`SvnBackupIT` 检查真实提交、无变化、目录替换和并发冲突；`SvnHttpIT` 检查认证、证书指纹和凭据不落盘。两类集成验证需要独立 HTTP/HTTPS SVN 服务，通过 `-Dprobeloom.svn.fixture=绝对路径` 读取测试属性文件，并用 `-Dprobeloom.home=项目根目录绝对路径` 定位客户端，不计入普通 `package` 的测试结果。属性字段见 `SvnTestFixture` 和 `SvnHttpIT`，仅使用独立临时仓库，测试后删除临时认证材料。

## 案例仓库备份

基础配置中的 SVN 备份使用 Apache Subversion CLI，同步配置仓库地址下的 `cases/`。一次备份提交一个版本；内容无变化时不提交，并发变更冲突时整次失败。密码只通过进程标准输入传递，不放入命令参数或认证缓存；每次客户端操作超时为 120 秒。HTTPS 默认使用 JVM 信任库中的 CA 校验；配置 SHA-256 指纹时仅接受匹配证书。临时配置可包含 CA 或匹配指纹的公开证书，任务结束后随临时工作目录清理。

默认 CA 模式使用证书中的 DNS 名称访问；配套 Serf 1.3.10 对 IP 类型 SAN 的识别有限制，使用 IP 地址时需配置服务端证书指纹。

调用链：导出案例工作簿 → `svn` 固定版本读取远端并对比 → `svnmucc` 单事务提交 `cases/` → 保存备份结果。Windows x64 发行包包含客户端；Linux/macOS 的 SVN 备份需要 PATH 中存在 `svn` 和 `svnmucc` 1.14+。

## 协议扩展

源码扩展入口是 `test-platform-app/src/main/java/dev/probeloom/service/ProtocolAdapter.java`。`HttpProtocolAdapter` 是首个实现，通过 Spring Bean 注册，接收冻结后的 `ProtocolConnection` 和步骤请求，返回 JSON `ObjectNode`。当前连接校验仅接受 HTTP；接入新协议时需同步声明连接及请求契约、校验、编辑界面和工作簿字段。

## 分发

ProbeLoom 应用代码采用 Apache-2.0，全文见根目录 `LICENSE`。第三方依赖保留各自授权，材料见 `THIRD-PARTY-NOTICES.md` 和 `licenses/`。执行 `python scripts/audit-dependencies.py` 可按当前发行 JAR、前端安装结果和随包客户端重新生成清单。

MySQL Connector/J 8.0.33 的官方源码发行归档、构建脚本、接口定义补充材料和读取说明随两种交付包提供，位于 `licenses/sources/mysql-connector-j_8.0.33/`。执行 `python scripts/package-release.py` 打包前会检查材料哈希及源码归档与运行驱动的一致性。该驱动继续遵守其 GPLv2 和 Universal FOSS Exception 1.0，不能改套项目主许可证。

Logback 1.2.3 采用 EPL 1.0，Jakarta Annotations 1.3.5 采用 EPL 2.0；两者的完整源码和构建说明也随两包提供。Apache SVN 本体采用 Apache-2.0，配套 MSYS 运行库等保留自身许可。它们作为独立程序调用，可替换 DLL、原文、对应源码及构建说明均随两包提供。其他组件的分发通知见 `licenses/supplemental-distribution-notices.md`，客户端分发说明见 `licenses/native/DISTRIBUTION-NOTICES.txt`。

分发时同时提供完整应用源码包 `probeloom-source.zip` 和运行包 `probeloom-runtime.zip`。运行包的 `START.txt` 记录对应源码包名称及 SHA-256，发行清单 `probeloom-manifest.json` 记录两个包的哈希。
