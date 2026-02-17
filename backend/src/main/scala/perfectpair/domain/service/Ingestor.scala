package perfectpair.domain.service

import io.netty.util.CharsetUtil
import ox.flow.Flow
import perfectpair.common.model.model.{ID, Properties}
import perfectpair.domain.model.helper.JSONBProps
import perfectpair.domain.model.{Movement, MovementSpec}
import perfectpair.domain.port.driven.{MovementRepo, Txn}

import java.io.{File, InputStream}

trait Parser:
  def parse(text: String): Properties

class CSVParser(columns: Map[Int, String]) extends Parser:
  override def parse(text: String): Properties =
    val fields = text.split(",") // todo replace with real csv parser
    for ((index, key) <- columns) yield ( key, fields(index))

class Ingestor(
  val movementRepo: MovementRepo,
  val parser:       Parser
):

  def ingest(movementSpec: MovementSpec, is: InputStream): Txn[Unit] =
    Flow
      .fromInputStream(is)
      .linesUtf8
      .map { line =>
        val props = parser.parse(line)
        Movement(
          inAccountId    = movementSpec.inAccountId,
          outAccountId   = movementSpec.outAccountId,
          amount         = 0, // fixme how to we get amount?
          properties     = JSONBProps(props),
          movementSpecId = movementSpec.id
        )
      }
      .grouped(100)
      .runForeach(movementRepo.insertAll)
