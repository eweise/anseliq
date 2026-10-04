package anseliq.view.components

object Card:
  // Mercury-style content card: filled + soft shadow instead of a stroked border, for a calmer
  // look than a border around every box. Deliberately excludes padding/spacing/max-width — callers
  // append their own (p-4, p-6, mb-4, max-w-lg, ...) since Tailwind's cascade doesn't reliably let
  // a later utility in the same class string override an earlier one for the same property, so
  // baking in a default padding here would risk being silently unoverridable at call sites that
  // need a different one (e.g. FundFlowPage's p-6 cards).
  val cls = "bg-white dark:bg-gray-800 shadow-sm rounded-lg"
