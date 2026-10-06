package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.util.JwtHelper
import anseliq.domain.model.*
import anseliq.domain.service.*
import com.typesafe.scalalogging.StrictLogging

import anseliq.adapter.config.AppContainer

// Serves every route class on one port, with the CORS/auth decorators and error handling applied
// to all of them. Each route class is given only the services and repos its routes use.
class HttpServer(app: AppContainer) extends cask.Main, StrictLogging:
  private val transactor = app.transactor
  private val views = ViewAssembler(
    fundFlowConfigRepo = app.fundFlowConfigRepo,
    disputeRepo        = app.disputeRepo,
    accountRepo        = app.accountRepo,
    movementRepo       = app.movementRepo,
    reconRepo          = app.reconRepo,
    movementSpecRepo   = app.movementSpecRepo,
    fileFeedRepo       = app.fileFeedRepo,
    reconSpecRepo      = app.reconSpecRepo,
    disputeCommentRepo = app.disputeCommentRepo,
    userRepo           = app.userRepo
  )

  val allRoutes = Seq(
    FundFlowRoutes(
      transactor         = transactor,
      fundFlowConfigRepo = app.fundFlowConfigRepo,
      accountRepo        = app.accountRepo,
      movementRepo       = app.movementRepo,
      reconRepo          = app.reconRepo,
      fundFlowService    = app.fundFlowService
    ),
    AccountRoutes(
      transactor     = transactor,
      accountRepo    = app.accountRepo,
      accountService = app.accountService
    ),
    MovementSpecRoutes(
      transactor          = transactor,
      movementSpecRepo    = app.movementSpecRepo,
      movementSpecService = app.movementSpecService
    ),
    FileFeedRoutes(
      transactor      = transactor,
      fileFeedRepo    = app.fileFeedRepo,
      fileFeedService = app.fileFeedService,
      ingestor        = app.ingestor
    ),
    ReconSpecRoutes(
      transactor       = transactor,
      reconSpecRepo    = app.reconSpecRepo,
      reconSpecService = app.reconSpecService
    ),
    ReconciliationRoutes(
      transactor           = transactor,
      views                = views,
      reconRepo            = app.reconRepo,
      reconciliationRunner = app.reconciliationRunner,
      manualReconciler     = app.manualReconciler
    ),
    MovementRoutes(
      transactor       = transactor,
      views            = views,
      movementRepo     = app.movementRepo,
      manualReconciler = app.manualReconciler
    ),
    DisputeRoutes(
      transactor     = transactor,
      views          = views,
      disputeRepo    = app.disputeRepo,
      disputeService = app.disputeService
    ),
    UserRoutes(
      transactor  = transactor,
      userService = app.userService,
      authService = app.authService
    ),
    ReportRoutes(
      transactor       = transactor,
      reportingService = app.reportingService
    ),
    ActivityRoutes(
      transactor        = transactor,
      jobRepo           = app.jobRepo,
      eventRepo         = app.eventRepo,
      fileInventoryRepo = app.fileInventoryRepo
    )
  )

  override def port: Int = sys.env.get("CASK_PORT").map(_.toInt).getOrElse(8080)

  // authDecorator must come after corsDecorator: a CORS preflight (OPTIONS) request is
  // short-circuited by corsDecorator before it ever reaches authDecorator, so preflight requests
  // never need a token. Order also determines which curried parameter list a decorator's injected
  // map lands in for a route that declares one (e.g. `(currentUser: UserClaim)`) — authDecorator
  // must be last so its map is the one bound there.
  override def mainDecorators = Seq(corsDecorator(), authDecorator(app.jwtSecret))

  private val corsHeaders = Seq(
    "Access-Control-Allow-Origin"  -> "*",
    "Access-Control-Allow-Methods" -> "GET, POST, DELETE, PUT, OPTIONS, HEAD",
    "Access-Control-Allow-Headers" -> "Authorization, Content-Type, Accept",
    "Access-Control-Max-Age"       -> "86400"
  )

  // Where cask renders a route that failed, outside corsDecorator: delegate returns a Result.Error
  // rather than throwing, so neither corsDecorator's `.map` nor its catch sees it. Two cases:
  //   - Exception: the route body threw (e.g. ErrIfNotFound's NotFoundError). cask catches it
  //     itself; its default would be a 500 with the full stack trace as the body, so it's mapped to
  //     a status and the plain message here instead (the trace still goes to the log).
  //   - anything else: cask couldn't bind the route's arguments (an unknown or malformed query
  //     param, say) — cask's own 400 is kept, just given the CORS headers.
  // Without the CORS headers the browser hides either one behind a generic "NetworkError when
  // attempting to fetch resource" instead of showing the message.
  override def handleEndpointError(
    routes:   cask.main.Routes,
    metadata: cask.router.EndpointMetadata[?],
    e:        cask.router.Result.Error,
    req:      cask.model.Request
  ): cask.Response.Raw =
    e match
      case cask.router.Result.Error.Exception(t) => errorResponse(t)
      case _ =>
        val response = super.handleEndpointError(routes, metadata, e, req)
        response.copy(headers = response.headers ++ corsHeaders)

  private def errorResponse(e: Throwable): cask.Response.Raw =
    val status = statusFor(e)
    if status >= 500 then logger.error("Unhandled error", e)
    else logger.warn(s"Request rejected with $status: ${e.getMessage}")
    cask.Response(Option(e.getMessage).getOrElse(e.toString), statusCode = status, headers = corsHeaders)

  class corsDecorator extends cask.RawDecorator:
    def wrapFunction(ctx: cask.model.Request, delegate: Delegate) =
      if ctx.exchange.getRequestMethod.toString == "OPTIONS" then
        cask.router.Result.Success(
          cask.Response("", statusCode = 200, headers = corsHeaders)
        )
      else
        // A route body's exceptions don't arrive here as a throw: cask catches them while invoking
        // the endpoint and returns them as Result.Error.Exception, which skips the `.map` below and
        // is rendered by handleEndpointError instead (see there). This catch is the fallback for
        // anything that does throw through delegate, so it still gets the same status mapping and
        // CORS headers rather than reaching the browser as a generic "Failed to fetch".
        try delegate(ctx, Map.empty).map(response => response.copy(headers = response.headers ++ corsHeaders))
        catch case e: Throwable => cask.router.Result.Success(errorResponse(e))

  // The domain's NonRetryableError subtypes are the caller's fault (bad input, no access, missing
  // or conflicting state), so they map to the matching 4xx; anything else — including the
  // retryable DBError — is a server-side failure and stays 500.
  private def statusFor(e: Throwable): Int = e match
    case _: IllegalArgumentError => 400
    case _: UnauthorizedError    => 401
    case _: ForbiddenError       => 403
    case _: NotFoundError        => 404
    case _: IllegalStateError    => 409
    case _                       => 500

  // Applied globally (see mainDecorators above) so every route requires a valid Bearer token
  // except /login itself (can't require a token to get a token). A route that needs to know who's
  // calling declares an extra curried parameter list — e.g. `(currentUser: JwtHelper.UserClaim)` —
  // which cask binds from this decorator's injected map; routes that don't declare it are
  // unaffected (cask only reads as many decorator-contributed param lists as a method declares).
  class authDecorator(jwtSecret: JwtSecret) extends cask.RawDecorator:
    def wrapFunction(ctx: cask.model.Request, delegate: Delegate) =
      if ctx.exchange.getRequestPath == HttpPath.login then delegate(ctx, Map.empty)
      else
        val bearerToken = ctx.headers.get("authorization").flatMap(_.headOption).collect { case s"Bearer $token" =>
          token
        }
        bearerToken.flatMap(t => JwtHelper.decode(t, jwtSecret.value).toOption) match
          case None        => cask.router.Result.Success(cask.Response("Unauthorized", statusCode = 401))
          case Some(claim) => delegate(ctx, Map("currentUser" -> claim))

  logger.info("Initializing backend")
