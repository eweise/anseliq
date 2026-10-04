package anseliq.domain.port.driven

import anseliq.domain.model.FundFlowConfig

trait FundFlowConfigRepo extends DomainRepo[FundFlowConfig, FundFlowConfig]:
  def findByName(name: String): Txn[Option[FundFlowConfig]]
