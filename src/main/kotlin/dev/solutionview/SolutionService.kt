package dev.solutionview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.messages.Topic
import dev.solutionview.model.MsBuildEvaluator
import dev.solutionview.model.MsBuildProject
import dev.solutionview.model.OutsideRoots
import dev.solutionview.model.ProjectContents
import dev.solutionview.model.ProjectItem
import dev.solutionview.model.Solution
import dev.solutionview.model.SolutionParser
import dev.solutionview.model.SolutionProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

fun interface SolutionListener {
    fun solutionChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<SolutionListener> = Topic.create("Solution View changes", SolutionListener::class.java)
    }
}

/** What we know about one project at the moment. */
sealed interface ProjectState {
    data object Loading : ProjectState
    data class Loaded(val project: MsBuildProject, val contents: ProjectContents) : ProjectState
    data object Missing : ProjectState
}

/**
 * Holds the open solution and the MSBuild data of its projects, and keeps both up to date
 * when files change on disk.
 */
@Service(Service.Level.PROJECT)
@State(name = "SolutionView", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SolutionService(
    private val project: Project,
    private val scope: CoroutineScope,
) : PersistentStateComponent<SolutionService.PersistedState>, Disposable {

    class PersistedState {
        var solutionPath: String? = null
        var showAllFiles: Boolean = false
        var solutionPaneShown: Boolean = false
        var addedContentRoots: MutableList<String> = mutableListOf()
    }

    private var persisted = PersistedState()
    private val states = ConcurrentHashMap<Path, ProjectState>()
    private val pendingProjects = ConcurrentHashMap.newKeySet<Path>()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val reevaluate = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var loadJob: Job? = null

    @Volatile
    var solution: Solution? = null
        private set

    @Volatile
    var statusMessage: String = "Looking for a solution…"
        private set

    init {
        startFlows()
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onFileEvents(events)
        })
    }

    override fun getState(): PersistedState = persisted

    override fun loadState(state: PersistedState) {
        persisted = state
    }

    override fun dispose() = Unit

    var showAllFiles: Boolean
        get() = persisted.showAllFiles
        set(value) {
            persisted.showAllFiles = value
            changed.tryEmit(Unit)
        }

    fun projectState(projectPath: Path): ProjectState? = states[projectPath]

    fun contents(projectPath: Path): ProjectContents? = (states[projectPath] as? ProjectState.Loaded)?.contents

    /** Solution files in the project folder and one level below it. */
    fun findSolutionFiles(): List<Path> {
        val base = baseDir() ?: return emptyList()
        fun list(dir: Path): List<Path> = try {
            Files.list(dir).use { s -> s.filter { SolutionParser.isSolutionFile(it.name) && Files.isRegularFile(it) }.toList() }
        } catch (e: Exception) {
            emptyList()
        }
        val top = list(base)
        if (top.isNotEmpty()) return top.sorted()
        val subdirs = try {
            Files.list(base).use { s -> s.filter { it.isDirectory() && !it.name.startsWith(".") }.toList() }
        } catch (e: Exception) {
            emptyList()
        }
        return subdirs.flatMap(::list).sorted()
    }

    fun selectSolution(path: Path) {
        persisted.solutionPath = path.toString()
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.IO) { loadSolution() }
    }

    fun reloadProject(projectPath: Path) {
        pendingProjects.add(projectPath)
        reevaluate.tryEmit(Unit)
    }

    /** The solution project whose folder holds [file], picking the deepest one. */
    fun projectFor(file: Path): SolutionProject? =
        solution?.allProjects()
            ?.filter { file.startsWith(it.directory) || contents(it.path)?.isIncluded(file) == true }
            ?.maxByOrNull { it.directory.nameCount }

    fun containsFile(file: VirtualFile): Boolean {
        val current = solution ?: return false
        val path = file.toNioPathOrNull() ?: return false
        return path.startsWith(current.directory) || projectFor(path) != null
    }

    /** Called once per project open by [SolutionStartupActivity]. */
    suspend fun initialize() {
        val base = baseDir()
        val requested = base?.let { PendingSolutions.take(it) }
        if (requested != null) {
            persisted.solutionPath = requested.toString()
            persisted.solutionPaneShown = false
        }
        val projects = readSolution()
        // Show the tree right away. The projects fill in while MSBuild evaluates them.
        if (projects != null) {
            loadJob?.cancel()
            loadJob = scope.launch(Dispatchers.IO) { evaluate(projects) }
        }
        if (solution != null && !persisted.solutionPaneShown) {
            persisted.solutionPaneShown = true
            withContext(Dispatchers.EDT) {
                if (!project.isDisposed) {
                    com.intellij.ide.projectView.ProjectView.getInstance(project)
                        .changeView(dev.solutionview.view.SolutionViewPane.ID)
                }
            }
        }
    }

    // ---- loading ---------------------------------------------------------------------------

    private suspend fun loadSolution() {
        val projects = readSolution() ?: return
        evaluate(projects)
    }

    /** Parses the solution file and returns its project paths, or null when there is no solution. */
    private fun readSolution(): List<Path>? {
        val path = chooseSolutionFile()
        if (path == null) {
            solution = null
            states.clear()
            statusMessage = "No .sln or .slnx file found in ${baseDir() ?: "the project folder"}"
            changed.tryEmit(Unit)
            syncContentRoots()
            return null
        }
        val parsed = try {
            SolutionParser.parse(path)
        } catch (e: Exception) {
            thisLogger().warn("Could not read $path", e)
            solution = null
            statusMessage = "Could not read ${path.name}: ${e.message}"
            changed.tryEmit(Unit)
            return null
        }
        val projects = parsed.allProjects().map { it.path }
        states.keys.retainAll(projects.toSet())
        projects.forEach { states.putIfAbsent(it, ProjectState.Loading) }
        solution = parsed
        changed.tryEmit(Unit)
        thisLogger().info("Opened solution ${path.name} with ${projects.size} project(s)")
        return projects
    }

    private fun chooseSolutionFile(): Path? {
        persisted.solutionPath?.let { saved ->
            val path = Path.of(saved)
            if (Files.isRegularFile(path)) return path
        }
        val found = findSolutionFiles()
        // Prefer the solution named like the folder, as Rider does when you open a folder.
        val base = baseDir()
        val chosen = found.firstOrNull { base != null && it.name.substringBeforeLast('.') == base.name } ?: found.firstOrNull()
        persisted.solutionPath = chosen?.toString()
        return chosen
    }

    private suspend fun evaluate(projectPaths: Collection<Path>) {
        evaluateAll(projectPaths)
        syncContentRoots()
        // Now that we know which projects are executables, offer a run configuration for each.
        withContext(Dispatchers.EDT) {
            dev.solutionview.run.DotnetRunConfigurations.ensureForExecutables(project)
        }
    }

    @Synchronized
    private fun syncContentRoots() {
        val base = baseDir() ?: return
        val roots = solution?.let { current ->
            OutsideRoots.of(current, base) { contents(it.path)?.project?.items.orEmpty().map(ProjectItem::path) }
        }.orEmpty()
        persisted.addedContentRoots =
            SolutionContentRoots.sync(project, roots, persisted.addedContentRoots).toMutableList()
    }

    private suspend fun evaluateAll(projectPaths: Collection<Path>) = coroutineScope {
        val evaluator = MsBuildEvaluator(DotnetCli.path(), DotnetCli.runner)
        // Each evaluation starts one MSBuild process. Four at a time keeps the machine responsive.
        val permits = Semaphore(4)
        for (path in projectPaths) {
            launch(Dispatchers.IO) {
                permits.withPermit {
                    val state = if (Files.isRegularFile(path)) {
                        val result = evaluator.evaluate(path)
                        ProjectState.Loaded(result, ProjectContents(result))
                    } else {
                        ProjectState.Missing
                    }
                    states[path] = state
                    if (state is ProjectState.Loaded) {
                        val p = state.project
                        LOG.info(
                            "Loaded ${path.fileName} via ${p.source}: ${p.items.size} item(s), " +
                                "${p.packages.size} package(s)" + (p.error?.let { " ($it)" } ?: ""),
                        )
                    }
                    changed.tryEmit(Unit)
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun startFlows() {
        scope.launch {
            changed.debounce(100).collect {
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) project.messageBus.syncPublisher(SolutionListener.TOPIC).solutionChanged()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            reevaluate.debounce(500).collect {
                val batch = pendingProjects.toList()
                pendingProjects.removeAll(batch.toSet())
                val known = solution?.allProjects()?.map { it.path }?.toSet().orEmpty()
                evaluate(batch.filter { it in known })
            }
        }
    }

    // ---- file changes ----------------------------------------------------------------------

    private fun onFileEvents(events: List<VFileEvent>) {
        val current = solution
        var reloadSolution = false
        var allProjects = false
        val affected = HashSet<Path>()

        for (event in events) {
            val path = event.path.toNioPathOrNull() ?: continue
            val name = path.name
            if (current != null && path == current.path) {
                reloadSolution = true
                continue
            }
            if (current == null && SolutionParser.isSolutionFile(name)) {
                reloadSolution = true
                continue
            }
            if (current == null) continue
            if (name.startsWith("Directory.") && (name.endsWith(".props") || name.endsWith(".targets"))) {
                allProjects = true
                continue
            }
            if (path.extension.endsWith("proj")) {
                affected.add(path)
                continue
            }
            // Editing a file does not change which files a project has. Adding, removing,
            // renaming or moving one can, because SDK projects pick files up through globs.
            if (event is VFileContentChangeEvent) continue
            if (event is VFilePropertyChangeEvent && !event.isRename) continue
            if (isBuildOutput(path)) continue
            projectFor(path)?.let { affected.add(it.path) }
            if (event is VFileMoveEvent) {
                event.oldPath.toNioPathOrNull()?.let { old -> projectFor(old)?.let { affected.add(it.path) } }
            }
        }

        when {
            reloadSolution -> reload()
            allProjects -> current?.allProjects()?.forEach { reloadProject(it.path) }
            else -> affected.forEach(::reloadProject)
        }
    }

    private fun isBuildOutput(path: Path): Boolean =
        (0 until path.nameCount).any { path.getName(it).toString() in BUILD_OUTPUT_DIRS }

    private fun baseDir(): Path? = project.guessProjectDir()?.toNioPathOrNull()

    companion object {
        private val LOG = logger<SolutionService>()

        fun getInstance(project: Project): SolutionService = project.service()
    }
}

/** Remembers which solution file the user picked in File > Open until the project has opened. */
object PendingSolutions {
    private val pending = ConcurrentHashMap<Path, Path>()

    fun put(directory: Path, solution: Path) {
        pending[directory.normalize()] = solution
    }

    fun take(directory: Path): Path? = pending.remove(directory.normalize())
}

internal fun VirtualFile.toNioPathOrNull(): Path? = try {
    if (isInLocalFileSystem) toNioPath() else null
} catch (e: Exception) {
    null
}

internal fun String.toNioPathOrNull(): Path? = try {
    Path.of(this)
} catch (e: Exception) {
    null
}
