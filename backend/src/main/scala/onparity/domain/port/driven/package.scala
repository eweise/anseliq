package onparity.domain.port

import com.augustnagro.magnum.DbTx

package object driven {
  type Txn[T] = DbTx ?=> T
}
