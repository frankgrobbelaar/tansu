plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
    format("misc") {
        target(
            ".editorconfig",
            ".gitattributes",
            ".gitignore",
            "*.md",
            "docs/*.md",
            "gradle/*.toml",
            ".github/**/*.yml",
            "app/src/**/*.xml",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
