package org.enso.runtimeversionmanager.components

import java.nio.file.{Files, Path}

import nl.gn0s1s.bump.SemVer
import org.enso.cli.OS
import org.enso.distribution.FileSystem
import org.enso.distribution.FileSystem.PathSyntax
import org.enso.runtimeversionmanager.config.GlobalRunnerConfigurationManager
import org.enso.runtimeversionmanager.releases.ReleaseNotFound
import org.enso.runtimeversionmanager.test.{
  OverrideTestVersionSuite,
  RuntimeVersionManagerTest,
  TestRuntimeVersionManagementUserInterface
}
import org.enso.runtimeversionmanager.components
import org.enso.testkit.OsSpec

class RuntimeVersionManagerSpec
    extends RuntimeVersionManagerTest
    with OsSpec
    with OverrideTestVersionSuite {

  override val testVersion: SemVer = SemVer(0, 0, 1)

  "RuntimeVersionManager" should {
    "find the latest engine version in semver ordering " +
    "(skipping broken releases)" in {
      val componentsManager = makeRuntimeVersionManager()
      componentsManager.fetchLatestEngineVersion() shouldEqual SemVer(0, 1, 0)
    }

    "install the engine and a matching runtime for it" in {
      val (distributionManager, componentsManager, _) = makeManagers()

      val version = SemVer(0, 0, 1)
      val engine  = componentsManager.findOrInstallEngine(SemVer(0, 0, 1))

      engine.version shouldEqual version
      assert(
        engine.path.startsWith(distributionManager.paths.engines),
        "Engine should be installed in the engines directory."
      )

      val runtime = componentsManager.findGraalRuntime(engine)
      runtime.value.version shouldEqual GraalVMVersion("23.2.0", "21.0.0")
      assert(
        runtime.value.path.startsWith(distributionManager.paths.runtimes),
        "Engine should be installed in the engines directory."
      )
    }

    "list installed engines and runtimes" in {
      val componentsManager = makeRuntimeVersionManager()
      val engineVersions =
        Set(SemVer(0, 0, 0), SemVer(0, 0, 1), SemVer(0, 1, 0))
      val runtimeVersions =
        Set(
          components.GraalVMVersion("1.0.0", "11"),
          components.GraalVMVersion("23.2.0", "21.0.0")
        )
      engineVersions.map(componentsManager.findOrInstallEngine)

      componentsManager
        .listInstalledEngines()
        .map(_.version)
        .toSet shouldEqual engineVersions
      componentsManager
        .listInstalledGraalRuntimes()
        .map(_.version)
        .toSet shouldEqual runtimeVersions

      val runtime1 =
        componentsManager
          .findGraalRuntime(components.GraalVMVersion("1.0.0", "11"))
          .value
      componentsManager.findEnginesUsingRuntime(runtime1) should have length 1

      val runtime2 =
        componentsManager
          .findGraalRuntime(components.GraalVMVersion("23.2.0", "21.0.0"))
          .value
      componentsManager.findEnginesUsingRuntime(runtime2) should have length 2
    }

    "preserve the broken mark when installing a broken release" in {
      val componentsManager = makeManagers(userInterface =
        new TestRuntimeVersionManagementUserInterface(installBroken = true)
      )._2
      val brokenVersion = SemVer(0, 9999, 0, Some("broken"))
      componentsManager.findOrInstallEngine(brokenVersion)

      assert(
        componentsManager.findEngine(brokenVersion).value.isMarkedBroken,
        "The broken release should still be marked as broken after being " +
        "installed and loaded."
      )
    }

    "skip broken releases when finding latest installed version" in {
      val (distributionManager, componentsManager, _) =
        makeManagers(userInterface =
          new TestRuntimeVersionManagementUserInterface(installBroken = true)
        )
      val configurationManager =
        new GlobalRunnerConfigurationManager(
          componentsManager,
          distributionManager
        )

      val validVersion          = SemVer(0, 0, 1)
      val newerButBrokenVersion = SemVer(0, 9999, 0, Some("broken"))
      componentsManager.findOrInstallEngine(validVersion)
      componentsManager.findOrInstallEngine(newerButBrokenVersion)

      configurationManager.defaultVersion shouldEqual validVersion
    }

    "issue a warning when a broken release is requested" in {
      val userInterface =
        new TestRuntimeVersionManagementUserInterface(installBroken = true)
      val componentsManager = makeManagers(userInterface = userInterface)._2

      val brokenVersion = SemVer(0, 9999, 0, Some("broken"))
      componentsManager.findOrInstallEngine(brokenVersion)
      assert(
        userInterface.wasAskedToInstallBroken,
        "User interface should have been queried if broken versions are allowed."
      )
      componentsManager.findEngine(brokenVersion).value
    }

    "issue a context-specific error when a nightly release cannot be found" in {
      val componentsManager = makeManagers()._2
      val nightlyVersion    = SemVer(0, 0, 0, Some("SNAPSHOT.2000-01-01"))
      val exception = intercept[ReleaseNotFound] {
        componentsManager.findOrInstallEngine(nightlyVersion)
      }
      exception.getMessage should include("Nightly releases expire")
    }

    "uninstall the runtime iff it is not used by any engines" in {
      val componentsManager = makeRuntimeVersionManager()
      val engineVersions =
        Seq(SemVer(0, 0, 0), SemVer(0, 0, 1), SemVer(0, 1, 0))
      engineVersions.map(componentsManager.findOrInstallEngine)

      componentsManager.listInstalledEngines() should have length 3
      componentsManager.listInstalledGraalRuntimes() should have length 2

      // remove the engine that shares the runtime with another one
      val version1 = SemVer(0, 1, 0)
      componentsManager.uninstallEngine(version1)
      val engines1 = componentsManager.listInstalledEngines()
      engines1 should have length 2
      engines1.map(_.version) should not contain version1
      componentsManager.listInstalledGraalRuntimes() should have length 2

      // remove the second engine that shared the runtime
      val version2 = SemVer(0, 0, 1)
      componentsManager.uninstallEngine(version2)
      val engines2 = componentsManager.listInstalledEngines()
      engines2 should have length 1
      engines2.map(_.version) should not contain version2
      val runtimes2 = componentsManager.listInstalledGraalRuntimes()
      runtimes2 should have length 1
      runtimes2.map(_.version).head shouldEqual components.GraalVMVersion(
        "1.0.0",
        "11"
      )

      // remove the last engine
      componentsManager.uninstallEngine(SemVer(0, 0, 0))
      componentsManager.listInstalledEngines() should have length 0
      componentsManager.listInstalledGraalRuntimes() should have length 0
    }

    "correctly handle version depending on installer type" in {
      val projectManager =
        makeManagers(installerKind = InstallerKind.ProjectManager)._2
      val launcher =
        makeManagers(installerKind = InstallerKind.Launcher)._2

      val engineWithDifferentVersionRequirements = SemVer(0, 1, 0)

      val manifest =
        launcher
          .findOrInstallEngine(engineWithDifferentVersionRequirements)
          .manifest

      val usualVersion = SemVer(0, 0, 0, Some("dev"))
      val bigVersion   = SemVer(9999, 0, 0)
      manifest.requiredInstallerVersions.launcher shouldEqual usualVersion
      manifest.requiredInstallerVersions.projectManager shouldEqual bigVersion

      manifest.minimumRequiredVersion(installerKind =
        InstallerKind.Launcher
      ) shouldEqual usualVersion
      manifest.minimumRequiredVersion(installerKind =
        InstallerKind.ProjectManager
      ) shouldEqual bigVersion

      val upgradeException = intercept[UpgradeRequiredError] {
        projectManager.findOrInstallEngine(
          engineWithDifferentVersionRequirements
        )
      }
      upgradeException.expectedVersion shouldEqual bigVersion
    }

    "support bundled components" in {
      val engineVersion  = SemVer(0, 1, 0)
      val runtimeVersion = GraalVMVersion("1.0.0", "11")
      prepareBundle(
        engineVersion  = engineVersion,
        runtimeVersion = runtimeVersion
      )
      val manager = makeRuntimeVersionManager()

      val engine = manager.findEngine(engineVersion).value
      engine.version shouldEqual engineVersion
      engine.isMarkedBroken shouldEqual false
      engine.ensureValid()

      manager.findGraalRuntime(engine).value.version shouldEqual runtimeVersion
      manager.findGraalRuntime(runtimeVersion).value.ensureValid()
    }

    "fail to uninstall a read-only bundled component" taggedAs OsUnix in {
      val engineVersion  = SemVer(0, 1, 0)
      val runtimeVersion = GraalVMVersion("1.0.0", "11")
      prepareBundle(
        engineVersion  = engineVersion,
        runtimeVersion = runtimeVersion
      )
      val manager          = makeRuntimeVersionManager()
      def installedEngines = manager.listInstalledEngines().map(_.version)
      def installedRuntimes =
        manager.listInstalledGraalRuntimes().map(_.version)

      val enginePath = getTestDirectory / "dist" / "0.1.0"
      val runtimePath =
        getTestDirectory / "runtime" / "graalvm-ce-java11-1.0.0"

      enginePath.toFile.setWritable(false)
      try {
        intercept[UninstallationError] {
          manager.uninstallEngine(engineVersion)
        }

        installedEngines shouldEqual Seq(engineVersion)
        installedRuntimes shouldEqual Seq(runtimeVersion)
      } finally {
        enginePath.toFile.setWritable(true)
      }

      runtimePath.toFile.setWritable(false)
      try {
        manager.uninstallEngine(engineVersion)

        installedEngines shouldEqual Seq()
        installedRuntimes shouldEqual Seq(runtimeVersion)

        manager.cleanupRuntimes()
        installedRuntimes shouldEqual Seq(runtimeVersion)
      } finally {
        runtimePath.toFile.setWritable(true)
      }

      manager.cleanupRuntimes()
      installedRuntimes shouldEqual Seq()
    }

    "include both bundled and installed components in list" in {
      prepareBundle(
        engineVersion  = SemVer(0, 0, 1),
        runtimeVersion = GraalVMVersion("23.2.0", "21.0.0")
      )
      val manager = makeRuntimeVersionManager()
      manager.findOrInstallEngine(SemVer(0, 0, 1).withPreRelease("pre"))

      manager
        .listInstalledEngines()
        .map(_.version) should contain theSameElementsAs Seq(
        SemVer(0, 0, 1),
        SemVer(0, 0, 1).withPreRelease("pre")
      )

      val runtimeVersions = manager.listInstalledGraalRuntimes().map(_.version)
      runtimeVersions.map(_.graalVersion) should contain theSameElementsAs Seq(
        "23.2.0",
        "2.0.0"
      )
      runtimeVersions.map(_.javaVersion).toSet shouldEqual Set("21.0.0", "11")
    }

    "cope with semantic versioning of Java" in {
      val engineVersion = SemVer(0, 0, 3)
      val graalVersion  = GraalVMVersion("23.0.0", "17.0.7")
      prepareBundle(
        engineVersion  = engineVersion,
        runtimeVersion = graalVersion
      )
      val manager = makeRuntimeVersionManager()
      val engine  = manager.findEngine(engineVersion).value
      engine.version shouldEqual engineVersion
      engine.ensureValid()

      manager.findGraalRuntime(engine).value.version shouldEqual graalVersion
      manager.findGraalRuntime(graalVersion).value.ensureValid()
    }
  }

  private def prepareBundle(
    engineVersion: SemVer,
    runtimeVersion: GraalVMVersion
  ): Unit = {
    FileSystem.writeTextFile(
      getTestDirectory / ".enso.bundle",
      "Enso Bundle Marker"
    )
    fakeInstallEngine(getTestDirectory / "dist", engineVersion, runtimeVersion)
    fakeInstallRuntime(getTestDirectory / "runtime", runtimeVersion)
  }

  private def fakeInstallEngine(
    searchPath: Path,
    engineVersion: SemVer,
    runtimeVersion: GraalVMVersion
  ): Unit = {
    val manifest = s"""minimum-launcher-version: 0.0.0-dev
                      |minimum-project-manager-version: 0.0.0-dev
                      |graal-vm-version: ${runtimeVersion.graalVersion}
                      |graal-java-version: ${runtimeVersion.javaVersion}""".stripMargin
    val root     = searchPath / engineVersion.toString
    Files.createDirectories(root)
    FileSystem.writeTextFile(root / "manifest.yaml", manifest)
    val components = root / "component"
    Files.createDirectories(components)
    makePlaceholder(components / "runner.jar")
    FileSystem.writeTextFile(components / "runtime.jar", "placeholder")
  }

  private def fakeInstallRuntime(
    searchPath: Path,
    version: GraalVMVersion
  ): Unit = {
    val root =
      searchPath / s"graalvm-ce-java${version.javaVersion}-${version.graalVersion}"
    val bin =
      if (OS.operatingSystem == OS.MacOS) root / "Contents" / "Home" / "bin"
      else root / "bin"
    Files.createDirectories(bin)
    val executable = if (OS.isWindows) "java.exe" else "java"
    makePlaceholder(bin / executable)
  }

  private def makePlaceholder(path: Path): Unit = {
    FileSystem.writeTextFile(path, "placeholder")
    path.toFile.setExecutable(true)
  }
}
