val gitCommitCountVersionCode = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull()
    ?: error("Unable to determine Android versionCode from the Git commit count")

extra["gitCommitCountVersionCode"] = gitCommitCountVersionCode

tasks.register("Delete", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
