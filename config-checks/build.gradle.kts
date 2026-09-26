plugins {
    alias(libs.plugins.forgeline.jvm.library)
}

// Tests that guard build, CI and test-suite invariants: each one exists because breaking it
// once caused a real bug. They read repository files, which are declared as inputs.
val repoRoot: File = rootDir
tasks.test {
    systemProperty("forgeline.repoRoot", repoRoot.absolutePath)
    inputs.files(
        fileTree(repoRoot) {
            include(".github/**", "gradle.properties", "gradle/*.properties", "build-logic/convention/src/**")
            include("**/src/test/**/*.kt")
            exclude("**/build/**", ".gradle/**", "config-checks/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
