import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
    `maven-publish`
}

group = "ru.ruscrafting"
version = providers.gradleProperty("pluginVersion").get()
description = "Standalone Origin world content plugin for RusCrafting"

jacoco {
    toolVersion = "0.8.15"
}

allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
kotlin.target.compilations.getByName("integrationTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.22")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.22")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.22")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.22")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.22")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")

    compileOnly(project(":arc-origin-api"))
    compileOnly("ru.ruscrafting.arc:arc-mounts-api:0.1.0")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.22")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1") {
        exclude(group = "io.netty")
    }
    compileOnly("com.denizenscript:denizen:1.3.2-SNAPSHOT") { isTransitive = false }
    compileOnly("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    compileOnly("com.github.LoneDev6:api-itemsadder:3.6.1")
    compileOnly("ru.ruscrafting.thirdparty:rediseconomy:4.5.12")
    implementation("de.tr7zw:item-nbt-api:2.15.7")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.22")
    testImplementation(project(":arc-origin-api"))
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.22")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1") {
        exclude(group = "io.netty")
    }
    testImplementation("com.denizenscript:denizen:1.3.2-SNAPSHOT") { isTransitive = false }
    testImplementation("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    testImplementation("com.github.LoneDev6:api-itemsadder:3.6.1")
    testImplementation("ru.ruscrafting.thirdparty:rediseconomy:4.5.12")
    testImplementation("de.tr7zw:item-nbt-api:2.15.7")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")

    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.22")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    forkEvery = 10
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("integrationTest") {
    description = "Runs Redis integration tests against disposable services."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("ArcOrigin")
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    mergeServiceFiles()
    relocate("de.tr7zw.changeme.nbtapi", "ru.arc.origin.internal.nbtapi")
    relocate("com.google.gson", "ru.arc.origin.internal.gson")

    // ARC owns these shared service contracts and provider implementations.
    exclude("ru/arc/origin/api/**")
    exclude("ru/arc/mounts/api/**")
    exclude("ru/arc/paper/api/**")
    exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
    exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
    exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
    exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
    exclude("org/bukkit/**")
    exclude("io/papermc/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("release-repository"))
        }
    }
    publications {
        create<MavenPublication>("runtimePlugin") {
            groupId = "ru.ruscrafting"
            artifactId = "ArcOrigin"
            version = project.version.toString()
            artifact(tasks.shadowJar)
            pom {
                name.set("ArcOrigin")
                description.set("Standalone Origin world content plugin for RusCrafting")
                url.set("https://github.com/alexey-va/ArcOrigin")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
