package anseliq.domain.service

import anseliq.common.model.model.ID
import anseliq.domain.model.*
import anseliq.domain.port.driven.*

class FileFeedService(fileFeedRepo: FileFeedRepo):

  def createFileFeed(feed: FileFeed): Txn[FileFeed] =
    fileFeedRepo.insert(feed)
    feed

  def updateFileFeed(
    id:                   ID,
    name:                 String,
    dataSourceJson:       String,
    expectedCadenceHours: Option[Int]
  ): Txn[FileFeed] =
    val existing = fileFeedRepo.findById(id).ErrIfNotFound("file feed", id)
    val updated = existing.copy(
      name                 = name,
      dataSourceJson       = dataSourceJson,
      expectedCadenceHours = expectedCadenceHours
    )
    fileFeedRepo.update(updated)
    updated

  def deleteFileFeed(id: ID): Txn[Unit] =
    fileFeedRepo.deleteById(id)
