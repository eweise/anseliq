import sbtwelcome._
import scala.Console._

/**
 * Logo generated on [[https://patorjk.com]]
 */

logo :=
  """
     |
     |    _                  _ ___ ___
     |   /_\  _ _  ___ ___| |_ _/ _ \
     |  / _ \| ' \(_-</ -_) || | (_) |
     | /_/ \_\_||_/__/\___|_|___\__\_\
     |
     | Reconciliation that explains itself
     |
     |""".stripMargin

usefulTasks := Seq(
  UsefulTask("backend/bgRun; frontend/devServer", "Start the backend in background, then the frontend dev server").alias("up"),
  UsefulTask("backend/bgStopAll", "Stop the backend started in background").alias("x"),
  UsefulTask("project frontend", "Navigate to Frontend project").alias("fe"),
  UsefulTask("project backend", "Navigate to Backend project").alias("be")
)

logoColor := CYAN
