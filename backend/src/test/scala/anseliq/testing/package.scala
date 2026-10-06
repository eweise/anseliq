package anseliq

import anseliq.domain.port.driven.Txn
import com.augustnagro.magnum.DbTx

package object testing:

  def rollBackOnly[T](f: Txn[T]): Txn[T] =
    val dbTx = summon[DbTx]
    dbTx.connection.setAutoCommit(false)
    val result = f
    dbTx.connection.rollback()
    result
