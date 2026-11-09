package dev.solutionview

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Files
import java.nio.file.Path

internal val BUILD_OUTPUT_DIRS = setOf("bin", "obj")

object SolutionContentRoots {
    fun sync(project: Project, roots: List<Path>, ownUrls: List<String>): List<String> {
        val module = ModuleManager.getInstance(project).modules.singleOrNull() ?: return ownUrls
        val foreignUrls = contentUrls(module) - ownUrls.toSet()
        val wanted = roots.associateBy(::urlOf).filterKeys { it !in foreignUrls }
        if (wanted.keys == ownUrls.toSet()) return ownUrls
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.filter { it.url in ownUrls }.forEach(model::removeContentEntry)
            wanted.forEach { (url, root) -> excludeBuildOutput(model.addContentEntry(url), root) }
        }
        return wanted.keys.toList()
    }

    private fun contentUrls(module: Module): Set<String> = ReadAction.computeBlocking<Set<String>, RuntimeException> {
        ModuleRootManager.getInstance(module).contentEntries.map { it.url }.toSet()
    }

    private fun excludeBuildOutput(entry: ContentEntry, root: Path) {
        if (!Files.isDirectory(root)) return
        BUILD_OUTPUT_DIRS.forEach { entry.addExcludeFolder(urlOf(root.resolve(it))) }
    }

    private fun urlOf(path: Path): String = VfsUtilCore.pathToUrl(path.toString())
}
