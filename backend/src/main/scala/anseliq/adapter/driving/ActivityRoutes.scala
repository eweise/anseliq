package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.port.driven.*
import com.augustnagro.magnum.*
import upickle.default.*

class ActivityRoutes(
  transactor:        Transactor,
  jobRepo:           JobRepo,
  eventRepo:         EventRepo,
  fileInventoryRepo: FileInventoryRepo
) extends ApiRoutes:

  // Jobs
  @cask.route(s"${HttpPath.jobs}/:jobType", methods = HttpCmd.GET)
  def jobsByType(jobType: String): String =
    val jobTypeInfo =
      try JobTypeInfo.valueOf(jobType)
      catch case ex: Exception => throw IllegalArgumentError("JobType", jobType)
    transact(transactor):
      val jobs = jobRepo.findByJobType(JobType.valueOf(jobTypeInfo.toString))
      write(jobs.map(Payload.jobPayloadFromDomain))

  // Events
  @cask.route(s"${HttpPath.events}/:eventType", methods = HttpCmd.GET)
  def getEvents(eventType: String): String =
    transact(transactor):
      write(eventRepo.findByEventType(eventType).map(Payload.eventPayloadFromDomain))

  // File Inventory
  @cask.route(s"${HttpPath.fileInventory}", methods = HttpCmd.GET)
  def getFileInventory(): String =
    transact(transactor):
      val entries = fileInventoryRepo.findAllWithFileFeedName()
      write(entries.map((fi, fileFeedName) => Payload.fileInventoryPayloadFromDomain(fi, fileFeedName)))

  initialize()
