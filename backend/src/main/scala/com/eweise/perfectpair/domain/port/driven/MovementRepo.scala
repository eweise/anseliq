package com.eweise.perfectpair.domain.port.driven

import com.augustnagro.magnum.DbCon
import com.eweise.perfectpair.domain.model.{Direction, ID, Movement}
import com.eweise.perfectpair.domain.port.driven.{MovementRepo, Txn}

trait MovementRepo extends DomainRepo[Movement, Movement]:
  def findUnReconciledByAccountAndDirection(accountID: ID, direction: Direction): Txn[Vector[Movement]]
  def findUnReconciledByAccount(accountID: ID): Txn[Vector[Movement]]
  def findByAccount(accountID: ID): Txn[Vector[Movement]]
