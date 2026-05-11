plugins {
    java
    alias(libs.plugins.spotless)
    alias(libs.plugins.paperweight.userdev)
}

/* Project Properties */
val projectGroup        = project.property("project_group")     as String
val projectId           = project.property("project_id")        as String
val projectVersion      = project.property("project_version")   as String
val projectName         = project.property("project_name")      as String
val minecraftVersion    = project.property("minecraft_version") as String

group = projectGroup
version = projectVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

spotless {
    java {
        palantirJavaFormat()
    }
}

repositories {
    mavenCentral()
    maven("https://repo.thenextlvl.net/snapshots")
}

dependencies {
    paperweight.paperDevBundle(minecraftVersion)

    compileOnly(libs.service.io)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    processResources {
        filteringCharset = "UTF-8"

        inputs.property("version", project.version)
        inputs.property("id", projectId)
        inputs.property("name", projectName)

        filesMatching("paper-plugin.yml") {
            expand(
                "version" to project.version,
                "id" to projectId,
                "name" to projectName
            )
        }
    }

    build {
        dependsOn(spotlessApply)
    }
}
