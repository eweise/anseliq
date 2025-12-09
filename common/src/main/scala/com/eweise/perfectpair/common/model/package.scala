package com.eweise.perfectpair.common

import java.util.UUID

package object model:

  object model:
    type ID = UUID
    type Money = BigDecimal
    type Properties = Map[String, String]
    type Tags = Map[String, String]

    val Zero = BigDecimal(0)

