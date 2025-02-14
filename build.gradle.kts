/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//to build everything:             "gradlew build"
//to build and upload everything:  "gradlew release"

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    `java-library`
    `maven-publish`

    id("com.gradleup.shadow") version "8.3.6"
}


////////////////////////////////////
//                                //
//     Project Configuration      //
//                                //
////////////////////////////////////


val javaVersion = JavaVersion.current()
val versionObj = Version(major = "5", minor = "3", revision = System.getenv("PUBLISH_VERSION") ?: "0", classifier = null)
val isGithubAction = System.getProperty("GITHUB_ACTION") != null || System.getenv("GITHUB_ACTION") != null
val isCI = System.getProperty("BUILD_NUMBER") != null // jenkins
        || System.getenv("BUILD_NUMBER") != null
        || System.getProperty("GIT_COMMIT") != null // jitpack
        || System.getenv("GIT_COMMIT") != null
        || isGithubAction // Github Actions

// Check the commit hash and version information
val commitHash: String by lazy {
    val commit = System.getenv("GIT_COMMIT") ?: System.getProperty("GIT_COMMIT") ?: System.getenv("GITHUB_SHA")
    // We only set the commit hash on CI builds since we don't want dirty local repos to set a wrong commit
    if (isCI && commit != null)
        commit.take(7)
    else
        "DEV"
}

val previousVersion: Version by lazy {
    val file = layout.projectDirectory.file(".version").asFile
    if (file.canRead())
        Version.parse(file.readText().trim())
    else
        versionObj
}

project.version = "$versionObj"
project.group = "com.github.rfresh2"

base {
    archivesName.set("JDA")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

configure<SourceSetContainer> {
    register("examples") {
        java.srcDir("src/examples/java")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}


////////////////////////////////////
//                                //
//    Dependency Configuration    //
//                                //
////////////////////////////////////


repositories {
    maven("https://maven.2b2t.vc/releases") {
        content {
            includeGroupByRegex("com.github.rfresh2.*")
        }
    }
    mavenLocal()
    mavenCentral()
}

dependencies {
    /* ABI dependencies */

    //Code safety
    compileOnly(libs.findbugs)
    compileOnly(libs.jetbrains.annotations)

    //Logger
    api(libs.slf4j)

    //Web Connection Support
    api(libs.websocket.client)
    api(libs.okhttp)

    //Opus library support
    api(libs.opus)

    //Collections Utility
    api(libs.commons.collections)

    //we use this only together with opus-java
    // if that dependency is excluded it also doesn't need jna anymore
    // since jna is a transitive runtime dependency of opus-java we don't include it explicitly as dependency
    compileOnly(libs.jna)

    /* Internal dependencies */

    //General Utility
    implementation(libs.bundles.jackson)
    val fastutilVersion = "8.5.15"
    implementation("com.github.rfresh2.fastutil.sets:long-sets:$fastutilVersion")
    implementation("com.github.rfresh2.fastutil.sets:int-sets:$fastutilVersion")
    implementation("com.github.rfresh2.fastutil.maps:long-object-maps:$fastutilVersion")
    implementation("com.github.rfresh2.fastutil.maps:int-object-maps:$fastutilVersion")
    implementation("com.github.rfresh2.fastutil.maps:int-long-maps:$fastutilVersion")
    implementation("com.github.rfresh2.fastutil.maps:long-long-maps:$fastutilVersion")
    implementation("com.github.rfresh2.fastutil.maps:object-int-maps:$fastutilVersion")

    //Audio crypto libraries
    implementation(libs.tink)

    implementation(libs.eventbus)

    //Sets the dependencies for the examples
    configurations["examplesImplementation"].withDependencies {
        addAll(configurations["api"].allDependencies)
        addAll(configurations["implementation"].allDependencies)
        addAll(configurations["compileOnly"].allDependencies)
    }

    testImplementation(libs.junit)
    testImplementation(libs.reflections)
    testImplementation(libs.mockito)
    testImplementation(libs.assertj)
    testImplementation(libs.commons.lang3)
    testImplementation(libs.logback.classic)
    testImplementation(libs.archunit)
}


////////////////////////////////////
//                                //
//    Build Task Configuration    //
//                                //
////////////////////////////////////


val jar by tasks.getting(Jar::class) {
    archiveBaseName.set(project.name)
    manifest.attributes(
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "net.dv8tion.jda")
}

val shadowJar by tasks.getting(ShadowJar::class) {
    archiveClassifier.set("withDependencies")
    exclude("*.pom")
}

val sourcesForRelease by tasks.creating(Copy::class) {
    from("src/main/java") {
        include("**/JDAInfo.java")
        val tokens = mapOf(
            "versionMajor" to versionObj.major,
            "versionMinor" to versionObj.minor,
            "versionRevision" to versionObj.revision,
            "versionClassifier" to nullableReplacement(versionObj.classifier),
            "commitHash" to commitHash
        )
        // Allow for setting null on some strings without breaking the source
        // for this, we have special tokens marked with "!@...@!" which are replaced to @...@
        filter { it.replace(Regex("\"!@|@!\""), "@") }
        // Then we can replace the @...@ with the respective values here
        filter<ReplaceTokens>("tokens" to tokens)
    }
    into("build/filteredSrc")

    includeEmptyDirs = false
}

val generateJavaSources by tasks.creating(SourceTask::class) {
    val javaSources = sourceSets["main"].allJava.filter {
        it.name != "JDAInfo.java"
    }.asFileTree

    source = javaSources + fileTree(sourcesForRelease.destinationDir)
    dependsOn(sourcesForRelease)
}

val noOpusJar by tasks.creating(ShadowJar::class) {
    dependsOn(shadowJar)
    archiveClassifier.set(shadowJar.archiveClassifier.get() + "-no-opus")

    configurations = shadowJar.configurations
    from(sourceSets["main"].output)
    exclude("natives/**")     // ~2 MB
    exclude("com/sun/jna/**") // ~1 MB
    exclude("club/minnced/opus/util/*")
    exclude("tomp2p/opuswrapper/*")

    manifest.inheritFrom(jar.manifest)
}

val minimalJar by tasks.creating(ShadowJar::class) {
    dependsOn(shadowJar)
    minimize()
    archiveClassifier.set(shadowJar.archiveClassifier.get() + "-min")

    configurations = shadowJar.configurations
    from(sourceSets["main"].output)
    exclude("natives/**")     // ~2 MB
    exclude("com/sun/jna/**") // ~1 MB
    exclude("com/google/crypto/tink/**") // ~2 MB
    exclude("com/google/gson/**") // ~300 KB
    exclude("com/google/protobuf/**") // ~2 MB
    exclude("google/protobuf/**")
    exclude("club/minnced/opus/util/*")
    exclude("tomp2p/opuswrapper/*")
    manifest.inheritFrom(jar.manifest)
}

val sourcesJar by tasks.creating(Jar::class) {
    archiveClassifier.set("sources")
    from("src/main/java") {
        exclude("**/JDAInfo.java")
    }
    from(sourcesForRelease.destinationDir)

    dependsOn(sourcesForRelease)
}

val javadoc by tasks.getting(Javadoc::class) {
    isFailOnError = isCI
    options.memberLevel = JavadocMemberLevel.PUBLIC
    options.encoding = "UTF-8"

    (options as? StandardJavadocDocletOptions)?.let { opt ->
        opt.author()
        opt.tags("incubating:a:Incubating:")
        opt.links(
                "https://docs.oracle.com/javase/8/docs/api/",
                "https://takahikokawasaki.github.io/nv-websocket-client/")
        if (JavaVersion.VERSION_1_8 < javaVersion) {
            opt.addBooleanOption("html5", true) // Adds search bar
            opt.addStringOption("-release", "8")
        }
        // Fix for https://stackoverflow.com/questions/52326318/maven-javadoc-search-redirects-to-undefined-url
        if (javaVersion in JavaVersion.VERSION_11..JavaVersion.VERSION_12) {
            opt.addBooleanOption("-no-module-directories", true)
        }
        // Java 13 changed accessibility rules.
        // On versions less than Java 13, we simply ignore the errors.
        // Both of these remove "no comment" warnings.
        if (javaVersion >= JavaVersion.VERSION_13) {
            opt.addBooleanOption("Xdoclint:all,-missing", true)
        } else {
            opt.addBooleanOption("Xdoclint:all,-missing,-accessibility", true)
        }

        opt.overview = "$projectDir/overview.html"
    }

    dependsOn(sourcesJar)
    source = sourcesJar.source.asFileTree
    exclude("MANIFEST.MF")

    //### excludes ###

    //jda internals
    exclude("net/dv8tion/jda/internal")

    //voice crypto
    exclude("com/iwebpp/crypto")
}

val javadocJar by tasks.creating(Jar::class) {
    dependsOn(javadoc)
    archiveClassifier.set("javadoc")
    from(javadoc.destinationDir)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.isIncremental = true

    val args = mutableListOf("-Xlint:deprecation", "-Xlint:unchecked")

    doFirst {
        options.compilerArgs = args
    }
}

val compileJava by tasks.getting(JavaCompile::class) {
    dependsOn(generateJavaSources)
    source = generateJavaSources.source
}

val build by tasks.getting(Task::class) {
    dependsOn(jar)
    dependsOn(javadocJar)
    dependsOn(sourcesJar)
    dependsOn(shadowJar)
    dependsOn(noOpusJar)
    dependsOn(minimalJar)

    jar.mustRunAfter(tasks.clean)
    shadowJar.mustRunAfter(sourcesJar)
}

val test by tasks.getting(Test::class) {
    useJUnitPlatform()
    failFast = true
}


////////////////////////////////////
//                                //
//    Publishing And Signing      //
//                                //
////////////////////////////////////


// Generate pom file for maven central

fun MavenPom.populate() {
    packaging = "jar"
    name.set(project.name)
    description.set("Java wrapper for the popular chat & VOIP service: Discord https://discord.com")
    url.set("https://github.com/discord-jda/JDA")
    scm {
        url.set("https://github.com/discord-jda/JDA")
        connection.set("scm:git:git://github.com/discord-jda/JDA")
        developerConnection.set("scm:git:ssh:git@github.com:discord-jda/JDA")
    }
    licenses {
        license {
            name.set("The Apache Software License, Version 2.0")
            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
        }
    }
    developers {
        developer {
            id.set("Minn")
            name.set("Florian Spieß")
            email.set("business@minn.dev")
        }
        developer {
            id.set("DV8FromTheWorld")
            name.set("Austin Keener")
            email.set("keeneraustin@yahoo.com")
        }
    }
}

// Skip fat jar publication (See https://github.com/johnrengelman/shadow/issues/586)
components.java.withVariantsFromConfiguration(configurations.shadowRuntimeElements.get()) { skip() }
val SoftwareComponentContainer.java
    get() = components.getByName<AdhocComponentWithVariants>("java")

publishing {
    repositories {
        maven("https://maven.2b2t.vc/releases") {
            name = "vc"
            credentials {
                username = System.getenv("MAVEN_USERNAME")
                password = System.getenv("MAVEN_PASSWORD")
            }
        }
    }
    publications {
        register<MavenPublication>("Release") {
            from(components["java"])

            artifactId = project.name
            groupId = project.group as String
            version = project.version as String

            artifact(sourcesJar)
            artifact(javadocJar)

            pom.populate()
        }
    }
}

////////////////////////////////////
//                                //
//   Release Task Configuration   //
//                                //
////////////////////////////////////


val rebuild by tasks.creating(Task::class) {
    group = "build"

    dependsOn(build)
    dependsOn(tasks.clean)
    build.mustRunAfter(tasks.clean)
}


////////////////////////////////////
//                                //
//            Helpers             //
//                                //
////////////////////////////////////

fun nullableReplacement(string: String?): String {
    return if (string == null) "null"
    else "\"$string\""
}

data class Version(
    val major: String,
    val minor: String,
    val revision: String,
    val classifier: String? = null
) {
    companion object {
        fun parse(string: String): Version {
            val (major, minor, revision) = string.substringBefore("-").split(".")
            val classifier = string.substringAfter("-").takeIf { "-" in string }
            return Version(major, minor, revision, classifier)
        }
    }

    override fun toString(): String {
        return "$major.$minor.$revision" + if (classifier != null) "-$classifier" else ""
    }
}
