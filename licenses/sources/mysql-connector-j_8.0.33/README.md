# MySQL Connector/J 8.0.33 源码材料

`mysql-connector-j-8.0.33.zip` 为 Oracle 官方发行归档，原封不动保留。包含 682 个 Java 源文件、预生成 protobuf Java、构建插桩源码、测试源码、`build.xml`、`LICENSE`、`README`、`CHANGES`、`INFO_SRC` 和 `INFO_BIN`。其内驱动 JAR 与 ProbeLoom 使用的 Maven Central 二进制逐字节相同；精确来源、版本、提交及 SHA-256 见 `SOURCE.json`。

驱动未被修改，版权及 GPLv2、Universal FOSS Exception 1.0 和原有第三方条款以归档内 `LICENSE` 为准，另保留在 `../../java/com.mysql_mysql-connector-j_8.0.33/LICENSE`。项目主许可证不改变这些条款。

`protocol/` 补充 MySQL Server 官方 `mysql-8.0.33` 版本的 12 个原始 `.proto` 接口定义、同目录两个 CMake 文件和原始 `LICENSE`。保留每个文件的版权和 GPLv2 条款，不将 Connector 的 Universal FOSS Exception 统称为这些接口源文件的授权。它们作为独立源码材料随包提供，平台使用的驱动二进制保持不变。

12 个定义生成的描述符与驱动源码内嵌描述符全部结构匹配；比较仅去除 protoc 自动补充的隐式 `json_name`，保留自定义和未知选项。来源、逐文件哈希及比对范围见 `protocol/source-entries.json` 和 `protocol/descriptor-review.json`。该比对确认接口对应关系，不表示整个驱动已完成可复现构建。

## 读取与构建

解压官方 ZIP 后，阅读根目录 `build.xml` 的 `description`，其中包含构建说明及 `build.properties` 示例。使用 JDK 8、Apache Ant 和独立第三方依赖目录；Ant 构建会编译源码并执行上游插桩步骤。Maven 的 sources.jar 未包含全部构建材料，不能代替这里的官方发行归档。

构建依赖包括 Javassist、Protobuf Java、SLF4J API、OCI SDK common `com.oracle.oci.sdk:oci-java-sdk-common:2.47.0`；需要生成 c3p0 集成类时还需 c3p0。OCI SDK 是完整驱动的编译依赖，运行时仅 OCI 认证需要，ProbeLoom 未增加该运行依赖。按上游 `build.xml` 准备外部依赖目录，在解压目录运行：

```powershell
ant -Dcom.mysql.cj.build.jdk="C:/path/to/jdk8" -Dcom.mysql.cj.extra.libs="C:/path/to/connector-j-libs" build
```

`build` 生成驱动 JAR，不执行上游数据库测试。原发行构建环境记录在 `INFO_BIN`。这里提供对应源码和构建入口，不承诺重新构建的二进制逐字节相同。

## 交付

本目录随 ProbeLoom 源码包和运行包同时提供。`scripts/package-release.py` 会核对源码材料哈希、官方归档中的驱动与运行 JAR 中驱动是否相同，并核对原始许可全文。不能将本目录替换为第三方下载链接。

ProbeLoom 应用代码采用 Apache-2.0，完整对应源码由同批 `probeloom-source.zip` 提供，运行包 `START.txt` 记录该源码包哈希；分发时必须同时提供两个包。驱动及本目录原始材料保留自身条款。当前本地交付不能代替公司的源码公开授权。
