import io.papermc.hangarpublishplugin.model.Platforms

plugins {
    id("java")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("io.papermc.hangar-publish-plugin") version "0.1.2"
}

group = "cn.lunadeer"
version = "5.0.0"

repositories {
    mavenCentral()
    maven ("https://repo.papermc.io/repository/maven-public/")
    maven ("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io") // for VaultAPI
    maven("https://repo.codemc.org/repository/maven-public") // for VaultUnlockedAPI
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    compileOnly("net.milkbowl.vault:VaultUnlockedAPI:2.7")
    testImplementation("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.processResources {
    outputs.upToDateWhen { false }
    // copy languages folder from PROJECT_DIR/languages to core/src/main/resources
    from(file("${rootProject.projectDir}/languages")) {
        into("languages")
    }
    // replace @version@ in plugin.yml with project version
    filesMatching("**/plugin.yml") {
        filter {
            it.replace("@version@", rootProject.version.toString())
        }
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    dependsOn(tasks.withType<ProcessResources>())
}

// CI publishes this exact artifact after the checks job has succeeded.
tasks.register<Copy>("stageRelease") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("release"))
}

hangarPublish {
    publications.register("plugin") {
        version.set(project.version as String) // use project version as publication version
        id.set("ReColorfulMap")
        channel.set("Release")
        changelog.set("See https://github.com/LunaDeerMC/ReColorfulMap/releases/tag/v${project.version}")
        apiKey.set(System.getenv("HANGAR_TOKEN"))
        // register platforms
        platforms {
            register(Platforms.PAPER) {
                jar.set(providers.gradleProperty("checkedReleaseJar")
                    .map { layout.projectDirectory.file(it) }
                    .orElse(tasks.shadowJar.flatMap { it.archiveFile }))
                platformVersions.set(kotlin.collections.listOf("1.20.1-1.20.6", "1.21.x", "26.2"))
            }
        }
    }
}

tasks.named("publishPluginPublicationToHangar") {
    if (!providers.gradleProperty("checkedReleaseJar").isPresent) {
        dependsOn(tasks.shadowJar)
    }
}
