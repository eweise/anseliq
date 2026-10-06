package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class FileFeedRoutes(
  transactor:      Transactor,
  fileFeedRepo:    FileFeedRepo,
  fileFeedService: FileFeedService,
  ingestor:        Ingestor
) extends ApiRoutes:

  // FileFeed — owns "where the file comes from and how to parse it"; a MovementSpec routes off
  // one of these to claim the rows for one account pair. Not scoped to a fund flow (a feed has no
  // notion of which fund flow it belongs to until a MovementSpec attaches accounts to it), so this
  // lists every feed rather than joining through accounts the way movement-specs-by-fund-flow does.
  @cask.route(s"${HttpPath.fileFeeds}", methods = HttpCmd.GET)
  def listFileFeeds(): String =
    transact(transactor):
      write(fileFeedRepo.findAll.map(Payload.fileFeedPayloadFromDomain))

  @cask.route(s"${HttpPath.fileFeed}", methods = HttpCmd.POST)
  def createFileFeed(request: cask.Request): String =
    val payload = read[FileFeedPayload](request.text())
    validateDataSourceConfig(payload)
    transact(transactor):
      write(Payload.fileFeedPayloadFromDomain(fileFeedService.createFileFeed(Payload.fileFeedPayloadToDomain(payload))))

  @cask.route(s"${HttpPath.fileFeed}/:id", methods = HttpCmd.PUT)
  def updateFileFeed(id: String, request: cask.Request): String =
    val payload = read[FileFeedPayload](request.text())
    validateDataSourceConfig(payload)
    val feedID = UUID.fromString(id)
    transact(transactor):
      val updated = fileFeedService.updateFileFeed(
        feedID,
        name                 = payload.name,
        dataSourceJson       = Payload.dataSourceJson(payload),
        expectedCadenceHours = payload.expectedCadenceHours
      )
      write(Payload.fileFeedPayloadFromDomain(updated))

  // Re-checks the same rules the frontend validates inline (DataSourceConfig.validate, in common)
  // as a safety net against a bypassed or buggy frontend — not the primary UX for catching mistakes.
  private def validateDataSourceConfig(payload: FileFeedPayload): Unit =
    payload.dataSourceConfig.foreach { ds =>
      val errors = ds.validate
      if errors.nonEmpty then throw IllegalArgumentError("dataSourceConfig", errors.mkString("; "))
    }

  @cask.route(s"${HttpPath.fileFeed}/:id/test-parse", methods = HttpCmd.POST)
  def testParseFileFeed(id: String, request: cask.Request): String =
    val content = request.text()
    transact(transactor):
      val fileFeedId = UUID.fromString(id)
      val fileFeed   = fileFeedRepo.findById(fileFeedId).ErrIfNotFound("FileFeed", fileFeedId)
      val result     = ingestor.testParse(fileFeed, content)
      write(Payload.parseTestResultPayloadFromDomain(result))

  @cask.delete(s"${HttpPath.fileFeed}/:id")
  def deleteFileFeed(id: String): String =
    transact(transactor):
      fileFeedService.deleteFileFeed(UUID.fromString(id))
    write("deleted")

  initialize()
