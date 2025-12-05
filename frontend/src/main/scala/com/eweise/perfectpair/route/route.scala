package com.eweise.perfectpair.route

import com.eweise.perfectpair.page.*
import scala.util.Try

object Route {
  object Home:
    def unapply(path: String): Option[Unit] = if path == "/" then Some(()) else None

  object ComponentDemo:
    def unapply(path: String): Option[Page] = Try {
      path.split("/components/")(1) match {
        case "toggle"     => Page.Toggle
      }
    }.toOption

  object Login:
    def unapply(path: String): Option[Unit] = if path.startsWith("login") then Some(()) else None

}
