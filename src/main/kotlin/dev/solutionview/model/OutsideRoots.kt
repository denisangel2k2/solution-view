package dev.solutionview.model

import java.nio.file.Path

object OutsideRoots {
    fun of(solution: Solution, baseDir: Path, projectFiles: (SolutionProject) -> List<Path>): List<Path> {
        val base = baseDir.normalize()
        val candidates = solution.allProjects().flatMap { listOf(it.directory) + projectFiles(it) } +
            solutionItemFiles(solution.items)
        return candidates.map(Path::normalize)
            .filterNot { it.startsWith(base) }
            .distinct()
            .sortedBy { it.nameCount }
            .fold(mutableListOf<Path>()) { kept, path -> kept.apply { if (none { path.startsWith(it) }) add(path) } }
    }

    private fun solutionItemFiles(items: List<SolutionItem>): List<Path> = items.flatMap { item ->
        when (item) {
            is SolutionFolder -> item.files + solutionItemFiles(item.children)
            is SolutionProject -> emptyList()
        }
    }
}
