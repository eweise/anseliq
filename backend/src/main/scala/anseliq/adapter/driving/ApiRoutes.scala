package anseliq.adapter.driving

import anseliq.common.util.JwtHelper
import anseliq.domain.model.*
import com.typesafe.scalalogging.StrictLogging

object HttpCmd:
  def GET    = Seq("get", "options")
  def POST   = Seq("post", "options")
  def PUT    = Seq("put", "options")
  def DELETE = Seq("delete", "options")

// Base for the per-area route classes HttpServer serves. CORS, auth and error handling are applied
// once, by HttpServer, to every route in every one of them.
trait ApiRoutes extends cask.Routes, StrictLogging

object ApiRoutes:
  def requireAdmin(currentUser: JwtHelper.UserClaim): Unit =
    if currentUser.role != UserRole.Admin.toString then throw ForbiddenError("Admin role required")
