// ProGuard is a task class, not a plugin id, so it arrives on the build classpath rather than through
// plugins {}. A buildscript block has to come before plugins {}.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.guardsquare:proguard-gradle:7.7.0")
    }
}

plugins {
    application
    jacoco
    alias(libs.plugins.javafx)
}

repositories {
    mavenCentral()
}

// The version stamped on the packaged app and its installers. It lives in gradle.properties so there is one
// place to bump at release time; -PappVersion=... still overrides it for a one-off build.
// Windows installers need a plain numeric version (major.minor.patch); jpackage rejects anything else.
val appVersion = project.property("appVersion") as String
version = appVersion

dependencies {
    implementation(libs.sshj)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.slf4j.simple)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter("6.0.1")
        }

        // Real SSH round trips against a server started inside the test (Apache MINA SSHD).
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter("6.0.1")
            dependencies {
                implementation(project())
                implementation(libs.sshd.core)
                runtimeOnly(libs.slf4j.simple)
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(tasks.named("test"))
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = "21.0.8"
    // javafx.media carries its own decoders, which is what the picture, sound and film viewer plays
    // through. It is the one module that adds native libraries of any size to the package.
    modules = listOf("javafx.controls", "javafx.swing", "javafx.media")
}

application {
    mainClass = "com.kubo.Launcher"
}

// Keep the original artwork at the repository root while packaging every size in the application jar.
sourceSets {
    main {
        resources.srcDir(rootProject.file("assets"))
    }
}

// Coverage

// Screens, previews and console entry points are checked visually, not measured.
val notMeasured = listOf(
    "com/kubo/ui/**",
    "com/kubo/Launcher*",
    "com/kubo/KuboApp*",
    "com/kubo/connection/ssh/ConnectionCheck*",
)

tasks.jacocoTestReport {
    dependsOn(tasks.test, tasks.named("integrationTest"))
    executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(notMeasured) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Obfuscation
//
// Kubo's own jar is rewritten so that its class, field and method names say nothing about what the code does,
// and anything no entry point reaches is dropped. The libraries beside it are passed in as library jars and
// ship exactly as published: renaming Bouncy Castle would break the lookups it does on itself, and would
// protect nothing that is not public already. The rules, and the reason behind each one, are in
// app/proguard-rules.pro.
//
// Only the packages go through it. `run`, the tests and the preview tasks use the plain classes, so what is
// developed against is readable and what is shipped is not.
//
//   gradlew jpackage -PskipObfuscation     packages the readable jar, for pinning down a packaging problem

val obfuscating = !project.hasProperty("skipObfuscation")
val obfuscatedDir = layout.buildDirectory.dir("obfuscated")

val obfuscate = tasks.register<proguard.gradle.ProGuardTask>("obfuscate") {
    group = "distribution"
    description = "Renames Kubo's own classes and drops what nothing reaches."
    dependsOn(tasks.jar, tasks.installDist)

    val ourJar = tasks.jar.get().archiveFileName.get()
    injars(tasks.jar.get().archiveFile)
    outjars(obfuscatedDir.get().file(ourJar))

    configuration(rootProject.file("app/proguard-rules.pro"))

    // The runtime Kubo is compiled against. Java 9 and later keep the platform in jmods rather than in a
    // rt.jar, and module-info is dropped because this jar is not modular.
    val javaHome = packageLauncher.get().metadata.installationPath.asFile
    libraryjars(
        mapOf("jarfilter" to "!**.jar", "filter" to "!module-info.class"),
        File(javaHome, "jmods").listFiles()?.sorted() ?: emptyList<File>(),
    )

    // Everything that ships beside our jar: read, never rewritten.
    doFirst {
        layout.buildDirectory.dir("install/app/lib").get().asFile.listFiles()
            ?.filter { it.name.endsWith(".jar") && it.name != ourJar }
            ?.sorted()
            ?.forEach { libraryjars(it) }
    }

    printmapping(layout.buildDirectory.file("obfuscated/mapping.txt"))
}

// What jpackage is handed: the same runtime jars, with the obfuscated one in place of ours. Built as its own
// folder rather than by overwriting install/app/lib, so `installDist` keeps meaning what it says and a
// packaging run never leaves an obfuscated jar behind for the next `run`.
val packageLib = tasks.register<Sync>("packageLib") {
    group = "distribution"
    description = "The runtime jars jpackage is given, with Kubo's own jar obfuscated."
    dependsOn(tasks.installDist)
    if (obfuscating) {
        dependsOn(obfuscate)
    }
    val ourJar = tasks.jar.get().archiveFileName.get()
    from(layout.buildDirectory.dir("install/app/lib")) {
        if (obfuscating) {
            exclude(ourJar)
        }
    }
    if (obfuscating) {
        from(obfuscatedDir) {
            include(ourJar)
        }
    }
    into(layout.buildDirectory.dir("package-lib"))
    doLast {
        logger.lifecycle(if (obfuscating) "Packaging the obfuscated jar." else "Packaging the readable jar.")
    }
}

// Desktop packages. Everything below comes from one application image, so what someone unzips and what an
// installer lays down are the same bytes.
//
//   gradlew jpackage                        a plain folder in build/jpackage
//   gradlew portable                        build/distributions/Kubo-windows-portable.zip
//   gradlew tarball                         build/distributions/Kubo-linux-x86_64.tar.gz
//   gradlew jpackage -PinstallerType=exe    the Windows installer offered on the site
//   gradlew jpackage -PinstallerType=msi    the Windows installer winget runs
//   gradlew jpackage -PinstallerType=deb    Debian, Ubuntu and their derivatives
//   gradlew jpackage -PinstallerType=rpm    Fedora, RHEL, Rocky, Alma, openSUSE
//
// jpackage cannot build for another system than the one it runs on: the Windows installers need Windows and
// the WiX Toolset v3 on the PATH, the Linux ones need Linux with dpkg-deb or rpmbuild. Add -PappVersion=0.2.0
// to stamp a release.

val onWindows = System.getProperty("os.name").lowercase().startsWith("windows")
val packageLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }

/**
 * The jpackage command line. Only the type and the destination change between the folder, the installers and
 * the archives, so everything else is written once and cannot drift apart between them.
 */
fun jpackageCommand(type: String, destination: File): List<String> {
    val binary = if (onWindows) "bin/jpackage.exe" else "bin/jpackage"
    val jpackage = packageLauncher.get().metadata.installationPath.file(binary).asFile.absolutePath
    // Windows wants an .ico; every other system wants a plain PNG.
    val icon = if (onWindows) "assets/kubo.ico" else "assets/kubo.png"
    val arguments = mutableListOf(
        jpackage,
        "--type", type,
        "--name", "Kubo",
        "--app-version", appVersion,
        "--vendor", "Gemmie",
        "--description", "See and manage your server over SSH",
        "--icon", rootProject.file(icon).absolutePath,
        "--input", layout.buildDirectory.dir("package-lib").get().asFile.absolutePath,
        // Setting the project version renames the jar to app-<version>.jar, so ask the jar task for its
        // real name rather than hard-coding it.
        "--main-jar", tasks.jar.get().archiveFileName.get(),
        "--main-class", "com.kubo.Launcher",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--dest", destination.absolutePath,
    )
    // The app-image is just a folder; a licence screen, a home page, a menu entry and an upgrade rule only
    // mean something to an installer. The portable archives carry LICENSE.txt beside the app instead.
    if (type != "app-image") {
        // Both are refused on a plain folder: there is no installer to show a licence in, and no package
        // metadata to carry a home page.
        arguments += listOf(
            "--license-file", rootProject.file("LICENSE.txt").absolutePath,
            "--about-url", "https://kubo.gemmi-e.com",
        )
        arguments += if (onWindows) listOf(
            // Without these the app installs but leaves no way to launch it: no Start-menu entry, no icon.
            "--win-menu",
            "--win-menu-group", "Gemmie",
            "--win-shortcut",
            "--win-dir-chooser",
            // A fixed id so a new version REPLACES the old install instead of stacking a second Kubo beside it.
            // This value must never change once released.
            "--win-upgrade-uuid", "0ef6eab5-fefa-4d5c-8eb4-5abd3e462b86",
        ) else listOf(
            // Package names are lower-case on Linux by convention, and required to be by dpkg.
            "--linux-package-name", "kubo",
            // The desktop entry, and the freedesktop category the menu files it under.
            "--linux-shortcut",
            "--linux-menu-group", "System",
            // Debian's Section and rpm's Group: a tool for administering a machine.
            "--linux-app-category", "admin",
            "--linux-deb-maintainer", "nellawassi@gmail.com",
            "--linux-rpm-license-type", "Proprietary",
            // Let jpackage read the bundled binaries and declare the system libraries they need, so the
            // package manager refuses to install it somewhere it could not run rather than failing later.
            "--linux-package-deps",
        )
    }
    return arguments
}

tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Packages Kubo with its own Java runtime into build/jpackage."
    dependsOn(packageLib)
    val installType = (project.findProperty("installerType") as String?) ?: "app-image"
    val output = layout.buildDirectory.dir("jpackage")
    doFirst {
        delete(output)
        commandLine(jpackageCommand(installType, output.get().asFile))
    }
}

// Built separately from `jpackage` so that asking for a portable archive always produces a folder, whatever
// -PinstallerType happens to be set to.
tasks.register<Exec>("appImage") {
    group = "distribution"
    description = "Builds the plain application folder that the portable archives are made of."
    dependsOn(packageLib)
    val output = layout.buildDirectory.dir("app-image")
    doFirst {
        delete(output)
        commandLine(jpackageCommand("app-image", output.get().asFile))
    }
}

// The portable editions: no installer, no administrator, nothing written to the registry or to /usr, and
// nothing left behind. Unfold them anywhere — a USB stick, a machine you cannot install on — and run Kubo.
// The names carry no version, so a release's latest/download link keeps working from one version to the next.
tasks.register<Zip>("portable") {
    group = "distribution"
    description = "Zips the application folder into a portable Windows release that needs no installer."
    dependsOn("appImage")
    from(layout.buildDirectory.dir("app-image"))
    from(rootProject.file("packaging/portable/windows")) { into("Kubo") }
    from(rootProject.file("LICENSE.txt")) { into("Kubo") }
    archiveFileName = "Kubo-windows-portable.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

// Gradle normalises permissions inside archives rather than copying what is on disk, so everything jpackage
// marked executable has to be marked executable again here. Miss this and the unpacked launcher will not run.
val executableInArchive = listOf("**/bin/**", "**/*.so", "**/*.so.*", "**/jspawnhelper", "**/jexec")

tasks.register<Tar>("tarball") {
    group = "distribution"
    description = "Packs the application folder into a portable Linux release that needs no installer."
    dependsOn("appImage")
    dirPermissions { unix("0755") }
    filePermissions { unix("0644") }
    from(layout.buildDirectory.dir("app-image")) {
        exclude(executableInArchive)
    }
    from(layout.buildDirectory.dir("app-image")) {
        include(executableInArchive)
        filePermissions { unix("0755") }
    }
    from(rootProject.file("packaging/portable/linux")) { into("Kubo") }
    from(rootProject.file("LICENSE.txt")) { into("Kubo") }
    archiveFileName = "Kubo-linux-x86_64.tar.gz"
    compression = Compression.GZIP
    destinationDirectory = layout.buildDirectory.dir("distributions")
}

// Previews and console tools

tasks.register<JavaExec>("connectionCheck") {
    group = "application"
    description = "Connects to a server over SSH from the console."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.connection.ssh.ConnectionCheck"
    standardInput = System.`in`
}

tasks.register<JavaExec>("previewConnection") {
    group = "application"
    description = "Renders the connection screen to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.connection.ConnectionPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewMedia") {
    group = "application"
    description = "Renders the picture, sound and film viewer to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.media.MediaPreview"
    // The sample picture is a file in the repository, so the task runs from its root.
    workingDir = rootProject.projectDir
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewShell") {
    group = "application"
    description = "Renders the two windows the shell opens for itself to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.shell.ShellPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewScope") {
    group = "application"
    description = "Renders the server scope with sample data to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.scope.ScopePreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewFiles") {
    group = "application"
    description = "Renders the file manager with a sample tree to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.files.FilesPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewDocker") {
    group = "application"
    description = "Renders the Docker screen with sample containers to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.docker.DockerPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewPackages") {
    group = "application"
    description = "Renders the packages screen against a sample server to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.packages.PackagesPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewNetwork") {
    group = "application"
    description = "Renders the network screen against a sample server to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.network.NetworkPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewServices") {
    group = "application"
    description = "Renders the services screen against a sample server to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.services.ServicesPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

tasks.register<JavaExec>("previewRedis") {
    group = "application"
    description = "Renders the Redis screen with a sample keyspace to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.redis.RedisPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}
