package perfectpair.view.components

import com.raquo.laminar.api.L.*

object Pill:
  enum Tone:
    case Good, Warning, Serious, Critical, Neutral, Accent

  def apply(text: String, tone: Tone): HtmlElement =
    val toneCls = tone match
      case Tone.Good     => "bg-md-good-bg dark:bg-md-good-bg-dark text-md-good dark:text-md-good-dark"
      case Tone.Warning  => "bg-md-warning-bg dark:bg-md-warning-bg-dark text-md-warning dark:text-md-warning-dark"
      case Tone.Serious  => "bg-md-serious-bg dark:bg-md-serious-bg-dark text-md-serious dark:text-md-serious-dark"
      case Tone.Critical => "bg-md-critical-bg dark:bg-md-critical-bg-dark text-md-critical dark:text-md-critical-dark"
      case Tone.Neutral  => "bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300"
      case Tone.Accent   => "bg-md-primary-container dark:bg-md-primary-container-dark text-md-on-primary-container dark:text-md-on-primary-container-dark"
    span(cls := s"inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold $toneCls", text)
