package onparity

import com.augustnagro.magnum.DbTx
import onparity.domain.port.driven.Txn

package object testing:

  def rollBackOnly[T](f: Txn[T]): Txn[T] =
    val dbTx = summon[DbTx]
    dbTx.connection.setAutoCommit(false)
    val result = f
    dbTx.connection.rollback()
    result
