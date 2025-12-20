package com.eweise.perfectpair.common.model

import com.eweise.perfectpair.common.model.model.{ID, Money, Properties}
import upickle.*
import upickle.default.*
import zio.json.*


/**
 * Shared models between Backend and Frontend
 */
case class LoginRequest(username: String, password: String)
case class LoginSuccess(accessToken: String, refreshToken: String)
case class LoginFailure(reason: String)
case class RandomMessage(text: String) derives ReadWriter

object LoginRequest {
  given JsonCodec[LoginRequest] = DeriveJsonCodec.gen[LoginRequest]
}

object LoginSuccess {
  given JsonCodec[LoginSuccess] = DeriveJsonCodec.gen[LoginSuccess]
}

object LoginFailure {
  given JsonCodec[LoginFailure] = DeriveJsonCodec.gen[LoginFailure]
}

object RandomMessage {
  given JsonCodec[RandomMessage] = DeriveJsonCodec.gen[RandomMessage]
}

enum MovementStatus:
  case Reconciled, WithinSLA, OutOfSLA


case class ReconciliationAccountPayload(
                                      name: String,
                                      matcher: Matcher = EqualityMatcher(Array("id"), Array("id")),
                                      agingLimit: String = ""
                                    ) 

sealed trait Matcher derives ReadWriter
case class EqualityMatcher(
                            inKeys: Array[String],
                            outKeys: Array[String]
                          ) extends Matcher

case class SQLMatcher(
                       sql: String
                     ) extends Matcher


object AdapterModel:
  case class AccountPayload(
                      id: String,
                      name: String,
                      fundFlowId: ID,
                      accountType: String,
                      balance: Money
                    ) derives ReadWriter


  case class MovementPayload(
                       id: String,
                       referenceId: String,
                       outAccountId: String,
                       inAccountId: String,
                       amount: Money,
                       properties: Properties,
                       reconciliationId: String,
                       status: String
                     ) derives ReadWriter

