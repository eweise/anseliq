package anseliq.app

import anseliq.adapter.config.AppContainer
import anseliq.adapter.driven.DbMigrations

object Main:
  /** `migrate` applies pending database migrations and exits; anything else runs the app. */
  def main(args: Array[String]): Unit =
    args.toList match
      case "migrate" :: _ => DbMigrations.migrate(new AppContainer {}.config)
      case _              => AppEnv().run(args)
