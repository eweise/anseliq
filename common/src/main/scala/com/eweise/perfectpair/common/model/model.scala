package com.eweise.perfectpair.common.model

import com.eweise.perfectpair.common.model.MovementStatus.WithinSLA
import com.eweise.perfectpair.common.model.model.{Money, Properties}
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

