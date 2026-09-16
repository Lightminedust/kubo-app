plugins {
    application
    jacoco
    alias(libs.plugins.javafx)
}

repositories {
    mavenCentral()
}

// The version stamped on the packaged app and its installer. Override at release time with
//   gradlew jpackage -PinstallerType=exe -PappVersion=0.2.0
// Windows installers need a plain numeric version (major.minor.patch); jpackage rejects anything else.
val appVersion = (project.findProperty("appVersion") as String?) ?: "0.1.0"
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
    modules = listOf("javafx.controls", "javafx.swing")
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

// Windows package: a self-contained Kubo folder with its own Java runtime (no Java needed on the PC).
// gradlew jpackage            -> build/jpackage/Kubo/Kubo.exe
// gradlew jpackage -PinstallerType=exe (or msi) -> an installer; needs the WiX Toolset on the PATH.
// gradlew jpackage -PinstallerType=exe -PappVersion=0.2.0 -> stamp a release version (default 0.1.0).
tasks.register<Exec>("jpackage") {
    group = "distribution"
    description = "Packages Kubo with its own Java runtime into build/jpackage."
    dependsOn(tasks.installDist)
    val installType = (project.findProperty("installerType") as String?) ?: "app-image"
    val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    val input = layout.buildDirectory.dir("install/app/lib")
    val output = layout.buildDirectory.dir("jpackage")
    doFirst {
        delete(output)
        val jpackage = launcher.get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath
        val arguments = mutableListOf(
            jpackage,
            "--type", installType,
            "--name", "Kubo",
            "--app-version", appVersion,
            "--vendor", "Gemmie",
            "--description", "See and manage your server over SSH",
            "--icon", rootProject.file("assets/kubo.ico").absolutePath,
            "--license-file", rootProject.file("LICENSE.txt").absolutePath,
            "--input", input.get().asFile.absolutePath,
            // Setting the project version renames the jar to app-<version>.jar, so ask the jar task for its
            // real name rather than hard-coding it.
            "--main-jar", tasks.jar.get().archiveFileName.get(),
            "--main-class", "com.kubo.Launcher",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--dest", output.get().asFile.absolutePath,
        )
        // The app-image is just a folder; the shortcut, menu and upgrade options only mean anything to an installer.
        if (installType != "app-image") {
            arguments += listOf(
                // Without these the app installs but leaves no way to launch it: no Start-menu entry, no icon.
                "--win-menu",
                "--win-menu-group", "Gemmie",
                "--win-shortcut",
                "--win-dir-chooser",
                // A fixed id so a new version REPLACES the old install instead of stacking a second Kubo beside it.
                // This value must never change once released.
                "--win-upgrade-uuid", "0ef6eab5-fefa-4d5c-8eb4-5abd3e462b86",
            )
        }
        commandLine(arguments)
    }
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

tasks.register<JavaExec>("previewRedis") {
    group = "application"
    description = "Renders the Redis screen with a sample keyspace to PNG files in build/previews."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.kubo.ui.redis.RedisPreview"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}
