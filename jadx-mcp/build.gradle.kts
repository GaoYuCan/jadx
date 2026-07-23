plugins {
	id("jadx-java")
	id("jadx-library")
	id("application")
	id("com.gradleup.shadow") version "8.3.8"
}

// Official MCP Java SDK 1.0.0 is compiled for Java 17 (uses records),
// so jadx-mcp itself requires Java 17+ at compile and runtime.
// The rest of jadx stays at Java 11.
java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(17)
}

dependencies {
	implementation(project(":jadx-core"))
	implementation(project(":jadx-plugins-tools"))
	implementation(project(":jadx-commons:jadx-app-commons"))

	// same input plugins as jadx-cli, so the MCP server can decode any supported input
	runtimeOnly(project(":jadx-plugins:jadx-dex-input"))
	runtimeOnly(project(":jadx-plugins:jadx-java-input"))
	runtimeOnly(project(":jadx-plugins:jadx-java-convert"))
	runtimeOnly(project(":jadx-plugins:jadx-smali-input"))
	runtimeOnly(project(":jadx-plugins:jadx-rename-mappings"))
	runtimeOnly(project(":jadx-plugins:jadx-kotlin-metadata"))
	runtimeOnly(project(":jadx-plugins:jadx-kotlin-source-debug-extension"))
	runtimeOnly(project(":jadx-plugins:jadx-xapk-input"))
	runtimeOnly(project(":jadx-plugins:jadx-aab-input"))
	runtimeOnly(project(":jadx-plugins:jadx-apkm-input"))
	runtimeOnly(project(":jadx-plugins:jadx-apks-input"))

	implementation("org.jcommander:jcommander:2.0")
	implementation("ch.qos.logback:logback-classic:1.5.32")
	implementation("com.google.code.gson:gson:2.13.2")

	// Official MCP Java SDK (bundles core + Jackson3 + STDIO transport)
	implementation("io.modelcontextprotocol.sdk:mcp:1.0.0")

	testImplementation(
		project
			.project(":jadx-core")
			.sourceSets
			.getByName("test")
			.output,
	)
	testImplementation(project(":jadx-gui"))
}

application {
	applicationName = "jadx-mcp"
	mainClass.set("jadx.mcp.JadxMcpServer")
	applicationDefaultJvmArgs =
		listOf(
			"-XX:+IgnoreUnrecognizedVMOptions",
			"-Xms256M",
			"-XX:MaxRAMPercentage=70.0",
			"-XX:ParallelGCThreads=3",
			// disable zip checks (#1962)
			"-Djdk.util.zip.disableZip64ExtraFieldValidation=true",
			// Foreign API access for 'directories' library (Windows only)
			"--enable-native-access=ALL-UNNAMED",
		)
	applicationDistribution.from("$rootDir") {
		include("README.md")
		include("NOTICE")
		include("LICENSE")
	}
}

tasks.jar {
	manifest {
		attributes(mapOf("Main-Class" to application.mainClass.get()))
	}
}

tasks.shadowJar {
	isZip64 = true
	mergeServiceFiles()
	manifest {
		from(tasks.jar.get().manifest)
	}
}

// workaround to exclude shadowJar 'all' artifact from publishing to maven (mirrors jadx-gui)
project.components.withType(AdhocComponentWithVariants::class.java).forEach { c ->
	c.withVariantsFromConfiguration(project.configurations.shadowRuntimeElements.get()) {
		skip()
	}
}
