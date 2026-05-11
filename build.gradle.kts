plugins {
    java
    alias(libs.plugins.spotless)
}

/* Project Properties */
val projectGroup        = project.property("project_group")     as String
val projectId           = project.property("project_id")        as String
val projectVersion      = project.property("project_version")   as String
val projectName         = project.property("project_name")      as String

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
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.thenextlvl.net/snapshots")
}

dependencies {
    compileOnly(libs.paper.api)
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
