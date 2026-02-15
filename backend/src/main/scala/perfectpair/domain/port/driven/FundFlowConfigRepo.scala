package perfectpair.domain.port.driven

import perfectpair.domain.model.FundFlowConfig

trait FundFlowConfigRepo extends DomainRepo[FundFlowConfig, FundFlowConfig]:
  def findByName(name :String):Txn[Option[FundFlowConfig]]
