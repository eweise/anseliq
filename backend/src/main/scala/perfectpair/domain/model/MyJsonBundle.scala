package perfectpair.domain.model

// 1. Create your custom configuration bundle
object MyJsonBundle extends upickle.AttributeTagged {
  // Change "$type" to whatever you want, like "type", "kind", or "class"
  override def tagName = "type"
}