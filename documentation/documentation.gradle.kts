import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.ByteArrayOutputStream
import java.nio.file.Files

buildscript {
	dependencies {
		// upgrade to latest jruby version due to a bugfix needed for Windows 10.
		// can be removed, when asciidoctorj uses this as a default version.
		classpath("org.jruby:jruby-complete:${Versions.jruby}")

		// classpath("org.asciidoctor:asciidoctorj-epub3:${Versions.asciidoctorPdf}")
		classpath("org.asciidoctor:asciidoctorj-pdf:${Versions.asciidoctorPdf}")
		classpath("org.asciidoctor:asciidoctorj-diagram:${Versions.asciidoctorDiagram}")
	}
}

plugins {
	id("org.asciidoctor.convert")
	id("org.ajoberstar.git-publish")
	`kotlin-library-conventions`
}

val mavenizedProjects: List<Project> by rootProject.extra

// Because we need to set up Javadoc aggregation
mavenizedProjects.forEach { evaluationDependsOn(it.path) }

javaLibrary {
	mainJavaVersion = JavaVersion.VERSION_1_8
	testJavaVersion = JavaVersion.VERSION_1_8
}

dependencies {
	asciidoctor("org.jruby:jruby-complete:${Versions.jruby}")

	// Jupiter API is used in src/main/java
	implementation(project(":junit-jupiter-api"))

	// Pull in all "Mavenized projects" to ensure that they are included
	// in reports generated by the ApiReportGenerator.
	val mavenizedProjects: List<Project> by rootProject.extra
	mavenizedProjects
			.filter { it.name != "junit-platform-console-standalone" }
			.forEach { testImplementation(it) }

	testImplementation("org.jetbrains.kotlin:kotlin-stdlib")

	testRuntimeOnly("org.apache.logging.log4j:log4j-core:${Versions.log4j}")
	testRuntimeOnly("org.apache.logging.log4j:log4j-jul:${Versions.log4j}")

	// for ApiReportGenerator
	testImplementation("io.github.classgraph:classgraph:${Versions.classgraph}")
}

asciidoctorj {
	version = Versions.asciidoctorJ
}

val docsVersion = if (rootProject.version.toString().contains("SNAPSHOT")) "snapshot" else rootProject.version
val docsDir = file("$buildDir/ghpages-docs")
val replaceCurrentDocs = project.hasProperty("replaceCurrentDocs")
val ota4jDocVersion = if (Versions.ota4j.contains("SNAPSHOT")) "snapshot" else Versions.ota4j
val apiGuardianDocVersion = if (Versions.apiGuardian.contains("SNAPSHOT")) "snapshot" else Versions.apiGuardian

gitPublish {
	repoUri.set("https://github.com/junit-team/junit5.git")
	branch.set("gh-pages")

	contents {
		from(docsDir)
		into("docs")
	}

	preserve {
		include("**/*")
		exclude("docs/$docsVersion/**")
		if (replaceCurrentDocs) {
			exclude("docs/current/**")
		}
	}
}

val generatedAsciiDocPath = file("$buildDir/generated/asciidoc")
val consoleLauncherOptionsFile = File(generatedAsciiDocPath, "console-launcher-options.txt")
val experimentalApisTableFile = File(generatedAsciiDocPath, "experimental-apis-table.txt")
val deprecatedApisTableFile = File(generatedAsciiDocPath, "deprecated-apis-table.txt")

tasks {

	val consoleLauncherTest by registering(JavaExec::class) {
		dependsOn(testClasses)
		val reportsDir = file("$buildDir/test-results")
		outputs.dir(reportsDir)
		outputs.cacheIf { true }
		classpath = sourceSets["test"].runtimeClasspath
		main = "org.junit.platform.console.ConsoleLauncher"
		args("--scan-classpath")
		args("--details", "tree")
		args("--include-classname", ".*Tests")
		args("--include-classname", ".*Demo")
		args("--exclude-tag", "exclude")
		args("--reports-dir", reportsDir)
		systemProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager")
	}

	test {
		dependsOn(consoleLauncherTest)
		exclude("**/*")
	}

	val generateConsoleLauncherOptions by registering(JavaExec::class) {
		classpath = sourceSets["test"].runtimeClasspath
		main = "org.junit.platform.console.ConsoleLauncher"
		args("--help")
		redirectOutput(this, consoleLauncherOptionsFile)
	}

	val generateExperimentalApisTable by registering(JavaExec::class) {
		classpath = sourceSets["test"].runtimeClasspath
		main = "org.junit.api.tools.ApiReportGenerator"
		args("EXPERIMENTAL")
		redirectOutput(this, experimentalApisTableFile)
	}

	val generateDeprecatedApisTable by registering(JavaExec::class) {
		classpath = sourceSets["test"].runtimeClasspath
		main = "org.junit.api.tools.ApiReportGenerator"
		args("DEPRECATED")
		redirectOutput(this, deprecatedApisTableFile)
	}

	asciidoctor {
		dependsOn(generateConsoleLauncherOptions, generateExperimentalApisTable, generateDeprecatedApisTable)

		// enable the Asciidoctor Diagram extension
		requires("asciidoctor-diagram")

		separateOutputDirs = false
		sources(delegateClosureOf<PatternSet> {
			include("**/index.adoc")
		})
		resources(delegateClosureOf<CopySpec> {
			from(sourceDir) {
				include("**/images/**")
				include("tocbot-*/**")
			}
		})

		backends("html5")
		backends("pdf")
		attributes(mapOf("linkToPdf" to "true"))

		attributes(mapOf(
				"jupiter-version" to version,
				"platform-version" to project.properties["platformVersion"],
				"vintage-version" to project.properties["vintageVersion"],
				"bom-version" to version,
				"junit4-version" to Versions.junit4,
				"apiguardian-version" to Versions.apiGuardian,
				"ota4j-version" to Versions.ota4j,
				"surefire-version" to  Versions.surefire,
				"release-branch" to project.properties["releaseBranch"],
				"docs-version" to project.properties["docsVersion"],
				"revnumber" to version,
				"consoleLauncherOptionsFile" to consoleLauncherOptionsFile,
				"experimentalApisTableFile" to experimentalApisTableFile,
				"deprecatedApisTableFile" to deprecatedApisTableFile,
				"outdir" to outputDir.absolutePath,
				"source-highlighter" to "coderay@", // TODO switch to "rouge" once supported by the html5 backend and on MS Windows
				"tabsize" to "4",
				"toc" to "left",
				"icons" to "font",
				"sectanchors" to true,
				"idprefix" to "",
				"idseparator" to "-"
		))

		sourceSets["test"].apply {
			attributes(mapOf(
					"testDir" to java.srcDirs.first(),
					"testResourcesDir" to resources.srcDirs.first()
			))
			withConvention(KotlinSourceSet::class) {
				attributes(mapOf("kotlinTestDir" to kotlin.srcDirs.first()))
			}
		}
	}

	val aggregateJavadocs by registering(Javadoc::class) {
		dependsOn(mavenizedProjects.map { it.tasks.jar })
		group = "Documentation"
		description = "Generates aggregated Javadocs"

		title = "JUnit $version API"

		val stylesheetFooterFile = rootProject.file("src/javadoc/stylesheet-footer.css")
		inputs.file(stylesheetFooterFile)

		options {
			memberLevel = JavadocMemberLevel.PROTECTED
			header = rootProject.description
			encoding = "UTF-8"
			(this as StandardJavadocDocletOptions).apply {
				splitIndex(true)
				addBooleanOption("Xdoclint:none", true)
				addBooleanOption("html5", true)
				// Javadoc 13 removed support for `--no-module-directories`
				// https://bugs.openjdk.java.net/browse/JDK-8215580
				val javaVersion = JavaVersion.current()
				if (javaVersion.isJava11 || javaVersion.isJava12) {
					addBooleanOption("-no-module-directories", true)
				}
				addMultilineStringsOption("tag").value = listOf(
						"apiNote:a:API Note:",
						"implNote:a:Implementation Note:"
				)
				jFlags("-Xmx1g")
				source("8") // https://github.com/junit-team/junit5/issues/1735
				links("https://docs.oracle.com/javase/8/docs/api/")
				links("https://ota4j-team.github.io/opentest4j/docs/$ota4jDocVersion/api/")
				links("https://apiguardian-team.github.io/apiguardian/docs/$apiGuardianDocVersion/api/")
				links("https://junit.org/junit4/javadoc/${Versions.junit4}/")
				links("https://joel-costigliola.github.io/assertj/core-8/api/")
				groups = mapOf(
						"Jupiter" to listOf("org.junit.jupiter.*"),
						"Vintage" to listOf("org.junit.vintage.*"),
						"Platform" to listOf("org.junit.platform.*")
				)
				use(true)
				noTimestamp(true)
			}
		}
		source(mavenizedProjects.map { it.sourceSets.main.get().allJava })

		setMaxMemory("1024m")
		setDestinationDir(file("$buildDir/docs/javadoc"))

		classpath = files(mavenizedProjects.map { it.sourceSets.main.get().compileClasspath })
				// Remove Kotlin classes from classpath due to "bad" class file
				// see https://bugs.openjdk.java.net/browse/JDK-8187422
				.filter { !it.path.contains("kotlin") }
				// Remove subproject JARs so Kotlin classes don"t get picked up
				.filter { it.isDirectory || !it.absolutePath.startsWith(projectDir.absolutePath) }

		doLast {
			val javadocStylesheet = File(destinationDir, "stylesheet.css")
			javadocStylesheet.appendBytes(stylesheetFooterFile.readBytes())
		}
		doLast {
			// For compatibility with pre JDK 10 versions of the Javadoc tool
			copy {
				from(File(destinationDir, "element-list"))
				into("$destinationDir")
				rename { "package-list" }
			}
		}
	}

	val prepareDocsForUploadToGhPages by registering(Copy::class) {
		dependsOn(aggregateJavadocs, asciidoctor)
		outputs.dir(docsDir)

		from("$buildDir/checksum") {
			include("published-checksum.txt")
		}
		from("$buildDir/asciidoc") {
			include("user-guide/**")
			include("release-notes/**")
			include("tocbot-*/**")
		}
		from("$buildDir/docs") {
			include("javadoc/**")
			filesMatching("**/*.html") {
				val favicon = "<link rel=\"icon\" type=\"image/png\" href=\"https://junit.org/junit5/assets/img/junit5-logo.png\">"
				filter { line ->
					if (line.startsWith("<head>")) line.replace("<head>", "<head>$favicon") else line
				}
			}
		}
		into("$docsDir/$docsVersion")
		filesMatching("javadoc/**") {
			path = path.replace("javadoc/", "api/")
		}
		includeEmptyDirs = false
	}

	val createCurrentDocsFolder by registering(Copy::class) {
		dependsOn(prepareDocsForUploadToGhPages)
		outputs.dir("$docsDir/current")
		onlyIf { replaceCurrentDocs }

		from("$docsDir/$docsVersion")
		into("$docsDir/current")
	}

	gitPublishCommit {
		dependsOn(prepareDocsForUploadToGhPages, createCurrentDocsFolder)
	}
}

fun redirectOutput(task: JavaExec, outputFile: File) {
	task.apply {
		outputs.file(outputFile)
		val byteStream = ByteArrayOutputStream()
		standardOutput = byteStream
		doLast {
			Files.createDirectories(outputFile.parentFile.toPath())
			Files.write(outputFile.toPath(), byteStream.toByteArray())
		}
	}
}

eclipse {
	classpath {
		plusConfigurations.add(project(":junit-platform-console").configurations["shadowed"])
		plusConfigurations.add(project(":junit-jupiter-params").configurations["shadowed"])
	}
}

idea {
	module {
		scopes["PROVIDED"]!!["plus"]!!.add(project(":junit-platform-console").configurations["shadowed"])
		scopes["PROVIDED"]!!["plus"]!!.add(project(":junit-jupiter-params").configurations["shadowed"])
	}
}
