import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = ForgelineSdk.COMPILE
            defaultConfig {
                minSdk = ForgelineSdk.MIN
                targetSdk = ForgelineSdk.TARGET
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JAVA_VERSION
                targetCompatibility = JAVA_VERSION
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
            }
        }
        configureKotlinCompile()
        configureUnitTests()
        dependencies {
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("truth"))
        }
    }
}
