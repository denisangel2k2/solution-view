package dev.solutionview.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OutsideRootsTest {
    @TempDir
    lateinit var dir: Path

    private val base: Path get() = dir.resolve("repo/app")

    private fun project(relative: String) = SolutionProject(relative.substringAfterLast('/'), dir.resolve(relative))

    private fun roots(items: List<SolutionItem>, files: Map<String, List<String>> = emptyMap()): List<Path> =
        OutsideRoots.of(Solution(base.resolve("App.sln"), items), base) { p ->
            files[p.name].orEmpty().map(dir::resolve)
        }

    @Test
    fun `projects inside the solution folder add no root`() {
        assertEquals(emptyList<Path>(), roots(listOf(project("repo/app/src/App/App.csproj"))))
    }

    @Test
    fun `a project outside the solution folder adds its folder`() {
        val result = roots(listOf(project("repo/app/src/App/App.csproj"), project("repo/shared/Lib/Lib.csproj")))

        assertEquals(listOf(dir.resolve("repo/shared/Lib")), result)
    }

    @Test
    fun `a linked file outside every project folder adds the file`() {
        val result = roots(
            listOf(project("repo/app/src/App/App.csproj")),
            mapOf("App.csproj" to listOf("repo/app/src/App/Program.cs", "repo/common/GlobalInfo.cs")),
        )

        assertEquals(listOf(dir.resolve("repo/common/GlobalInfo.cs")), result)
    }

    @Test
    fun `roots inside another outside root are dropped`() {
        val result = roots(
            listOf(
                project("repo/shared/Lib/Lib.csproj"),
                project("repo/shared/Lib/Sub/Sub.csproj"),
            ),
            mapOf("Lib.csproj" to listOf("repo/shared/Lib/Linked.cs")),
        )

        assertEquals(listOf(dir.resolve("repo/shared/Lib")), result)
    }

    @Test
    fun `solution items outside the solution folder are added from nested folders`() {
        val items = listOf(
            SolutionFolder(
                "Solution Items",
                listOf(SolutionFolder("Build", emptyList(), listOf(dir.resolve("repo/Directory.Build.props")))),
                listOf(base.resolve("README.md")),
            ),
        )

        assertEquals(listOf(dir.resolve("repo/Directory.Build.props")), roots(items))
    }
}
