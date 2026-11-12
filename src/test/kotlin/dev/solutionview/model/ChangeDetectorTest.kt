package dev.solutionview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ChangeDetectorTest {
    private val root = Path.of("/repo")
    private val readme = root.resolve("README.md")
    private val app = SolutionProject("App", root.resolve("src/App/App.csproj"))
    private val lib = SolutionProject("Lib", root.resolve("src/Lib/Lib.csproj"))

    private fun detector(changedFiles: Set<Path> = emptySet(), changedProjects: Set<SolutionProject> = emptySet()) =
        ChangeDetector(isFileChanged = { it in changedFiles }, isProjectChanged = { it in changedProjects })

    @Test
    fun `nothing changed gives none`() {
        assertEquals(ChangeDepth.NONE, detector().depthOf(listOf(readme), listOf(app, lib)))
    }

    @Test
    fun `a changed file of the folder itself is immediate`() {
        val depth = detector(changedFiles = setOf(readme), changedProjects = setOf(app)).depthOf(listOf(readme), listOf(app))

        assertEquals(ChangeDepth.IMMEDIATE, depth)
    }

    @Test
    fun `a changed project is nested`() {
        assertEquals(ChangeDepth.NESTED, detector(changedProjects = setOf(lib)).depthOf(emptyList(), listOf(app, lib)))
    }

    @Test
    fun `a changed file in a nested solution folder is nested`() {
        val nested = SolutionFolder("Build", emptyList(), listOf(readme))

        assertEquals(ChangeDepth.NESTED, detector(changedFiles = setOf(readme)).depthOf(emptyList(), listOf(nested)))
    }

    @Test
    fun `a folder with no tracked or untracked changes is none`() {
        val depth = ChangeDepth.ofDirectory(root.resolve("src"), ChangeDepth.NONE, listOf(root.resolve("docs/new.md")))

        assertEquals(ChangeDepth.NONE, depth)
    }

    @Test
    fun `an untracked file directly in the folder is immediate`() {
        val depth = ChangeDepth.ofDirectory(root.resolve("src"), ChangeDepth.NONE, listOf(root.resolve("src/New.cs")))

        assertEquals(ChangeDepth.IMMEDIATE, depth)
    }

    @Test
    fun `an untracked folder deeper inside is nested`() {
        val depth = ChangeDepth.ofDirectory(root, ChangeDepth.NONE, listOf(root.resolve("src/NewModule")))

        assertEquals(ChangeDepth.NESTED, depth)
    }

    @Test
    fun `tracked changes win over untracked ones`() {
        val depth = ChangeDepth.ofDirectory(root, ChangeDepth.IMMEDIATE, listOf(root.resolve("src/NewModule")))

        assertEquals(ChangeDepth.IMMEDIATE, depth)
    }
}
