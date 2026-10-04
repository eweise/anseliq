package anseliq.domain.service

class SubsetSum:

  /**
   * NOT CURRENTLY USED. Determines whether any subset of the given set sums to the target value.
   * Uses a bottom-up dynamic programming approach with a 2D boolean table.
   *
   * @param set
   *   the array of non-negative integers
   * @param target
   *   the target sum
   * @return
   *   true if a subset exists that sums to target
   */
  def canSum(set: Array[Int], target: Int): Boolean =
    val n = set.length
    // dp(i)(j) = true if a subset of set(0..i-1) sums to j
    val dp = Array.ofDim[Boolean](n + 1, target + 1)

    // Base case: sum of 0 is always achievable with an empty subset
    for i <- 0 to n do dp(i)(0) = true

    for i <- 1 to n do
      for j <- 1 to target do
        // Exclude set(i-1)
        dp(i)(j) = dp(i - 1)(j)
        // Include set(i-1) if it doesn't exceed the current target
        if set(i - 1) <= j then dp(i)(j) = dp(i)(j) || dp(i - 1)(j - set(i - 1))

    dp(n)(target)

  /**
   * Finds the first subset (by array order) of the given set that sums to the target. Builds a
   * suffix DP table where dp(i)(j) = can a subset of set(i..n-1) sum to j, then greedily includes
   * elements from left to right.
   *
   * @param set
   *   the array of non-negative integers
   * @param target
   *   the target sum
   * @return
   *   an Option containing the first subset found, or None
   */
  def findFirstSubset(set: Array[Int], target: Int): Option[List[Int]] =
    val n = set.length
    // dp(i)(j) = true if a subset of set(i..n-1) sums to j
    val dp = Array.ofDim[Boolean](n + 1, target + 1)

    // Base case: sum of 0 is always achievable
    for i <- 0 to n do dp(i)(0) = true

    // Fill from right to left
    for i <- (n - 1) to 0 by -1 do
      for j <- 1 to target do
        dp(i)(j)                     = dp(i + 1)(j)
        if set(i) <= j then dp(i)(j) = dp(i)(j) || dp(i + 1)(j - set(i))

    if !dp(0)(target) then None
    else
      def solve(i: Int, remaining: Int): List[Int] =
        if remaining == 0 then Nil
        else if i == n then Nil
        else if set(i) <= remaining && dp(i + 1)(remaining - set(i)) then set(i) :: solve(i + 1, remaining - set(i))
        else solve(i + 1, remaining)

      Some(solve(0, target))

  /**
   * Finds all subsets of the given set that sum to the target value. Builds a DP table then
   * recursively backtracks through all valid paths.
   *
   * @param set
   *   the array of non-negative integers
   * @param target
   *   the target sum
   * @return
   *   a list of all subsets (each a List[Int]) that sum to target
   */
  def findAllSubsets(set: Array[Int], target: Int): List[List[Int]] =
    val n  = set.length
    val dp = Array.ofDim[Boolean](n + 1, target + 1)

    for i <- 0 to n do dp(i)(0) = true

    for i <- 1 to n do
      for j <- 1 to target do
        dp(i)(j)                         = dp(i - 1)(j)
        if set(i - 1) <= j then dp(i)(j) = dp(i)(j) || dp(i - 1)(j - set(i - 1))

    if !dp(n)(target) then List.empty
    else
      def backtrack(i: Int, remaining: Int): List[List[Int]] =
        if remaining == 0 then List(List.empty)
        else if i == 0 then List.empty
        else
          // Paths that exclude set(i-1)
          val excluded =
            if dp(i - 1)(remaining) then backtrack(i - 1, remaining)
            else List.empty

          // Paths that include set(i-1)
          val included =
            if set(i - 1) <= remaining && dp(i - 1)(remaining - set(i - 1)) then
              backtrack(i - 1, remaining - set(i - 1)).map(set(i - 1) :: _)
            else List.empty

          excluded ++ included

      backtrack(n, target)
