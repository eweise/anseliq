package com.eweise.perfectpair.domain.model

import com.eweise.perfectpair.common.model.{EqualityMatcher, Matcher}
import upickle.default.*

enum Direction:
  case IN, OUT

case class MovementSpec(
  name:           String,
  inAccountName:  String,
  outAccountName: String,
  attributes:     Array[String] = Array.empty
) derives ReadWriter

