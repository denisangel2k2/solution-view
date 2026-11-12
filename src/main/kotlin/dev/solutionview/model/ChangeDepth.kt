package dev.solutionview.model

import java.nio.file.Path

enum class ChangeDepth {
    NONE, IMMEDIATE, NESTED;

    companion object {
        fun ofDirectory(directory: Path, tracked: ChangeDepth, unversioned: List<Path>): ChangeDepth {
            val inside = unversioned.filter { it != directory && it.startsWith(directory) }
            return when {
                tracked == IMMEDIATE || inside.any { it.parent == directory } -> IMMEDIATE
                tracked == NESTED || inside.isNotEmpty() -> NESTED
                else -> NONE
            }
        }
    }
}

class ChangeDetector(
    private val isFileChanged: (Path) -> Boolean,
    private val isProjectChanged: (SolutionProject) -> Boolean,
) {
    fun depthOf(files: List<Path>, children: List<SolutionItem>): ChangeDepth = when {
        files.any(isFileChanged) -> ChangeDepth.IMMEDIATE
        children.any(::isChanged) -> ChangeDepth.NESTED
        else -> ChangeDepth.NONE
    }

    private fun isChanged(item: SolutionItem): Boolean = when (item) {
        is SolutionProject -> isProjectChanged(item)
        is SolutionFolder -> depthOf(item.files, item.children) != ChangeDepth.NONE
    }
}
