package onparity.domain.port.driven

import onparity.domain.model.FundFlowConfig

trait FundFlowConfigRepo extends DomainRepo[FundFlowConfig, FundFlowConfig]:
  def findByName(name: String): Txn[Option[FundFlowConfig]]
