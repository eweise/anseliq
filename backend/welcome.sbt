import sbtwelcome.*

import scala.Console.*

/**
 * Logo generated on [[https://patorjk.com]]
 */

logo :=
  s"""
     |
     |███████ ████████ ██ ███    ██  ██████   ██████  ███    ██     ██████  ███████  █████   ██████  ██   ██
     |██         ██    ██ ████   ██ ██       ██    ██ ████   ██     ██   ██ ██      ██   ██ ██       ██   ██
     |███████    ██    ██ ██ ██  ██ ██   ███ ██    ██ ██ ██  ██     ██████  █████   ███████ ██       ███████
     |     ██    ██    ██ ██  ██ ██ ██    ██ ██    ██ ██  ██ ██     ██   ██ ██      ██   ██ ██       ██   ██
     |███████    ██    ██ ██   ████  ██████   ██████  ██   ████     ██████  ███████ ██   ██  ██████  ██   ██
     |     |
     | Scala Version : ${(scalaVersion).value}
     | 
     |""".stripMargin

usefulTasks := Seq(
  UsefulTask("backend/bgRun; frontend/devServer", "Start the backend in background, then the frontend dev server")
    .alias("up"),
  UsefulTask("bgRun", "Start server in background").alias("a"),
  UsefulTask("bgStopAll", "Stop the server started with bgRun").alias("x"),
  UsefulTask("Docker/publishLocal", "Publish server locally  as a docker image").alias("dpl"),
  UsefulTask("Docker/publish", "Publish server as a docker image to remote container registry").alias("dp"),
  UsefulTask("project root", "Return to root project").alias("r")
)

logoColor := RED

// bgStop needs the job's id, which goes up with every bgRun, so the menu stops them all instead.
lazy val bgStopAll = taskKey[Unit]("Stops every background job, e.g. the server started with bgRun")
bgStopAll := Def.uncached {
  val jobs = bgJobService.value
  jobs.jobs.foreach(jobs.stop)
}
