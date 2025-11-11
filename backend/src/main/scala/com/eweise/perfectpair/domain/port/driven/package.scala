package com.eweise.perfectpair.domain.port

import com.augustnagro.magnum.DbTx

package object driven {
  type Txn[T] = DbTx ?=> T
}
