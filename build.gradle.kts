import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.29.0"
}

group = "io.github.joohyung-park"
version = "0.2.2"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.joohyung-park:daemonizer:0.1.2")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        maven {
            name = "localStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

tasks.register<Zip>("bundleForCentralPortal") {
    dependsOn("publishAllPublicationsToLocalStagingRepository")
    from(layout.buildDirectory.dir("staging-deploy"))
    archiveFileName.set("bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("bundle"))
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("io.github.joohyung-park", "proxxy", version.toString())

    pom {
        name.set("proxxy")
        description.set("Thread-safe, partitioned proxy for Java interfaces — routes method calls by a caller-supplied router function to dedicated daemon threads.")
        url.set("https://github.com/on-the-ground/proxxy")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("joohyung-park")
                name.set("Joohyung Park")
                email.set("gcjoohyung@naver.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/on-the-ground/proxxy.git")
            developerConnection.set("scm:git:ssh://github.com/on-the-ground/proxxy.git")
            url.set("https://github.com/on-the-ground/proxxy")
        }
    }
}
