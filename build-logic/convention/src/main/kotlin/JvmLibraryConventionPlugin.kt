import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JAVA_VERSION
            targetCompatibility = JAVA_VERSION
        }
        configureKotlinCompile()
        configureUnitTests()
        // Android modules expose testDebugUnitTest; alias it so one task runs every module's tests.
        tasks.register("testDebugUnitTest") { dependsOn("test") }
        dependencies {
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("truth"))
        }
    }
}
