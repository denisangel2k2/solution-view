package dev.solutionview

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsConfiguration
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

object SolutionVcsRoots {
    private val LOG = logger<SolutionVcsRoots>()

    fun register(project: Project, paths: List<Path>) {
        val manager = ProjectLevelVcsManager.getInstance(project)
        manager.runAfterInitialization {
            if (!project.isDisposed) addMissingMappings(project, manager, paths)
        }
    }

    private fun addMissingMappings(project: Project, manager: ProjectLevelVcsManager, paths: List<Path>) {
        val existing = manager.getDirectoryMappings()
        val mappedDirectories = existing.map { it.directory }.toSet()
        val ignored = VcsConfiguration.getInstance(project)
        val missing = paths.asSequence()
            .mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) }
            .filter { manager.getVcsFor(it) == null }
            .mapNotNull(::enclosingRoot)
            .distinct()
            .filterNot { it.directory in mappedDirectories || ignored.isIgnoredUnregisteredRoot(it.directory) }
            .toList()
        if (missing.isEmpty()) return
        LOG.info("Registering VCS roots: ${missing.joinToString { "${it.vcs} ${it.directory}" }}")
        manager.setDirectoryMappings(existing + missing)
    }

    private fun enclosingRoot(file: VirtualFile): VcsDirectoryMapping? {
        val checkers = VcsRootChecker.EXTENSION_POINT_NAME.extensionList
        return generateSequence(file) { it.parent }.firstNotNullOfOrNull { candidate ->
            checkers.firstOrNull { it.isRoot(candidate) }?.let { VcsDirectoryMapping(candidate.path, it.supportedVcs.name) }
        }
    }
}
