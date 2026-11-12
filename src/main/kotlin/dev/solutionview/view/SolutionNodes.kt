package dev.solutionview.view

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.FileStatusManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ThreeState
import dev.solutionview.ProjectState
import dev.solutionview.SolutionIcons
import dev.solutionview.SolutionService
import dev.solutionview.model.ChangeDepth
import dev.solutionview.model.ChangeDetector
import dev.solutionview.model.LoadSource
import dev.solutionview.model.ProjectContents
import dev.solutionview.model.Solution
import dev.solutionview.model.SolutionFolder
import dev.solutionview.model.SolutionItem
import dev.solutionview.model.SolutionProject
import dev.solutionview.toNioPathOrNull
import java.nio.file.Path

// Sort weights. The pane comparator sorts by weight first, then by name.
private const val WEIGHT_DEPENDENCIES = -100
private const val WEIGHT_FOLDER = -50
private const val WEIGHT_PROJECT = -40
private const val WEIGHT_MESSAGE = -200

private fun findFile(path: Path): VirtualFile? = LocalFileSystem.getInstance().findFileByNioFile(path)

private fun solutionService(project: Project) = SolutionService.getInstance(project)

private val CLEAN_STATUSES = setOf(FileStatus.NOT_CHANGED, FileStatus.SUPPRESSED, FileStatus.IGNORED)

private fun fileStatus(project: Project, path: Path): FileStatus =
    findFile(path)?.let { FileStatusManager.getInstance(project).getStatus(it) } ?: FileStatus.NOT_CHANGED

private fun statusOf(depth: ChangeDepth): FileStatus = when (depth) {
    ChangeDepth.NONE -> FileStatus.NOT_CHANGED
    ChangeDepth.IMMEDIATE -> FileStatus.NOT_CHANGED_IMMEDIATE
    ChangeDepth.NESTED -> FileStatus.NOT_CHANGED_RECURSIVE
}

private fun trackedDepth(state: ThreeState): ChangeDepth = when (state) {
    ThreeState.YES -> ChangeDepth.IMMEDIATE
    ThreeState.UNSURE -> ChangeDepth.NESTED
    ThreeState.NO -> ChangeDepth.NONE
}

private fun directoryStatus(project: Project, directory: VirtualFile): FileStatus {
    val own = FileStatusManager.getInstance(project).getStatus(directory)
    if (own !in CLEAN_STATUSES) return own
    val path = directory.toNioPathOrNull() ?: return FileStatus.NOT_CHANGED
    val changes = ChangeListManager.getInstance(project)
    val unversioned = changes.unversionedFilesPaths.mapNotNull { it.path.toNioPathOrNull() }
    return statusOf(ChangeDepth.ofDirectory(path, trackedDepth(changes.haveChangesUnder(directory)), unversioned))
}

private fun directoryStatus(project: Project, path: Path): FileStatus =
    findFile(path)?.let { directoryStatus(project, it) } ?: FileStatus.NOT_CHANGED

private fun itemsStatus(project: Project, files: List<Path>, children: List<SolutionItem>): FileStatus {
    val detector = ChangeDetector(
        isFileChanged = { fileStatus(project, it) !in CLEAN_STATUSES },
        isProjectChanged = { directoryStatus(project, it.directory) !in CLEAN_STATUSES },
    )
    return statusOf(detector.depthOf(files, children))
}

/** Invisible root. Its single child is the solution node, or a message when there is none. */
class SolutionRootNode(project: Project, settings: ViewSettings) :
    ProjectViewNode<Project>(project, project, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val ideProject = value
        val service = solutionService(ideProject)
        val solution = service.solution ?: return listOf(MessageNode(ideProject, settings, service.statusMessage))
        return listOf(SolutionNode(ideProject, solution, settings))
    }

    override fun contains(file: VirtualFile): Boolean = solutionService(value).containsFile(file)

    override fun update(presentation: PresentationData) {
        presentation.presentableText = value.name
    }
}

class SolutionNode(project: Project, solution: Solution, settings: ViewSettings) :
    ProjectViewNode<Solution>(project, solution, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> = itemNodes(project!!, value.items, settings)

    override fun contains(file: VirtualFile): Boolean = solutionService(project!!).containsFile(file)

    override fun getVirtualFile(): VirtualFile? = findFile(value.path)

    override fun getFileStatus(): FileStatus =
        fileStatus(project!!, value.path).takeUnless { it in CLEAN_STATUSES }
            ?: itemsStatus(project!!, emptyList(), value.items)

    override fun update(presentation: PresentationData) {
        val count = value.allProjects().size
        presentation.presentableText = "Solution '${value.name}'"
        presentation.addText("Solution '${value.name}'", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        presentation.addText("  ·  $count project${if (count == 1) "" else "s"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        presentation.setIcon(SolutionIcons.Solution)
        presentation.tooltip = value.path.toString()
    }

    override fun isAlwaysExpand(): Boolean = true
}

internal fun itemNodes(project: Project, items: List<SolutionItem>, settings: ViewSettings): List<AbstractTreeNode<*>> =
    items.map { item ->
        when (item) {
            is SolutionFolder -> SolutionFolderNode(project, item, settings)
            is SolutionProject -> ProjectNode(project, item, settings)
        }
    }

/** A solution folder: a virtual folder that exists only inside the solution file. */
class SolutionFolderNode(project: Project, folder: SolutionFolder, settings: ViewSettings) :
    ProjectViewNode<SolutionFolder>(project, folder, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val ideProject = project!!
        return itemNodes(ideProject, value.children, settings) +
            value.files.map { fileNode(ideProject, it, settings, excluded = false) }
    }

    override fun contains(file: VirtualFile): Boolean {
        val path = file.toNioPathOrNull() ?: return false
        return value.files.contains(path) || value.children.any { child ->
            child is SolutionProject && path.startsWith(child.directory)
        }
    }

    override fun getFileStatus(): FileStatus = itemsStatus(project!!, value.files, value.children)

    override fun update(presentation: PresentationData) {
        presentation.presentableText = value.name
        presentation.setIcon(AllIcons.Nodes.Folder)
    }

    override fun getWeight(): Int = WEIGHT_FOLDER
}

/** A project (.csproj, .fsproj, .vbproj) and the files MSBuild says belong to it. */
class ProjectNode(project: Project, solutionProject: SolutionProject, settings: ViewSettings) :
    ProjectViewNode<SolutionProject>(project, solutionProject, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val ideProject = project!!
        val state = solutionService(ideProject).projectState(value.path) as? ProjectState.Loaded ?: return emptyList()
        return listOf(DependenciesNode(ideProject, value, state.project, settings)) +
            folderChildren(ideProject, settings, value, state.contents, "")
    }

    override fun contains(file: VirtualFile): Boolean {
        val path = file.toNioPathOrNull() ?: return false
        return path.startsWith(value.directory) ||
            solutionService(project!!).contents(value.path)?.isIncluded(path) == true
    }

    override fun getVirtualFile(): VirtualFile? = findFile(value.path)

    override fun getFileStatus(): FileStatus = directoryStatus(project!!, value.directory)

    override fun update(presentation: PresentationData) {
        presentation.presentableText = value.name
        presentation.setIcon(SolutionIcons.forProject(value.kind))
        when (val state = solutionService(project!!).projectState(value.path)) {
            ProjectState.Loading, null -> presentation.locationString = "loading…"
            ProjectState.Missing -> {
                presentation.addText(value.name, SimpleTextAttributes.ERROR_ATTRIBUTES)
                presentation.addText("  (project file not found)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                presentation.tooltip = value.path.toString()
            }
            is ProjectState.Loaded -> {
                val loaded = state.project
                if (loaded.source == LoadSource.XML_FALLBACK) {
                    presentation.addText(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    presentation.addText("  (estimated: MSBuild did not load it)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    presentation.tooltip = loaded.error ?: value.path.toString()
                } else {
                    presentation.tooltip = value.path.toString()
                }
            }
        }
    }

    override fun isAlwaysShowPlus(): Boolean = true

    override fun shouldApply(): Boolean = true

    override fun getWeight(): Int = WEIGHT_PROJECT
}

/**
 * A folder inside a project. The value is the [PsiDirectory] when the folder exists on disk,
 * so New, Paste, Delete and the other file actions work on it. Folders that exist only
 * through `Link` metadata have the folder path as value.
 */
class ProjectFolderNode(
    project: Project,
    value: Any,
    settings: ViewSettings,
    private val owner: SolutionProject,
    private val relativeDir: String,
    private val excluded: Boolean,
) : ProjectViewNode<Any>(project, value, settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val ideProject = project!!
        val contents = solutionService(ideProject).contents(owner.path) ?: return emptyList()
        return folderChildren(ideProject, settings, owner, contents, relativeDir)
    }

    override fun contains(file: VirtualFile): Boolean {
        val dir = (value as? PsiDirectory)?.virtualFile ?: return false
        return VfsUtilCore.isAncestor(dir, file, false)
    }

    override fun getVirtualFile(): VirtualFile? = (value as? PsiDirectory)?.virtualFile

    override fun getFileStatus(): FileStatus =
        virtualFile?.let { directoryStatus(project!!, it) } ?: FileStatus.NOT_CHANGED

    override fun update(presentation: PresentationData) {
        val name = relativeDir.substringAfterLast('/')
        presentation.presentableText = name
        presentation.setIcon(AllIcons.Nodes.Folder)
        if (excluded) presentation.addText(name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }

    override fun canNavigate(): Boolean = false

    override fun shouldApply(): Boolean = true

    override fun getWeight(): Int = WEIGHT_FOLDER
}

/** Files that are on disk but not part of the project. Shown grayed, like Rider's "Show All Files". */
class ExcludedFileNode(project: Project, file: com.intellij.psi.PsiFile, settings: ViewSettings) :
    PsiFileNode(project, file, settings) {
    override fun updateImpl(data: PresentationData) {
        super.updateImpl(data)
        data.forcedTextForeground = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
    }
}

/** Shown in place of a file the solution or project points to but that does not exist. */
class MissingFileNode(project: Project, path: Path, settings: ViewSettings) :
    ProjectViewNode<Path>(project, path, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun contains(file: VirtualFile): Boolean = false
    override fun update(presentation: PresentationData) {
        presentation.presentableText = value.fileName.toString()
        presentation.addText(value.fileName.toString(), SimpleTextAttributes.ERROR_ATTRIBUTES)
        presentation.addText("  (missing)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        presentation.setIcon(AllIcons.General.Warning)
        presentation.tooltip = value.toString()
    }
}

/** A line of text in place of the tree, for example when no solution file was found. */
class MessageNode(project: Project, settings: ViewSettings, private val message: String) :
    ProjectViewNode<String>(project, "message:$message", settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun contains(file: VirtualFile): Boolean = false
    override fun update(presentation: PresentationData) {
        presentation.presentableText = message
        presentation.addText(message, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        presentation.setIcon(AllIcons.General.Information)
    }
    override fun getWeight(): Int = WEIGHT_MESSAGE
}

internal fun fileNode(project: Project, path: Path, settings: ViewSettings, excluded: Boolean): AbstractTreeNode<*> {
    val virtualFile = findFile(path) ?: return MissingFileNode(project, path, settings)
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return MissingFileNode(project, path, settings)
    return if (excluded) ExcludedFileNode(project, psiFile, settings) else PsiFileNode(project, psiFile, settings)
}

/**
 * The children of one folder of a project. Normally these are the folders and files MSBuild
 * lists. With "Show All Files" on, everything on disk shows, and files outside the project
 * appear grayed.
 */
internal fun folderChildren(
    project: Project,
    settings: ViewSettings,
    owner: SolutionProject,
    contents: ProjectContents,
    relativeDir: String,
): List<AbstractTreeNode<*>> {
    val dirPath = if (relativeDir.isEmpty()) owner.directory else owner.directory.resolve(relativeDir)
    val psiManager = PsiManager.getInstance(project)
    val result = mutableListOf<AbstractTreeNode<*>>()
    val shown = HashSet<Path>()

    fun folderNode(name: String, excluded: Boolean): AbstractTreeNode<*> {
        val childRel = ProjectContents.child(relativeDir, name)
        val psiDir = findFile(dirPath.resolve(name))?.takeIf { it.isDirectory }?.let(psiManager::findDirectory)
        return ProjectFolderNode(project, psiDir ?: "${owner.path}|$childRel", settings, owner, childRel, excluded)
    }

    if (solutionService(project).showAllFiles) {
        val dir = findFile(dirPath)
        dir?.children?.forEach { child ->
            val childPath = child.toNioPathOrNull() ?: return@forEach
            if (child.isDirectory) {
                result += folderNode(child.name, excluded = !contents.hasDirectory(ProjectContents.child(relativeDir, child.name)))
            } else if (childPath != owner.path) {
                result += fileNode(project, childPath, settings, excluded = !contents.isIncluded(childPath))
            }
            shown.add(childPath)
        }
    }

    for (name in contents.childDirectories(relativeDir)) {
        if (dirPath.resolve(name) !in shown) result += folderNode(name, excluded = false)
    }
    for (file in contents.files(relativeDir)) {
        if (file !in shown) result += fileNode(project, file, settings, excluded = false)
    }
    return result
}

/** Rider's "Dependencies" node: target frameworks, NuGet packages and project references. */
class DependenciesNode(
    project: Project,
    private val owner: SolutionProject,
    private val loaded: dev.solutionview.model.MsBuildProject,
    settings: ViewSettings,
) : ProjectViewNode<String>(project, "dependencies|${owner.path}", settings) {

    override fun getChildren(): Collection<AbstractTreeNode<*>> {
        val ideProject = project!!
        val groups = mutableListOf<AbstractTreeNode<*>>()
        if (loaded.targetFrameworks.isNotEmpty()) {
            groups += GroupNode(ideProject, settings, "${owner.path}|frameworks", "Frameworks", AllIcons.Nodes.PpJdk) {
                loaded.targetFrameworks.map { tfm ->
                    LeafNode(ideProject, settings, "${owner.path}|tfm|$tfm", tfm, null, AllIcons.Nodes.PpJdk)
                }
            }
        }
        if (loaded.packages.isNotEmpty()) {
            groups += GroupNode(ideProject, settings, "${owner.path}|packages", "Packages", AllIcons.Nodes.PpLibFolder) {
                loaded.packages.map { pkg ->
                    LeafNode(ideProject, settings, "${owner.path}|pkg|${pkg.name}", pkg.name, pkg.version, AllIcons.Nodes.PpLib)
                }
            }
        }
        if (loaded.projectReferences.isNotEmpty()) {
            groups += GroupNode(ideProject, settings, "${owner.path}|projects", "Projects", AllIcons.Nodes.ModuleGroup) {
                loaded.projectReferences.map { ref ->
                    val kind = dev.solutionview.model.ProjectKind.of(ref)
                    LeafNode(
                        ideProject, settings, "${owner.path}|ref|$ref",
                        ref.fileName.toString().substringBeforeLast('.'), null, SolutionIcons.forProject(kind),
                    )
                }
            }
        }
        return groups
    }

    override fun contains(file: VirtualFile): Boolean = false

    override fun update(presentation: PresentationData) {
        presentation.presentableText = "Dependencies"
        presentation.setIcon(AllIcons.Nodes.PpLibFolder)
    }

    override fun getWeight(): Int = WEIGHT_DEPENDENCIES
}

private class GroupNode(
    project: Project,
    settings: ViewSettings,
    key: String,
    private val title: String,
    private val icon: javax.swing.Icon,
    private val children: () -> List<AbstractTreeNode<*>>,
) : ProjectViewNode<String>(project, key, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = children()
    override fun contains(file: VirtualFile): Boolean = false
    override fun update(presentation: PresentationData) {
        presentation.presentableText = title
        presentation.setIcon(icon)
    }
}

private class LeafNode(
    project: Project,
    settings: ViewSettings,
    key: String,
    private val title: String,
    private val detail: String?,
    private val icon: javax.swing.Icon,
) : ProjectViewNode<String>(project, key, settings) {
    override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
    override fun contains(file: VirtualFile): Boolean = false
    override fun isAlwaysLeaf(): Boolean = true
    override fun update(presentation: PresentationData) {
        presentation.presentableText = title
        if (detail != null) presentation.locationString = detail
        presentation.setIcon(icon)
    }
}
