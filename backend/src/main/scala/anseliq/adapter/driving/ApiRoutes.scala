package anseliq.adapter.driving

import anseliq.common.http.HttpPath
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

  // POSTs that only read: their input (search criteria, an uploaded sample file) doesn't fit a
  // query string, but they change nothing, so a Viewer may call them.
  private val readOnlyPosts: Seq[String => Boolean] = Seq(
    _ == s"${HttpPath.movements}/search",
    _ == s"${HttpPath.movements}/bad-file-preview",
    path => path.startsWith(s"${HttpPath.fileFeed}/") && path.endsWith("/test-parse")
  )

  /**
   * Whether a user with this role may make this request. Viewers are read-only: checked once for
   * every route (by HttpServer's authDecorator) rather than in each write route, so a new write
   * route is closed to them without anyone having to remember to.
   */
  def roleMayCall(role: String, method: String, path: String): Boolean =
    role != UserRole.Viewer.toString ||
      Set("GET", "HEAD", "OPTIONS").contains(method.toUpperCase) ||
      (method.equalsIgnoreCase("POST") && readOnlyPosts.exists(_(path)))
