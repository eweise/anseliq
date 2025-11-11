package com.eweise.perfectpair.domain

import java.util.UUID

package object model:
  type ID         = UUID
  type Money      = BigDecimal
  type Properties = Map[String, String]
  type Tags       = Map[String, String]

  val Zero = BigDecimal(0)

  def createID(): UUID = UUID.randomUUID()
