import sbt.io.Path.relativeTo
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
import sbt.Keys.libraryDependencies

lazy val scala3Version  = "3.8.1"
lazy val upickleVersion = "4.4.2"
ThisBuild / organization := "anseliq"
ThisBuild / licenses     := List(License.Apache2)
ThisBuild / homepage     := Some(url("https://github.com/eweise/anseliq"))
ThisBuild / scalaVersion := scala3Version
// The version comes from git (sbt-dynver): 1.2.3 on the commit tagged v1.2.3, and something like
// 1.2.3-4-abcd1234 after it. "-" rather than dynver's default "+", which a Docker tag can't contain.
ThisBuild / dynverSeparator := "-"

lazy val root = project.in(file("."))

/**
 * Frontend subproject
 */
lazy val devServer   = taskKey[Unit]("Start both ~fastOptJS and vite dev server concurrently")
lazy val publishDist = taskKey[Unit]("Build a static web artifact")
lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin, DockerPlugin)
  .settings(
    name := "frontend",
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true,
    // Source maps seem to be broken with bundler
    Compile / fastOptJS / scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    Compile / fullOptJS / scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    // Inside frontend/, not sbt 2's target/out, so Vite resolves the bare imports in the linked JS
    // (e.g. chart.js) from frontend/node_modules, which only covers what's under frontend/.
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "target" / "frontend-fastopt",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "target" / "frontend-opt",
    libraryDependencies ++= Seq(
      "com.raquo"                  %% "laminar"    % Dependencies.Laminar,
      "io.frontroute"              %% "frontroute" % Dependencies.Frontroute,
      "com.lihaoyi"                %% "upickle"    % upickleVersion,
      "com.softwaremill.quicklens" %% "quicklens"  % Dependencies.Quicklens
    )
  )
  .settings(
    dockerBaseImage       := "nginx:stable-perl",
    Docker / publish      := (Docker / publish).dependsOn(Compile / fullLinkJS).value,
    Docker / publishLocal := (Docker / publishLocal).dependsOn(Compile / fullLinkJS).value,
    dockerExposedPorts    := Seq(80),
    dockerRepository      := Some(DockerSettings.repository),
    dockerAlias           := DockerAlias(dockerRepository.value, None, "anseliq-frontend", Some(version.value)),
    dockerAliases ++= DockerSettings.devAlias(dockerAlias.value, isSnapshot.value),
    DockerSettings.platformSetting,
    Docker / defaultLinuxInstallLocation := "/usr/share/nginx/html",
    dockerCommands := dockerCommands.value.filter {
      case ExecCmd(cmd, _) => cmd != "ENTRYPOINT" && cmd != "CMD" && cmd != "USER"
      case Cmd(cmd, _)     => cmd != "USER" && cmd != "RUN"
      case _               => true

    } ++ Seq(Cmd("COPY", "nginx.conf", "/etc/nginx/nginx.conf"), Cmd("CMD", """["nginx", "-g", "daemon off;"]""")),
    // sbt 2 maps virtual file references rather than Files, so each one goes through fileConverter.
    Docker / mappings ++= {
      publishDist.value
      val converter       = fileConverter.value
      val frontendDist    = baseDirectory.value / "dist"
      val nginxConfigFile = baseDirectory.value / "nginx.conf"

      (frontendDist ** "*").get().filter(_.isFile).map { file =>
        converter.toVirtualFile(file.toPath) -> s"/usr/share/nginx/html/${frontendDist.relativize(file).get.getPath}"
      } :+ (converter.toVirtualFile(nginxConfigFile.toPath) -> "/nginx.conf")
    }
  )
  .settings(
    devServer := Def.uncached {
      val sourceDirs = (Compile / sourceDirectories).value
      val watchSrcs  = (Compile / watchSources).value
      val scalaVer   = scalaVersion.value
      val backendUrl = Environtments.backendBaseUrl()
      val projectRef = thisProjectRef.value
      val taskKey    = projectRef / Compile / fastOptJS
      val sbtState   = state.value

      // Extracted.runTask throws when the task fails, e.g. a compilation error.
      val compilationRunner = () =>
        try {
          Project.extract(sbtState).runTask(taskKey, sbtState)
          true
        } catch {
          case e: Exception =>
            println(s"[Scala.js ERROR] Compilation error: ${e.getMessage}")
            false
        }

      CLIHelper.startDevEnvironment(
        scalaVer,
        sourceDirs,
        compilationRunner,
        prefix     = "frontend",
        backendUrl = Some(backendUrl)
      )
    },
    // Uncached (sbt 2 caches tasks by default): both run npm and Vite for their side effects.
    publishDist := Def.uncached {
      (Compile / fullLinkJS).value
      CLIHelper.buildFrontend(prefix = "frontend", backendUrl = Some(Environtments.backendBaseUrl()))
    }
  )
  .dependsOn(common.js)

/**
 * Backend subproject
 */
// Backend suites left out of a full test run, to be run on their own with testOnly (see below).
lazy val explicitOnlySuites = Set(
  "anseliq.testing.DataSetupScenario1",
  "anseliq.domain.service.LargeFileIngestionReconciliationSuite",
  "anseliq.testing.SeedHappyPathDemo",
  "anseliq.testing.PopulateS3Buckets"
)
lazy val backend = project
  .in(file("backend"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "backend",
    // In its own JVM, so bgStop can stop it: run inside sbt's, the HTTP server outlives the job.
    run / fork := true,
    libraryDependencies ++= Seq(
      "ch.qos.logback"              % "logback-classic"            % Dependencies.LogbackClassic,
      "net.logstash.logback"        % "logstash-logback-encoder"   % Dependencies.LogstashEncoder,
      "com.typesafe.scala-logging" %% "scala-logging"              % "3.9.5",
      "com.fasterxml.uuid"          % "java-uuid-generator"        % "5.2.0",
      "com.softwaremill.ox"        %% "core"                       % "1.0.3",
      "org.scalatest"              %% "scalatest"                  % Dependencies.Scalatest % Test,
      "com.augustnagro"            %% "magnum"                     % "2.0.0-M3",
      "org.postgresql"              % "postgresql"                 % "42.7.4",
      "com.zaxxer"                  % "HikariCP"                   % "6.3.0",
      "org.flywaydb"                % "flyway-core"                % "11.8.2",
      "org.flywaydb"                % "flyway-database-postgresql" % "11.8.2",
      "org.scalamock"              %% "scalamock"                  % "6.0.0"                % Test,
      "com.augustnagro"            %% "magnumpg"                   % "2.0.0-M3",
      "com.typesafe"                % "config"                     % "1.4.3",
      "com.lihaoyi"                %% "cask"                       % "0.10.2",
      "org.scalameta"              %% "munit"                      % "1.0.2"                % Test,
      "io.circe"                   %% "circe-core"                 % "0.14.10",
      "io.circe"                   %% "circe-parser"               % "0.14.10",
      "software.amazon.awssdk"      % "s3"                         % "2.29.0",
      // For the default credentials chain to pick up an IRSA web identity token (see S3FileSourceFactory).
      "software.amazon.awssdk" % "sts"       % "2.29.0",
      "com.jayway.jsonpath"    % "json-path" % "2.9.0",
      "at.favre.lib"           % "bcrypt"    % "0.10.2"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Most tests isolate themselves with rollBackOnly, but a few (e.g. IngestResumableSuite) commit
    // real data for real, briefly, before their own teardown deletes it — because they exercise
    // Ingestor.ingestResumable's batched commits, which by design commit on their own connection
    // regardless of any enclosing transaction. Running test classes in parallel (sbt's default) lets
    // another concurrently-running test's unfiltered query (e.g. LedgerSuite's movementRepo.findAll.head)
    // transiently see that real data and pick up the wrong row. Serializing test execution removes the
    // race entirely; the whole backend suite is fast enough (low single-digit seconds) that this isn't
    // a meaningful cost.
    Test / parallelExecution := false,
    // DataSetupScenario1 seeds real, committed data into the local dev DB (no rollBackOnly) and is meant
    // to be run explicitly (sbt "testOnly anseliq.testing.DataSetupScenario1"), not as part of the
    // regular suite where it would pollute state for every other test. LargeFileIngestionReconciliationSuite
    // ingests and reconciles two 5,000,000-row files, which takes on the order of hours given Reconciler's
    // current per-row matching loop, and is likewise meant to be run explicitly
    // (sbt "testOnly anseliq.domain.service.LargeFileIngestionReconciliationSuite"). These are scoped to
    // the tasks that run the whole suite, not `testOnly`, so explicit invocation still works: in sbt 2,
    // `test` runs testQuick, with testQuick's own options, and testFull runs with `test`'s.
    // SeedHappyPathDemo likewise commits demo data on purpose
    // (sbt "testOnly anseliq.testing.SeedHappyPathDemo"), and PopulateS3Buckets writes files into
    // LocalStack for a running backend to pick up (sbt "testOnly anseliq.testing.PopulateS3Buckets").
    Test / test / testOptions += Tests.Filter(name => !explicitOnlySuites(name)),
    Test / testQuick / testOptions += Tests.Filter(name => !explicitOnlySuites(name)),
    mainClass := Some("anseliq.app.Main")
  )
  .settings(
    dockerBaseImage    := "eclipse-temurin:26-jre",
    dockerExposedPorts := Seq(8080),
    dockerRepository   := Some(DockerSettings.repository),
    dockerAlias        := DockerAlias(dockerRepository.value, None, "anseliq-backend", Some(version.value)),
    dockerAliases ++= DockerSettings.devAlias(dockerAlias.value, isSnapshot.value),
    DockerSettings.platformSetting
  )
  .dependsOn(common.jvm)

/**
 * Shared project between frontend and backend
 */
lazy val common = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("common"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    scalaVersion := scala3Version,
    scalacOptions ++= Seq(
      "-Xmax-inlines",
      "64"
    ), // see [[https://docs.scala-lang.org/scala3/guides/migration/options-new.html]]
    libraryDependencies ++= Seq(
      "dev.zio"              %% "zio-json"        % Dependencies.ZioJson,
      "dev.zio"              %% "zio-http"        % Dependencies.ZioHttp,
      "com.github.jwt-scala" %% "jwt-json-common" % Dependencies.JwtScala,
      "com.lihaoyi"          %% "upickle"         % upickleVersion,
      "org.scalatest"        %% "scalatest"       % Dependencies.Scalatest % Test
    ),
    buildInfoKeys := Seq[BuildInfoKey](
      BuildInfoKey.action("backendBaseUrl") {
        Environtments.backendBaseUrl()
      }
    ),
    buildInfoPackage := "anseliq.common"
  )
