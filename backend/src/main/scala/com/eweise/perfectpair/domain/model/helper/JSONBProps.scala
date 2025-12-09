package com.eweise.perfectpair.domain.model.helper

import com.augustnagro.magnum.pg.json.JsonBDbCodec
import com.eweise.perfectpair.common.model.model.Properties
import io.circe.parser.decode as circeDecode
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder}

trait CirceJsonBDbCodec[A] extends JsonBDbCodec[A]

object CirceJsonBDbCodec:
  def derived[A: Encoder: Decoder]: CirceJsonBDbCodec[A] = new:
    def encode(a: A): String = a.asJson.toString

    def decode(json: String): A = circeDecode[A](json) match
      case Right(a)  => a
      case Left(err) => throw err

case class JSONBProps(props: Properties) derives Codec.AsObject, CirceJsonBDbCodec
