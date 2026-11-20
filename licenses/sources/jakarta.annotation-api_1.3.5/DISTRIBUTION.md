# Jakarta Annotations 1.3.5 distribution and source

The unmodified jakarta.annotation:jakarta.annotation-api:1.3.5 binary is distributed under EPL-2.0. The optional GPL secondary-license route is not selected. The application license applies to its own code and does not replace the component license. Original LICENSE.md and NOTICE.md are included, including their alternative-license text. All original copyright, trademark, attribution and other notices remain intact.

The complete official 1.3.5 source tree is available in jakarta.annotation-api_1.3.5-source.zip beside this file. Extract it to obtain the source, resources, parent/API POMs and build configuration. The archive also retains the upstream specification module and its separate original notices; inclusion does not relicense specification material under the application's license. All 16 published API Java source files match the archive after line-ending normalization. SOURCE.json identifies the official fixed commit, published sources.jar and hashes. No source or binary changes were made.

Build entry: extracted root pom.xml and api/pom.xml. Use JDK 8 and Maven with publicly available org.eclipse.ee4j:project:1.0.5 parent and plugins declared by the upstream POMs:

```text
mvn -pl api -am -Dmaven.test.skip=true package
```

This is the upstream build entry, not an executed or byte-for-byte reproducibility claim. The archive contains preferred source and build configuration; public build-tool/dependency downloads require network access.

The source code for this component is available under EPL-2.0 in the accompanying source archive. Subsequent distribution must preserve source availability, the EPL license and all notices, and satisfy EPL-2.0 section 3. No upstream contributor provides warranties or conditions, including title, non-infringement, merchantability or fitness for purpose; no upstream contributor is liable for direct, indirect, special, incidental or consequential damages, including lost profits. Any additional warranty or liability undertaking is solely that of its distributor. These terms do not limit recipients' rights in the EPL source code. Commercial distributors must observe EPL section 4 concerning their own acts or omissions.
