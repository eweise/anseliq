import sbt.io.Path.relativeTo
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
import sbt.Keys.libraryDependencies

lazy val scala3Version  = "3.8.1"
lazy val upickleVersion = "4.4.2"
ThisBuild / organization := "anseliq"
ThisBuild / scalaVersion := scala3Version

lazy val root = (project in file("."))

/**
 * Frontend subproject
 */
lazy val devServer   = taskKey[Unit]("Start both ~fastOptJS and vite dev server concurrently")
lazy val publishDist = taskKey[Unit]("Build a static web artifact")
lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin, DockerPlugin)
  .settings(
    name    := "frontend",
    version := "0.1.0",
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    scalaJSUseMainModuleInitializer := true,
    // Source maps seem to be broken with bundler
    Compile / fastOptJS / scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    Compile / fullOptJS / scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    libraryDependencies ++= Seq(
      "com.raquo"                  %%% "laminar"    % Dependencies.Laminar,
      "io.frontroute"              %%% "frontroute" % Dependencies.Frontroute,
      "com.lihaoyi"                %%% "upickle"    % upickleVersion,
      "com.softwaremill.quicklens" %%% "quicklens"  % Dependencies.Quicklens
    )
  )
  .settings(
    dockerBaseImage       := "nginx:stable-perl",
    Docker / publish      := (Docker / publish).dependsOn(Compile / fullLinkJS).value,
    Docker / publishLocal := (Docker / publishLocal).dependsOn(Compile / fullLinkJS).value,
    dockerExposedPorts    := Seq(80),
    dockerRepository      := Some(DockerSettings.repository),
    dockerAlias := DockerAlias
      .apply(dockerRepository.value, None, "anseliq", dockerAlias.value.tag.map(t => "frontend-" + t)),
    DockerSettings.x86ArchSetting,
    Docker / defaultLinuxInstallLocation := "/usr/share/nginx/html",
    dockerCommands := dockerCommands.value.filter {
      case ExecCmd(cmd, _) => cmd != "ENTRYPOINT" && cmd != "CMD" && cmd != "USER"
      case Cmd(cmd, _)     => cmd != "USER" && cmd != "RUN"
      case _               => true

    } ++ Seq(Cmd("COPY", "nginx.conf", "/etc/nginx/nginx.conf"), Cmd("CMD", """["nginx", "-g", "daemon off;"]""")),
    Docker / mappings ++= {
      publishDist.value
      val frontendDist    = baseDirectory.value / "dist"
      val nginxConfigFile = baseDirectory.value / "nginx.conf"

      (frontendDist ** "*").get.map { file =>
        file -> s"/usr/share/nginx/html/${frontendDist.relativize(file).get.getPath}"
      } :+ (nginxConfigFile -> "/nginx.conf")

    }
  )
  .settings(
    devServer := {
      val sourceDirs = (Compile / sourceDirectories).value
      val watchSrcs  = (Compile / watchSources).value
      val scalaVer   = scalaVersion.value
      val backendUrl = Environtments.backendBaseUrl
      val projectRef = thisProjectRef.value
      val taskKey    = projectRef / Compile / fastOptJS
      val sbtState   = state.value

      val compilationRunner = () =>
        try
          Project.runTask(taskKey, sbtState) match {
            case Some((_, Value(_))) => true  // Success
            case Some((_, Inc(_)))   => false // Compilation failed
            case None =>
              println(s"[Scala.js ERROR] Task $taskKey not found")
              false
          }
        catch {
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
    publishDist := {
      (Compile / fullLinkJS).value
      CLIHelper.buildFrontend(prefix = "frontend", backendUrl = Some(Environtments.backendBaseUrl))
    }
  )
  .dependsOn(common.js)

/**
 * Backend subproject
 */
lazy val backend = (project in file("backend"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name    := "backend",
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "ch.qos.logback"              % "logback-classic"     % Dependencies.LogbackClassic,
      "net.logstash.logback"        % "logstash-logback-encoder" % Dependencies.LogstashEncoder,
      "com.typesafe.scala-logging" %% "scala-logging"       % "3.9.5",
      "com.fasterxml.uuid"          % "java-uuid-generator" % "5.2.0",
      "com.softwaremill.ox"        %% "core"                % "1.0.3",
      "org.scalatest"              %% "scalatest"           % Dependencies.Scalatest % Test,
      "com.augustnagro"            %% "magnum"              % "2.0.0-M3",
      "org.postgresql"              % "postgresql"          % "42.7.4",
      "com.zaxxer"                  % "HikariCP"            % "6.3.0",
      "org.scalamock"              %% "scalamock"           % "6.0.0"                % Test,
      "com.augustnagro"            %% "magnumpg"            % "2.0.0-M3",
      "com.typesafe"                % "config"              % "1.4.3",
      "com.lihaoyi"                %% "cask"                % "0.10.2",
      "org.scalameta"              %% "munit"               % "1.0.2"                % Test,
      "io.circe"                   %% "circe-core"          % "0.14.10",
      "io.circe"                   %% "circe-parser"        % "0.14.10",
      "software.amazon.awssdk"      % "s3"                  % "2.29.0",
      "com.jayway.jsonpath"         % "json-path"           % "2.9.0",
      "at.favre.lib"                 % "bcrypt"              % "0.10.2"
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
    // (sbt "testOnly anseliq.domain.service.LargeFileIngestionReconciliationSuite"). Both are scoped to
    // the `test` task only (not `testOnly`/`testQuick`) so explicit invocation still works.
    // SeedHappyPathDemo likewise commits demo data on purpose
    // (sbt "testOnly anseliq.testing.SeedHappyPathDemo"), and PopulateS3Buckets writes files into
    // LocalStack for a running backend to pick up (sbt "testOnly anseliq.testing.PopulateS3Buckets").
    Test / test / testOptions += Tests.Filter(name =>
      name != "anseliq.testing.DataSetupScenario1" &&
        name != "anseliq.domain.service.LargeFileIngestionReconciliationSuite" &&
        name != "anseliq.testing.SeedHappyPathDemo" &&
        name != "anseliq.testing.PopulateS3Buckets"
    ),
    mainClass := Some("anseliq.app.Main")
  )
  .settings(
    dockerBaseImage    := "eclipse-temurin:25-jre",
    dockerExposedPorts := Seq(8080),
    dockerRepository   := Some(DockerSettings.repository),
    dockerAlias := DockerAlias
      .apply(dockerRepository.value, None, "anseliq", dockerAlias.value.tag.map(t => "backend-" + t)),
    DockerSettings.x86ArchSetting
  )
  .dependsOn(common.jvm)

/**
 * Shared project between frontend and backend
 */
lazy val common = (crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure) in file("common"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    scalaVersion := scala3Version,
    scalacOptions ++= Seq(
      "-Xmax-inlines",
      "64"
    ), // see [[https://docs.scala-lang.org/scala3/guides/migration/options-new.html]]
    libraryDependencies ++= Seq(
      "dev.zio"              %%% "zio-json"        % Dependencies.ZioJson,
      "dev.zio"              %%% "zio-http"        % Dependencies.ZioHttp,
      "com.github.jwt-scala" %%% "jwt-json-common" % Dependencies.JwtScala,
      "com.lihaoyi"           %% "upickle"         % upickleVersion,
      "org.scalatest"         %% "scalatest"       % Dependencies.Scalatest % Test
    ),
    buildInfoKeys := Seq[BuildInfoKey](
      BuildInfoKey.action("backendBaseUrl") {
        Environtments.backendBaseUrl()
      }
    ),
    buildInfoPackage := "anseliq.common"
  )
