import scala.sys.process._
import java.nio.file.{Paths, Files}
import com.w47s0n.consolebox.Consolebox
import com.w47s0n.consolebox.*
import scala.util.matching.Regex
import sbt._
import sbt.Keys._
import LoggingUtils._

object CLIHelper {
  private val DevServerUrl = "http://localhost:9876"

  def boxedConfigs(configs: (String, String)*): String = {
    val formattedLines = configs.map { case (key, value) => s"$key: $value" }
    Consolebox.box(formattedLines.mkString("\n"))
  }

  private def installNpmPackages(prefix: String = ""): Unit = {
    val command        = List("npm", "install", "--prefix", prefix)
    val pattern        = "found\\s\\d+\\svulnerabilities".r
    val successMessage = boxedSuccess("Installed npm packages successfully !")
    CommandWatcher.watch(command, pattern, successMessage)
  }

  private def removeDistFolderIfAny(prefix: String = ""): Unit = {
    val distFolder = Paths.get(s"${prefix}dist")
    if (Files.exists(distFolder)) {
      s"rm -r ${prefix}dist".!
      logWarning(s"Deleted existing '${prefix}dist' folder")
    }
  }

  def buildFrontend(prefix: String = ""): Unit = {
    installNpmPackages(prefix)
    val distPrefix = if (prefix.isEmpty) "" else s"$prefix/"
    removeDistFolderIfAny(distPrefix)

    val buildCommand = Seq("npm", "run", "build") ++ (if (prefix.nonEmpty) Seq("--prefix", prefix) else Seq.empty)
    val pattern      = ".built in.".r

    val successMessage = boxedSuccess(s"Web app is available at directory '${distPrefix}dist'")
    CommandWatcher.watch(buildCommand, pattern, successMessage)
  }

  def startDevEnvironment(
    sourceDirectories: Seq[File],
    compilationRunner: () => Boolean,
    prefix:            String = ""
  ): Unit = {
    installNpmPackages(prefix)
    printStartupMessage()

    val viteServerStarter = () => {
      var viteReadyCalled = false
      val viteLogger = ProcessLogger(
        line => {
          if (line.contains("ready in") && !viteReadyCalled) {
            viteReadyCalled = true
            logSuccess(
              "Development environment ready!",
              s"Web app now available on $DevServerUrl",
              "",
              "Press Ctrl+C to stop"
            )
          }
          println(s"[Vite] $line")
        },
        line => logError(s"[Vite ERROR] $line")
      )

      val devCommand = Seq("npm", "run", "dev") ++ (if (prefix.nonEmpty) Seq("--prefix", prefix) else Seq.empty)
      Process(
        devCommand,
        None,
        "FORCE_COLOR" -> "1"
      ).run(viteLogger)
    }

    new ScalaJSCodeWatcher(
      sourceDirectories,
      compilationRunner,
      viteServerStarter
    ).start()
  }

  private def printStartupMessage(): Unit =
    println(
      boxedConfigs(
        "Starting development environment" -> "",
        "Scala.js compiler"                -> "Starting in watch mode...",
        "Vite dev server"                  -> "Starting..."
      )
    )
}
