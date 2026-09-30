plugins {
    alias(libs.plugins.forgeline.jvm.library)
}

dependencies {
    api(projects.core.model)
    // Reads the inputs a workflow asks for when started by hand, on GitHub and Forgejo alike.
    implementation(libs.snakeyaml.engine)
}
