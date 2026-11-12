# Solution View

A plugin for [Rebased](https://github.com/detachhead/rebased) (and other IntelliJ-platform IDEs, build 261+)
that opens .NET solutions in a Rider-style **Solution** tab and builds them with the `dotnet` CLI.
It works on Windows, macOS and Linux.

## What it does

- Adds a **Solution** tab to the Project tool window. The normal **Project** file tree stays.
- Reads `.sln` and `.slnx` files: solution folders, solution items and projects.
- Asks MSBuild which files belong to each project (`dotnet msbuild -getItem`), so SDK globs,
  `Compile Remove`, linked files and imported `.props` files show correctly.
  Multi-targeted projects show the files of their last target framework, as Rider shows one framework.
  If `dotnet` is missing or MSBuild fails (for example Windows-only or C++ projects on macOS), it reads the
  project XML and marks the project "estimated". That reader handles SDK-style, old-style and shared projects.
- Projects, linked files and solution items outside the solution folder become extra content roots
  (with `bin` and `obj` excluded), so Search Everywhere (Double Shift), Go to File and Find in Files find them.
- Git works when the repository root is above the solution folder (for example a worktree): the plugin
  registers the root, so the Commit, Log and Pull Requests tool windows appear.
- Git colors in the tree: files show their status, and projects, folders and solution folders that
  contain modified, deleted or untracked files show the "changed descendants" color.
- **Dependencies** node per project: target frameworks, NuGet packages, project references.
- **Show All Files** toggle (files outside the project show grayed), file nesting
  (`Foo.xaml.cs`, `appsettings.Development.json`, `Foo.Designer.cs`, ...).
- File > Open accepts a `.sln` or `.slnx` file directly.
- **Build / Rebuild / Clean** for the solution (right-click the solution node, Build menu, main toolbar,
  Ctrl+F9 or Cmd+F9) or for one project (right-click the project). Errors and warnings appear per project in the Build tool window and link to the code.
- Updates the tree when you add, delete, rename or move files, or edit a project file.
- **Run configurations**: one ".NET Project" configuration (`dotnet run`) for every executable project,
  created when the solution loads, with the solution's main project selected as startup project.
  Right-click a project for **Run 'App'**. Stack traces and compiler errors in the console are clickable.

## Requirements

- .NET SDK 8 or later on the machine (`dotnet` on PATH, in `DOTNET_ROOT`, or in the default install folder).
- To build the plugin: nothing else. Gradle downloads a Java 25 toolchain if you have none.

## Build

```bash
./gradlew buildPlugin
```

On Windows use `gradlew.bat buildPlugin`. The plugin zip is in `build/distributions/`.

The build compiles against a local Rebased install. Set its path if it is not in the default place:

| OS | Default path |
|-|-|
| macOS | `/Applications/Rebased.app` |
| Windows | `%LOCALAPPDATA%\Programs\Rebased` |
| Linux | `~/Applications/Rebased` (the `usr` folder of an extracted AppImage, or the unpacked tar.gz) |

```bash
./gradlew buildPlugin -PrebasedPath=/path/to/Rebased
```

You can also set the `REBASED_PATH` environment variable.

## Install

Add the plugin repository once, and Rebased installs and updates the plugin like any other:

1. **Settings > Plugins > gear icon > Manage Plugin Repositories…**
2. Add `https://github.com/denisangel2k2/solution-view/releases/latest/download/updatePlugins.xml`
3. In the **Marketplace** tab, search for **Solution View** and install it.

Or download the zip from [Releases](https://github.com/denisangel2k2/solution-view/releases) and use
**Install Plugin from Disk…** in the same gear menu.

## Release

Push a tag such as `v0.2.0`. GitHub Actions compiles against the Rebased version set in
`.github/workflows/release.yml`, runs the tests, and publishes the zip and `updatePlugins.xml` to a GitHub release.
When Rebased moves to a new platform version, bump `REBASED_VERSION` there first and check that CI passes.

## Tests

```bash
./gradlew unitTest
```

To check the plugin against your own repository without the IDE (read-only: MSBuild only evaluates projects):

```bash
SOLUTION_VIEW_SMOKE_DIR=~/dev/MyRepo ./gradlew unitTest --tests '*RealSolutionsSmokeTest*'
```

The report goes to `build/smoke-report.txt`: per solution, which projects loaded through MSBuild, which fell
back to the XML reader and why, and which project files are missing.

The standard `test` task is off: it checks the target as a full JetBrains IDE first, and Rebased
renames its core plugin descriptor, so that check fails. One test runs the real `dotnet msbuild`
and is skipped when `dotnet` is not installed.

## Not done yet

- NuGet manager, debugger, code intelligence.
- A unit test runner. Rebased 1.1.23 (platform 262) removed the platform's test runner UI, so use
  `dotnet test` in the terminal or let your agent run tests.
- The build event constructors used in `DotnetBuildService` are deprecated in 2026.1 and should move to
  the new builder API.
