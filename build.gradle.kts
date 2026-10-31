import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
}

base {
    archivesName = project.property("archives_base_name") as String
    // The Minecraft version the add-on is built for goes into its version, e.g. 1.0.0+26.3
    version = "${project.property("mod_version")}+${libs.versions.minecraft.get()}"
    group = project.property("maven_group") as String
}

repositories {
    mavenCentral()
    maven {
        name = "CCBlueX Releases"
        url = uri("https://maven.ccbluex.net/releases")
    }
    maven {
        name = "CCBlueX Snapshots"
        url = uri("https://maven.ccbluex.net/snapshots")
    }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
}

loom {
    accessWidenerPath = file("src/main/resources/donut-addon.accesswidener")
}

// Two things to leave alone here:
//
// 1. There is no `mappings(...)` line. LiquidBounce declares none either, and Loom defaults to
//    Mojang official mappings for this Minecraft version. A different mapping set produces an
//    add-on that compiles and then fails on every Minecraft call.
// 2. Dependencies use plain `implementation`, not `modImplementation`. This Loom version has no
//    remapping step - the development and production namespaces are both Mojang official - so the
//    `mod*` configurations do not exist. LiquidBounce's own build does the same.
dependencies {
    minecraft(libs.minecraft)

    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.fabric.kotlin)

    // The client itself; there is no separate API artifact.
    implementation(libs.liquidbounce)

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Gradle keeps a resolved snapshot for a day; the client publishes one on every push to nextgen.
configurations.all {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}

// Check the release's compiled bytecode against another client, without recompiling it
// against that client's API and accidentally hiding a binary-incompatible nightly change.
providers.gradleProperty("liquidbounce_runtime_version").orNull?.let { version ->
    configurations.named("testRuntimeClasspath") {
        resolutionStrategy.force("net.ccbluex:liquidbounce:$version")
    }
}

tasks.processResources {
    val modVersion = providers.gradleProperty("mod_version").zip(libs.versions.minecraft) { version, minecraft ->
        "$version+$minecraft"
    }
    val minecraftVersion = libs.versions.minecraft
    val loaderVersion = libs.versions.fabric.loader
    val fabricKotlinVersion = libs.versions.fabric.kotlin

    inputs.property("version", modVersion)
    inputs.property("minecraft_version", minecraftVersion)
    inputs.property("loader_version", loaderVersion)
    inputs.property("fabric_kotlin_version", fabricKotlinVersion)

    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to modVersion.get(),
                "minecraft_version" to minecraftVersion.get(),
                "loader_version" to loaderVersion.get(),
                "fabric_kotlin_version" to fabricKotlinVersion.get(),
            )
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = libs.versions.jdk.get().toInt()
}

java {
    withSourcesJar()

    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    }
}

kotlin {
    compilerOptions {
        jvmToolchain(libs.versions.jdk.get().toInt())
        // LiquidBounce is compiled with preview features, which marks its classes as pre-release
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
    from("licenses") { into("META-INF/licenses") }
    from("CREDITS.md") { into("META-INF") }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("donut.mainClassDirectories", sourceSets.main.get().output.classesDirs.asPath)
}

// Ports use reviewed source; supplied binaries stay outside source and resources.
val verifySourceIsolation = tasks.register("verifySourceIsolation") {
    group = "verification"
    description = "Rejects executable input artifacts in addon sources and local libraries."
    doLast {
        // All project-local source roots (including tests) and local dependency
        // directories are checked before any compilation or test execution.
        val sourceRoots = listOf("src", "libs", "lib").map(::file).filter { it.exists() }
        val forbiddenExtensions = setOf(
            "jar", "class", "dll", "so", "dylib", "exe", "bin", "scr", "com"
        )
        val forbidden = sourceRoots.flatMap { root -> root.walkTopDown().filter { it.isFile }.filter { file ->
            val extension = file.extension.lowercase()
            if (extension in forbiddenExtensions) return@filter true
            // Catch executable files disguised as ordinary resource formats.
            val header = file.inputStream().use { stream ->
                stream.readNBytes(4)
            }
            header.contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())) ||
                header.contentEquals(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) ||
                header.take(2) == listOf(0x4D.toByte(), 0x5A.toByte()) ||
                header.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }.map { it.relativeTo(projectDir).path }.toList() }
        check(forbidden.isEmpty()) {
            "Executable input files in addon source roots: " +
                forbidden.joinToString(", ")
        }
    }
}

tasks.matching {
    it.name in setOf("compileKotlin", "compileJava", "processResources", "jar")
}.configureEach {
    dependsOn(verifySourceIsolation)
}

// Check the produced jar as well as the source directories, including when a
// previously built archive was considered up-to-date by Gradle.
val verifyAddonPackage = tasks.register("verifyAddonPackage") {
    group = "verification"
    dependsOn(tasks.named("jar"))
    doLast {
        val archive = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            val forbiddenExtensions = setOf("jar", "dll", "so", "dylib", "exe", "bin")
            val rejected = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }
                .filter { path ->
                    val name = path.lowercase()
                    name.substringAfterLast('.', "") in forbiddenExtensions
                }.toList()
            check(rejected.isEmpty()) {
                "Executable input content in addon jar: " + rejected.joinToString(", ")
            }
        }
    }
}
tasks.named("assemble") { dependsOn(verifyAddonPackage) }
